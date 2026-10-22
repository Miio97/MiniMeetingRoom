package client.media;

import com.google.gson.JsonObject;
import common.meeting.RoomSnapshot;
import common.signaling.SignalException;
import dev.onvoid.webrtc.media.MediaDevices;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.property.*;

/** One bounded owner for audio. Transport repair preserves the user's explicit microphone choice. */
public final class AudioCallController implements AutoCloseable {
    @FunctionalInterface interface SessionFactory {
        NativeAudioSession create(boolean headless, boolean capture, AudioDevice microphone, AudioDevice speaker);
    }
    private final SessionFactory sessions;
    private record Subscription(String publication, String transport, NativeAudioSession.Peer peer) { }
    private final VideoCallController.Signaling signaling;
    private final Supplier<RoomSnapshot> room;
    private final Supplier<String> self;
    private final boolean headless;
    private final MicrophoneConsentStore consent;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("audio-client-owner").factory());
    private final Semaphore admission=new Semaphore(16);
    private final Set<CompletableFuture<?>> jobs=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(), uiScheduled = new AtomicBoolean();
    private final ReadOnlyStringWrapper status=new ReadOnlyStringWrapper("");
    private final AtomicReference<String> latestStatus=new AtomicReference<>();
    private final AtomicBoolean statusScheduled=new AtomicBoolean();
    public ReadOnlyStringProperty statusProperty() {return status.getReadOnlyProperty();}
    private final ReadOnlyBooleanWrapper requested = new ReadOnlyBooleanWrapper();
    private final ReadOnlyBooleanWrapper pending = new ReadOnlyBooleanWrapper();
    private final CameraSwitch microphone = new CameraSwitch(this::changed);
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> samplesBySource = new ConcurrentHashMap<>();
    public long receivedSamples(String participant) { var value=samplesBySource.get(participant); return value==null ? 0 : value.get(); }
    private final AtomicLong receivedSamples = new AtomicLong();
    private final CompletableFuture<Void> closeComplete = new CompletableFuture<>();
    private volatile boolean entered, active;
    private long publishedAt;
    private boolean micRequestConfirmed;
    private NativeAudioSession receive, capture;
    private volatile NativeAudioSession.Peer publisher;
    private volatile AdaptiveVideoPolicy.Evidence voiceEvidence=AdaptiveVideoPolicy.Evidence.UNKNOWN;
    AdaptiveVideoPolicy.Evidence networkQuality() {return voiceEvidence;}
    void networkSample(NetworkSample sample) {voiceEvidence=sample.voiceUp().evidence().merge(sample.voiceDown().evidence());}
    void statsSources(List<MediaStatsSource> target) {
        if(!entered || !controlConnected || closed.get()) return;
        var current=publisher;
        if(current!=null) target.add(new MediaStatsSource(current,MediaStatsSource.Kind.AUDIO,true,false,current::statsAsync));
        for(var sub:subscriptions.values()) target.add(new MediaStatsSource(sub.peer(),MediaStatsSource.Kind.AUDIO,false,false,sub.peer()::statsAsync));
    }
    private AudioDevice selectedMicrophone, selectedSpeaker;
    private ScheduledFuture<?> generated;
    public AudioCallController(VideoCallController.Signaling signaling, Supplier<RoomSnapshot> room,
            Supplier<String> self, boolean headless, MicrophoneConsentStore consent) {
        this(signaling, room, self, headless, consent, NativeAudioSession::new);
    }
    AudioCallController(VideoCallController.Signaling signaling, Supplier<RoomSnapshot> room,
            Supplier<String> self, boolean headless, MicrophoneConsentStore consent, SessionFactory sessions) {
        this.sessions = sessions;
        this.signaling=signaling; this.room=room; this.self=self; this.headless=headless; this.consent=consent;
        worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(() -> {
            if (!entered || closed.get()) return;
            try { synchronize(); }
            catch (Exception | Error failure) {
                try { disconnect(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                report(failure);
            }
        }, 1, 1, TimeUnit.SECONDS);
    }
    public ReadOnlyBooleanProperty requestedProperty() { return requested.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty pendingProperty() { return pending.getReadOnlyProperty(); }
    public CompletableFuture<Void> closeComplete() { return closeComplete; }
    public long receivedSamples() { return receivedSamples.get(); }
    public CompletableFuture<MicrophoneConsentStore.Choice> choice() { return submit(consent::read); }
    public CompletableFuture<Void> access(boolean allowed) { return submit(() -> { consent.access(allowed); return null; }); }
    public CompletableFuture<List<AudioDevice>> microphones() { return submit(() -> headless ? List.of() : MediaDevices.getAudioCaptureDevices()); }
    public CompletableFuture<List<AudioDevice>> speakers() { return submit(() -> headless ? List.of() : MediaDevices.getAudioRenderDevices()); }
    public CompletableFuture<AudioDevice> defaultMicrophone() { return submit(() -> headless ? null : MediaDevices.getDefaultAudioCaptureDevice()); }
    public CompletableFuture<Void> selectMicrophone(AudioDevice device) {
        return submit(() -> { if (active || microphone.pending()) throw new SignalException("MEDIA_BUSY"); selectedMicrophone=device;
            if (device != null) consent.device(device.getDescriptor()); return null; });
    }
    public CompletableFuture<Void> selectSpeaker(AudioDevice device) {
        return submit(() -> {
            selectedSpeaker=device;
            if (device != null) consent.speaker(device.getDescriptor());
            if (entered && controlConnected) for (var subscription : subscriptions.values()) release(subscription);
            closeSubscriptions(); if (receive != null) { receive.close(); receive=null; }
            return null;
        });
    }
    public void enter() {
        entered = true;
        if (!closed.get() && active && publisher == null && microphone.wanted()) {
            submit(() -> { synchronize(); return null; });
        }
    }
    private volatile boolean controlConnected = true;
    public void controlConnected(boolean connected) {
        controlConnected = connected; if(!connected) voiceEvidence=AdaptiveVideoPolicy.Evidence.UNKNOWN;
        if (!connected) submit(() -> { disconnect(); return null; });
    }
    public CompletableFuture<Void> toggle() {
        return microphone.toggle(active, () -> submit(() -> { start(); return null; }), () -> submit(() -> { stop(true); return null; }));
    }
    private void changed() {
        Runnable update = () -> {
            requested.set(!closed.get() && (microphone.pending() ? microphone.wanted() : active));
            pending.set(!closed.get() && microphone.pending());
        };
        if (Platform.isFxApplicationThread()) update.run(); else if(uiScheduled.compareAndSet(false,true)) Platform.runLater(() -> {uiScheduled.set(false);update.run();});
    }
    private void wanted() { if (!microphone.wanted() || closed.get()) throw new CancellationException(); }
    private void start() throws Exception {
        wanted();
        if (consent.read().access() != MicrophoneConsentStore.Access.ALLOWED) throw new SignalException("MICROPHONE_PERMISSION_REQUIRED");
        if (room.get() == null) throw new SignalException("NOT_A_MEMBER");
        boolean attempted=false;
        try {
            if (!headless && selectedMicrophone == null) {
                String preferred=consent.read().preferredDevice();
                selectedMicrophone=MediaDevices.getAudioCaptureDevices().stream().filter(d -> d.getDescriptor().equals(preferred)).findFirst().orElse(null);
            }
            wanted(); capture=sessions.create(headless,true,selectedMicrophone,null); wanted();
            active=true;
            // Pre-join opens capture only after the user's click, and creates no publication.
            if (entered) { attempted=true; publish(); }
            changed(); show("");
        } catch (Exception | Error failure) {
            try { stop(attempted); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            report(failure); throw failure;
        }
    }
    private void publish() throws Exception {
        wanted(); publisher=capture.publisher(); JsonObject body=new JsonObject(); body.addProperty("sdp",publisher.offer()); wanted();
        JsonObject answer=command("AUDIO_PUBLISH",body); publisher.answer(answer.get("sdp").getAsString()); wanted();
        publishedAt = System.nanoTime();
        micRequestConfirmed = false;
        if (headless) generated=worker.scheduleAtFixedRate(new Runnable() {
            private long sample;
            public void run() {
                if (capture == null) return;
                byte[] pcm=new byte[960];
                for(int i=0;i<480;i++) { short value=(short)(Math.sin(2*Math.PI*440*sample++/48000)*6000); pcm[2*i]=(byte)value; pcm[2*i+1]=(byte)(value>>8); }
                capture.push(pcm);
            }
        },0,10,TimeUnit.MILLISECONDS);
    }
    private void stop(boolean notify) throws Exception {
        active=false;
        publishedAt = 0;
        micRequestConfirmed = false;
        if (generated != null) { generated.cancel(false); generated=null; }
        if (publisher != null) { publisher.close(); publisher=null; }
        if (capture != null) { capture.close(); capture=null; }
        changed();
        if (notify && room.get()!=null) command("AUDIO_STOP",new JsonObject());
    }
    private void synchronize() throws Exception {
        if (!controlConnected) return;
        RoomSnapshot snapshot=room.get(); if (snapshot==null) { disconnect(); return; }
        if (active && publisher==null && microphone.wanted()) {
            try { publish(); } catch (Exception | Error failure) {
                try { stop(true); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                microphone.resetOff(); throw failure;
            }
            snapshot = room.get();
            if (snapshot == null) { disconnect(); return; }
        }
        String own = self.get();
        boolean micRequested = snapshot.participants().stream()
                .anyMatch(p -> p.participantId().equals(own) && p.mediaState().micRequested());
        if (publisher != null) {
            micRequestConfirmed |= micRequested;
            if (VideoCallController.shouldStopPublication(micRequested, micRequestConfirmed, System.nanoTime() - publishedAt)) {
                microphone.resetOff(); stop(true);
            }
        }
        if (publisher != null && (publisher.failed() || publisher.stalled()) && microphone.wanted()) {
            // Repair the transport only while the user still wants the microphone on.
            publisher.close(); publisher = null;
            publishedAt = 0; micRequestConfirmed = false;
            if (generated != null) { generated.cancel(false); generated = null; }
            command("AUDIO_STOP", new JsonObject());
            publish();
        }
        JsonObject sources=command("AUDIO_STATE",new JsonObject()).getAsJsonObject("sources");
        for(String target:new ArrayList<>(subscriptions.keySet())) {
            Subscription s=subscriptions.get(target);
            if (!sources.has(target) || !s.publication().equals(sources.get(target).getAsString()) || s.peer().failed() || s.peer().stalled()) {
                try { release(s); }
                catch (Exception failure) { report(failure); continue; }
                s.peer().close(); subscriptions.remove(target);
            }
        }
        if (subscriptions.isEmpty() && receive != null) {
            receive.close(); receive = null;
        }
        var currentParticipants = snapshot.participants();
        samplesBySource.keySet().removeIf(id -> currentParticipants.stream().noneMatch(p -> p.participantId().equals(id)));
        for(var source:sources.entrySet()) {
            if (closed.get()) return;
            if (subscriptions.containsKey(source.getKey()) || source.getKey().equals(self.get()) || self.get() == null) continue;
            if (receive == null) {
                if (!headless && selectedSpeaker == null) {
                    String preferred = consent.read().preferredSpeaker();
                    selectedSpeaker = MediaDevices.getAudioRenderDevices().stream()
                            .filter(device -> device.getDescriptor().equals(preferred)).findFirst()
                            .orElseGet(MediaDevices::getDefaultAudioRenderDevice);
                }
                receive = sessions.create(headless, false, null, selectedSpeaker);
            }
            NativeAudioSession.Peer peer=receive.subscriber(count -> { receivedSamples.addAndGet(count); samplesBySource.computeIfAbsent(source.getKey(), ignored -> new AtomicLong()).addAndGet(count); });
            String transport = null;
            try {
                JsonObject body=new JsonObject(); body.addProperty("source",source.getKey()); body.addProperty("publication",source.getValue().getAsString()); body.addProperty("sdp",peer.offer());
                JsonObject answer = command("AUDIO_SUBSCRIBE",body);
                transport = answer.get("transport").getAsString();
                peer.answer(answer.get("sdp").getAsString());
                subscriptions.put(source.getKey(),new Subscription(source.getValue().getAsString(),transport,peer));
            } catch (Exception | Error failure) {
                if (transport != null) try { release(new Subscription(source.getValue().getAsString(), transport, peer)); }
                catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                peer.close(); report(failure);
            }
        }
    }
    private void release(Subscription subscription) throws Exception {
        JsonObject body = new JsonObject(); var ids = new com.google.gson.JsonArray();
        ids.add(subscription.transport()); body.add("transports", ids);
        command("AUDIO_UNSUBSCRIBE", body);
    }
    private JsonObject command(String type, JsonObject body) throws Exception {
        if (!controlConnected) throw new SignalException("CONNECTION_RESTORING");
        if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
        try { return signaling.request(type,body,UUID.randomUUID().toString()).get(16,TimeUnit.SECONDS); }
        catch (ExecutionException failure) { if (failure.getCause() instanceof Exception cause) throw cause; throw failure; }
    }
    private void closeSubscriptions() { subscriptions.values().forEach(s -> s.peer().close()); subscriptions.clear(); }
    private void disconnect() {
        microphone.resetOff(); try { stop(false); } catch (Exception ignored) { }
        closeSubscriptions(); samplesBySource.clear(); if(receive!=null) { receive.close(); receive=null; }
    }
    private void show(String message) {
        latestStatus.set(message);if(!statusScheduled.compareAndSet(false,true)) return;
        Platform.runLater(() -> {String value=latestStatus.getAndSet(null);statusScheduled.set(false);if(!closed.get() && value != null && !Objects.equals(status.get(), value)) status.set(value);if(latestStatus.get()!=null) show(latestStatus.get());});
    }
    private void report(Throwable failure) {
        if (closed.get() || failure instanceof CancellationException) return;
        show(CameraErrors.audioMessage(failure));
        common.diagnostics.Diagnostics.current().failure("video.client.operation.failed",failure,
                Map.of("kind","AUDIO","phase","audio","code",CameraErrors.audioCode(failure)));
    }
    private <T> CompletableFuture<T> submit(Callable<T> action) {
        CompletableFuture<T> result=new CompletableFuture<>();
        if (closed.get()) return CompletableFuture.failedFuture(new SignalException("CONNECTION_CLOSED"));
        if (!admission.tryAcquire()) return CompletableFuture.failedFuture(new SignalException("MEDIA_BUSY"));
        jobs.add(result);
        try { worker.execute(() -> { try { if(closed.get()) throw new SignalException("CONNECTION_CLOSED"); result.complete(action.call()); }
            catch(Exception | Error failure) { result.completeExceptionally(failure); } finally { jobs.remove(result); admission.release(); } }); }
        catch(RejectedExecutionException failure) { jobs.remove(result); admission.release(); result.completeExceptionally(new SignalException("CONNECTION_CLOSED")); }
        return result;
    }
    @Override public void close() {
        if (!closed.compareAndSet(false,true)) return;
        microphone.close(); worker.shutdownNow(); jobs.forEach(job -> job.completeExceptionally(new SignalException("CONNECTION_CLOSED"))); jobs.clear();
        closeComplete.orTimeout(10, TimeUnit.SECONDS);
        Thread.ofVirtual().name("audio-client-close").start(() -> {
            try {
                if (!worker.awaitTermination(6,TimeUnit.SECONDS)) throw new IllegalStateException("AUDIO_CLEANUP_FAILED");
                disconnect(); closeComplete.complete(null);
            } catch(Exception | Error failure) {
                closeComplete.completeExceptionally(new IllegalStateException("AUDIO_CLEANUP_FAILED", failure));
            }
        });
    }
}
