package client.media;

import dev.onvoid.webrtc.media.video.*;
import dev.onvoid.webrtc.media.FourCC;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.*;

/** One latest heap frame, no native buffers retained across callbacks, at most one FX job. */
public final class VideoFrameMailbox implements VideoTrackSink, AutoCloseable {
    private record Pixels(long epoch, int width, int height, byte[] values) { }
    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / 30;
    private static final long FRAME_JITTER_NS = 8_000_000L;
    private final AtomicReference<Pixels> latest = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicLong nextFrame = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean clear = new AtomicBoolean();
    private final AtomicReference<byte[]> spare = new AtomicReference<>();
    public VideoTrackSink bind() {
        long epoch = generation.incrementAndGet(); nextFrame.set(0); clear();
        return frame -> receive(frame, epoch);
    }
    public void clear() { latest.set(null); clear.set(true); schedule(); }
    public void pause() { generation.incrementAndGet(); clear(); }
    private final AtomicLong received = new AtomicLong(), rendered = new AtomicLong(), dropped = new AtomicLong();
    private final ReadOnlyObjectWrapper<Image> image = new ReadOnlyObjectWrapper<>();
    public ReadOnlyObjectProperty<Image> imageProperty() { return image.getReadOnlyProperty(); }
    public long receivedFrames() { return received.get(); }
    public long renderedFrames() { return rendered.get(); }
    public long droppedFrames() { return dropped.get(); }
    @Override public void onVideoFrame(VideoFrame frame) {
        receive(frame, generation.get());
    }
    private void receive(VideoFrame frame, long epoch) {
        try {
            if (closed.get() || epoch != generation.get()) return;
            received.incrementAndGet();
            long now = System.nanoTime(), before = nextFrame.get();
            // Advance the cadence with tolerance for Windows UVC/OS scheduling jitter (~15.6 ms ticks).
            long target = before == 0 || now - before > FRAME_INTERVAL_NS
                    ? now + FRAME_INTERVAL_NS
                    : Math.max(now + FRAME_INTERVAL_NS - FRAME_JITTER_NS, before + FRAME_INTERVAL_NS);
            if (now < before - FRAME_JITTER_NS || !nextFrame.compareAndSet(before, target)) { dropped.incrementAndGet(); return; }
            if (!(frame.buffer instanceof I420Buffer buffer)) { dropped.incrementAndGet(); return; }
            int w = buffer.getWidth(), h = buffer.getHeight();
            if (w < 1 || h < 1 || w > 1280 || h > 720) { dropped.incrementAndGet(); return; }
            int rotation = frame.rotation;
            int width = rotation == 90 || rotation == 270 ? h : w, height = rotation == 90 || rotation == 270 ? w : h;
            byte[] pixels = spare.getAndSet(null);
            if(pixels==null || pixels.length!=w*h*4) pixels=new byte[w*h*4];
            // libyuv ARGB uses BGRA byte order, matching JavaFX's opaque BGRA pixels.
            // The native converter avoids a Java YUV conversion loop on the capture callback.
            try { VideoBufferConverter.convertFromI420(buffer,pixels,FourCC.ARGB); }
            catch(Exception failure) { dropped.incrementAndGet();return; }
            if(rotation!=0) {
                byte[] rotated=new byte[pixels.length];
                for(int row=0;row<h;row++) for(int column=0;column<w;column++) {
                    int x=column,destY=row;
                    if(rotation==90) {x=h-1-row;destY=column;}
                    else if(rotation==180) {x=w-1-column;destY=h-1-row;}
                    else if(rotation==270) {x=row;destY=w-1-column;}
                    System.arraycopy(pixels,(row*w+column)*4,rotated,(destY*width+x)*4,4);
                }
                spare.compareAndSet(null,pixels);pixels=rotated;
            }
            if (closed.get() || epoch != generation.get()) return;
            Pixels replaced=latest.getAndSet(new Pixels(epoch,width,height,pixels));
            if(replaced!=null) {spare.compareAndSet(null,replaced.values());dropped.incrementAndGet();}
            schedule();
        } finally { frame.release(); }
    }
    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            boolean requestedClear = clear.getAndSet(false);
            Pixels pixels = latest.getAndSet(null);
            if (closed.get() || (pixels == null && requestedClear) || (pixels != null && pixels.epoch() != generation.get())) {
                if (image.get() != null) image.set(null);
            } else if (pixels != null) {
                WritableImage target = image.get() instanceof WritableImage previous && previous.getWidth() == pixels.width()
                        && previous.getHeight() == pixels.height() ? previous : new WritableImage(pixels.width(), pixels.height());
                target.getPixelWriter().setPixels(0, 0, pixels.width(), pixels.height(), PixelFormat.getByteBgraPreInstance(), pixels.values(), 0, pixels.width()*4);
                if (image.get() != target) image.set(target);
                rendered.incrementAndGet();
            }
            if(pixels!=null && !closed.get()) spare.compareAndSet(null,pixels.values());
            scheduled.set(false); if (latest.get() != null || clear.get()) schedule();
        });
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) { generation.incrementAndGet(); latest.set(null); spare.set(null); schedule(); } }
}
