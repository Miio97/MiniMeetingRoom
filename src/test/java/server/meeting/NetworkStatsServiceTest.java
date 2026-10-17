package server.meeting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkStatsServiceTest {
    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private String url;
    private MutableClock clock;
    private NetworkStatsService service;

    private static class MutableClock extends Clock {
        private Instant current;
        MutableClock(Instant initial) { this.current = initial; }
        void set(Instant now) { this.current = now; }
        void advanceSeconds(long seconds) { this.current = this.current.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private void execute(String sql) throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:stats_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ten_Hien_Thi VARCHAR(100) NOT NULL
                )""");
        execute("""
                CREATE TABLE Phong_Hop (
                    Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL UNIQUE
                )""");
        execute("""
                CREATE TABLE Thong_Ke_Ket_Noi (
                    Ma_Thong_Ke BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL,
                    Ma_Nguoi_Dung BIGINT NOT NULL,
                    Do_Tre_Ms DECIMAL(8,2),
                    Do_Rung_Ms DECIMAL(8,2),
                    Ty_Le_Mat_Goi_Tin DECIMAL(5,2),
                    Bitrate_Kbps INT,
                    FPS SMALLINT,
                    Chieu_Rong SMALLINT,
                    Chieu_Cao SMALLINT,
                    Trang_Thai_Ket_Noi VARCHAR(20),
                    Thoi_Diem_Lay_Mau TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""");

        execute("INSERT INTO Nguoi_Dung (Ma_Nguoi_Dung, Ten_Hien_Thi) VALUES (1, 'User 1')");
        execute("INSERT INTO Phong_Hop (Ma_Phong, Ma_Phong_Tham_Gia) VALUES (10, 'ROOM10')");
        clock = new MutableClock(T0);
        service = new NetworkStatsService(this::connection, clock, 1000, 30, 30);
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.close();
    }

    @Test
    void testPreflightSucceedsWhenTableExistsAndFailsGracefullyWhenMissing() throws SQLException {
        assertTrue(service.preflight(), "Preflight should succeed when table exists");
        assertTrue(service.isEnabled());

        // Drop table and test another service instance
        execute("DROP TABLE Thong_Ke_Ket_Noi");
        NetworkStatsService missing = new NetworkStatsService(this::connection, clock, 1000, 30, 30);
        assertFalse(missing.preflight(), "Preflight should return false without throwing when table is missing");
        assertFalse(missing.isEnabled());
        missing.close();
    }

    @Test
    void testBatchInsertAndCleanupBoundaries() throws SQLException {
        service.start();

        // Record two samples
        service.record(new NetworkStatsService.StatsRecord(
                10, 1, 45.5, 12.0, 0.5, 800, 30, 640, 480, "TOT", T0
        ));
        service.record(new NetworkStatsService.StatsRecord(
                10, 1, 95.0, 25.0, 1.5, 750, 28, 640, 480, "TRUNG_BINH", T0
        ));

        assertEquals(2, service.queuedCount());
        int flushed = service.flushBatch();
        assertEquals(2, flushed);
        assertEquals(0, service.queuedCount());
        assertEquals(2, count("SELECT COUNT(*) FROM Thong_Ke_Ket_Noi"));

        // Verify quality threshold computation
        assertEquals("TOT", NetworkStatsService.calculateQuality(70.0, 15.0, 0.5));
        assertEquals("TRUNG_BINH", NetworkStatsService.calculateQuality(100.0, 15.0, 0.5));
        assertEquals("TRUNG_BINH", NetworkStatsService.calculateQuality(70.0, 25.0, 0.5));
        assertEquals("TRUNG_BINH", NetworkStatsService.calculateQuality(70.0, 15.0, 1.5));
        assertEquals("KEM", NetworkStatsService.calculateQuality(170.0, 15.0, 0.5));
        assertEquals("KEM", NetworkStatsService.calculateQuality(70.0, 45.0, 0.5));
        assertEquals("KEM", NetworkStatsService.calculateQuality(70.0, 15.0, 3.5));
        assertNull(NetworkStatsService.calculateQuality(null, null, null));

        // Test cleanup at 29s (should not delete)
        clock.set(T0.plusSeconds(29));
        int deleted = service.cleanup();
        assertEquals(0, deleted);
        assertEquals(2, count("SELECT COUNT(*) FROM Thong_Ke_Ket_Noi"));

        // Test cleanup at 30s (should delete)
        clock.set(T0.plusSeconds(30));
        deleted = service.cleanup();
        assertEquals(2, deleted);
        assertEquals(0, count("SELECT COUNT(*) FROM Thong_Ke_Ket_Noi"));
    }

    @Test
    void testRetentionZeroCleansImmediately() throws SQLException {
        NetworkStatsService zeroRetention = new NetworkStatsService(this::connection, clock, 1000, 30, 0);
        zeroRetention.start();
        zeroRetention.record(new NetworkStatsService.StatsRecord(
                10, 1, 50.0, 10.0, 0.0, 500, 25, 320, 240, "TOT", T0
        ));
        zeroRetention.flushBatch();
        assertEquals(1, count("SELECT COUNT(*) FROM Thong_Ke_Ket_Noi"));

        // With retention=0, cleanup at T0 should delete it immediately
        int deleted = zeroRetention.cleanup();
        assertEquals(1, deleted);
        assertEquals(0, count("SELECT COUNT(*) FROM Thong_Ke_Ket_Noi"));
        zeroRetention.close();
    }

    @Test
    void testQueueFullDropsOldestSample() {
        service.preflight();
        // Insert 1025 records (queue capacity is 1024)
        for (int i = 0; i < 1025; i++) {
            service.record(new NetworkStatsService.StatsRecord(
                    10, 1, (double) i, null, null, null, null, null, null, "TOT", T0
            ));
        }

        assertEquals(1024, service.queuedCount(), "Queue should be capped at 1024");
        assertEquals(1, service.droppedSamples(), "Exactly 1 oldest sample should be dropped");
    }

    @Test
    void test10SecondBatchFlushWith10Records() throws SQLException {
        NetworkStatsService tenSecService = new NetworkStatsService(this::connection, clock, 1000, 10, 30, 60);
        assertEquals(10, tenSecService.flushIntervalSeconds());
        tenSecService.start();

        // Simulate 10 samples (1 per second over 10 seconds)
        for (int i = 0; i < 10; i++) {
            tenSecService.record(new NetworkStatsService.StatsRecord(
                    10, 1, 40.0 + i, 10.0, 0.1, 1000, 30, 1280, 720, "TOT", T0.plusSeconds(i)
            ));
        }

        assertEquals(10, tenSecService.queuedCount());
        int inserted = tenSecService.flushBatch();
        assertEquals(10, inserted);
        assertEquals(0, tenSecService.queuedCount());
        assertEquals(10, count("SELECT COUNT(*) FROM Thong_Ke_Ket_Noi"));
        tenSecService.close();
    }
}
