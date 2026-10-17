package server.meeting;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import server.auth.ConnectionProvider;

/**
 * Periodically deletes chat messages (VAN_BAN, HE_THONG) from permanently closed rooms
 * older than the configured retention minutes (default 20).
 * TEP messages are preserved to prevent cascading deletion of attachments leaving orphaned files.
 */
public final class ChatRetentionService implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(ChatRetentionService.class.getName());
    private static final int BATCH_SIZE = 500;
    private final ConnectionProvider connections;
    private final Clock clock;
    private final int retentionMinutes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("chat-retention-").factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();

    public ChatRetentionService(ConnectionProvider connections, Clock clock) {
        this(connections, clock, server.config.ServerConfig.getInt("chat.retention-minutes", 20));
    }

    public ChatRetentionService(ConnectionProvider connections, Clock clock, int retentionMinutes) {
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
        this.retentionMinutes = Math.max(1, Math.min(1440, retentionMinutes));
    }

    public int retentionMinutes() {
        return retentionMinutes;
    }

    public synchronized void start() {
        if (closed.get() || !started.compareAndSet(false, true)) return;
        sweep();
        scheduler.scheduleWithFixedDelay(this::sweep, 30, 30, TimeUnit.SECONDS);
    }

    public int sweep() {
        if (closed.get()) return 0;
        Instant cutoff = clock.instant().minus(Duration.ofMinutes(retentionMinutes));
        int totalDeleted = 0;
        while (!closed.get()) {
            List<Long> batch = new ArrayList<>();
            String selectSql = "SELECT t.Ma_Tin_Nhan FROM Tin_Nhan t " +
                    "JOIN Phong_Hop p ON t.Ma_Phong = p.Ma_Phong " +
                    "WHERE t.Loai_Tin_Nhan IN ('VAN_BAN', 'HE_THONG') " +
                    "AND p.Trang_Thai = 'DA_DONG' AND p.Ngay_Dong IS NOT NULL AND p.Ngay_Dong <= ? " +
                    "LIMIT " + BATCH_SIZE;
            try (var conn = connections.getConnection();
                 var ps = conn.prepareStatement(selectSql)) {
                ps.setTimestamp(1, Timestamp.from(cutoff));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        batch.add(rs.getLong(1));
                    }
                }
            } catch (SQLException failure) {
                LOG.warning("CHAT_RETENTION_ERROR select code=" + failure.getSQLState());
                break;
            }

            if (batch.isEmpty()) break;

            String deleteSql = "DELETE FROM Tin_Nhan WHERE Ma_Tin_Nhan = ?";
            try (var conn = connections.getConnection();
                 var ps = conn.prepareStatement(deleteSql)) {
                for (Long id : batch) {
                    ps.setLong(1, id);
                    ps.addBatch();
                }
                ps.executeBatch();
                totalDeleted += batch.size();
            } catch (SQLException failure) {
                LOG.warning("CHAT_RETENTION_ERROR delete code=" + failure.getSQLState());
                break;
            }

            if (batch.size() < BATCH_SIZE) break;
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
    }
}
