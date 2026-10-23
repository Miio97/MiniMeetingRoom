package client.media;

import dev.onvoid.webrtc.media.video.VideoTrackSource;

/** Camera production input; deterministic sources can be supplied by integration tests. */
public interface VideoInput extends AutoCloseable {
    VideoTrackSource source();
    default int width() { return 0; }
    default int height() { return 0; }
    default int fps() { return 30; }
    default boolean available() { return true; }
    void start();
    @Override void close();
}
