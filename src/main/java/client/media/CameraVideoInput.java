package client.media;

import dev.onvoid.webrtc.media.video.*;

public final class CameraVideoInput implements VideoInput {
    private final VideoDeviceSource source = new VideoDeviceSource();
    private boolean closed;
    public CameraVideoInput(VideoDevice device) {
        this(device, dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureCapabilities(device));
    }
    public CameraVideoInput(VideoDevice device, java.util.List<VideoCaptureCapability> capabilities) {
        try {
            var capability = capabilities.stream().filter(c -> c.width > 0 && c.height > 0 && c.width <= 1280 && c.height <= 720)
                    .min(java.util.Comparator.comparingInt(c -> Math.abs(c.width - 640) + Math.abs(c.height - 480) + Math.abs(c.frameRate - 15)))
                    .orElseThrow(() -> new IllegalStateException("CAMERA_FORMAT_UNAVAILABLE"));
            source.setVideoCaptureDevice(device); source.setVideoCaptureCapability(capability);
        }
        catch (RuntimeException failure) { source.dispose(); throw failure; }
    }
    public VideoTrackSource source() { return source; }
    public synchronized void start() { if (closed) throw new IllegalStateException("CAMERA_CLOSED"); source.start(); }
    public synchronized void close() { if (!closed) { closed = true; try { source.stop(); } finally { source.dispose(); } } }
}
