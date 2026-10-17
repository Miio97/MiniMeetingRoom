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

/** One bounded owner for audio. Views never own devices; capture is explicit and never reconnects itself. */
public final class AudioCallController implements AutoCloseable {
    @FunctionalInterface interface SessionFactory {
        NativeAudioSession create(boolean headless, boolean capture, AudioDevice microphone, AudioDevice speaker);
    }
    private final SessionFactory sessions;
    private record Subscription(String publication, NativeAudioSession.Peer peer) { }
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
    private final CameraSwitch microphone = new CameraSwitch(this::changed);
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final RtcQualityMonitor qualityMonitor = new RtcQualityMonitor();
    private int qualityCursor;
    private final Map<String, AtomicLong> samplesBySource = new ConcurrentHashMap<>();
    public long receivedSamples(String participant) { var value=samplesBySource.get(participant); return value==null ? 0 : value.get(); }
    private final AtomicLong receivedSamples = new AtomicLong();
    private final CompletableFuture<Void> closeComplete = new CompletableFuture<>();
    private volatile boolean entered, active;
    private NativeAudioSession receive, capture;
    private volatile NativeAudioSession.Peer publisher;
    /** Called only by the video stats sampler; never stalls PCM generation on the audio owner. */
    synchronized AdaptiveVideoPolicy.Evidence networkQuality() {
        qualityMonitor.begin();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        List<NativeAudioSession.Peer> peers = new ArrayList<>();
        NativeAudioSession.Peer current = publisher; if (current != null) peers.add(current);
        for (Subscription subscription : subscriptions.values()) peers.add(subscription.peer());
        int examined = 0;
        for (; examined < peers.size(); examined++) {
            if (closed.get() || System.nanoTime() >= deadline) break;
            var peer = peers.get(Math.floorMod(qualityCursor + examined, peers.size()));
            qualityMonitor.accept(peer, peer.stats(100));
        }
        qualityCursor = peers.isEmpty() ? 0 : Math.floorMod(qualityCursor + examined, peers.size());
        return qualityMonitor.finish();
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
            closeSubscriptions(); if (receive != null) { receive.close(); receive=null; }
            return null;
        });
    }
    public void enter() { entered=true; }
    public CompletableFuture<Void> toggle() {
        return microphone.toggle(active, () -> submit(() -> { start(); return null; }), () -> submit(() -> { stop(true); return null; }));
    }
    private void changed() {
        Runnable update = () -> requested.set(!closed.get() && (microphone.pending() ? microphone.wanted() : active));
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
        if (generated != null) { generated.cancel(false); generated=null; }
        if (publisher != null) { publisher.close(); publisher=null; }
        if (capture != null) { capture.close(); capture=null; }
        changed();
        if (notify && room.get()!=null) command("AUDIO_STOP",new JsonObject());
    }
    private void synchronize() throws Exception {
        RoomSnapshot snapshot=room.get(); if (snapshot==null) { disconnect(); return; }
        if (active && publisher==null && microphone.wanted()) {
            try { publish(); } catch (Exception | Error failure) {
                try { stop(true); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
                microphone.resetOff(); throw failure;
            }
        }
        if (publisher!=null && (publisher.failed() || snapshot.participants().stream().filter(p -> p.participantId().equals(self.get())).noneMatch(p -> p.mediaState().micRequested()))) {
            microphone.resetOff(); stop(true);
        }
        JsonObject sources=command("AUDIO_STATE",new JsonObject()).getAsJsonObject("sources");
        for(String target:new ArrayList<>(subscriptions.keySet())) {
            Subscription s=subscriptions.get(target);
            if (!sources.has(target) || !s.publication().equals(sources.get(target).getAsString()) || s.peer().failed()) { s.peer().close(); subscriptions.remove(target); }
        }
        if (subscriptions.isEmpty() && receive != null) {
            receive.close(); receive = null;
        }
        samplesBySource.keySet().removeIf(id -> snapshot.participants().stream().noneMatch(p -> p.participantId().equals(id)));
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
            try {
                JsonObject body=new JsonObject(); body.addProperty("source",source.getKey()); body.addProperty("publication",source.getValue().getAsString()); body.addProperty("sdp",peer.offer());
                peer.answer(command("AUDIO_SUBSCRIBE",body).get("sdp").getAsString()); subscriptions.put(source.getKey(),new Subscription(source.getValue().getAsString(),peer));
            } catch (Exception | Error failure) { peer.close(); report(failure); }
        }
    }
    private JsonObject command(String type, JsonObject body) throws Exception {
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
        Platform.runLater(() -> {String value=latestStatus.getAndSet(null);statusScheduled.set(false);if(!closed.get()) status.set(value);if(latestStatus.get()!=null) show(latestStatus.get());});
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
