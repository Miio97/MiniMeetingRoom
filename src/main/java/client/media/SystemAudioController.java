package client.media;

import com.google.gson.*;

import common.meeting.RoomSnapshot;
import common.signaling.SignalException;

import dev.onvoid.webrtc.media.audio.AudioDevice;

import javafx.application.Platform;
import javafx.beans.property.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** SCREEN_AUDIO is an Opus transport owned by a SCREEN publication, never by the microphone. */
final class SystemAudioController implements AutoCloseable {
    private record Subscription(
            String publication, String transport, NativeAudioSession.Peer peer) {}

    private final VideoCallController.Signaling signaling;
    private final Supplier<RoomSnapshot> room;
    private final Supplier<String> self;
    private final Supplier<SystemAudioInput> inputs;
    private final boolean headless;
    private final ScheduledThreadPoolExecutor worker =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("screen-audio-owner").factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<?>> jobs = ConcurrentHashMap.newKeySet();
    private final Semaphore admission = new Semaphore(16);
    private final Map<String, Subscription> subscriptions = new HashMap<>();
    private final Set<String> retiring = new LinkedHashSet<>();
    private final Map<String, AtomicLong> received = new ConcurrentHashMap<>();
    private final ReadOnlyBooleanWrapper enabled = new ReadOnlyBooleanWrapper(),
            pending = new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper("");
    private final CompletableFuture<Void> closeComplete = new CompletableFuture<>();
    private volatile boolean wanted, paused, connected = true, entered;
    private volatile String publication;
    private String publishedFor;
    private boolean stopPending;
    private NativeAudioSession capture, receive;
    private NativeAudioSession.Peer publisher;
    private SystemAudioInput input;
    private Supplier<AudioDevice> speaker = () -> null;

    SystemAudioController(
            VideoCallController.Signaling signaling,
            Supplier<RoomSnapshot> room,
            Supplier<String> self,
            boolean headless,
            Supplier<SystemAudioInput> inputs) {
        this.signaling = signaling;
        this.room = room;
        this.self = self;
        this.headless = headless;
        this.inputs = inputs;
        worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(
                () -> {
                    if (closed.get() || !entered || !connected) return;
                    try {
                        synchronize();
                    } catch (Exception | Error failure) {
                        report(failure);
                    }
                },
                1,
                1,
                TimeUnit.SECONDS);
    }

    ReadOnlyBooleanProperty enabledProperty() {
        return enabled.getReadOnlyProperty();
    }

    ReadOnlyBooleanProperty pendingProperty() {
        return pending.getReadOnlyProperty();
    }

    ReadOnlyStringProperty statusProperty() {
        return status.getReadOnlyProperty();
    }

    CompletableFuture<Void> closeComplete() {
        return closeComplete;
    }

    long receivedSamples(String participant) {
        var count = received.get(participant);
        return count == null ? 0 : count.get();
    }

    void enter() {
        entered = true;
    }

    void publication(String value) {
        publication = value;
        submit(
                () -> {
                    reconcile();
                    return null;
                });
    }

    CompletableFuture<Void> prioritizeVoice(boolean value) {
        paused = value;
        return submit(
                () -> {
                    reconcile();
                    if (value) closeReceivers();
                    return null;
                });
    }

    void controlConnected(boolean value) {
        connected = value;
        if (!value) {
            wanted = false;
            update();
            submit(
                    () -> {
                        stopSend(false);
                        closeReceivers();
                        return null;
                    });
        }
    }

    void speaker(Supplier<AudioDevice> value) {
        speaker = value;
    }

    void speakerChanged() {
        submit(
                () -> {
                    closeReceivers();
                    return null;
                });
    }

    CompletableFuture<Void> setEnabled(boolean value) {
        wanted = value;
        update();
        return submit(
                () -> {
                    ui(() -> pending.set(true));
                    try {
                        reconcile();
                        return null;
                    } catch (Exception | Error failure) {
                        wanted = false;
                        update();
                        report(failure);
                        throw failure;
                    } finally {
                        ui(() -> pending.set(false));
                    }
                });
    }

