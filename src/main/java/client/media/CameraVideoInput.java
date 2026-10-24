package client.media;

import dev.onvoid.webrtc.media.video.*;

public final class CameraVideoInput implements VideoInput {
    private final VideoDeviceSource source = new VideoDeviceSource();
    private boolean closed;
    private final VideoCaptureCapability capability;

    public CameraVideoInput(VideoDevice device) {
        this(device, dev.onvoid.webrtc.media.MediaDevices.getVideoCaptureCapabilities(device));
    }

    public CameraVideoInput(
            VideoDevice device, java.util.List<VideoCaptureCapability> capabilities) {
        try {
            capability = select(capabilities);
            source.setVideoCaptureDevice(device);
            source.setVideoCaptureCapability(capability);
        } catch (RuntimeException failure) {
            source.dispose();
            throw failure;
        }
    }

    public VideoTrackSource source() {
        return source;
    }

    public int width() {
        return capability.width;
    }

    public int height() {
        return capability.height;
    }

    public int fps() {
        return capability.frameRate;
    }

    static VideoCaptureCapability select(java.util.List<VideoCaptureCapability> capabilities) {
        return capabilities.stream()
                .filter(
                        c ->
                                c.width > 0
                                        && c.height > 0
                                        && c.width <= 1280
                                        && c.height <= 720
                                        && c.frameRate > 0
                                        && c.frameRate <= 30)
                // Prefer smooth capture: 720p/8 must not beat 480p/30 merely for its dimensions.
                .min(
                        java.util.Comparator.<VideoCaptureCapability>comparingInt(c -> -c.frameRate)
                                .thenComparingInt(c -> -(c.width * c.height)))
                .orElseThrow(() -> new IllegalStateException("CAMERA_FORMAT_UNAVAILABLE"));
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("CAMERA_CLOSED");
        source.start();
    }

    public synchronized void close() {
        if (!closed) {
            closed = true;
            try {
                source.stop();
            } finally {
                source.dispose();
            }
        }
    }
}
