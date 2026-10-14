package server.meeting;

import common.auth.*;
import common.meeting.*;
import java.sql.*;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import server.auth.AuthService;
import server.auth.PasswordHasher;
import static org.junit.jupiter.api.Assertions.*;

/** Duc: all actors are registered and logged in through the production authentication service. */
class RoomServiceTest {
    private String database;
    private AuthService auth;
    private RoomService rooms;
    private AuthResponse host, guest, third;
    private final TestClock clock = new TestClock();
    @BeforeEach void setup() throws Exception {
        database = "jdbc:h2:mem:room_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        try (Connection connection = connection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE Nguoi_Dung (Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY, Ten_Dang_Nhap VARCHAR_IGNORECASE(50) UNIQUE, Mat_Khau_Bam VARCHAR(255), Ten_Hien_Thi VARCHAR(100), Email VARCHAR(255) UNIQUE, Trang_Thai VARCHAR(20) DEFAULT 'HOAT_DONG', Lan_Dang_Nhap_Cuoi TIMESTAMP)");
            sql.execute("CREATE TABLE Phien_Dang_Nhap (Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Nguoi_Dung BIGINT REFERENCES Nguoi_Dung, Token_Bam VARCHAR(255) UNIQUE, Ngay_Tao TIMESTAMP, Het_Han_Luc TIMESTAMP, Thu_Hoi_Luc TIMESTAMP)");
            sql.execute("CREATE TABLE Phong_Hop (Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong_Tham_Gia VARCHAR(20) UNIQUE, Ten_Phong VARCHAR(100), Ma_PIN_Bam VARCHAR(255), Ma_Chu_Phong BIGINT REFERENCES Nguoi_Dung, Trang_Thai VARCHAR(20), Da_Khoa BOOLEAN, So_Nguoi_Toi_Da SMALLINT, Ngay_Dong TIMESTAMP)");
            sql.execute("CREATE TABLE Thanh_Vien_Phong (Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT REFERENCES Phong_Hop, Ma_Nguoi_Dung BIGINT REFERENCES Nguoi_Dung, Vai_Tro VARCHAR(20), Tham_Gia_Luc TIMESTAMP DEFAULT CURRENT_TIMESTAMP, Roi_Phong_Luc TIMESTAMP, Ly_Do_Roi VARCHAR(20))");
        }
        auth = new AuthService(this::connection, new PasswordHasher(), clock, Duration.ofHours(24));
        rooms = new RoomService(this::connection, clock);
        host = account("duc", "Đức"); guest = account("guest", "Thành viên thật"); third = account("third", "Thành viên thứ ba");
    }
    @AfterEach void cleanup() throws Exception { rooms.close(); try (var connection = connection(); var sql = connection.createStatement()) { sql.execute("DROP ALL OBJECTS"); } }
    private Connection connection() throws SQLException { return DriverManager.getConnection(database); }
    private AuthResponse account(String username, String name) throws Exception {
        assertTrue(auth.handle(new AuthRequest("REGISTER", username, "Password123!", name, null, null)).success());
        AuthResponse login = auth.handle(new AuthRequest("LOGIN", username, "Password123!", null, null, null)); assertTrue(login.success()); return login;
    }
    private RoomResponse create(int capacity) throws Exception { return rooms.handle(RoomRequest.create(host.token(), "Phòng thật", "1234", capacity)); }
    private RoomResponse join(AuthResponse user, RoomResponse room) throws Exception { return rooms.handle(RoomRequest.join(user.token(), room.room().code(), "1234")); }
    private RoomResponse command(AuthResponse user, RoomResponse room, String action, long target) throws Exception { return rooms.handle(RoomRequest.command(action, user.token(), room.room().id(), room.ticket(), target)); }
    @Test void sameRoomContainsOnlyRegisteredAccountsAndTheirRealNames() throws Exception {
        RoomResponse a = create(10), b = join(guest, a); assertTrue(b.success());
        assertEquals(a.room().name(), b.room().name()); assertEquals(a.room().id(), b.room().id());
        assertEquals(b.members(), command(host, a, "ROOM_POLL", 0).members());
        assertEquals(2, b.members().size()); assertEquals("Đức", b.members().getFirst().name());
        assertEquals("ALREADY_IN_ROOM", join(guest, a).code());
    }
    @Test void hostMuteAndCameraOffReachTheTargetButMemberCannotControlOthers() throws Exception {
        RoomResponse a = create(10), b = join(guest, a);
        RoomResponse enabled = rooms.handle(RoomRequest.media(guest.token(), b.room().id(), b.ticket(), true, true));
        assertTrue(enabled.members().get(1).microphone()); assertTrue(enabled.members().get(1).camera());
        assertEquals("HOST_REQUIRED", command(guest, b, "ROOM_MIC_OFF", host.user().id()).code());
        assertTrue(command(host, a, "ROOM_MIC_OFF", guest.user().id()).success());
        assertTrue(command(host, a, "ROOM_CAMERA_OFF", guest.user().id()).success());
        Participant target = command(guest, b, "ROOM_POLL", 0).members().get(1);
        assertFalse(target.microphone()); assertFalse(target.camera());
    }
    @Test void kickRevokesRoomAccessAndPreservesLeaveReason() throws Exception {
        RoomResponse a = create(10), b = join(guest, a);
        assertEquals("HOST_REQUIRED", command(guest, b, "ROOM_KICK", host.user().id()).code());
        command(host, a, "ROOM_KICK", guest.user().id());
        assertEquals("NOT_IN_ROOM", command(guest, b, "ROOM_POLL", 0).code());
        assertEquals(1, command(host, a, "ROOM_POLL", 0).members().size());
        try (var connection = connection(); var sql = connection.createStatement(); var result = sql.executeQuery("SELECT Ly_Do_Roi FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung = " + guest.user().id())) { result.next(); assertEquals("BI_MOI_RA", result.getString(1)); }
    }
    @Test void lockAndCloseAreHostOnlyAndAffectAllClients() throws Exception {
        RoomResponse a = create(10), b = join(guest, a);
        assertEquals("HOST_REQUIRED", rooms.handle(RoomRequest.settings(guest.token(), b.room().id(), b.ticket(), true, null)).code());
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), true, null));
        assertEquals("ROOM_LOCKED", join(third, a).code()); assertTrue(command(guest, b, "ROOM_POLL", 0).room().locked());
        assertEquals("HOST_REQUIRED", command(guest, b, "ROOM_CLOSE", 0).code()); command(host, a, "ROOM_CLOSE", 0);
        assertEquals("ROOM_CLOSED", command(guest, b, "ROOM_POLL", 0).code()); assertEquals("ROOM_CLOSED", join(third, a).code());
        try (var connection = connection(); var sql = connection.createStatement(); var result = sql.executeQuery("SELECT Vai_Tro FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung = " + guest.user().id())) {
            result.next(); assertEquals("THANH_VIEN", result.getString(1));
        }
    }
    @Test void wrongPinAndFullRoomCannotAdmitUsers() throws Exception {
        RoomResponse a = create(2);
        assertEquals("WRONG_PIN", rooms.handle(RoomRequest.join(guest.token(), a.room().code(), "9999")).code());
        join(guest, a); assertEquals("ROOM_FULL", join(third, a).code());
    }
    @Test void approvalAndTransferUseCurrentHostIdentity() throws Exception {
        RoomResponse a = create(10);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        RoomResponse pending = join(guest, a); assertTrue(pending.pending()); assertTrue(pending.members().isEmpty());
        assertEquals(1, command(host, a, "ROOM_POLL", 0).waiting().size());
        command(host, a, "ROOM_PENDING_APPROVE", guest.user().id());
        RoomResponse b = command(guest, pending, "ROOM_POLL", 0); assertFalse(b.pending());
        command(host, a, "ROOM_TRANSFER", guest.user().id());
        assertEquals("HOST_REQUIRED", command(host, a, "ROOM_KICK", guest.user().id()).code());
        assertTrue(command(guest, b, "ROOM_CLOSE", 0).success());
    }
    @Test void invalidSessionsAndForgedTicketsCannotReadRoom() throws Exception {
        RoomResponse a = create(10);
        assertEquals("SESSION_INVALID", rooms.handle(RoomRequest.command("ROOM_POLL", "forged", a.room().id(), a.ticket(), 0)).code());
        assertEquals("NOT_IN_ROOM", rooms.handle(RoomRequest.command("ROOM_POLL", guest.token(), a.room().id(), a.ticket(), 0)).code());
        assertEquals("NOT_IN_ROOM", rooms.handle(RoomRequest.command("ROOM_POLL", host.token(), a.room().id(), "forged", 0)).code());
    }
    @Test void heartbeatTimeoutRemovesDisconnectedAccountAndPromotesRemainingUser() throws Exception {
        RoomResponse a = create(10), b = join(guest, a);
        clock.advance(9); command(guest, b, "ROOM_POLL", 0); clock.advance(2);
        RoomResponse snapshot = command(guest, b, "ROOM_POLL", 0);
        assertEquals(1, snapshot.members().size()); assertTrue(snapshot.members().getFirst().host());
        assertEquals("NOT_IN_ROOM", command(host, a, "ROOM_POLL", 0).code());
    }
    @Test void waitingAccountCannotCreateOrJoinAnotherRoom() throws Exception {
        RoomResponse a = create(10);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        RoomResponse waiting = join(guest, a);
        RoomResponse other = rooms.handle(RoomRequest.create(third.token(), "Phòng khác", "1234", 10));
        assertEquals("ALREADY_IN_ROOM", join(guest, other).code());
        assertEquals("ALREADY_IN_ROOM", rooms.handle(RoomRequest.create(guest.token(), "Khác", "1234", 10)).code());
        assertTrue(command(guest, waiting, "ROOM_LEAVE", 0).success());
        assertTrue(join(guest, other).success());
        assertTrue(command(host, a, "ROOM_POLL", 0).waiting().isEmpty());
    }
    @Test void rejectionAndCancellationRevokeWaitingTickets() throws Exception {
        RoomResponse a = create(10);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        RoomResponse waiting = join(guest, a);
        assertTrue(command(host, a, "ROOM_PENDING_REJECT", guest.user().id()).success());
        assertEquals("NOT_IN_ROOM", command(guest, waiting, "ROOM_POLL", 0).code());
        waiting = join(guest, a);
        command(guest, waiting, "ROOM_LEAVE", 0);
        assertEquals("NOT_IN_ROOM", command(guest, waiting, "ROOM_POLL", 0).code());
        assertEquals("INVALID_TARGET", command(host, a, "ROOM_PENDING_APPROVE", guest.user().id()).code());
    }
    @Test void turningApprovalOffAdmitsPendingUsersAfterUnlock() throws Exception {
        RoomResponse a = create(10);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        RoomResponse b = join(guest, a);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), true, false));
        assertTrue(command(guest, b, "ROOM_POLL", 0).pending());
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), false, null));
        assertFalse(command(guest, b, "ROOM_POLL", 0).pending());
        assertEquals(2, command(host, a, "ROOM_POLL", 0).members().size());
    }
    @Test void logoutWhileWaitingCannotBeApproved() throws Exception {
        RoomResponse a = create(10);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        join(guest, a);
        auth.handle(new AuthRequest("LOGOUT", null, null, null, null, guest.token()));
        assertEquals("SESSION_INVALID", command(host, a, "ROOM_PENDING_APPROVE", guest.user().id()).code());
        assertTrue(command(host, a, "ROOM_POLL", 0).waiting().isEmpty());
    }
    @Test void hostLeavePromotesOneMemberAndLastLeaveClearsPendingQueue() throws Exception {
        RoomResponse a = create(10), b = join(guest, a);
        rooms.handle(RoomRequest.settings(host.token(), a.room().id(), a.ticket(), null, true));
        RoomResponse waiting = join(third, a);
        command(host, a, "ROOM_LEAVE", 0);
        RoomResponse next = command(guest, b, "ROOM_POLL", 0);
        assertEquals(1, next.members().size()); assertTrue(next.members().getFirst().host());
        assertEquals(1, next.waiting().size());
        command(guest, b, "ROOM_LEAVE", 0);
        assertEquals("NOT_IN_ROOM", command(third, waiting, "ROOM_POLL", 0).code());
    }
    @Test void databaseFailureRestoresInMemoryMembership() throws Exception {
        RoomResponse a = create(10), b = join(guest, a);
        try (var connection = connection(); var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE Thanh_Vien_Phong ADD CONSTRAINT Test_No_Leave CHECK (Roi_Phong_Luc IS NULL)");
        }
        assertThrows(SQLException.class, () -> command(host, a, "ROOM_KICK", guest.user().id()));
        assertEquals(2, command(guest, b, "ROOM_POLL", 0).members().size());
        try (var connection = connection(); var sql = connection.createStatement()) {
            sql.execute("ALTER TABLE Thanh_Vien_Phong DROP CONSTRAINT Test_No_Leave");
        }
    }
    private static final class TestClock extends Clock {
        private Instant instant = Instant.parse("2026-10-14T10:00:00Z");
        void advance(long seconds) { instant = instant.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
