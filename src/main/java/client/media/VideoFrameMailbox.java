package client.media;

import dev.onvoid.webrtc.media.video.*;
import java.util.concurrent.atomic.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.*;

/** One latest heap frame, no native buffers retained across callbacks, at most one FX job. */
public final class VideoFrameMailbox implements VideoTrackSink, AutoCloseable {
    private record Pixels(long epoch, int width, int height, int[] values) { }
    private final AtomicReference<Pixels> latest = new AtomicReference<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(), closed = new AtomicBoolean();
    private final AtomicLong nextFrame = new AtomicLong();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean clear = new AtomicBoolean();
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
            if (now < before || !nextFrame.compareAndSet(before, now + 100_000_000L)) { dropped.incrementAndGet(); return; }
            if (!(frame.buffer instanceof I420Buffer buffer)) { dropped.incrementAndGet(); return; }
            int w = buffer.getWidth(), h = buffer.getHeight();
            if (w < 1 || h < 1 || w > 1280 || h > 720) { dropped.incrementAndGet(); return; }
            int rotation = frame.rotation;
            int width = rotation == 90 || rotation == 270 ? h : w, height = rotation == 90 || rotation == 270 ? w : h;
            int[] pixels = new int[width * height];
            var y = buffer.getDataY(); var u = buffer.getDataU(); var v = buffer.getDataV();
            for (int row = 0; row < h; row++) for (int column = 0; column < w; column++) {
                int luma = Math.max(0, (y.get(row * buffer.getStrideY() + column) & 255) - 16);
                int blue = (u.get((row / 2) * buffer.getStrideU() + column / 2) & 255) - 128;
                int red = (v.get((row / 2) * buffer.getStrideV() + column / 2) & 255) - 128;
                int rgb = 0xff000000 | clamp((298 * luma + 409 * red + 128) >> 8) << 16
                        | clamp((298 * luma - 100 * blue - 208 * red + 128) >> 8) << 8 | clamp((298 * luma + 516 * blue + 128) >> 8);
                int x = column, destY = row;
                if (rotation == 90) { x = h - 1 - row; destY = column; }
                else if (rotation == 180) { x = w - 1 - column; destY = h - 1 - row; }
                else if (rotation == 270) { x = row; destY = w - 1 - column; }
                pixels[destY * width + x] = rgb;
            }
            if (closed.get() || epoch != generation.get()) return;
            if (latest.getAndSet(new Pixels(epoch, width, height, pixels)) != null) dropped.incrementAndGet();
            schedule();
        } finally { frame.release(); }
    }
    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }
    private void schedule() {
        if (!scheduled.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            Pixels pixels = latest.getAndSet(null);
            boolean requestedClear = clear.getAndSet(false);
            if (closed.get() || requestedClear || pixels != null && pixels.epoch() != generation.get()) image.set(null);
            else if (pixels != null) {
                WritableImage target = image.get() instanceof WritableImage previous && previous.getWidth() == pixels.width()
                        && previous.getHeight() == pixels.height() ? previous : new WritableImage(pixels.width(), pixels.height());
                target.getPixelWriter().setPixels(0, 0, pixels.width(), pixels.height(), PixelFormat.getIntArgbPreInstance(), pixels.values(), 0, pixels.width());
                image.set(target); rendered.incrementAndGet();
            }
            scheduled.set(false); if (latest.get() != null || clear.get()) schedule();
        });
    }
    @Override public void close() { if (closed.compareAndSet(false, true)) { generation.incrementAndGet(); latest.set(null); schedule(); } }
}
