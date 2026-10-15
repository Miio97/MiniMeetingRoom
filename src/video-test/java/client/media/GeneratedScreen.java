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
    public VideoTrackSource source() {return source;}
    public void start() {producer.scheduleAtFixedRate(this::frame,0,100,TimeUnit.MILLISECONDS);}
    private synchronized void frame() {
        if(closed.get()) return;
        NativeI420Buffer buffer=NativeI420Buffer.allocate(640,360);
        try {
            for(int y=0;y<360;y++) for(int x=0;x<640;x++) buffer.getDataY().put(y*buffer.getStrideY()+x,(byte)(40+((x/40+y/30+frame/10)%2)*140));
            for(int i=0;i<buffer.getDataU().capacity();i++) buffer.getDataU().put(i,(byte)90);
            for(int i=0;i<buffer.getDataV().capacity();i++) buffer.getDataV().put(i,(byte)180);
            frame++;source.pushFrame(new VideoFrame(buffer,System.nanoTime()));
        } finally {buffer.release();}
    }
    public synchronized void close() {if(closed.compareAndSet(false,true)) {producer.shutdownNow();source.dispose();}}
}
