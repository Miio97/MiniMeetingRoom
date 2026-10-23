package client.media;

import dev.onvoid.webrtc.media.video.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deterministic I420 screen pattern, independent of OS desktop capture. */
final class GeneratedScreen implements VideoInput {
    private final CustomVideoSource source=new CustomVideoSource();
    private final ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed=new AtomicBoolean();
    private int frame;
    private final int width,height;
    private volatile boolean frozen;
    final java.util.concurrent.atomic.AtomicInteger starts=new java.util.concurrent.atomic.AtomicInteger();
    final java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger();
    GeneratedScreen() {this(640,360);}
    GeneratedScreen(int width,int height) {this.width=width;this.height=height;}
    void freeze() {frozen=true;}
    public boolean available() {return !closed.get();}
    public VideoTrackSource source() {return source;}
    public void start() {starts.incrementAndGet();producer.scheduleAtFixedRate(this::frame,0,100,TimeUnit.MILLISECONDS);}
    private synchronized void frame() {
        if(closed.get() || frozen) return;
        NativeI420Buffer buffer=NativeI420Buffer.allocate(width,height);
        try {
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) buffer.getDataY().put(y*buffer.getStrideY()+x,(byte)(40+((x/40+y/30+frame/10)%2)*140));
            for(int i=0;i<buffer.getDataU().capacity();i++) buffer.getDataU().put(i,(byte)90);
            for(int i=0;i<buffer.getDataV().capacity();i++) buffer.getDataV().put(i,(byte)180);
            frame++;source.pushFrame(new VideoFrame(buffer,System.nanoTime()));
        } finally {buffer.release();}
    }
    public synchronized void close() {if(closed.compareAndSet(false,true)) {closes.incrementAndGet();producer.shutdownNow();source.dispose();}}
}
