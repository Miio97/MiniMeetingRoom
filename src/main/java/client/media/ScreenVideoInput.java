package client.media;

import dev.onvoid.webrtc.media.video.*;
import dev.onvoid.webrtc.media.video.desktop.*;
import java.util.*;
import java.util.concurrent.*;

/** SDK 0.19.0 desktop capture; source identifiers and titles stay local to the picker. */
public final class ScreenVideoInput implements VideoInput {
    public record Choice(String title, long id, boolean window) {
        @Override public String toString() { return title; }
    }
    public record Settings(int width, int height, int fps, int bitrate) {
        public static Settings configured() {
            return new Settings(number("width",1280,320,1280),number("height",720,240,720),
                    number("fps",10,1,15),number("bitrate",1800000,200000,5000000));
        }
        private static int number(String key,int fallback,int min,int max) {
            int value=Integer.getInteger("mmr.screen."+key,fallback);return Math.max(min,Math.min(max,value));
        }
    }
    private final CustomVideoSource source=new CustomVideoSource();
    private final Choice choice;
    private final Settings settings=Settings.configured();
    // Create, use and dispose the OS capturer on the same thread (DXGI/WGC affinity).
    private final ScheduledThreadPoolExecutor owner=new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("screen-capture-owner").factory());
    private final Object frameLock=new Object();
    private DesktopCapturer capturer;
    private long lastSourceCheck;
    private volatile boolean closed,failed;
    private boolean started;
    private CompletableFuture<Void> disposal;
    public ScreenVideoInput(Choice choice) {
        this.choice=Objects.requireNonNull(choice);
        owner.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        owner.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        owner.setRemoveOnCancelPolicy(true);
    }
    public static List<Choice> choices() {
        List<Choice> result=new ArrayList<>();
        for(boolean window:new boolean[]{false,true}) {
            DesktopCapturer capturer=window ? new WindowCapturer() : new ScreenCapturer();
            try { for(DesktopSource source:capturer.getDesktopSources()) result.add(new Choice(source.title,source.id,window)); }
            finally {capturer.dispose();}
        }
        return List.copyOf(result);
    }
    public boolean available() {return !closed && !failed;}
    public VideoTrackSource source() {return source;}
    @Override public int fps() {return settings.fps();}
    public synchronized void start() {
        if(closed) throw new IllegalStateException("SCREEN_CAPTURE_FAILED");
        if(started) return;
        started=true;
        owner.scheduleWithFixedDelay(this::capture,0,Math.max(1,1000/settings.fps()),TimeUnit.MILLISECONDS);
    }
    private void capture() {
        if(closed || failed) return;
        try {
            if(capturer==null) {
                capturer=choice.window() ? new WindowCapturer() : new ScreenCapturer();
                // VideoDesktopSource hard-codes focus=true without exposing a Java setter.
                // The lower-level capturer lets preview and publish leave the picker in front.
                capturer.setFocusSelectedSource(false);
                capturer.setMaxFrameRate(settings.fps());
                var selected=capturer.getDesktopSources().stream().filter(s -> s.id==choice.id()).findFirst()
                        .orElseThrow(() -> new IllegalStateException("SCREEN_CAPTURE_FAILED"));
                capturer.selectSource(selected);
                capturer.start(this::captured);
                lastSourceCheck=System.nanoTime();
            }
            if(System.nanoTime()-lastSourceCheck>TimeUnit.SECONDS.toNanos(3)) {
                lastSourceCheck=System.nanoTime();
                if(capturer.getDesktopSources().stream().noneMatch(s -> s.id==choice.id())) {failed=true;return;}
            }
            capturer.captureFrame();
        } catch(Exception | LinkageError failure) {failed=true;}
        catch(Error failure) {if(failure.getClass()!=Error.class) throw failure;failed=true;}
    }
    private void captured(DesktopCapturer.Result result,VideoFrame frame) {
        try {
            if(result==DesktopCapturer.Result.ERROR_PERMANENT) failed=true;
            if(result!=DesktopCapturer.Result.SUCCESS || frame==null) return;
            synchronized(frameLock) {
                if(closed || failed) return;
                int w=frame.buffer.getWidth(),h=frame.buffer.getHeight();
                // Minimized windows can yield a 1x1 frame; retain the last usable image.
                if(w<2 || h<2) return;
                double scale=Math.min(1,Math.min((double)settings.width()/w,(double)settings.height()/h));
                if(scale<1) {
                    int width=Math.max(2,((int)(w*scale)) & ~1),height=Math.max(2,((int)(h*scale)) & ~1);
                    var scaled=frame.buffer.cropAndScale(0,0,w,h,width,height);
                    try {source.pushFrame(new VideoFrame(scaled,frame.rotation,frame.timestampNs));}
                    finally {scaled.release();}
                } else source.pushFrame(frame);
            }
        } catch(Exception | LinkageError failure) {failed=true;}
        catch(Error failure) {if(failure.getClass()!=Error.class) throw failure;failed=true;}
        finally {if(frame!=null) frame.release();}
    }
    public void close() {
        CompletableFuture<Void> done;
        synchronized(this) {
            if(disposal==null) {
                closed=true;disposal=new CompletableFuture<>();
                owner.execute(() -> {
                    try {
                        try {if(capturer!=null) {capturer.dispose();capturer=null;}}
                        finally {synchronized(frameLock) {source.dispose();}}
                        disposal.complete(null);
                    } catch(Exception | LinkageError failure) {disposal.completeExceptionally(new IllegalStateException("SCREEN_CLEANUP_FAILED"));}
                    catch(Error failure) {disposal.completeExceptionally(new IllegalStateException("SCREEN_CLEANUP_FAILED"));if(failure.getClass()!=Error.class) throw failure;}
                });
                owner.shutdown();
            }
            done=disposal;
        }
        done.join();
    }
}