    private void reconcile() throws Exception {
        if (stopPending && connected && room.get() != null) {
            command("SCREEN_AUDIO_STOP", new JsonObject(), UUID.randomUUID().toString());
            stopPending = false;
        }
        String parent = publication;
        if (publisher != null
                && (!wanted
                        || paused
                        || !connected
                        || parent == null
                        || !Objects.equals(parent, publishedFor))) stopSend(true);
        if (!wanted
                || paused
                || !connected
                || !entered
                || parent == null
                || publisher != null
                || closed.get()) return;
        ui(() -> pending.set(true));
        try {
            capture = new NativeAudioSession(true, true, null, null);
            publisher = capture.publisher();
            var body = new JsonObject();
            body.addProperty("sdp", publisher.offer());
            body.addProperty("publication", parent);
            var reply = command("SCREEN_AUDIO_PUBLISH", body, UUID.randomUUID().toString());
            publisher.answer(reply.get("sdp").getAsString());
            publishedFor = parent;
            if (!wanted || paused || !Objects.equals(parent, publication) || closed.get()) {
                stopSend(true);
                return;
            }
            input = inputs.get();
            input.start(
                    pcm -> {
                        // Off/cancel takes effect immediately, even while a native start is
                        // completing.
                        if (wanted
                                && !paused
                                && connected
                                && !closed.get()
                                && Objects.equals(parent, publication))
                            capture.pushSystemAudio(pcm);
                    });
            show("");
        } catch (Exception | Error failure) {
            try {
                stopSend(true);
            } catch (Exception | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            wanted = false;
            update();
            report(failure);
            throw failure;
        } finally {
            ui(() -> pending.set(false));
        }
    }

    private void stopSend(boolean notify) throws Exception {
        boolean published = publisher != null;
        publishedFor = null;
        try {
            if (input != null) {
                input.close();
                input = null;
            }
        } finally {
            if (publisher != null) {
                publisher.close();
                publisher = null;
            }
            if (capture != null) {
                capture.close();
                capture = null;
            }
        }
        stopPending |= published;
        if (stopPending && notify && connected && room.get() != null) {
            command("SCREEN_AUDIO_STOP", new JsonObject(), UUID.randomUUID().toString());
            stopPending = false;
        }
    }

    private void synchronize() throws Exception {
        var snapshot = room.get();
        if (snapshot == null) {
            publication = null;
            wanted = false;
            update();
            stopSend(false);
            closeReceivers();
            return;
        }
        if (publisher != null && (publisher.failed() || (input != null && input.failed()))) {
            wanted = false;
            update();
            stopSend(true);
            report(new IllegalStateException("SYSTEM_AUDIO_FAILED"));
        }
        reconcile();
        if (paused) {
            closeReceivers();
            return;
        }
        if (subscriptions.isEmpty()
                && publication == null
                && snapshot.participants().stream().noneMatch(p -> p.mediaState().shareRequested()))
            return;
        flush();
        var sources =
                command("SCREEN_AUDIO_STATE", new JsonObject(), UUID.randomUUID().toString())
                        .getAsJsonObject("sources");
        for (String target : new ArrayList<>(subscriptions.keySet())) {
            var sub = subscriptions.get(target);
            if (!sources.has(target)
                    || !sub.publication().equals(sources.get(target).getAsString())
                    || sub.peer().failed()) {
                retiring.add(sub.transport());
                sub.peer().close();
                subscriptions.remove(target);
            }
        }
        flush();
        if (subscriptions.isEmpty() && receive != null) {
            receive.close();
            receive = null;
        }
        for (var source : sources.entrySet()) {
            String target = source.getKey();
            if (target.equals(self.get()) || subscriptions.containsKey(target) || closed.get())
                continue;
            if (receive == null)
                receive =
                        new NativeAudioSession(
                                headless, false, null, headless ? null : speaker.get());
            var peer =
                    receive.subscriber(
                            count ->
                                    received.computeIfAbsent(target, ignored -> new AtomicLong())
                                            .addAndGet(count));
            String transport = UUID.randomUUID().toString();
            try {
                var body = new JsonObject();
                body.addProperty("source", target);
                body.addProperty("publication", source.getValue().getAsString());
                body.addProperty("sdp", peer.offer());
                var answer = command("SCREEN_AUDIO_SUBSCRIBE", body, transport);
                transport = answer.get("transport").getAsString();
                peer.answer(answer.get("sdp").getAsString());
                subscriptions.put(
                        target, new Subscription(source.getValue().getAsString(), transport, peer));
            } catch (Exception | Error failure) {
                retiring.add(transport);
                peer.close();
                try {
                    flush();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                report(failure);
            }
        }
    }

    private void closeReceivers() throws Exception {
        for (var sub : subscriptions.values()) {
            retiring.add(sub.transport());
            sub.peer().close();
        }
        subscriptions.clear();
        if (receive != null) {
            receive.close();
            receive = null;
        }
        if (connected && room.get() != null && !closed.get()) flush();
    }

    private void flush() throws Exception {
        while (!retiring.isEmpty()) {
            var batch = retiring.stream().limit(6).toList();
            var ids = new JsonArray();
            batch.forEach(ids::add);
            var body = new JsonObject();
            body.add("transports", ids);
            command("SCREEN_AUDIO_UNSUBSCRIBE", body, UUID.randomUUID().toString());
            retiring.removeAll(batch);
        }
    }

    private JsonObject command(String type, JsonObject body, String id) throws Exception {
        if (closed.get() || !connected) throw new SignalException("CONNECTION_CLOSED");
        try {
            return signaling.request(type, body, id).get(16, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            throw failure;
        }
    }

    private void update() {
        ui(() -> enabled.set(wanted && !closed.get()));
    }

    private void show(String message) {
        ui(() -> status.set(message));
    }

    private void ui(Runnable action) {
        if (Platform.isFxApplicationThread()) action.run();
        else Platform.runLater(action);
    }

    private void report(Throwable failure) {
        if (closed.get()) return;
        String code =
                "SYSTEM_AUDIO_UNAVAILABLE".equals(CameraErrors.unwrap(failure).getMessage())
                        ? "SYSTEM_AUDIO_UNAVAILABLE"
                        : "SYSTEM_AUDIO_FAILED";
        show(CameraErrors.message(new SignalException(code)));
    }

    private <T> CompletableFuture<T> submit(Callable<T> action) {
        if (closed.get())
            return CompletableFuture.failedFuture(new SignalException("CONNECTION_CLOSED"));
        if (!admission.tryAcquire())
            return CompletableFuture.failedFuture(new SignalException("MEDIA_BUSY"));
        var result = new CompletableFuture<T>();
        jobs.add(result);
        try {
            worker.execute(
                    () -> {
                        try {
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
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        wanted = false;
        publication = null;
        update();
        worker.shutdownNow();
        jobs.forEach(job -> job.completeExceptionally(new SignalException("CONNECTION_CLOSED")));
        jobs.clear();
        Thread.ofVirtual()
                .name("screen-audio-close")
                .start(
                        () -> {
                            try {
                                if (!worker.awaitTermination(18, TimeUnit.SECONDS))
                                    throw new IllegalStateException("SYSTEM_AUDIO_FAILED");
                                stopSend(false);
                                closeReceivers();
                                if (connected && room.get() != null) {
                                    if (stopPending)
                                        signaling
                                                .request(
                                                        "SCREEN_AUDIO_STOP",
                                                        new JsonObject(),
                                                        UUID.randomUUID().toString())
                                                .get(3, TimeUnit.SECONDS);
                                    while (!retiring.isEmpty()) {
                                        var batch = retiring.stream().limit(6).toList();
                                        var ids = new JsonArray();
                                        batch.forEach(ids::add);
                                        var body = new JsonObject();
                                        body.add("transports", ids);
                                        signaling
                                                .request(
                                                        "SCREEN_AUDIO_UNSUBSCRIBE",
                                                        body,
                                                        UUID.randomUUID().toString())
                                                .get(3, TimeUnit.SECONDS);
                                        retiring.removeAll(batch);
                                    }
                                }
                                closeComplete.complete(null);
                            } catch (Exception | Error failure) {
                                closeComplete.completeExceptionally(
                                        new IllegalStateException("SYSTEM_AUDIO_FAILED"));
                            }
                        });
    }
}
