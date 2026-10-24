package client.media;

import java.util.concurrent.TimeUnit;

/** RTP progress, not decoded PCM: a decoder can produce concealment silence forever. */
final class AudioRtpWatchdog {
    private static final long TIMEOUT = TimeUnit.SECONDS.toNanos(15);
    private long lastProgress, packets = -1;

    AudioRtpWatchdog(long now) {
        lastProgress = now;
    }

    synchronized void reset(long now) {
        lastProgress = now;
        packets = -1;
    }

    synchronized void observe(long count, long now) {
        if (count < 0) return;
        if (packets < 0 || count != packets) lastProgress = now;
        packets = count;
    }

    synchronized boolean stalled(long now) {
        return now - lastProgress >= TIMEOUT;
    }
}
