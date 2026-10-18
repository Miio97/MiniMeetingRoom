package server.schedule;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.schedule.ScheduleInfo;
import common.schedule.ScheduleRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ScheduleServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private String url;
    private ScheduleService service;
    private Session alice;
    private Session bob;
    private long testRoomId;
    private String testRoomCode;

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        url = "jdbc:h2:mem:schedules_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ten_Dang_Nhap VARCHAR(50) NOT NULL UNIQUE,
                    Mat_Khau_Bam VARCHAR(255) NOT NULL,
                    Ten_Hien_Thi VARCHAR(100) NOT NULL,
                    Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG'
                )
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE,
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Het_Han_Luc TIMESTAMP NOT NULL,
                    Thu_Hoi_Luc TIMESTAMP NULL
                )
                """);
        execute("""
                CREATE TABLE Phong_Hop (
                    Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL UNIQUE,
                    Ten_Phong VARCHAR(100) NOT NULL,
                    Ma_PIN_Bam VARCHAR(255),
                    Ma_Chu_Phong BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'DANG_HOAT_DONG',
                    Da_Khoa BOOLEAN NOT NULL DEFAULT FALSE,
                    So_Nguoi_Toi_Da SMALLINT NOT NULL DEFAULT 10,
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Ngay_Dong TIMESTAMP NULL
                )
                """);
        execute("""
                CREATE TABLE Lich_Hen (
                    Ma_Lich_Hen BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Nguoi_Tao BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Tieu_De VARCHAR(100) NOT NULL,
                    Mo_Ta VARCHAR(1000) NULL,
                    Bat_Dau_Luc TIMESTAMP NOT NULL,
                    Ket_Thuc_Luc TIMESTAMP NOT NULL,
                    Lap_Lai VARCHAR(20) NOT NULL DEFAULT 'KHONG',
                    Ma_Phong BIGINT NULL REFERENCES Phong_Hop(Ma_Phong),
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Ngay_Xoa TIMESTAMP NULL
                )
                """);
        service = new ScheduleService(() -> DriverManager.getConnection(url), Clock.fixed(NOW, ZoneOffset.UTC));
        alice = session("alice", "Alice");
        bob = session("bob", "Bob");

        // Tạo 1 phòng họp sẵn để test gắn phòng
        testRoomCode = "MMR-12345678";
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_Chu_Phong) VALUES (?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, testRoomCode);
            ps.setString(2, "Phòng Họp Demo");
            ps.setLong(3, alice.userId());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                testRoomId = keys.getLong(1);
            }
        }
    }

    @AfterEach
    void clearDatabase() throws SQLException {
        execute("DROP ALL OBJECTS");
    }

    @Test
    void createDerivesCreatorFromSessionAndStoresUtc() throws Exception {
        ScheduleRequest req = new ScheduleRequest("Họp tuần", "Thảo luận tiến độ",
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", testRoomCode);
        AuthRequest authReq = new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, req);
        AuthResponse res = service.handle(authReq);

        assertTrue(res.success(), res::message);
        assertEquals("OK", res.code());
        assertNotNull(res.schedule());
        ScheduleInfo info = res.schedule();
        assertTrue(info.id() > 0);
        assertEquals(alice.userId(), info.creatorId());
        assertEquals("Họp tuần", info.title());
        assertEquals("Thảo luận tiến độ", info.description());
        assertEquals("2026-10-18T14:00:00Z", info.startsAt());
        assertEquals("2026-10-18T15:00:00Z", info.endsAt());
        assertEquals(testRoomCode, info.roomCode());
        assertEquals(testRoomId, info.roomId());
    }

    @Test
    void listOnlyReturnsOwnNonDeletedSchedulesWithinInterval() throws Exception {
        // Alice tạo 2 lịch: 1 cái hôm nay, 1 cái 10 ngày sau
        ScheduleRequest req1 = new ScheduleRequest("Lịch 1", "Mô tả 1",
                "2026-10-18T11:00:00Z", "2026-10-18T12:00:00Z", "KHONG_LAP", null);
        ScheduleRequest req2 = new ScheduleRequest("Lịch 2", "Mô tả 2",
                "2026-10-28T11:00:00Z", "2026-10-28T12:00:00Z", "KHONG_LAP", null);
        // Bob tạo 1 lịch
        ScheduleRequest reqBob = new ScheduleRequest("Lịch Bob", "Mô tả Bob",
                "2026-10-18T11:00:00Z", "2026-10-18T12:00:00Z", "KHONG_LAP", null);

        assertTrue(service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, req1)).success());
        assertTrue(service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, req2)).success());
        assertTrue(service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, bob.token(), null, reqBob)).success());

        // Alice list trong khoảng 2 ngày tới
        ScheduleRequest listReq = ScheduleRequest.list("2026-10-18T00:00:00Z", "2026-10-20T00:00:00Z");
        AuthResponse aliceList = service.handle(new AuthRequest("LIST_SCHEDULES", null, null, null, null, alice.token(), null, listReq));
        assertTrue(aliceList.success());
        assertEquals(1, aliceList.schedules().size());
        assertEquals("Lịch 1", aliceList.schedules().getFirst().title());

        // Bob list trong cùng khoảng: Bob chỉ thấy lịch của Bob, không thấy lịch Alice
        AuthResponse bobList = service.handle(new AuthRequest("LIST_SCHEDULES", null, null, null, null, bob.token(), null, listReq));
        assertTrue(bobList.success());
        assertEquals(1, bobList.schedules().size());
        assertEquals("Lịch Bob", bobList.schedules().getFirst().title());
    }

    @Test
    void deleteSoftDeletesAndPreventsNonCreatorDeletion() throws Exception {
        ScheduleRequest req = new ScheduleRequest("Lịch xóa", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", null);
        AuthResponse created = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, req));
        assertTrue(created.success());
        long scheduleId = created.schedule().id();

        // Bob cố gắng xóa lịch của Alice -> PERMISSION_DENIED
        ScheduleRequest deleteReq = ScheduleRequest.delete(scheduleId);
        AuthResponse bobDelete = service.handle(new AuthRequest("DELETE_SCHEDULE", null, null, null, null, bob.token(), null, deleteReq));
        assertFalse(bobDelete.success());
        assertEquals("PERMISSION_DENIED", bobDelete.code());

        // Alice xóa lịch của mình -> Thành công
        AuthResponse aliceDelete = service.handle(new AuthRequest("DELETE_SCHEDULE", null, null, null, null, alice.token(), null, deleteReq));
        assertTrue(aliceDelete.success());

        // Kiểm tra database: Ngay_Xoa đã được set (xóa mềm)
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement("SELECT Ngay_Xoa FROM Lich_Hen WHERE Ma_Lich_Hen = ?")) {
            ps.setLong(1, scheduleId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotNull(rs.getTimestamp(1));
            }
        }

        // Alice list lại -> không còn lịch này
        AuthResponse listRes = service.handle(new AuthRequest("LIST_SCHEDULES", null, null, null, null, alice.token(), null,
                ScheduleRequest.list("2026-10-18T00:00:00Z", "2026-10-19T00:00:00Z")));
        assertTrue(listRes.success());
        assertTrue(listRes.schedules().isEmpty());
    }

    @Test
    void rejectsInvalidOrExpiredOrRevokedToken() throws Exception {
        ScheduleRequest req = new ScheduleRequest("Tiêu đề", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", null);

        // Token rỗng hoặc không tồn tại
        AuthResponse resFake = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, "invalid-token", null, req));
        assertFalse(resFake.success());
        assertEquals("SESSION_EXPIRED", resFake.code());

        // Token hết hạn
        Session expiredSession = expiredSession("expired-user");
        AuthResponse resExpired = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, expiredSession.token(), null, req));
        assertFalse(resExpired.success());
        assertEquals("SESSION_EXPIRED", resExpired.code());

        // Token bị thu hồi
        Session revokedSession = revokedSession("revoked-user");
        AuthResponse resRevoked = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, revokedSession.token(), null, req));
        assertFalse(resRevoked.success());
        assertEquals("SESSION_EXPIRED", resRevoked.code());
    }

    @Test
    void validatesInputAndRejectsNonexistentRoom() throws Exception {
        // Tiêu đề rỗng
        ScheduleRequest reqEmptyTitle = new ScheduleRequest("   ", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", null);
        AuthResponse resEmpty = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reqEmptyTitle));
        assertFalse(resEmpty.success());
        assertEquals("VALIDATION_ERROR", resEmpty.code());

        // Tiêu đề quá dài > 100 ký tự
        ScheduleRequest reqLongTitle = new ScheduleRequest("A".repeat(101), null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", null);
        AuthResponse resLongTitle = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reqLongTitle));
        assertFalse(resLongTitle.success());
        assertEquals("VALIDATION_ERROR", resLongTitle.code());

        // Kết thúc trước bắt đầu
        ScheduleRequest reqBadTime = new ScheduleRequest("Hợp lệ", null,
                "2026-10-18T15:00:00Z", "2026-10-18T14:00:00Z", "KHONG_LAP", null);
        AuthResponse resBadTime = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reqBadTime));
        assertFalse(resBadTime.success());
        assertEquals("VALIDATION_ERROR", resBadTime.code());

        // Mô tả quá dài > 1000 ký tự
        ScheduleRequest reqLongDesc = new ScheduleRequest("Hợp lệ", "B".repeat(1001),
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", null);
        AuthResponse resLongDesc = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reqLongDesc));
        assertFalse(resLongDesc.success());
        assertEquals("VALIDATION_ERROR", resLongDesc.code());

        // Gắn phòng không tồn tại
        ScheduleRequest reqBadRoom = new ScheduleRequest("Hợp lệ", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", "MMR-99999999");
        AuthResponse resBadRoom = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reqBadRoom));
        assertFalse(resBadRoom.success());
        assertEquals("ROOM_NOT_FOUND", resBadRoom.code());
    }

    @Test
    void rejectsWhenExceeding200Schedules() throws Exception {
        // Chèn sẵn 200 lịch cho Bob trực tiếp qua SQL
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO Lich_Hen (Ma_Nguoi_Tao, Tieu_De, Bat_Dau_Luc, Ket_Thuc_Luc) VALUES (?, ?, ?, ?)")) {
            for (int i = 0; i < 200; i++) {
                ps.setLong(1, bob.userId());
                ps.setString(2, "Lịch " + i);
                ps.setTimestamp(3, Timestamp.from(NOW), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                ps.setTimestamp(4, Timestamp.from(NOW.plusSeconds(3600)), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                ps.addBatch();
            }
            ps.executeBatch();
        }

        // Bob cố gắng tạo lịch thứ 201 -> Bị từ chối
        ScheduleRequest req201 = new ScheduleRequest("Lịch 201", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG_LAP", null);
        AuthResponse res = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, bob.token(), null, req201));
        assertFalse(res.success());
        assertEquals("SCHEDULE_LIMIT_EXCEEDED", res.code());
    }

    @Test
    void rejectsScheduleLessThan15MinutesInAdvance() throws Exception {
        // Tạo lịch cách NOW chỉ 5 phút -> từ chối
        ScheduleRequest req5Min = new ScheduleRequest("Họp gấp", null,
                NOW.plusSeconds(300).toString(), NOW.plusSeconds(3900).toString(), "KHONG", null);
        AuthResponse res5Min = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, req5Min));
        assertFalse(res5Min.success());
        assertEquals("VALIDATION_ERROR", res5Min.code());
        assertTrue(res5Min.message().contains("ít nhất 15 phút"));

        // Tạo lịch trong quá khứ -> từ chối
        ScheduleRequest reqPast = new ScheduleRequest("Họp quá khứ", null,
                NOW.minusSeconds(600).toString(), NOW.plusSeconds(3000).toString(), "KHONG", null);
        AuthResponse resPast = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reqPast));
        assertFalse(resPast.success());
        assertEquals("VALIDATION_ERROR", resPast.code());

        // Tạo lịch cách NOW đúng 15 phút -> thành công
        ScheduleRequest req15Min = new ScheduleRequest("Họp đúng chuẩn", null,
                NOW.plusSeconds(900).toString(), NOW.plusSeconds(4500).toString(), "KHONG", null);
        AuthResponse res15Min = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, req15Min));
        assertTrue(res15Min.success(), res15Min::message);
    }

    @Test
    void rejectsConflictingOverlappingSchedulesForSameUser() throws Exception {
        // Alice tạo lịch 14:00 - 15:00
        ScheduleRequest original = new ScheduleRequest("Họp gốc", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG", null);
        AuthResponse created = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, original));
        assertTrue(created.success());
        long originalId = created.schedule().id();

        // Alice tạo lịch trùng giờ 14:30 - 15:30 -> Bị từ chối do trùng lịch
        ScheduleRequest overlap1 = new ScheduleRequest("Họp trùng 1", null,
                "2026-10-18T14:30:00Z", "2026-10-18T15:30:00Z", "KHONG", null);
        AuthResponse resOverlap1 = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, overlap1));
        assertFalse(resOverlap1.success());
        assertEquals("SCHEDULE_TIME_CONFLICT", resOverlap1.code());
        assertTrue(resOverlap1.message().contains("Họp gốc"));

        // Alice tạo lịch trùng giờ 13:30 - 14:30 -> Bị từ chối
        ScheduleRequest overlap2 = new ScheduleRequest("Họp trùng 2", null,
                "2026-10-18T13:30:00Z", "2026-10-18T14:30:00Z", "KHONG", null);
        AuthResponse resOverlap2 = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, overlap2));
        assertFalse(resOverlap2.success());
        assertEquals("SCHEDULE_TIME_CONFLICT", resOverlap2.code());

        // Alice tạo lịch bao trùm 13:00 - 16:00 -> Bị từ chối
        ScheduleRequest overlap3 = new ScheduleRequest("Họp trùng 3", null,
                "2026-10-18T13:00:00Z", "2026-10-18T16:00:00Z", "KHONG", null);
        AuthResponse resOverlap3 = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, overlap3));
        assertFalse(resOverlap3.success());
        assertEquals("SCHEDULE_TIME_CONFLICT", resOverlap3.code());

        // Alice tạo lịch liền kề 15:00 - 16:00 -> Thành công (không trùng)
        ScheduleRequest adjacent = new ScheduleRequest("Họp liền kề", null,
                "2026-10-18T15:00:00Z", "2026-10-18T16:00:00Z", "KHONG", null);
        AuthResponse resAdj = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, adjacent));
        assertTrue(resAdj.success(), resAdj::message);

        // Bob tạo lịch trùng khung giờ 14:00 - 15:00 -> Thành công (người dùng khác nhau không chặn nhau)
        ScheduleRequest bobSameTime = new ScheduleRequest("Họp của Bob", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG", null);
        AuthResponse resBob = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, bob.token(), null, bobSameTime));
        assertTrue(resBob.success(), resBob::message);

        // Alice xóa lịch gốc, sau đó tạo lại cùng khung giờ 14:00 - 15:00 -> Thành công (xóa mềm không chặn lịch mới)
        assertTrue(service.handle(new AuthRequest("DELETE_SCHEDULE", null, null, null, null, alice.token(), null, ScheduleRequest.delete(originalId))).success());
        ScheduleRequest reCreate = new ScheduleRequest("Họp tạo lại", null,
                "2026-10-18T14:00:00Z", "2026-10-18T15:00:00Z", "KHONG", null);
        AuthResponse resReCreate = service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, alice.token(), null, reCreate));
        assertTrue(resReCreate.success(), resReCreate::message);
    }

    // --- Helpers ---
    private Session session(String username, String displayName) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi) VALUES (?, 'hash', ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, username);
                statement.setString(2, displayName);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    id = keys.getLong(1);
                }
            }
            String token = "token-" + username + "-" + UUID.randomUUID();
            String hash = hash(token);
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Phien_Dang_Nhap (Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc) VALUES (?, ?, ?)")) {
                statement.setLong(1, id);
                statement.setString(2, hash);
                statement.setTimestamp(3, Timestamp.from(NOW.plusSeconds(86400)), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                statement.executeUpdate();
            }
            return new Session(id, token);
        }
    }

    @Test
    void strictAdvanceAndPositiveDurationApplyToEverySchedule() throws Exception {
            for (long seconds : new long[] {-60, 0, 840, 899}) {
                AuthResponse response = createAt(alice, NOW.plusSeconds(seconds), NOW.plusSeconds(7200));
                assertEquals("VALIDATION_ERROR", response.code());
            }
        assertEquals("VALIDATION_ERROR", createAt(alice, NOW.plusSeconds(900), NOW.plusSeconds(900)).code());
        assertTrue(createAt(alice, NOW.plusSeconds(900), NOW.plusSeconds(1800)).success());
    }

    @Test
    void overlapsAreAccountScopedAllowAdjacentAndIgnoreDeletedSchedules() throws Exception {
        Instant start = Instant.parse("2026-10-18T23:30:00Z");
        Instant end = start.plusSeconds(7200);
        AuthResponse first = createAt(alice, start, end);
        assertTrue(first.success());
        assertEquals("SCHEDULE_TIME_CONFLICT", createAt(alice, start.plusSeconds(3600), end.plusSeconds(3600)).code());
        assertEquals("SCHEDULE_TIME_CONFLICT", createAt(alice, start.minusSeconds(60), end.plusSeconds(60)).code());
        assertTrue(createAt(bob, start, end).success());
        assertTrue(createAt(alice, end, end.plusSeconds(3600)).success());
        assertTrue(service.handle(new AuthRequest("DELETE_SCHEDULE", null, null, null, null, alice.token(), null,
                ScheduleRequest.delete(first.schedule().id()))).success());
        assertTrue(createAt(alice, start, end).success());
    }

    @Test
    void simultaneousCreatesForSameAccountCannotOverlap() throws Exception {
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<AuthResponse> task = () -> {
                gate.await();
                return createAt(alice, NOW.plusSeconds(3600), NOW.plusSeconds(7200));
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            gate.countDown();
            var responses = List.of(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, responses.stream().filter(AuthResponse::success).count());
            assertEquals(1, responses.stream().filter(r -> "SCHEDULE_TIME_CONFLICT".equals(r.code())).count());
        }
    }

    private AuthResponse createAt(Session session, Instant start, Instant end) throws Exception {
        return service.handle(new AuthRequest("CREATE_SCHEDULE", null, null, null, null, session.token(), null,
                new ScheduleRequest("Boundary test", null, start.toString(), end.toString(), "KHONG", null)));
    }

    private Session expiredSession(String username) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi) VALUES (?, 'hash', ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, username);
                statement.setString(2, username);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    id = keys.getLong(1);
                }
            }
            String token = "expired-" + UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Phien_Dang_Nhap (Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc) VALUES (?, ?, ?)")) {
                statement.setLong(1, id);
                statement.setString(2, hash(token));
                statement.setTimestamp(3, Timestamp.from(NOW.minusSeconds(3600)), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                statement.executeUpdate();
            }
            return new Session(id, token);
        }
    }

    private Session revokedSession(String username) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi) VALUES (?, 'hash', ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, username);
                statement.setString(2, username);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    assertTrue(keys.next());
                    id = keys.getLong(1);
                }
            }
            String token = "revoked-" + UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Phien_Dang_Nhap (Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc, Thu_Hoi_Luc) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, id);
                statement.setString(2, hash(token));
                statement.setTimestamp(3, Timestamp.from(NOW.plusSeconds(86400)), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                statement.setTimestamp(4, Timestamp.from(NOW.minusSeconds(60)), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                statement.executeUpdate();
            }
            return new Session(id, token);
        }
    }

    private static String hash(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private record Session(long userId, String token) { }
}
