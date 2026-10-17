package client.media;

/**
 * Calculates WebRTC network statistics deltas over a monotonic clock window.
 * Converts seconds to milliseconds, handles counter resets and null metrics.
 */
public final class NetworkStatsCalculator {
    private long prevTimestampNanos = -1;
    private Long prevBytesSent;
    private Long prevFramesSent;
    private Long prevPacketsLost;
    private Long prevPacketsReceived;

    public record RawCounters(
            long timestampNanos,
            Double rttSeconds,
            Double maxJitterSeconds,
            Long packetsLost,
            Long packetsReceived,
            Long bytesSent,
            Long framesSent,
            Integer width,
            Integer height
    ) { }

    public record CalculatedSample(
            Double rttMs,
            Double jitterMs,
            Double lossPct,
            Integer bitrateKbps,
            Integer fps,
            Integer width,
            Integer height
    ) {
        public boolean isEmpty() {
            return rttMs == null && jitterMs == null && lossPct == null
                    && bitrateKbps == null && fps == null && width == null && height == null;
        }
    }

    public synchronized CalculatedSample process(RawCounters raw) {
        Double rttMs = raw.rttSeconds() != null && raw.rttSeconds() >= 0 ? raw.rttSeconds() * 1000.0 : null;
        Double jitterMs = raw.maxJitterSeconds() != null && raw.maxJitterSeconds() >= 0 ? raw.maxJitterSeconds() * 1000.0 : null;

        Double lossPct = null;
        if (raw.packetsLost() != null && raw.packetsReceived() != null) {
            if (prevPacketsLost != null && prevPacketsReceived != null) {
                long deltaLost = raw.packetsLost() - prevPacketsLost;
                long deltaRecv = raw.packetsReceived() - prevPacketsReceived;
                if (deltaLost >= 0 && deltaRecv >= 0) {
                    long totalDelta = deltaLost + deltaRecv;
                    if (totalDelta > 0) {
                        lossPct = (deltaLost * 100.0) / (double) totalDelta;
                    }
                }
                // Counter reset / negative delta: window discarded, loss remains null
            }
            prevPacketsLost = raw.packetsLost();
            prevPacketsReceived = raw.packetsReceived();
        }

        Integer bitrateKbps = null;
        Integer fps = null;
        long now = raw.timestampNanos();
        if (prevTimestampNanos > 0 && now > prevTimestampNanos) {
            double elapsedSeconds = (now - prevTimestampNanos) / 1_000_000_000.0;
            if (elapsedSeconds > 0) {
                if (raw.bytesSent() != null && prevBytesSent != null) {
                    long deltaBytes = raw.bytesSent() - prevBytesSent;
                    if (deltaBytes >= 0) {
                        bitrateKbps = (int) Math.round((deltaBytes * 8.0 / 1000.0) / elapsedSeconds);
                    }
                }
                if (raw.framesSent() != null && prevFramesSent != null) {
                    long deltaFrames = raw.framesSent() - prevFramesSent;
                    if (deltaFrames >= 0) {
                        fps = (int) Math.round(deltaFrames / elapsedSeconds);
                    }
                }
            }
        }
        prevTimestampNanos = now;
        if (raw.bytesSent() != null) prevBytesSent = raw.bytesSent();
        if (raw.framesSent() != null) prevFramesSent = raw.framesSent();

        Integer width = raw.width() != null && raw.width() > 0 ? raw.width() : null;
        Integer height = raw.height() != null && raw.height() > 0 ? raw.height() : null;

        return new CalculatedSample(rttMs, jitterMs, lossPct, bitrateKbps, fps, width, height);
    }
}
