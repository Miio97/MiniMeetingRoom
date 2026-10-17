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

class ChatRetentionServiceTest {
    private static final Instant T0 = Instant.parse("2026-10-14T10:00:00Z");
    private String url;
    private MutableClock clock;
    private ChatRetentionService service;

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
        url = "jdbc:h2:mem:chat_retention_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ten_Hien_Thi VARCHAR(100) NOT NULL
                )""");
        execute("""
                CREATE TABLE Phong_Hop (
                    Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL UNIQUE,
                    Trang_Thai VARCHAR(20) NOT NULL,
                    Ngay_Dong TIMESTAMP NULL
                )""");
        execute("""
                CREATE TABLE Tin_Nhan (
                    Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL,
                    Ma_Nguoi_Gui BIGINT NOT NULL,
                    Loai_Tin_Nhan VARCHAR(20) NOT NULL,
                    Noi_Dung TEXT,
                    Ngay_Gui TIMESTAMP NOT NULL,
                    Ngay_Xoa TIMESTAMP NULL
                )""");

        execute("INSERT INTO Nguoi_Dung (Ma_Nguoi_Dung, Ten_Hien_Thi) VALUES (1, 'User 1')");
        clock = new MutableClock(T0);
        service = new ChatRetentionService(this::connection, clock, 20);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void testRetentionCutoff19Min59SecPreserved20MinDeleted() throws SQLException {
        // Room 1 closed at T0
        execute("INSERT INTO Phong_Hop (Ma_Phong, Ma_Phong_Tham_Gia, Trang_Thai, Ngay_Dong) VALUES " +
                "(1, 'ROOM1', 'DA_DONG', '" + Timestamp.from(T0) + "')");
        // Messages in Room 1
        execute("INSERT INTO Tin_Nhan (Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Noi_Dung, Ngay_Gui) VALUES " +
                "(1, 1, 'VAN_BAN', 'Hello', '" + Timestamp.from(T0.minusSeconds(300)) + "')," +
                "(1, 1, 'HE_THONG', 'System alert', '" + Timestamp.from(T0.minusSeconds(200)) + "')," +
                "(1, 1, 'TEP', 'File meta', '" + Timestamp.from(T0.minusSeconds(100)) + "')");

        // At 19 minutes 59 seconds after close
        clock.set(T0.plusSeconds(19 * 60 + 59));
        int deleted = service.sweep();
        assertEquals(0, deleted, "No messages should be deleted at 19:59");
        assertEquals(3, count("SELECT COUNT(*) FROM Tin_Nhan"));

        // At exactly 20 minutes after close
        clock.set(T0.plusSeconds(20 * 60));
        deleted = service.sweep();
        assertEquals(2, deleted, "VAN_BAN and HE_THONG messages should be deleted at 20:00");
        assertEquals(1, count("SELECT COUNT(*) FROM Tin_Nhan"));

        // TEP message remains intact
        assertEquals(1, count("SELECT COUNT(*) FROM Tin_Nhan WHERE Loai_Tin_Nhan = 'TEP'"));
    }

    @Test
    void testOpenRoomAndClosedRoomWithoutNgayDongNotDeleted() throws SQLException {
        // Open room
        execute("INSERT INTO Phong_Hop (Ma_Phong, Ma_Phong_Tham_Gia, Trang_Thai, Ngay_Dong) VALUES " +
                "(1, 'OPEN1', 'DANG_HOAT_DONG', NULL)");
        // Closed room with NULL Ngay_Dong
        execute("INSERT INTO Phong_Hop (Ma_Phong, Ma_Phong_Tham_Gia, Trang_Thai, Ngay_Dong) VALUES " +
                "(2, 'CLOSED_NULL', 'DA_DONG', NULL)");

        execute("INSERT INTO Tin_Nhan (Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Noi_Dung, Ngay_Gui) VALUES " +
                "(1, 1, 'VAN_BAN', 'Msg in open room', '" + Timestamp.from(T0) + "')," +
                "(2, 1, 'VAN_BAN', 'Msg in closed null room', '" + Timestamp.from(T0) + "')");

        clock.set(T0.plusSeconds(3600)); // 1 hour later
        int deleted = service.sweep();
        assertEquals(0, deleted, "Open rooms or closed rooms without Ngay_Dong must not be deleted");
        assertEquals(2, count("SELECT COUNT(*) FROM Tin_Nhan"));
    }

    @Test
    void testBatchDeletionOver500RowsAndRerunningNoError() throws SQLException {
        execute("INSERT INTO Phong_Hop (Ma_Phong, Ma_Phong_Tham_Gia, Trang_Thai, Ngay_Dong) VALUES " +
                "(1, 'ROOM_BATCH', 'DA_DONG', '" + Timestamp.from(T0) + "')");

        // Insert 1200 messages
        try (Connection c = connection(); var ps = c.prepareStatement(
                "INSERT INTO Tin_Nhan (Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Noi_Dung, Ngay_Gui) VALUES (?, 1, 'VAN_BAN', 'msg', ?)")) {
            for (int i = 0; i < 1200; i++) {
                ps.setLong(1, 1);
                ps.setTimestamp(2, Timestamp.from(T0));
                ps.addBatch();
            }
            ps.executeBatch();
        }
        assertEquals(1200, count("SELECT COUNT(*) FROM Tin_Nhan"));

        clock.set(T0.plusSeconds(25 * 60)); // 25 minutes later
        int deleted = service.sweep();
        assertEquals(1200, deleted, "All 1200 messages should be deleted across batches");
        assertEquals(0, count("SELECT COUNT(*) FROM Tin_Nhan"));

        // Running again should cleanly return 0 and cause no error
        int secondRun = service.sweep();
        assertEquals(0, secondRun);
    }
}
