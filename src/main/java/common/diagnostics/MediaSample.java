package common.diagnostics;

/**
 * A measured window of media statistics. Counters are deltas during intervalMillis,
 * not lifetime totals. Null means unavailable; no guessed or simulated values are logged.
 * trackId is a session-local number, never a user ID or name. Delays use milliseconds.
 * Byte counters count RTP payload bytes excluding protocol headers. Adapters must
 * skip a window when lifetime counters reset rather than submitting negative deltas.
 */
public record MediaSample(long trackId, Kind kind, long intervalMillis,
        Long packetsSent, Long packetsReceived, Long packetsLost,
        Long bytesSent, Long bytesReceived, Double rttMillis, Double jitterMillis,
        Long framesEncoded, Long framesDecoded, Long framesDropped) {
    public enum Kind { AUDIO, VIDEO, SCREEN }

    public MediaSample {
        if (trackId < 0 || kind == null || intervalMillis <= 0) {
            throw new IllegalArgumentException("Invalid media window identity or interval");
        }
        count(packetsSent);
        count(packetsReceived);
        count(packetsLost);
        count(bytesSent);
        count(bytesReceived);
        count(framesEncoded);
        count(framesDecoded);
        count(framesDropped);
        delay(rttMillis);
        delay(jitterMillis);
    }

    private static void count(Long value) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException("Media counters must be nonnegative");
        }
    }

    private static void delay(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException("Media delays must be finite and nonnegative");
        }
    }
}
