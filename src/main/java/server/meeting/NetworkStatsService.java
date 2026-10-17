package server.meeting;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import server.auth.ConnectionProvider;
import server.config.ServerConfig;

/**
 * Service to buffer, batch insert and clean up network statistics in Thong_Ke_Ket_Noi.
 * Uses a bounded queue (1024 capacity), dropping the oldest sample when full.
 * Performs a read-only preflight check on startup; disables itself gracefully if table is absent.
 */
public final class NetworkStatsService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(NetworkStatsService.class.getName());
    private static final int QUEUE_CAPACITY = 1024;
    private static final int CLEANUP_BATCH_SIZE = 5000;

    public static final double RTT_GOOD_THRESHOLD_MS = 80.0;
    public static final double RTT_MEDIUM_THRESHOLD_MS = 160.0;
    public static final double LOSS_GOOD_THRESHOLD_PCT = 1.0;
    public static final double LOSS_MEDIUM_THRESHOLD_PCT = 3.0;
    public static final double JITTER_GOOD_THRESHOLD_MS = 20.0;
    public static final double JITTER_MEDIUM_THRESHOLD_MS = 40.0;

    public record StatsRecord(
            long roomId,
            long userId,
            Double rttMs,
            Double jitterMs,
            Double lossPct,
            Integer bitrateKbps,
            Integer fps,
            Integer width,
            Integer height,
            String quality,
            Instant sampledAt
    ) { }

    public static final int DEFAULT_FLUSH_INTERVAL_SECONDS = 10;

    private final ConnectionProvider connections;
    private final Clock clock;
    private final int sampleIntervalMs;
    private final int flushIntervalSeconds;
    private final int cleanupIntervalSeconds;
    private final int retentionSeconds;

    private final BlockingQueue<StatsRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong droppedSamples = new AtomicLong();
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2, Thread.ofPlatform().daemon().name("network-stats-", 0).factory());

    public NetworkStatsService(ConnectionProvider connections, Clock clock) {
        this(connections, clock,
                ServerConfig.getInt("stats.sample-interval-ms", 1000),
                ServerConfig.getInt("stats.flush-interval-seconds", DEFAULT_FLUSH_INTERVAL_SECONDS),
                ServerConfig.getInt("stats.cleanup-interval-seconds", 30),
                ServerConfig.getInt("stats.retention-seconds", 60));
    }

    public NetworkStatsService(ConnectionProvider connections, Clock clock,
                               int sampleIntervalMs, int cleanupIntervalSeconds, int retentionSeconds) {
        this(connections, clock, sampleIntervalMs, DEFAULT_FLUSH_INTERVAL_SECONDS, cleanupIntervalSeconds, retentionSeconds);
    }

    public NetworkStatsService(ConnectionProvider connections, Clock clock,
                               int sampleIntervalMs, int flushIntervalSeconds, int cleanupIntervalSeconds, int retentionSeconds) {
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
        this.sampleIntervalMs = Math.max(100, sampleIntervalMs);
        this.flushIntervalSeconds = Math.max(1, flushIntervalSeconds);
        this.cleanupIntervalSeconds = Math.max(1, cleanupIntervalSeconds);
        this.retentionSeconds = Math.max(0, retentionSeconds);
    }

    public boolean isEnabled() { return enabled.get(); }
    public long droppedSamples() { return droppedSamples.get(); }
    public int queuedCount() { return queue.size(); }
    public int flushIntervalSeconds() { return flushIntervalSeconds; }
    public int retentionSeconds() { return retentionSeconds; }

    public static String calculateQuality(Double rtt, Double jitter, Double loss) {
        if (rtt == null && jitter == null && loss == null) return null;
        boolean hasPoor = (rtt != null && rtt >= RTT_MEDIUM_THRESHOLD_MS)
                || (loss != null && loss >= LOSS_MEDIUM_THRESHOLD_PCT)
                || (jitter != null && jitter >= JITTER_MEDIUM_THRESHOLD_MS);
        if (hasPoor) return "KEM";
        boolean hasMedium = (rtt != null && rtt >= RTT_GOOD_THRESHOLD_MS)
                || (loss != null && loss >= LOSS_GOOD_THRESHOLD_PCT)
                || (jitter != null && jitter >= JITTER_GOOD_THRESHOLD_MS);
        if (hasMedium) return "TRUNG_BINH";
        return "TOT";
    }

    public boolean preflight() {
        try (var conn = connections.getConnection(); var stmt = conn.createStatement()) {
            stmt.executeQuery("SELECT Ma_Thong_Ke, Ma_Phong, Ma_Nguoi_Dung, Do_Tre_Ms, Do_Rung_Ms, " +
                    "Ty_Le_Mat_Goi_Tin, Bitrate_Kbps, FPS, Chieu_Rong, Chieu_Cao, Trang_Thai_Ket_Noi, " +
                    "Thoi_Diem_Lay_Mau FROM Thong_Ke_Ket_Noi WHERE 1=0").close();
            enabled.set(true);
            return true;
        } catch (SQLException e) {
            LOG.warning("NETWORK_STATS_DISABLED: Thong_Ke_Ket_Noi table missing or inaccessible (code=" + e.getSQLState() + ")");
            enabled.set(false);
            return false;
        }
    }

    public synchronized void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        if (!preflight()) return;

        // Schedule periodic batch insert (every flushIntervalSeconds, e.g. 10 seconds, writing 10 samples per participant)
        scheduler.scheduleWithFixedDelay(this::flushBatch, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);

        // Schedule periodic cleanup (every cleanupIntervalSeconds)
        scheduler.scheduleWithFixedDelay(this::cleanup, cleanupIntervalSeconds, cleanupIntervalSeconds, TimeUnit.SECONDS);
    }

    public boolean record(StatsRecord record) {
        if (!enabled.get() || closed.get()) return false;
        while (!queue.offer(record)) {
            // Drop oldest sample when queue is full
            queue.poll();
            droppedSamples.incrementAndGet();
        }
        return true;
    }

    public int flushBatch() {
        if (!enabled.get() || closed.get()) return 0;
        List<StatsRecord> batch = new ArrayList<>();
        queue.drainTo(batch, QUEUE_CAPACITY);
        if (batch.isEmpty()) return 0;

        String insertSql = "INSERT INTO Thong_Ke_Ket_Noi (" +
                "Ma_Phong, Ma_Nguoi_Dung, Do_Tre_Ms, Do_Rung_Ms, Ty_Le_Mat_Goi_Tin, " +
                "Bitrate_Kbps, FPS, Chieu_Rong, Chieu_Cao, Trang_Thai_Ket_Noi, Thoi_Diem_Lay_Mau) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (var conn = connections.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement(insertSql)) {
                for (StatsRecord r : batch) {
                    ps.setLong(1, r.roomId());
                    ps.setLong(2, r.userId());
                    if (r.rttMs() != null) ps.setDouble(3, r.rttMs()); else ps.setNull(3, java.sql.Types.DECIMAL);
                    if (r.jitterMs() != null) ps.setDouble(4, r.jitterMs()); else ps.setNull(4, java.sql.Types.DECIMAL);
                    if (r.lossPct() != null) ps.setDouble(5, r.lossPct()); else ps.setNull(5, java.sql.Types.DECIMAL);
                    if (r.bitrateKbps() != null) ps.setInt(6, r.bitrateKbps()); else ps.setNull(6, java.sql.Types.INTEGER);
                    if (r.fps() != null) ps.setInt(7, r.fps()); else ps.setNull(7, java.sql.Types.SMALLINT);
                    if (r.width() != null) ps.setInt(8, r.width()); else ps.setNull(8, java.sql.Types.SMALLINT);
                    if (r.height() != null) ps.setInt(9, r.height()); else ps.setNull(9, java.sql.Types.SMALLINT);
                    if (r.quality() != null) ps.setString(10, r.quality()); else ps.setNull(10, java.sql.Types.VARCHAR);
                    ps.setTimestamp(11, Timestamp.from(r.sampledAt()));
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                return batch.size();
            } catch (SQLException failure) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                LOG.warning("NETWORK_STATS_BATCH_INSERT_FAILED: " + failure.getSQLState());
                return 0;
            } finally {
                try { conn.setAutoCommit(autoCommit); } catch (SQLException ignored) { }
            }
        } catch (SQLException failure) {
            LOG.warning("NETWORK_STATS_CONNECTION_FAILED: " + failure.getSQLState());
            return 0;
        }
    }

    public int cleanup() {
        if (!enabled.get() || closed.get()) return 0;
        Instant cutoff = retentionSeconds <= 0 ? clock.instant() : clock.instant().minusSeconds(retentionSeconds);
        int totalDeleted = 0;
        while (!closed.get()) {
            List<Long> ids = new ArrayList<>();
            String selectSql = "SELECT Ma_Thong_Ke FROM Thong_Ke_Ket_Noi WHERE Thoi_Diem_Lay_Mau <= ? LIMIT " + CLEANUP_BATCH_SIZE;
            try (var conn = connections.getConnection();
                 var ps = conn.prepareStatement(selectSql)) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) ids.add(rs.getLong(1));
                }
            } catch (SQLException failure) {
                LOG.warning("NETWORK_STATS_CLEANUP_SELECT_FAILED: " + failure.getSQLState());
                break;
            }

            if (ids.isEmpty()) break;

            String deleteSql = "DELETE FROM Thong_Ke_Ket_Noi WHERE Ma_Thong_Ke = ?";
            try (var conn = connections.getConnection();
                 var ps = conn.prepareStatement(deleteSql)) {
                for (Long id : ids) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
                totalDeleted += ids.size();
            } catch (SQLException failure) {
                LOG.warning("NETWORK_STATS_CLEANUP_DELETE_FAILED: " + failure.getSQLState());
                break;
            }

            if (ids.size() < CLEANUP_BATCH_SIZE) break;
        }
        return totalDeleted;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        // Final attempt to flush remaining queue items before exiting
        flushBatch();
    }
}
