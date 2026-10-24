package client.network;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Per-meeting application traffic and ROOM_POLL response times, using a monotonic clock. */
public final class NetworkMonitor implements AutoCloseable {
    private static final long SECOND = Duration.ofSeconds(1).toNanos();
    private static final long WINDOW = 30 * SECOND;
    private static final long RATE_WINDOW = 5 * SECOND;

    public enum Quality {
        WAITING,
        GOOD,
        FAIR,
        POOR,
        DISCONNECTED,
        STOPPED
    }

    public record Sample(double second, Double rttMillis) {}

    public record Snapshot(
            Quality quality,
            Double rttMillis,
            Double jitterMillis,
            double sendBitsPerSecond,
            double receiveBitsPerSecond,
            long bytesSent,
            long bytesReceived,
            int requests,
            int failures,
            double failurePercent,
            double elapsedSeconds,
            List<Sample> history) {
        public Snapshot {
            history = List.copyOf(history);
        }
    }

    private record Poll(long at, Double rttMillis) {}

    private record Exchange(long at, boolean failed) {}

    private static final class Traffic {
        final long at;
        long sent, received;

        Traffic(long at) {
            this.at = at;
        }
    }

    private final LongSupplier clock;
    private final long started;
    private final Deque<Poll> polls = new ArrayDeque<>();
    private final Deque<Exchange> exchanges = new ArrayDeque<>();
    private final Deque<Traffic> traffic = new ArrayDeque<>();
    private long sent, received;
    private long lastPoll = -1;
    private boolean pollFailed, closed;
    private long stopped;

    public NetworkMonitor() {
        this(System::nanoTime);
    }

    NetworkMonitor(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        started = clock.getAsLong();
    }

    /** A valid server rejection is still a successful network exchange. */
    public synchronized void exchange(boolean poll, Double rttMillis) {
        if (closed) return;
        if (rttMillis != null && (!Double.isFinite(rttMillis) || rttMillis < 0)) {
            throw new IllegalArgumentException("Invalid response duration");
        }
        long now = now();
        prune(now);
        exchanges.addLast(new Exchange(now, rttMillis == null));
        if (poll) {
            lastPoll = now;
            pollFailed = rttMillis == null;
            polls.addLast(new Poll(now, rttMillis));
            while (polls.size() > 256) polls.removeFirst();
        }
    }

    private synchronized void bytes(long count, boolean outbound) {
        if (closed || count <= 0) return;
        long now = now();
        prune(now);
        long bucket = now / SECOND * SECOND;
        Traffic current = traffic.peekLast();
        if (current == null || current.at != bucket) {
            current = new Traffic(bucket);
            traffic.addLast(current);
        }
        if (outbound) {
            current.sent += count;
            sent += count;
        } else {
            current.received += count;
            received += count;
        }
    }

    public InputStream input(InputStream input) {
        return new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                int value = in.read();
                if (value >= 0) bytes(1, false);
                return value;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int count = in.read(buffer, offset, length);
                bytes(count, false);
                return count;
            }
        };
    }

    public OutputStream output(OutputStream output) {
        return new FilterOutputStream(output) {
            @Override
            public void write(int value) throws IOException {
                out.write(value);
                bytes(1, true);
            }

            @Override
            public void write(byte[] buffer, int offset, int length) throws IOException {
                out.write(buffer, offset, length);
                bytes(length, true);
            }
        };
    }

    public synchronized Snapshot snapshot() {
        long now = closed ? stopped : now();
        prune(now);
        Double rtt = null, jitter = null;
        double variation = 0;
        int pairs = 0, failures = 0;
        Poll previous = null;
        List<Sample> history = new ArrayList<>();
        for (Poll poll : polls) {
            history.add(new Sample(poll.at / (double) SECOND, poll.rttMillis));
            if (poll.rttMillis != null) {
                rtt = poll.rttMillis;
                if (previous != null
                        && previous.rttMillis != null
                        && poll.at - previous.at <= 3 * SECOND) {
                    variation += Math.abs(poll.rttMillis - previous.rttMillis);
                    pairs++;
                }
            }
            previous = poll;
        }
        if (pairs > 0) jitter = variation / pairs;
        for (Exchange exchange : exchanges) if (exchange.failed) failures++;
        double failurePercent = exchanges.isEmpty() ? 0 : failures * 100.0 / exchanges.size();
        long recentSent = 0, recentReceived = 0;
        for (Traffic bucket : traffic) {
            recentSent += bucket.sent;
            recentReceived += bucket.received;
        }
        // Include the entire oldest one-second bucket in both bytes and elapsed time.
        long rateStart = Math.max(0, (now - RATE_WINDOW) / SECOND * SECOND);
        double rateSeconds = (now - rateStart) / (double) SECOND;
        Double currentRtt = lastPoll < 0 || pollFailed || now - lastPoll > 5 * SECOND ? null : rtt;
        Quality quality;
        if (closed) quality = Quality.STOPPED;
        else if (lastPoll < 0) quality = now > 5 * SECOND ? Quality.DISCONNECTED : Quality.WAITING;
        else if (pollFailed || now - lastPoll > 5 * SECOND) quality = Quality.DISCONNECTED;
        else if (now - lastPoll > 3 * SECOND
                || rtt == null
                || rtt >= 160
                || (jitter != null && jitter >= 40)
                || failurePercent >= 10) quality = Quality.POOR;
        else if (rtt >= 80 || (jitter != null && jitter >= 20) || failures > 0)
            quality = Quality.FAIR;
        else quality = Quality.GOOD;
        return new Snapshot(
                quality,
                currentRtt,
                currentRtt == null ? null : jitter,
                rateSeconds <= 0 ? 0 : recentSent * 8.0 / rateSeconds,
                rateSeconds <= 0 ? 0 : recentReceived * 8.0 / rateSeconds,
                sent,
                received,
                exchanges.size(),
                failures,
                failurePercent,
                now / (double) SECOND,
                history);
    }

    private long now() {
        return Math.max(0, clock.getAsLong() - started);
    }

    private void prune(long now) {
        while (!polls.isEmpty() && polls.getFirst().at <= now - WINDOW) polls.removeFirst();
        while (!exchanges.isEmpty() && exchanges.getFirst().at <= now - WINDOW)
            exchanges.removeFirst();
        while (!traffic.isEmpty() && traffic.getFirst().at + SECOND <= now - RATE_WINDOW)
            traffic.removeFirst();
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            stopped = now();
            closed = true;
        }
    }
}
