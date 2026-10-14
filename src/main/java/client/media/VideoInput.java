package client.media;

import dev.onvoid.webrtc.media.video.VideoTrackSource;

/** Camera production input; deterministic sources can be supplied by integration tests. */
public interface VideoInput extends AutoCloseable {
    VideoTrackSource source();
    void start();
    @Override void close();
}
