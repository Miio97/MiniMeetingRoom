package client.media;

import com.google.gson.JsonObject;

import common.meeting.RoomSnapshot;
import common.signaling.SignalException;

import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;

import javafx.application.Platform;
import javafx.beans.property.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

/**
 * One bounded owner for audio. Transport repair preserves the user's explicit microphone choice.
 */
public final class AudioCallController implements AutoCloseable {
    @FunctionalInterface
    interface SessionFactory {
        NativeAudioSession create(
                boolean headless, boolean capture, AudioDevice microphone, AudioDevice speaker);
    }

    private final SessionFactory sessions;

    private record Subscription(
            String publication, String transport, NativeAudioSession.Peer peer) {}

    private final VideoCallController.Signaling signaling;
    private final Supplier<RoomSnapshot> room;
    private final Supplier<String> self;
    private final boolean headless;
    private final MicrophoneConsentStore consent;
    private final ScheduledThreadPoolExecutor worker =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("audio-client-owner").factory());
    private final ThreadPoolExecutor receiver =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon().name("audio-client-receive").factory());
    private final AtomicBoolean receiving = new AtomicBoolean(),
            receiveAgain = new AtomicBoolean(),
            receiveReset = new AtomicBoolean();
    private final AtomicLong receiveEpoch = new AtomicLong();
    private final Semaphore admission = new Semaphore(16);
    private final Set<CompletableFuture<?>> jobs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(), uiScheduled = new AtomicBoolean();
    private volatile MediaSourceState pushedSources;
    private final AtomicBoolean sourcesQueued = new AtomicBoolean();

    void mediaSources(JsonObject activeSources, JsonObject pausedSources) {
        var next = new MediaSourceState(activeSources, pausedSources);
        if (closed.get() || next.equals(pushedSources)) return;
        pushedSources = next;
        receiveEpoch.incrementAndGet();
        refreshSources();
    }

    private void refreshSources() {
        if (!entered || !sourcesQueued.compareAndSet(false, true)) return;
        var observed = pushedSources;
        submit(
                        () -> {
                            synchronize();
                            return null;
                        })
                .whenComplete(
                        (v, e) -> {
                            sourcesQueued.set(false);
                            if (!closed.get() && observed != pushedSources) refreshSources();
                        });
    }

    void roomChanged() {
        if (!closed.get()) refreshSources();
    }

    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");
    private final AtomicReference<String> latestStatus = new AtomicReference<>();
    private final AtomicBoolean statusScheduled = new AtomicBoolean();

    public ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    private final ReadOnlyBooleanWrapper requested = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper pending = new ReadOnlyBooleanWrapper();
    private final CameraSwitch microphone = new CameraSwitch(this::changed);
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> samplesBySource = new ConcurrentHashMap<>();

    public long receivedSamples(String participant) {
        var value = samplesBySource.get(participant);
        return value == null ? 0 : value.get();
    }

    private final AtomicLong receivedSamples = new AtomicLong();
    private final CompletableFuture<Void> closeComplete = new CompletableFuture<>();
    private volatile boolean entered, active;
    private long publishedAt;
    private boolean micRequestConfirmed;
    private NativeAudioSession receive;
    private volatile NativeAudioSession capture;
    private volatile NativeAudioSession.Peer publisher;
    private NativeAudioSession.Peer retainedPublisher;
    private String publisherTransport, retainedTransport, retainedRoom, retainedSelf;
    private long retainUntil;
    private ScheduledFuture<?> retentionExpiry;
    private volatile AdaptiveVideoPolicy.Evidence voiceEvidence =
            AdaptiveVideoPolicy.Evidence.UNKNOWN;

    AdaptiveVideoPolicy.Evidence networkQuality() {
        return voiceEvidence;
    }

    void networkSample(NetworkSample sample) {
        voiceEvidence = sample.voiceUp().evidence().merge(sample.voiceDown().evidence());
    }

    void statsSources(List<MediaStatsSource> target) {
        if (!entered || !controlConnected || closed.get()) return;
        var current = publisher;
        if (current != null && active)
            target.add(
                    new MediaStatsSource(
                            current,
                            MediaStatsSource.Kind.AUDIO,
                            true,
                            false,
                            current::statsAsync));
        for (var sub : subscriptions.values())
            target.add(
                    new MediaStatsSource(
                            sub.peer(),
                            MediaStatsSource.Kind.AUDIO,
                            false,
                            sub.peer().presentationPaused(),
                            sub.peer()::statsAsync));
    }

    private AudioDevice selectedMicrophone;
    private volatile AudioDevice selectedSpeaker;
    private Runnable speakerChanged = () -> {};

    void onSpeakerChanged(Runnable listener) {
        speakerChanged = listener;
    }

    AudioDevice preferredSpeaker() {
        if (selectedSpeaker != null) return selectedSpeaker;
        String preferred;
        try {
            preferred = consent.read().preferredSpeaker();
        } catch (java.io.IOException failure) {
            return MediaDevices.getDefaultAudioRenderDevice();
        }
        return MediaDevices.getAudioRenderDevices().stream()
                .filter(d -> d.getDescriptor().equals(preferred))
                .findFirst()
                .orElseGet(MediaDevices::getDefaultAudioRenderDevice);
    }

    private ScheduledFuture<?> generated;

    public AudioCallController(
            VideoCallController.Signaling signaling,
            Supplier<RoomSnapshot> room,
            Supplier<String> self,
            boolean headless,
            MicrophoneConsentStore consent) {
        this(signaling, room, self, headless, consent, NativeAudioSession::new);
    }

    AudioCallController(
            VideoCallController.Signaling signaling,
            Supplier<RoomSnapshot> room,
            Supplier<String> self,
            boolean headless,
            MicrophoneConsentStore consent,
            SessionFactory sessions) {
        this.sessions = sessions;
        this.signaling = signaling;
        this.room = room;
        this.self = self;
        this.headless = headless;
        this.consent = consent;
        worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(
                () -> {
                    if (!entered || closed.get()) return;
                    try {
                        synchronize();
                    } catch (Exception | Error failure) {
                        try {
                            disconnect();
                        } catch (Exception | Error cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        report(failure);
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    public ReadOnlyBooleanProperty requestedProperty() {
        return requested.getReadOnlyProperty();
    }

    public ReadOnlyBooleanProperty pendingProperty() {
        return pending.getReadOnlyProperty();
    }

    public CompletableFuture<Void> closeComplete() {
        return closeComplete;
    }

    public long receivedSamples() {
        return receivedSamples.get();
    }

    public CompletableFuture<MicrophoneConsentStore.Choice> choice() {
        return submit(consent::read);
    }

    public CompletableFuture<Void> access(boolean allowed) {
        return submit(
                () -> {
                    consent.access(allowed);
                    return null;
                });
    }

    public CompletableFuture<List<AudioDevice>> microphones() {
        return submit(() -> headless ? List.of() : MediaDevices.getAudioCaptureDevices());
    }

    public CompletableFuture<List<AudioDevice>> speakers() {
        return submit(() -> headless ? List.of() : MediaDevices.getAudioRenderDevices());
    }

    public CompletableFuture<AudioDevice> defaultMicrophone() {
        return submit(() -> headless ? null : MediaDevices.getDefaultAudioCaptureDevice());
    }

    public CompletableFuture<Void> selectMicrophone(AudioDevice device) {
        return submit(
                () -> {
                    if (active || microphone.pending()) throw new SignalException("MEDIA_BUSY");
                    releaseRetained(true);
                    selectedMicrophone = device;
                    if (device != null) consent.device(device.getDescriptor());
                    return null;
                });
    }

    public CompletableFuture<Void> selectSpeaker(AudioDevice device) {
        return submit(
                () -> {
                    selectedSpeaker = device;
                    speakerChanged.run();
                    if (device != null) consent.speaker(device.getDescriptor());
                    receiveEpoch.incrementAndGet();
                    receiveReset.set(true);
                    scheduleReceive();
                    return null;
                });
    }

    public void enter() {
        entered = true;
        if (pushedSources != null) refreshSources();
        if (!closed.get() && active && publisher == null && microphone.wanted()) {
            submit(
                    () -> {
                        synchronize();
                        return null;
                    });
        }
    }

    private volatile boolean controlConnected = true;

    public void controlConnected(boolean connected) {
        controlConnected = connected;
        if (!connected) {
            pushedSources = null;
            receiveEpoch.incrementAndGet();
            voiceEvidence = AdaptiveVideoPolicy.Evidence.UNKNOWN;
        }
        if (!connected)
            submit(
                    () -> {
                        disconnect();
                        return null;
                    });
    }

    public CompletableFuture<Void> toggle() {
        return microphone.toggle(
                active,
                () ->
                        submit(
                                () -> {
                                    start();
                                    return null;
                                }),
                () ->
                        submit(
                                () -> {
                                    stop(true, true);
                                    return null;
                                }));
    }

    private void changed() {
        Runnable update =
                () -> {
                    requested.set(
                            !closed.get() && (microphone.pending() ? microphone.wanted() : active));
                    pending.set(!closed.get() && microphone.pending());
                };
        if (Platform.isFxApplicationThread()) update.run();
        else if (uiScheduled.compareAndSet(false, true))
            Platform.runLater(
                    () -> {
                        uiScheduled.set(false);
                        update.run();
                    });
    }

    private void wanted() {
        if (!microphone.wanted() || closed.get()) throw new CancellationException();
    }

    private void start() throws Exception {
        wanted();
        if (consent.read().access() != MicrophoneConsentStore.Access.ALLOWED)
            throw new SignalException("MICROPHONE_PERMISSION_REQUIRED");
        if (room.get() == null) throw new SignalException("NOT_A_MEMBER");
        boolean attempted = false;
        try {
            if (!headless && selectedMicrophone == null) {
                String preferred = consent.read().preferredDevice();
                selectedMicrophone =
                        MediaDevices.getAudioCaptureDevices().stream()
                                .filter(d -> d.getDescriptor().equals(preferred))
                                .findFirst()
                                .orElse(null);
            }
            wanted();
            if (!canReusePublisher()) {
                releaseRetained(true);
                capture = sessions.create(headless, true, selectedMicrophone, null);
            }
            wanted();
            active = true;
            // Pre-join opens capture only after the user's click, and creates no publication.
            if (entered) {
                attempted = true;
                publish();
            }
            changed();
            show("");
        } catch (Exception | Error failure) {
            try {
                stop(attempted);
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            report(failure);
            throw failure;
        }
    }

    private void publish() throws Exception {
        boolean reused = false;
        wanted();
        if (canReusePublisher()) {
            publisher = retainedPublisher;
            publisherTransport = retainedTransport;
            retainedPublisher = null;
            retainedTransport = null;
            if (retentionExpiry != null) {
                retentionExpiry.cancel(false);
                retentionExpiry = null;
            }
            try {
                // A new user click authorizes reopening the recording endpoint.
                publisher.resumeInput();
                JsonObject body = new JsonObject();
                body.addProperty("transport", publisherTransport);
                command("AUDIO_RESUME", body);
                reused = true;
            } catch (SignalException expired) {
                if (!"MEDIA_REUSE_EXPIRED".equals(expired.code())) throw expired;
                publisher.close();
                publisher = null;
                publisherTransport = null;
            }
        }
        if (!reused) {
            wanted();
            publisher = capture.publisher();
            JsonObject body = new JsonObject();
            body.addProperty("sdp", publisher.offer());
            wanted();
            JsonObject answer = command("AUDIO_PUBLISH", body);
            publisher.answer(answer.get("sdp").getAsString());
            publisherTransport =
                    answer.has("transport") ? answer.get("transport").getAsString() : null;
        }
        wanted();
        publishedAt = System.nanoTime();
        var acknowledgedRoom = room.get();
        micRequestConfirmed =
                acknowledgedRoom != null
                        && acknowledgedRoom.participants().stream()
                                .anyMatch(
                                        p ->
                                                p.participantId().equals(self.get())
                                                        && p.mediaState().micRequested());
        if (headless)
            generated =
                    worker.scheduleAtFixedRate(
                            new Runnable() {
                                private long sample;

                                public void run() {
                                    if (capture == null) return;
                                    byte[] pcm = new byte[960];
                                    for (int i = 0; i < 480; i++) {
                                        short value =
                                                (short)
                                                        (Math.sin(
                                                                        2 * Math.PI * 440 * sample++
                                                                                / 48000)
                                                                * 6000);
                                        pcm[2 * i] = (byte) value;
                                        pcm[2 * i + 1] = (byte) (value >> 8);
                                    }
                                    capture.push(pcm);
                                }
                            },
                            0,
                            10,
                            TimeUnit.MILLISECONDS);
    }

    private void stop(boolean notify) throws Exception {
        stop(notify, false);
    }

    private void stop(boolean notify, boolean retain) throws Exception {
        active = false;
        publishedAt = 0;
        micRequestConfirmed = false;
        if (generated != null) {
            generated.cancel(false);
            generated = null;
        }
        if (retain
                && notify
                && entered
                && controlConnected
                && publisher != null
                && publisherTransport != null
                && room.get() != null
                && !publisher.failed()) {
            try {
                publisher.pauseInput();
                JsonObject body = new JsonObject();
                body.addProperty("transport", publisherTransport);
                command("AUDIO_PAUSE", body);
                releaseRetained(false);
                retainedPublisher = publisher;
                retainedTransport = publisherTransport;
                retainedRoom = room.get() == null ? null : room.get().roomId();
                retainedSelf = self.get();
                publisher = null;
                publisherTransport = null;
                retainUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                var retained = retainedPublisher;
                retentionExpiry =
                        worker.schedule(
                                () -> {
                                    if (retainedPublisher == retained) {
                                        releaseRetained(true);
                                        if (controlConnected && room.get() != null)
                                            try {
                                                command("AUDIO_STOP", new JsonObject());
                                            } catch (Exception failure) {
                                                report(failure);
                                            }
                                    }
                                },
                                30,
                                TimeUnit.SECONDS);
                changed();
                return;
            } catch (Exception | Error failure) {
                // Close the complete owner on any device-stop failure: no background capture is
                // acceptable.
                if (publisher != null) {
                    publisher.close();
                    publisher = null;
                }
                if (capture != null) {
                    capture.close();
                    capture = null;
                }
                publisherTransport = null;
                releaseRetained(false);
                changed();
                try {
                    command("AUDIO_STOP", new JsonObject());
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                if (!(failure instanceof SignalException signal)
                        || !Set.of(
                                        "UNKNOWN_MESSAGE",
                                        "UNKNOWN_TYPE",
                                        "UNKNOWN_OPERATION",
                                        "MEDIA_REUSE_EXPIRED")
                                .contains(signal.code())) throw failure;
                return;
            }
        }
        if (publisher != null) {
            publisher.close();
            publisher = null;
        }
        publisherTransport = null;
        releaseRetained(false);
        if (capture != null) {
            capture.close();
            capture = null;
        }
        changed();
        if (notify && room.get() != null) command("AUDIO_STOP", new JsonObject());
    }

    private boolean canReusePublisher() {
        var current = room.get();
        return retainedPublisher != null
                && !retainedPublisher.failed()
                && controlConnected
                && current != null
                && System.nanoTime() < retainUntil
                && Objects.equals(retainedRoom, current.roomId())
                && Objects.equals(retainedSelf, self.get());
    }

    private void releaseRetained(boolean closeCapture) {
        if (retentionExpiry != null) {
            retentionExpiry.cancel(false);
            retentionExpiry = null;
        }
        if (retainedPublisher != null) {
            retainedPublisher.close();
            retainedPublisher = null;
        }
        retainedTransport = retainedRoom = retainedSelf = null;
        retainUntil = 0;
        if (closeCapture && capture != null) {
            capture.close();
            capture = null;
        }
    }

    private void synchronize() throws Exception {
        if (!controlConnected) return;
        RoomSnapshot snapshot = room.get();
        if (snapshot == null) {
            disconnect();
            return;
        }
        if (active && publisher == null && microphone.wanted()) {
            try {
                publish();
            } catch (Exception | Error failure) {
                try {
                    stop(true);
                } catch (Exception | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                microphone.resetOff();
                throw failure;
            }
            snapshot = room.get();
            if (snapshot == null) {
                disconnect();
                return;
            }
        }
        String own = self.get();
        boolean micRequested =
                snapshot.participants().stream()
                        .anyMatch(
                                p ->
                                        p.participantId().equals(own)
                                                && p.mediaState().micRequested());
        if (publisher != null) {
            micRequestConfirmed |= micRequested;
            if (VideoCallController.shouldStopPublication(
                    micRequested, micRequestConfirmed, System.nanoTime() - publishedAt)) {
                microphone.resetOff();
                stop(true);
            }
        }
        if (publisher != null
                && (publisher.failed() || publisher.stalled())
                && microphone.wanted()) {
            // Repair the transport only while the user still wants the microphone on.
            publisher.close();
            publisher = null;
            publishedAt = 0;
            micRequestConfirmed = false;
            if (generated != null) {
                generated.cancel(false);
                generated = null;
            }
            command("AUDIO_STOP", new JsonObject());
            publish();
        }
        scheduleReceive();
    }

    private void scheduleReceive() {
        receiveAgain.set(true);
        if (closed.get() || !receiving.compareAndSet(false, true)) return;
        try {
            receiver.execute(
                    () -> {
                        try {
                            do {
                                receiveAgain.set(false);
                                synchronizeReceived();
                            } while (receiveAgain.get() && !closed.get());
                        } catch (Exception | Error failure) {
                            receiveAgain.set(false);
                            report(failure);
                        } finally {
                            receiving.set(false);
                            if (!closed.get() && receiveAgain.get()) scheduleReceive();
                        }
                    });
        } catch (RejectedExecutionException ignored) {
            receiving.set(false);
        }
    }

    private void disposeReceived() {
        if (controlConnected && !closed.get())
            for (var subscription : subscriptions.values()) {
                try {
                    release(subscription);
                } catch (Exception failure) {
                    report(failure);
                }
            }
        closeSubscriptions();
        samplesBySource.clear();
        if (receive != null) {
            receive.close();
            receive = null;
        }
    }

    private void synchronizeReceived() throws Exception {
        if (receiveReset.getAndSet(false)) disposeReceived();
        if (!controlConnected || closed.get()) return;
        RoomSnapshot snapshot = room.get();
        if (snapshot == null) {
            disposeReceived();
            return;
        }
        long epoch = receiveEpoch.get();
        MediaSourceState sourceState = pushedSources;
        if (sourceState == null) {
            JsonObject state = command("AUDIO_STATE", new JsonObject());
            sourceState =
                    new MediaSourceState(
                            state.getAsJsonObject("sources"),
                            state.getAsJsonObject("pausedSources"));
        }
        JsonObject sources = sourceState.sources();
        for (String target : new ArrayList<>(subscriptions.keySet())) {
            Subscription s = subscriptions.get(target);
            boolean retained = sourceState.retained(target, s.publication());
            s.peer().pausePresentation(retained);
            if ((!sources.has(target) && !retained)
                    || sources.has(target)
                            && !s.publication().equals(sources.get(target).getAsString())
                    || s.peer().failed()
                    || !retained && s.peer().stalled()) {
                try {
                    release(s);
                } catch (Exception failure) {
                    report(failure);
                    continue;
                }
                s.peer().close();
                subscriptions.remove(target);
            }
        }
        if (subscriptions.isEmpty() && receive != null) {
            receive.close();
            receive = null;
        }
        var currentParticipants = snapshot.participants();
        samplesBySource
                .keySet()
                .removeIf(
                        id ->
                                currentParticipants.stream()
                                        .noneMatch(p -> p.participantId().equals(id)));
        for (var source : sources.entrySet()) {
            if (closed.get()) return;
            if (subscriptions.containsKey(source.getKey())
                    || source.getKey().equals(self.get())
                    || self.get() == null) continue;
            if (receive == null) {
                if (!headless && selectedSpeaker == null) {
                    String preferred = consent.read().preferredSpeaker();
                    selectedSpeaker =
                            MediaDevices.getAudioRenderDevices().stream()
                                    .filter(device -> device.getDescriptor().equals(preferred))
                                    .findFirst()
                                    .orElseGet(MediaDevices::getDefaultAudioRenderDevice);
                }
                receive = sessions.create(headless, false, null, selectedSpeaker);
            }
            NativeAudioSession.Peer peer =
                    receive.subscriber(
                            count -> {
                                receivedSamples.addAndGet(count);
                                samplesBySource
                                        .computeIfAbsent(
                                                source.getKey(), ignored -> new AtomicLong())
                                        .addAndGet(count);
                            });
            String transport = null;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("source", source.getKey());
                body.addProperty("publication", source.getValue().getAsString());
                body.addProperty("sdp", peer.offer());
                JsonObject answer = command("AUDIO_SUBSCRIBE", body);
                transport = answer.get("transport").getAsString();
                peer.answer(answer.get("sdp").getAsString());
                var ready = new Subscription(source.getValue().getAsString(), transport, peer);
                if (closed.get()
                        || !controlConnected
                        || epoch != receiveEpoch.get()
                        || room.get() == null
                        || !Objects.equals(snapshot.roomId(), room.get().roomId())) {
                    try {
                        release(ready);
                    } finally {
                        peer.close();
                    }
                    continue;
                }
                subscriptions.put(source.getKey(), ready);
            } catch (Exception | Error failure) {
                if (transport != null)
                    try {
                        release(new Subscription(source.getValue().getAsString(), transport, peer));
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                peer.close();
                report(failure);
            }
        }
    }

    private void release(Subscription subscription) throws Exception {
        JsonObject body = new JsonObject();
        var ids = new com.google.gson.JsonArray();
        ids.add(subscription.transport());
        body.add("transports", ids);
        command("AUDIO_UNSUBSCRIBE", body);
    }

    private JsonObject command(String type, JsonObject body) throws Exception {
        if (!controlConnected) throw new SignalException("CONNECTION_RESTORING");
        if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
        try {
            return signaling
                    .request(type, body, UUID.randomUUID().toString())
                    .get(16, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private void closeSubscriptions() {
        subscriptions.values().forEach(s -> s.peer().close());
        subscriptions.clear();
    }

    private void disconnect() {
        microphone.resetOff();
        try {
            stop(false);
        } catch (Exception ignored) {
        }
        receiveReset.set(true);
        scheduleReceive();
    }

    private void show(String message) {
        latestStatus.set(message);
        if (!statusScheduled.compareAndSet(false, true)) return;
        Platform.runLater(
                () -> {
                    String value = latestStatus.getAndSet(null);
                    statusScheduled.set(false);
                    if (!closed.get() && value != null && !Objects.equals(status.get(), value))
                        status.set(value);
                    if (latestStatus.get() != null) show(latestStatus.get());
                });
    }

    private void report(Throwable failure) {
        if (closed.get() || failure instanceof CancellationException) return;
        show(CameraErrors.audioMessage(failure));
    }

    private <T> CompletableFuture<T> submit(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed.get())
            return CompletableFuture.failedFuture(new SignalException("CONNECTION_CLOSED"));
        if (!admission.tryAcquire())
            return CompletableFuture.failedFuture(new SignalException("MEDIA_BUSY"));
        jobs.add(result);
        try {
            worker.execute(
                    () -> {
                        try {
                            if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                            result.complete(action.call());
                        } catch (Exception | Error failure) {
                            result.completeExceptionally(failure);
                        } finally {
                            jobs.remove(result);
                            admission.release();
                        }
                    });
        } catch (RejectedExecutionException failure) {
            jobs.remove(result);
            admission.release();
            result.completeExceptionally(new SignalException("CONNECTION_CLOSED"));
        }
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        microphone.close();
        worker.shutdownNow();
        receiver.shutdownNow();
        jobs.forEach(job -> job.completeExceptionally(new SignalException("CONNECTION_CLOSED")));
        jobs.clear();
        closeComplete.orTimeout(10, TimeUnit.SECONDS);
        Thread.ofVirtual()
                .name("audio-client-close")
                .start(
                        () -> {
                            try {
                                if (!worker.awaitTermination(6, TimeUnit.SECONDS))
                                    throw new IllegalStateException("AUDIO_CLEANUP_FAILED");
                                if (!receiver.awaitTermination(6, TimeUnit.SECONDS))
                                    throw new IllegalStateException("AUDIO_CLEANUP_FAILED");
                                disconnect();
                                disposeReceived();
                                closeComplete.complete(null);
                            } catch (Exception | Error failure) {
                                closeComplete.completeExceptionally(
                                        new IllegalStateException("AUDIO_CLEANUP_FAILED", failure));
                            }
                        });
    }
}
