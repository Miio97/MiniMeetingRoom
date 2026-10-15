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
    private record Subscription(String publication,NativeVideoSession.Peer peer) { }
    private final VideoCallController.Signaling signaling;
    private final Supplier<RoomSnapshot> room;
    private final Supplier<String> self;
    private final Supplier<List<ScreenVideoInput.Choice>> catalog;
    private final Function<ScreenVideoInput.Choice,VideoInput> inputFactory;
    private final ScheduledThreadPoolExecutor worker=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("screen-client-owner").factory());
    private final AtomicBoolean closed=new AtomicBoolean(),uiScheduled=new AtomicBoolean(),statusScheduled=new AtomicBoolean();
    private final AtomicReference<String> latestStatus=new AtomicReference<>();
    private final Semaphore admission=new Semaphore(16);
    private final Set<CompletableFuture<?>> jobs=ConcurrentHashMap.newKeySet();
    private final Map<String,VideoFrameMailbox> frames=new ConcurrentHashMap<>();
    private final Map<String,Subscription> subscriptions=new HashMap<>();
    private final ReadOnlyBooleanWrapper requested=new ReadOnlyBooleanWrapper();
    private final ReadOnlyStringWrapper status=new ReadOnlyStringWrapper("");
    private final CameraSwitch sharing=new CameraSwitch(this::changed);
    private final CompletableFuture<Void> closeComplete=new CompletableFuture<>();
    private volatile boolean active,entered;
    private final AtomicLong previewEpoch=new AtomicLong();
    private NativeVideoSession nativeSession;
    private NativeVideoSession.Peer publisher;
    private NativeVideoSession.Preview preview;
    private VideoInput input;
    private long lastFrameCount,lastProgress,lastSourceCheck;
    public ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,boolean headless) {
        this(signaling,room,self,headless ? List::of : ScreenVideoInput::choices);
    }
    public ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,Supplier<List<ScreenVideoInput.Choice>> catalog) {
        this(signaling,room,self,catalog,ScreenVideoInput::new);
    }
    public ScreenCallController(VideoCallController.Signaling signaling,Supplier<RoomSnapshot> room,Supplier<String> self,
            Supplier<List<ScreenVideoInput.Choice>> catalog,Function<ScreenVideoInput.Choice,VideoInput> inputFactory) {
        this.signaling=signaling;this.room=room;this.self=self;this.catalog=catalog;this.inputFactory=inputFactory;worker.setRemoveOnCancelPolicy(true);
        worker.scheduleWithFixedDelay(() -> {
            if(!entered || closed.get()) return;
            try {synchronize();} catch(Exception | LinkageError failure) {disconnect();failed(failure);}
        },1,1,TimeUnit.SECONDS);
    }
    public void enter() {entered=true;}
    public ReadOnlyBooleanProperty requestedProperty() {return requested.getReadOnlyProperty();}
    public ReadOnlyStringProperty statusProperty() {return status.getReadOnlyProperty();}
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
                }}
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
    private void changed() {
        Runnable update=() -> requested.set(!closed.get() && (sharing.pending() ? sharing.wanted() : active));
        if(Platform.isFxApplicationThread()) update.run();else if(uiScheduled.compareAndSet(false,true)) Platform.runLater(() -> {uiScheduled.set(false);update.run();});
    }
    private void ensureNative() {if(nativeSession==null) nativeSession=new NativeVideoSession();}
    private void wanted() {if(!sharing.wanted() || closed.get()) throw new CancellationException();}
    private void start(Supplier<VideoInput> factory) throws Exception {
        wanted();if(!entered || room.get()==null) throw new SignalException("NOT_A_MEMBER");
        boolean attempted=false;stopLocal();
        try {
            ensureNative();wanted();input=factory.get();wanted();
            var settings=ScreenVideoInput.Settings.configured();
            publisher=nativeSession.publisher(input,frames(self.get()),settings.bitrate(),settings.fps());
            long before=frames(self.get()).receivedFrames();wanted();input.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(frames(self.get()).receivedFrames()<=before) {wanted();if(System.nanoTime()>deadline) throw new SignalException("SCREEN_NO_FRAMES");Thread.sleep(30);}
            JsonObject body=new JsonObject();body.addProperty("sdp",publisher.offer());wanted();attempted=true;
            publisher.answer(command("SCREEN_PUBLISH",body).get("sdp").getAsString());wanted();active=true;
            lastFrameCount=frames(self.get()).receivedFrames();lastProgress=System.nanoTime();changed();show("");
        } catch(Exception | LinkageError failure) {stop(attempted);if(!(failure instanceof CancellationException)) failed(failure);throw failure;}
    }
    private void stop(boolean notify) throws Exception {stopLocal();if(notify && room.get()!=null) command("SCREEN_STOP",new JsonObject());}
    private void stopLocal() {
        active=false;VideoInput current=input;input=null;
        try {if(current!=null) current.close();}
        finally {if(preview!=null) {preview.close();preview=null;}if(publisher!=null) {publisher.close();publisher=null;}changed();}
    }
    private void synchronize() throws Exception {
        RoomSnapshot snapshot=room.get();if(snapshot==null) {disconnect();return;}
        if(publisher!=null && (publisher.failed() || snapshot.participants().stream().filter(p -> p.participantId().equals(self.get())).noneMatch(p -> p.mediaState().shareRequested()))) {
            sharing.resetOff();stop(true);
        }
        if(active) {
            if(input instanceof ScreenVideoInput desktop && System.nanoTime()-lastSourceCheck>TimeUnit.SECONDS.toNanos(3)) {
                lastSourceCheck=System.nanoTime();if(!desktop.available()) {sharing.resetOff();stop(true);throw new SignalException("SCREEN_CAPTURE_FAILED");}
            }
            long count=frames(self.get()).receivedFrames();if(count!=lastFrameCount) {lastFrameCount=count;lastProgress=System.nanoTime();}
            if(System.nanoTime()-lastProgress>TimeUnit.SECONDS.toNanos(8)) {sharing.resetOff();stop(true);throw new SignalException("SCREEN_NO_FRAMES");}
        }
        JsonObject sources=command("SCREEN_STATE",new JsonObject()).getAsJsonObject("sources");
        for(String target:new ArrayList<>(subscriptions.keySet())) {
            var subscription=subscriptions.get(target);
            if(!sources.has(target) || !subscription.publication().equals(sources.get(target).getAsString()) || subscription.peer().failed()) {
                subscription.peer().close();subscriptions.remove(target);
            }
        }
        for(var source:sources.entrySet()) {
            if(closed.get()) return;
            String target=source.getKey();if(target.equals(self.get()) || subscriptions.containsKey(target)) continue;
            ensureNative();NativeVideoSession.Peer peer=nativeSession.subscriber(frames(target));
            try {
                JsonObject body=new JsonObject();body.addProperty("source",target);body.addProperty("publication",source.getValue().getAsString());body.addProperty("sdp",peer.offer());
                peer.answer(command("SCREEN_SUBSCRIBE",body).get("sdp").getAsString());subscriptions.put(target,new Subscription(source.getValue().getAsString(),peer));
            } catch(Exception failure) {peer.close();failed(failure);}
        }
        for(String participant:new ArrayList<>(frames.keySet())) if(snapshot.participants().stream().noneMatch(p -> p.participantId().equals(participant))) {
            VideoFrameMailbox old=frames.remove(participant);if(old!=null) old.close();
        }
    }
    private JsonObject command(String type,JsonObject body) throws Exception {
        if(closed.get()) throw new SignalException("CONNECTION_CLOSED");
        try {return signaling.request(type,body,UUID.randomUUID().toString()).get(16,TimeUnit.SECONDS);}
        catch(ExecutionException failure) {if(failure.getCause() instanceof Exception cause) throw cause;throw failure;}
    }
    private void disconnect() {sharing.resetOff();stopLocal();subscriptions.values().forEach(s -> s.peer().close());subscriptions.clear();}
    private void show(String message) {
        latestStatus.set(message);if(!statusScheduled.compareAndSet(false,true)) return;
        Platform.runLater(() -> {String value=latestStatus.getAndSet(null);statusScheduled.set(false);if(!closed.get()) status.set(value);if(latestStatus.get()!=null) show(latestStatus.get());});
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
        Thread.ofVirtual().name("screen-client-close").start(() -> {
            try {while(!worker.awaitTermination(2,TimeUnit.SECONDS)) {} disconnect();if(nativeSession!=null) nativeSession.close();frames.values().forEach(VideoFrameMailbox::close);closeComplete.complete(null);}
            catch(Exception | LinkageError failure) {closeComplete.completeExceptionally(new IllegalStateException("SCREEN_CLEANUP_FAILED"));}
        });
    }
}
