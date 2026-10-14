package server.rooms;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.room.RoomInfo;
import common.room.RoomRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.auth.PasswordHasher;

class RoomServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    private static final String PIN = "012345";
    private String url;
    private RoomService service;
    private Session host;
    private Session guest;
    private Session other;

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        url = "jdbc:h2:mem:rooms_" + UUID.randomUUID()
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
                    Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'CHO',
                    Da_Khoa BOOLEAN NOT NULL DEFAULT FALSE,
                    So_Nguoi_Toi_Da SMALLINT NOT NULL DEFAULT 10 CHECK (So_Nguoi_Toi_Da > 0),
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Ngay_Dong TIMESTAMP NULL
                )
                """);
        execute("""
                CREATE TABLE Thanh_Vien_Phong (
                    Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop(Ma_Phong),
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Vai_Tro VARCHAR(20) NOT NULL DEFAULT 'THANH_VIEN',
                    Tham_Gia_Luc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Roi_Phong_Luc TIMESTAMP NULL,
                    Ly_Do_Roi VARCHAR(20) NULL
                )
                """);
        service = new RoomService(() -> DriverManager.getConnection(url), new PasswordHasher(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        host = session("Host", "Chủ phòng");
        guest = session("Guest", "Khách một");
        other = session("Other", "Khách hai");
    }

    @AfterEach
    void clearDatabase() throws SQLException {
        execute("DROP ALL OBJECTS");
    }

    @Test
    void createDerivesHostFromSessionStoresHashedPinAndUtcMetadata() throws Exception {
        AuthRequest spoofed = new AuthRequest("CREATE_ROOM", "Guest", "irrelevant", "Fake host", "fake@example.com",
                host.token(), new RoomRequest("  Họp kế hoạch  ", PIN, null, 10));
        AuthResponse response = service.handle(spoofed);
        assertTrue(response.success());
        RoomInfo room = response.room();
        assertTrue(room.id() > 0);
        assertTrue(room.code().matches("MMR-[0-9A-F]{8}"));
        assertEquals("Họp kế hoạch", room.name());
        assertEquals(host.userId(), room.hostId());
        assertEquals(NOW, Instant.parse(room.createdAt()));
        assertEquals("DANG_HOAT_DONG", room.status());
        assertFalse(room.locked());
        assertEquals(10, room.capacity());
        assertEquals(1, room.people());
        assertEquals(1, room.participants().size());
        assertEquals(host.userId(), room.participants().getFirst().userId());
        assertEquals("Chủ phòng", room.participants().getFirst().displayName());
        assertEquals("CHU_PHONG", room.participants().getFirst().role());
        assertNull(response.user());
        assertNull(response.token());
        String hash = textValue("SELECT Ma_PIN_Bam FROM Phong_Hop");
        assertNotEquals(PIN, hash);
        assertTrue(new PasswordHasher().verify(PIN, hash));
        assertEquals(NOW, timestamp("SELECT Ngay_Tao FROM Phong_Hop"));
        assertEquals(NOW, timestamp("SELECT Tham_Gia_Luc FROM Thanh_Vien_Phong"));
        assertEquals(1, count("SELECT COUNT(*) FROM Phong_Hop"));
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test
    void separateCreatesHaveUniqueIdsCodesAndSaltedPins() throws Exception {
        Set<Long> ids = new HashSet<>();
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            RoomInfo room = create(host, "Room " + i, 10).room();
            assertTrue(ids.add(room.id()));
            assertTrue(codes.add(room.code()));
        }
        assertEquals(4, count("SELECT COUNT(DISTINCT Ma_PIN_Bam) FROM Phong_Hop"));
    }

    @Test
    void joinByNumericIdAndCaseInsensitiveCodeReturnsAllParticipants() throws Exception {
        RoomInfo room = create(host, "Họp nhóm", 10).room();
        AuthResponse byId = join(guest, " " + room.id() + " ", PIN);
        assertTrue(byId.success());
        AuthResponse byCode = join(other, "  " + room.code().toLowerCase() + "  ", PIN);
        assertTrue(byCode.success());
        assertEquals(3, byCode.room().people());
        assertEquals(Set.of(host.userId(), guest.userId(), other.userId()),
                byCode.room().participants().stream().map(p -> p.userId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals("THANH_VIEN", byCode.room().participants().stream()
                .filter(p -> p.userId() == guest.userId()).findFirst().orElseThrow().role());
    }

    @Test
    void duplicateJoinRequiresPinAndDoesNotDuplicateMembershipOrFailWhenFull() throws Exception {
        RoomInfo room = create(host, "Small", 2).room();
        assertTrue(join(guest, room.code(), PIN).success());
        assertEquals("INVALID_ROOM_PIN", join(guest, room.code(), "9999").code());
        assertTrue(join(guest, Long.toString(room.id()), PIN).success());
        assertTrue(join(host, room.code(), PIN).success());
        assertEquals(2, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        assertEquals("ROOM_FULL", join(other, room.code(), PIN).code());
    }

    @Test
    void joinRejectsMissingWrongPinLockedClosedAndClosedTimestampWithoutWriting() throws Exception {
        RoomInfo room = create(host, "Checks", 10).room();
        assertEquals("ROOM_NOT_FOUND", join(guest, "MMR-FFFFFFFF", PIN).code());
        assertEquals("ROOM_NOT_FOUND", join(guest, "999999999", PIN).code());
        assertEquals("VALIDATION_ERROR", join(guest, "' OR '1'='1", PIN).code());
        assertEquals("INVALID_ROOM_PIN", join(guest, room.code(), "9999").code());
        execute("UPDATE Phong_Hop SET Da_Khoa = TRUE");
        assertEquals("ROOM_LOCKED", join(guest, room.code(), PIN).code());
        assertEquals("ROOM_LOCKED", join(host, room.code(), PIN).code());
        execute("UPDATE Phong_Hop SET Da_Khoa = FALSE, Trang_Thai = 'DA_DONG'");
        assertEquals("ROOM_CLOSED", join(guest, room.code(), PIN).code());
        execute("UPDATE Phong_Hop SET Trang_Thai = 'DANG_HOAT_DONG', Ngay_Dong = TIMESTAMP '2026-10-14 10:00:00'");
        assertEquals("ROOM_CLOSED", join(guest, room.code(), PIN).code());
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test
    void joiningWaitingRoomActivatesItAndHostCanRejoinWithHostRole() throws Exception {
        RoomInfo room = create(host, "Waiting", 10).room();
        execute("UPDATE Phong_Hop SET Trang_Thai = 'CHO'");
        assertEquals("DANG_HOAT_DONG", join(guest, room.code(), PIN).room().status());
        assertTrue(action("LEAVE_ROOM", host, room.code()).success());
        AuthResponse rejoined = join(host, room.code(), PIN);
        assertTrue(rejoined.success());
        assertEquals("CHU_PHONG", rejoined.room().participants().stream()
                .filter(p -> p.userId() == host.userId()).findFirst().orElseThrow().role());
        assertEquals(2, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        assertEquals(3, count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test
    void capacityIsAtomicForTwoConcurrentContendersForLastSeat() throws Exception {
        RoomInfo room = create(host, "Last seat", 2).room();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return join(guest, room.code(), PIN); });
            var second = executor.submit(() -> { start.await(); return join(other, room.code(), PIN); });
            start.countDown();
            AuthResponse one = first.get(15, TimeUnit.SECONDS);
            AuthResponse two = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(one.success(), two.success());
            assertEquals("ROOM_FULL", one.success() ? two.code() : one.code());
            assertEquals(2, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        }
    }

    @Test
    void concurrentDuplicateJoinMakesOnlyOneActiveMembership() throws Exception {
        RoomInfo room = create(host, "Same guest", 10).room();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return join(guest, room.code(), PIN); });
            var second = executor.submit(() -> { start.await(); return join(guest, room.code(), PIN); });
            start.countDown();
            assertTrue(first.get(15, TimeUnit.SECONDS).success());
            assertTrue(second.get(15, TimeUnit.SECONDS).success());
            assertEquals(2, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        }
    }

    @Test
    void everyRoomActionRejectsUnknownExpiredRevokedAndInactiveSessions() throws Exception {
        RoomInfo room = create(host, "Private", 10).room();
        assertAllActionsRejectSession(null, room.code());
        assertAllActionsRejectSession(" ", room.code());
        assertAllActionsRejectSession("x".repeat(257), room.code());
        assertAllActionsRejectSession("forged-token", room.code());
        execute("UPDATE Phien_Dang_Nhap SET Het_Han_Luc = TIMESTAMP '2026-10-14 10:00:00' WHERE Ma_Nguoi_Dung = " + guest.userId());
        assertAllActionsRejectSession(guest.token(), room.code());
        execute("UPDATE Phien_Dang_Nhap SET Het_Han_Luc = TIMESTAMP '2026-10-15 10:00:00', Thu_Hoi_Luc = TIMESTAMP '2026-10-14 09:00:00' WHERE Ma_Nguoi_Dung = " + guest.userId());
        assertAllActionsRejectSession(guest.token(), room.code());
        execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc = NULL WHERE Ma_Nguoi_Dung = " + guest.userId());
        for (String status : List.of("BI_KHOA", "VO_HIEU_HOA")) {
            execute("UPDATE Nguoi_Dung SET Trang_Thai = '" + status + "' WHERE Ma_Nguoi_Dung = " + guest.userId());
            assertAllActionsRejectSession(guest.token(), room.code());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM Phong_Hop"));
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test
    void createRollsBackRoomWhenInitialHostMembershipFails() throws Exception {
        execute("ALTER TABLE Thanh_Vien_Phong ADD CONSTRAINT deny_host CHECK (Vai_Tro <> 'CHU_PHONG')");
        assertThrows(SQLException.class, () -> create(host, "Atomic create", 10));
        assertEquals(0, count("SELECT COUNT(*) FROM Phong_Hop"));
        assertEquals(0, count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test
    void joinRollsBackMembershipWhenSnapshotFails() throws Exception {
        RoomInfo room = create(host, "Atomic join", 10).room();
        execute("ALTER TABLE Nguoi_Dung DROP COLUMN Ten_Hien_Thi");
        assertThrows(SQLException.class, () -> join(guest, room.code(), PIN));
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test
    void getRequiresActiveMembershipAndListsOnlyRelatedRoomsWithoutParticipants() throws Exception {
        RoomInfo room = create(host, "Private", 10).room();
        assertEquals("ROOM_ACCESS_DENIED", action("GET_ROOM", guest, room.code()).code());
        assertTrue(action("LIST_ROOMS", guest, null).rooms().isEmpty());
        assertTrue(join(guest, room.code(), PIN).success());
        assertEquals(2, action("GET_ROOM", host, room.code()).room().participants().size());
        assertEquals(2, action("GET_ROOM", guest, Long.toString(room.id())).room().participants().size());
        assertTrue(action("LEAVE_ROOM", guest, room.code()).success());
        assertEquals("ROOM_ACCESS_DENIED", action("GET_ROOM", guest, room.code()).code());
        List<RoomInfo> historical = action("LIST_ROOMS", guest, null).rooms();
        assertEquals(1, historical.size());
        assertEquals(room.id(), historical.getFirst().id());
        assertEquals(1, historical.getFirst().people());
        assertTrue(historical.getFirst().participants().isEmpty());
        assertTrue(action("LIST_ROOMS", other, null).rooms().isEmpty());
        assertEquals("ROOM_NOT_FOUND", action("GET_ROOM", host, "MMR-FFFFFFFF").code());
        execute("UPDATE Phong_Hop SET Trang_Thai = 'DA_DONG'");
        assertEquals("ROOM_CLOSED", action("GET_ROOM", host, room.code()).code());
    }

    @Test
    void listReturnsOnlyTwentyMostRecentOwnedRooms() throws Exception {
        for (int i = 0; i < 25; i++) {
            execute("INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_Chu_Phong, Ngay_Tao) VALUES ('MMR-"
                    + String.format("%08X", i) + "', 'Room " + i + "', " + host.userId() + ", TIMESTAMP '2026-10-14 10:00:00')");
        }
        List<RoomInfo> rooms = action("LIST_ROOMS", host, null).rooms();
        assertEquals(20, rooms.size());
        assertEquals("Room 24", rooms.getFirst().name());
        assertEquals("Room 5", rooms.getLast().name());
        assertTrue(rooms.stream().allMatch(r -> r.people() == 0 && r.participants().isEmpty()));
        assertTrue(action("LIST_ROOMS", guest, null).rooms().isEmpty());
    }

    @Test
    void leaveRecordsHistoryPreservesOccupiedRoomAndClosesOnlyLastMember() throws Exception {
        RoomInfo room = create(host, "Leave", 10).room();
        assertTrue(join(guest, room.code(), PIN).success());
        AuthResponse hostLeave = action("LEAVE_ROOM", host, room.code());
        assertTrue(hostLeave.success());
        assertEquals("DANG_HOAT_DONG", hostLeave.room().status());
        assertEquals(1, hostLeave.room().people());
        assertTrue(hostLeave.room().participants().isEmpty());
        assertNull(timestamp("SELECT Ngay_Dong FROM Phong_Hop"));
        assertTrue(action("LEAVE_ROOM", host, room.code()).success());
        AuthResponse lastLeave = action("LEAVE_ROOM", guest, room.code());
        assertEquals("DA_DONG", lastLeave.room().status());
        assertEquals(0, lastLeave.room().people());
        assertEquals(NOW, timestamp("SELECT Ngay_Dong FROM Phong_Hop"));
        assertEquals(2, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Ly_Do_Roi = 'TU_ROI' AND Roi_Phong_Luc IS NOT NULL"));
        assertTrue(action("LEAVE_ROOM", guest, room.code()).success());
        assertEquals("ROOM_CLOSED", join(guest, room.code(), PIN).code());
    }

    @Test
    void unrelatedLeaveCannotCloseAnEmptyWaitingRoom() throws Exception {
        RoomInfo room = create(host, "Waiting", 10).room();
        execute("DELETE FROM Thanh_Vien_Phong");
        execute("UPDATE Phong_Hop SET Trang_Thai = 'CHO'");
        assertEquals("ROOM_ACCESS_DENIED", action("LEAVE_ROOM", guest, room.code()).code());
        assertEquals("CHO", textValue("SELECT Trang_Thai FROM Phong_Hop"));
        assertNull(timestamp("SELECT Ngay_Dong FROM Phong_Hop"));
    }

    @Test
    void leaveRollsBackMembershipWhenClosingRoomFails() throws Exception {
        RoomInfo room = create(host, "Atomic leave", 10).room();
        execute("ALTER TABLE Phong_Hop ADD CONSTRAINT deny_close CHECK (Trang_Thai <> 'DA_DONG')");
        assertThrows(SQLException.class, () -> action("LEAVE_ROOM", host, room.code()));
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        assertEquals("DANG_HOAT_DONG", textValue("SELECT Trang_Thai FROM Phong_Hop"));
    }

    @Test
    void malformedRequestsAreRejectedBeforeOpeningDatabase() throws Exception {
        RoomService disconnected = new RoomService(() -> { throw new AssertionError("Must not open database"); },
                new PasswordHasher(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals("INVALID_REQUEST", disconnected.handle(null).code());
        assertEquals("INVALID_REQUEST", disconnected.handle(new AuthRequest("DELETE_ROOM", null, null, null, null, host.token())).code());
        for (RoomRequest room : List.of(new RoomRequest(" ", PIN, null, 10),
                new RoomRequest("x".repeat(101), PIN, null, 10), new RoomRequest("Name", "123", null, 10),
                new RoomRequest("Name", "１２３４", null, 10), new RoomRequest("Name", "1234567", null, 10),
                new RoomRequest("Name", "12 34", null, 10), new RoomRequest("Name", PIN, null, 1),
                new RoomRequest("Name", PIN, null, 251))) {
            assertEquals("VALIDATION_ERROR", disconnected.handle(request("CREATE_ROOM", host.token(), room)).code());
        }
        assertEquals("VALIDATION_ERROR", disconnected.handle(request("CREATE_ROOM", host.token(), null)).code());
        assertEquals("VALIDATION_ERROR", disconnected.handle(request("JOIN_ROOM", host.token(),
                new RoomRequest(null, PIN, " ", 0))).code());
        assertEquals("VALIDATION_ERROR", disconnected.handle(request("JOIN_ROOM", host.token(),
                new RoomRequest(null, "bad", "1", 0))).code());
    }

    private void assertAllActionsRejectSession(String token, String identifier) throws SQLException {
        for (String action : List.of("CREATE_ROOM", "JOIN_ROOM", "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM")) {
            RoomRequest room = new RoomRequest("Attack", PIN, identifier, 10);
            assertEquals("SESSION_EXPIRED", service.handle(request(action, token, room)).code(), action);
        }
    }

    private AuthResponse create(Session session, String name, int capacity) throws SQLException {
        return service.handle(request("CREATE_ROOM", session.token(), new RoomRequest(name, PIN, null, capacity)));
    }

    private AuthResponse join(Session session, String identifier, String pin) throws SQLException {
        return service.handle(request("JOIN_ROOM", session.token(), new RoomRequest(null, pin, identifier, 0)));
    }

    private AuthResponse action(String action, Session session, String identifier) throws SQLException {
        return service.handle(request(action, session.token(), new RoomRequest(null, null, identifier, 0)));
    }

    private static AuthRequest request(String action, String token, RoomRequest room) {
        return new AuthRequest(action, null, null, null, null, token, room);
    }

    private Session session(String username, String displayName) throws Exception {
        try (Connection connection = DriverManager.getConnection(url)) {
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi) VALUES (?, 'unused', ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, username);
                statement.setString(2, displayName);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            String token = UUID.randomUUID().toString();
            String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Phien_Dang_Nhap (Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc) VALUES (?, ?, ?)")) {
                statement.setLong(1, id);
                statement.setString(2, hash);
                statement.setTimestamp(3, Timestamp.from(NOW.plusSeconds(86400)), utc());
                statement.executeUpdate();
            }
            return new Session(id, token);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private String textValue(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private Instant timestamp(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            Timestamp timestamp = result.getTimestamp(1, utc());
            return timestamp == null ? null : timestamp.toInstant();
        }
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private record Session(long userId, String token) {
    }
}
