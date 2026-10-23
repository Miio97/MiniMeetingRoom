package client.media;

import com.google.gson.JsonObject;
import common.meeting.RoomSnapshot;
import common.signaling.SignalException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javafx.application.Platform;
import javafx.beans.property.*;

/** SCREEN has its own publication, capture and receive queues; camera is never stopped by screen work. */
public final class ScreenCallController implements AutoCloseable {
    private record Subscription(String publication,String transport,NativeVideoSession.Peer peer) { }
    private final VideoCallController.Signaling signaling;
    private final Supplier<RoomSnapshot> room;
    private final Supplier<String> self;
    private final Supplier<List<ScreenVideoInput.Choice>> catalog;
    private final Function<ScreenVideoInput.Choice,VideoInput> inputFactory;
    private final SystemAudioController systemAudio;
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("screen-client-owner").factory());
    private final AtomicBoolean closed=new AtomicBoolean(),uiScheduled=new AtomicBoolean(),statusScheduled=new AtomicBoolean();
    private volatile MediaSourceState pushedSources;
    private final AtomicBoolean sourcesQueued=new AtomicBoolean();
    void mediaSources(JsonObject activeSources,JsonObject pausedSources) {
        var next=new MediaSourceState(activeSources,pausedSources);
        if(closed.get() || next.equals(pushedSources)) return;
        pushedSources=next;refreshSources();
    }
    private void refreshSources() {
        if(!entered || !sourcesQueued.compareAndSet(false,true)) return;
        var observed=pushedSources;
        submit(() -> {synchronize();return null;}).whenComplete((v,e) -> {
            sourcesQueued.set(false);if(!closed.get() && observed!=pushedSources) refreshSources();
        });
    }
    void roomChanged() { if(!closed.get()) refreshSources(); }
    private final AtomicReference<String> latestStatus=new AtomicReference<>();
    private final Semaphore admission=new Semaphore(16);
    private final Set<CompletableFuture<?>> jobs=ConcurrentHashMap.newKeySet();
    private final Map<String,VideoFrameMailbox> frames=new ConcurrentHashMap<>();
    private final Map<String,Subscription> subscriptions=new ConcurrentHashMap<>();
    private final ReadOnlyBooleanWrapper requested=new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper status=new ReadOnlyStringWrapper("");
    private final CameraSwitch sharing=new CameraSwitch(this::changed);
    private final CompletableFuture<Void> closeComplete=new CompletableFuture<>();
    private volatile boolean active,entered;
    private long publishedAt;
    private boolean shareRequestConfirmed, publicationConfirmed;
    private String ownPublication;
    private final Set<String> retiringTransports=new LinkedHashSet<>();
    private final AtomicLong previewEpoch=new AtomicLong();
    private NativeVideoSession nativeSession;
    private volatile NativeVideoSession.Peer publisher;
    private volatile boolean networkPaused;
    private boolean appliedNetworkPause;
    CompletableFuture<Void> prioritizeVoice(boolean value) {
        networkPaused = value;
        var audioPause=systemAudio.prioritizeVoice(value);
        return submit(() -> { applyNetworkPause(); return null; }).thenCombine(audioPause,(a,b) -> null);
    }
    private void applyNetworkPause() throws Exception {
        // A new publisher must deliver RTP before entering voice priority.
        boolean pause=networkPaused && publicationConfirmed;
        if (publisher != null && appliedNetworkPause != pause) publisher.pauseTransmission(pause);
        appliedNetworkPause = pause;
        if (networkPaused) {
            subscriptions.values().forEach(this::retire);
            subscriptions.clear();
        }
        flushUnsubscribes();
    }
    private void retire(Subscription subscription) {
        retiringTransports.add(subscription.transport());
        subscription.peer().close();
    }
    private void flushUnsubscribes() throws Exception {
        while(!retiringTransports.isEmpty()) {
            var batch=retiringTransports.stream().limit(6).toList();
            var ids=new com.google.gson.JsonArray();batch.forEach(ids::add);
            var body=new JsonObject();body.add("transports",ids);
            command("SCREEN_UNSUBSCRIBE",body);
            retiringTransports.removeAll(batch);
        }
    }
    void statsSources(List<MediaStatsSource> target) {
        if(!entered || !controlConnected || closed.get()) return;
        var current=publisher;
        if(current!=null) target.add(new MediaStatsSource(current,MediaStatsSource.Kind.SCREEN,true,networkPaused,current::statsAsync));
        for(var sub:subscriptions.values()) target.add(new MediaStatsSource(sub.peer(),MediaStatsSource.Kind.SCREEN,false,networkPaused,sub.peer()::statsAsync));
    }
    private NativeVideoSession.Preview preview;
    private VideoInput input;
    private long lastSourceCheck;
    private volatile boolean prepared;
    public ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,boolean headless) {
        this(signaling,room,self,headless ? List::of : ScreenVideoInput::choices,ScreenVideoInput::new,headless,WindowsSystemAudioInput::new);
    }
    public ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,Supplier<List<ScreenVideoInput.Choice>> catalog) {
        this(signaling,room,self,catalog,ScreenVideoInput::new);
    }
    public ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,
            Supplier<List<ScreenVideoInput.Choice>> catalog,Function<ScreenVideoInput.Choice,VideoInput> inputFactory) {
        this(signaling,room,self,catalog,inputFactory,false,WindowsSystemAudioInput::new);
    }
    ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,
            Supplier<List<ScreenVideoInput.Choice>> catalog,Function<ScreenVideoInput.Choice,VideoInput> inputFactory,
            boolean headless,Supplier<SystemAudioInput> audioInputs) {
        systemAudio=new SystemAudioController(signaling,room,self,headless,audioInputs);
        this.signaling=signaling;this.room=room;this.self=self;this.catalog=catalog;this.inputFactory=inputFactory;worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(() -> {
            if(!entered || closed.get()) return;
            try {synchronize();} catch(Exception | LinkageError failure) {failedReceive(failure);}
        },1,1,TimeUnit.SECONDS);
    }
    public void enter() {entered=true;systemAudio.enter();if(pushedSources!=null) refreshSources();}
    private volatile boolean controlConnected = true;
    public void controlConnected(boolean connected) {
        systemAudio.controlConnected(connected);
        controlConnected = connected;if(!connected) pushedSources=null;
        if (!connected) submit(() -> { disconnect(); networkPaused = false; appliedNetworkPause = false; return null; });
    }
    public ReadOnlyBooleanProperty requestedProperty() {return requested.getReadOnlyProperty();}
    public ReadOnlyStringProperty statusProperty() {return status.getReadOnlyProperty();}
    public ReadOnlyBooleanProperty systemAudioEnabledProperty() {return systemAudio.enabledProperty();}
    public ReadOnlyBooleanProperty systemAudioPendingProperty() {return systemAudio.pendingProperty();}
    public ReadOnlyStringProperty systemAudioStatusProperty() {return systemAudio.statusProperty();}
    public CompletableFuture<Void> setSystemAudioEnabled(boolean value) {return systemAudio.setEnabled(value);}
    public boolean systemAudioSupported() {return WindowsSystemAudioInput.supported();}
    public long receivedSystemAudioSamples(String participant) {return systemAudio.receivedSamples(participant);}
    void systemAudioSpeaker(Supplier<dev.onvoid.webrtc.media.audio.AudioDevice> speaker) {systemAudio.speaker(speaker);}
    void systemAudioSpeakerChanged() {systemAudio.speakerChanged();}
    public CompletableFuture<Void> closeComplete() {return closeComplete;}
    public VideoFrameMailbox frames(String participant) {return frames.computeIfAbsent(participant,ignored -> new VideoFrameMailbox());}
    public CompletableFuture<List<ScreenVideoInput.Choice>> choices() {return submit(catalog::get);}
    public CompletableFuture<Void> preview(ScreenVideoInput.Choice choice) {return previewInput(() -> inputFactory.apply(choice));}
    public CompletableFuture<Void> previewInput(Supplier<VideoInput> factory) {
        long epoch=previewEpoch.incrementAndGet();
        return submit(() -> {
            if(epoch!=previewEpoch.get() || active) return null;
            stopLocal();
            try {ensureNative();input=factory.get();preview=nativeSession.preview(input,frames(self.get()));long before=frames(self.get()).receivedFrames();input.start();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(frames(self.get()).receivedFrames()<=before) {
                    if(epoch!=previewEpoch.get() || closed.get()) throw new CancellationException();
                    if(System.nanoTime()>deadline) throw new SignalException("SCREEN_NO_FRAMES");Thread.sleep(30);
                }
                prepared=true;}
            catch(Exception | LinkageError failure) {stopLocal();throw failure;}
            return null;
        });
    }
    public CompletableFuture<Void> endPreview() {
        previewEpoch.incrementAndGet();return submit(() -> {if(!active) stopLocal();return null;});
    }
    public CompletableFuture<Void> toggle(ScreenVideoInput.Choice choice) {return toggleInput(() -> inputFactory.apply(choice));}
    public CompletableFuture<Void> toggleInput(Supplier<VideoInput> factory) {
        return sharing.toggle(active,() -> submit(() -> {start(factory);return null;}),() -> submit(() -> {stop(true);return null;}));
    }
    public CompletableFuture<Void> stopShare() {
        return sharing.stop(active,() -> submit(() -> {stop(true);return null;}));
    }
    public CompletableFuture<Void> publishPreparedScreen() {
        return sharing.publishPrepared(() -> prepared && !active,
                () -> submit(() -> {start(null);return null;}),
                () -> submit(() -> {stop(true);return null;}));
    }
    private void changed() {
        Runnable update=() -> requested.set(!closed.get() && (sharing.pending() ? sharing.wanted() : active));
        if(Platform.isFxApplicationThread()) update.run();else if(uiScheduled.compareAndSet(false,true)) Platform.runLater(() -> {uiScheduled.set(false);update.run();});
    }
    private void ensureNative() {if(nativeSession==null) nativeSession=new NativeVideoSession();}
    private void wanted() {if(!sharing.wanted() || closed.get()) throw new CancellationException();}
    private void start(Supplier<VideoInput> factory) throws Exception {
        wanted();if(!entered || room.get()==null) throw new SignalException("NOT_A_MEMBER");
        boolean attempted=false;
        boolean reuse=factory==null && prepared && input!=null;
        if(!reuse) stopLocal();
        try {
            ensureNative();wanted();
            if(reuse) {preview.close();preview=null;prepared=false;}
            else {input=Objects.requireNonNull(factory).get();}
            wanted();
            var settings=ScreenVideoInput.Settings.configured();
            publisher=nativeSession.publisher(input,frames(self.get()),settings.bitrate(),settings.fps());
            long before=frames(self.get()).receivedFrames();wanted();if(!reuse) input.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!reuse && frames(self.get()).receivedFrames()<=before) {wanted();if(System.nanoTime()>deadline) throw new SignalException("SCREEN_NO_FRAMES");Thread.sleep(30);}
            JsonObject body=new JsonObject();body.addProperty("sdp",publisher.offer());wanted();attempted=true;
            var reply=command("SCREEN_PUBLISH",body);
            ownPublication=reply.get("transport").getAsString();
            publisher.answer(reply.get("sdp").getAsString());wanted();
            publishedAt = System.nanoTime(); shareRequestConfirmed = false;
            appliedNetworkPause=false;publicationConfirmed=false;active=true;
            systemAudio.publication(ownPublication);
            lastSourceCheck=0;changed();show("");
        } catch(Exception | LinkageError failure) {try {stop(attempted);} catch(Exception cleanup) {failure.addSuppressed(cleanup);}if(!(failure instanceof CancellationException)) failed(failure);throw failure;}
    }
    private void stop(boolean notify) throws Exception {stopLocal();systemAudio.setEnabled(false);if(notify && room.get()!=null) command("SCREEN_STOP",new JsonObject());}
    private void stopLocal() {
        systemAudio.publication(null);
        active=false;prepared=false;publishedAt=0;shareRequestConfirmed=false;publicationConfirmed=false;ownPublication=null;appliedNetworkPause=false;VideoInput current=input;input=null;
        try {if(current!=null) current.close();}
        finally {if(preview!=null) {preview.close();preview=null;}if(publisher!=null) {publisher.close();publisher=null;}changed();}
    }
    private void synchronize() throws Exception {
        if (!controlConnected) return;
        RoomSnapshot snapshot=room.get();if(snapshot==null) {disconnect();return;}
        // Local capture failures retire only the publisher. Room receiving keeps running.
        try {
            var own=snapshot.participants().stream().filter(p -> p.participantId().equals(self.get())).findFirst();
            boolean shareRequested=own.map(p -> p.mediaState().shareRequested()).orElse(false);
            if(publisher!=null) {
                shareRequestConfirmed |= shareRequested;
                if(publisher.failed() || VideoCallController.shouldStopPublication(shareRequested, shareRequestConfirmed, System.nanoTime()-publishedAt)) {
                    sharing.resetOff();stop(true);
                }
            }
            if(active && input!=null && System.nanoTime()-lastSourceCheck>TimeUnit.SECONDS.toNanos(3)) {
                lastSourceCheck=System.nanoTime();
                if(!input.available()) throw new SignalException("SCREEN_CAPTURE_FAILED");
            }
        } catch(Exception | LinkageError failure) {
            sharing.resetOff();
            try {stop(true);} catch(Exception cleanup) {failure.addSuppressed(cleanup);}
            failed(failure);
        }
        MediaSourceState sourceState=pushedSources;
        // The first outgoing RTP must be confirmed before voice priority can pause this publication.
        if(sourceState==null || active && !publicationConfirmed) {
            JsonObject state=command("SCREEN_STATE",new JsonObject());
            publicationConfirmed |= ownPublication!=null && state.has("publication") && ownPublication.equals(state.get("publication").getAsString());
            if(sourceState==null) sourceState=new MediaSourceState(state.getAsJsonObject("sources"),state.getAsJsonObject("pausedSources"));
        }
        applyNetworkPause();
        JsonObject sources=sourceState.sources();
        if (networkPaused) return;
        for(String target:new ArrayList<>(subscriptions.keySet())) {
            var subscription=subscriptions.get(target);
            if(!sources.has(target) || !subscription.publication().equals(sources.get(target).getAsString()) || subscription.peer().failed()) {
                retire(subscription);subscriptions.remove(target);
            }
        }
        flushUnsubscribes();
        for(var source:sources.entrySet()) {
            if(closed.get()) return;
            String target=source.getKey();if(target.equals(self.get()) || subscriptions.containsKey(target)) continue;
            ensureNative();NativeVideoSession.Peer peer=nativeSession.subscriber(frames(target));
            String transport=UUID.randomUUID().toString();
            try {
                JsonObject body=new JsonObject();body.addProperty("source",target);body.addProperty("publication",source.getValue().getAsString());body.addProperty("sdp",peer.offer());
                var reply=command("SCREEN_SUBSCRIBE",body,transport);
                transport=reply.get("transport").getAsString();
                peer.answer(reply.get("sdp").getAsString());subscriptions.put(target,new Subscription(source.getValue().getAsString(),transport,peer));show("");
            } catch(Exception | LinkageError failure) {retiringTransports.add(transport);peer.close();try {flushUnsubscribes();} catch(Exception cleanup) {failure.addSuppressed(cleanup);}failedReceive(failure);}
        }
        for(String participant:new ArrayList<>(frames.keySet())) if(snapshot.participants().stream().noneMatch(p -> p.participantId().equals(participant))) {
            VideoFrameMailbox old=frames.remove(participant);if(old!=null) old.close();
        }
    }
    private JsonObject command(String type,JsonObject body) throws Exception {
        return command(type,body,UUID.randomUUID().toString());
    }
    private JsonObject command(String type,JsonObject body,String requestId) throws Exception {
        if (!controlConnected) throw new SignalException("CONNECTION_RESTORING");
        if(closed.get()) throw new SignalException("CONNECTION_CLOSED");
        try {return signaling.request(type,body,requestId).get(16,TimeUnit.SECONDS);}
        catch(ExecutionException failure) {if(failure.getCause() instanceof Exception cause) throw cause;throw failure;}
    }
    private void disconnect() {
        systemAudio.setEnabled(false);
        sharing.resetOff();boolean published=publisher!=null;stopLocal();
        subscriptions.values().forEach(this::retire);subscriptions.clear();
        if(controlConnected && room.get()!=null) {
            try {
                if(published) signaling.request("SCREEN_STOP",new JsonObject(),UUID.randomUUID().toString()).get(3,TimeUnit.SECONDS);
                while(!retiringTransports.isEmpty()) {
                    var batch=retiringTransports.stream().limit(6).toList();
                    var ids=new com.google.gson.JsonArray();batch.forEach(ids::add);
                    var body=new JsonObject();body.add("transports",ids);
                    signaling.request("SCREEN_UNSUBSCRIBE",body,UUID.randomUUID().toString()).get(3,TimeUnit.SECONDS);
                    retiringTransports.removeAll(batch);
                }
            } catch(Exception failure) {failedReceive(failure);}
        }
    }
    private void show(String message) {
        latestStatus.set(message);if(!statusScheduled.compareAndSet(false,true)) return;
        Platform.runLater(() -> {String value=latestStatus.getAndSet(null);statusScheduled.set(false);if(!closed.get() && value != null && !Objects.equals(status.get(), value)) status.set(value);if(latestStatus.get()!=null) show(latestStatus.get());});
    }
    private void failedReceive(Throwable failure) {
        String code=CameraErrors.screenReceiveCode(failure);
        common.diagnostics.Diagnostics.current().failure("video.client.operation.failed",failure,Map.of("kind","SCREEN","phase","subscribe","code",code));
        show(CameraErrors.message(new SignalException(code)));
    }
    private void failed(Throwable failure) {
        common.diagnostics.Diagnostics.current().failure("video.client.operation.failed",failure,Map.of("kind","SCREEN","phase","screen","code",CameraErrors.screenCode(failure)));
        show(CameraErrors.screenMessage(failure));
    }
    private <T> CompletableFuture<T> submit(Callable<T> action) {
        if(closed.get()) return CompletableFuture.failedFuture(new SignalException("CONNECTION_CLOSED"));
        if(!admission.tryAcquire()) return CompletableFuture.failedFuture(new SignalException("MEDIA_BUSY"));
        CompletableFuture<T> result=new CompletableFuture<>();jobs.add(result);
        try {worker.execute(() -> {try {if(closed.get()) throw new SignalException("CONNECTION_CLOSED");result.complete(action.call());}
            catch(Exception | LinkageError failure) {result.completeExceptionally(failure);}finally {jobs.remove(result);admission.release();}});}
        catch(RejectedExecutionException failure) {jobs.remove(result);admission.release();result.completeExceptionally(new SignalException("CONNECTION_CLOSED"));}return result;
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        sharing.close();worker.shutdownNow();jobs.forEach(job -> job.completeExceptionally(new SignalException("CONNECTION_CLOSED")));jobs.clear();
        systemAudio.close();
        Thread.ofVirtual().name("screen-client-close").start(() -> {
            try {while(!worker.awaitTermination(2,TimeUnit.SECONDS)) {} disconnect();if(nativeSession!=null) nativeSession.close();frames.values().forEach(VideoFrameMailbox::close);systemAudio.closeComplete().get(24,TimeUnit.SECONDS);closeComplete.complete(null);}
            catch(Exception | LinkageError failure) {closeComplete.completeExceptionally(new IllegalStateException("SCREEN_CLEANUP_FAILED"));}
        });
    }
}
