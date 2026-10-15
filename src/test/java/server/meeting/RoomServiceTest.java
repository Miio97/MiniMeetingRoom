package server.meeting;

import common.meeting.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.auth.SessionPrincipal;
import static org.junit.jupiter.api.Assertions.*;

class RoomServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00Z");
    private static final byte[] KEY = new byte[32];
    private String url;
    private final MutableClock clock = new MutableClock();
    private final List<RoomService> owned = new ArrayList<>();
    private final List<RoomEvent> events = new CopyOnWriteArrayList<>();
    private RoomRepository repository;
    private RoomService service;

    @BeforeEach
    void createDatabase() throws SQLException {
        url = "jdbc:h2:mem:rooms_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("""
                CREATE TABLE Nguoi_Dung (Ma_Nguoi_Dung BIGINT PRIMARY KEY,
                    Ten_Hien_Thi VARCHAR(100) NOT NULL, Trang_Thai VARCHAR(20) NOT NULL)
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (Ma_Phien BIGINT PRIMARY KEY, Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE, Het_Han_Luc TIMESTAMP NOT NULL, Thu_Hoi_Luc TIMESTAMP)
                """);
        execute("""
                CREATE TABLE Phong_Hop (Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL UNIQUE, Ten_Phong VARCHAR(100) NOT NULL,
                    Ma_PIN_Bam VARCHAR(255), Ma_Chu_Phong BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Trang_Thai VARCHAR(20) NOT NULL, Da_Khoa BOOLEAN NOT NULL, So_Nguoi_Toi_Da SMALLINT NOT NULL CHECK(So_Nguoi_Toi_Da>0),
                    Ngay_Tao TIMESTAMP NOT NULL, Ngay_Dong TIMESTAMP)
                """);
        execute("""
                CREATE TABLE Thanh_Vien_Phong (Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop(Ma_Phong), Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Vai_Tro VARCHAR(20) NOT NULL, Tham_Gia_Luc TIMESTAMP NOT NULL, Roi_Phong_Luc TIMESTAMP, Ly_Do_Roi VARCHAR(20))
                """);
        execute("""
                CREATE TABLE MMR_Phong_Van_Hanh (Ma_Phong BIGINT PRIMARY KEY REFERENCES Phong_Hop(Ma_Phong),
                    Room_Opaque_ID VARCHAR(24) NOT NULL UNIQUE, Instance_ID VARCHAR(64) NOT NULL,
                    Server_Epoch VARCHAR(24) NOT NULL, Ngay_Tao TIMESTAMP NOT NULL)
                """);
        execute("""
                CREATE TABLE MMR_Yeu_Cau_Phong (Ma_Phien BIGINT NOT NULL REFERENCES Phien_Dang_Nhap(Ma_Phien),
                    Request_ID VARCHAR(64) NOT NULL, Operation VARCHAR(32) NOT NULL, Body_HMAC BINARY(32) NOT NULL,
                    Result_Code VARCHAR(32) NOT NULL, Ma_Phong BIGINT REFERENCES Phong_Hop(Ma_Phong),
                    Ma_Thanh_Vien_Phong BIGINT REFERENCES Thanh_Vien_Phong(Ma_Thanh_Vien_Phong),
                    Participant_Opaque_ID VARCHAR(24), Ngay_Tao TIMESTAMP NOT NULL, Het_Han_Luc TIMESTAMP NOT NULL,
                    PRIMARY KEY(Ma_Phien,Request_ID))
                """);
        for (int user = 1; user <= 6; user++) {
            execute("INSERT INTO Nguoi_Dung VALUES (" + user + ", 'User " + user + "', 'HOAT_DONG')");
            execute("INSERT INTO Phien_Dang_Nhap VALUES (" + user + ", " + user + ", '" + hashFor(user)
                    + "', TIMESTAMP '2026-10-15 06:00:00', NULL)");
        }
        repository = new RoomRepository(() -> DriverManager.getConnection(url));
        service = newService("instance-a", events::add); service.start();
    }
    @AfterEach
    void closeAll() throws SQLException {
        owned.forEach(RoomService::close);
        execute("DROP ALL OBJECTS");
    }

    @Test
    void createPinAndDurableRetryUseOneRoomAndRejectChangedRequest() throws Exception {
        RoomSnapshot first = create(1, "create-room-00001", 3, false);
        RoomSnapshot replay = create(1, "create-room-00001", 3, false);
        assertEquals(first.roomId(), replay.roomId());
        assertEquals(1, count("Phong_Hop")); assertEquals(1, count("MMR_Yeu_Cau_Phong"));
        assertEquals(RoomStatus.READY, first.status()); assertTrue(first.participants().isEmpty());
        assertEquals(20, first.joinCode().length()); assertTrue(first.roomId().matches("r_[A-Za-z0-9_-]{22}"));
        String hash = scalar("SELECT Ma_PIN_Bam FROM Phong_Hop"); assertNotEquals("1234", hash); assertTrue(hash.startsWith("pbkdf2-sha256$"));
        assertEquals(32, scalarBytes("SELECT Body_HMAC FROM MMR_Yeu_Cau_Phong").length);
        error("REQUEST_ID_REUSED", () -> create(1, "create-room-00001", 2, false));
        assertFalse(new CreateRoomParams("private-name", "private-pin", 2, false).toString().contains("private"));
    }

    @Test
    void hostStartsRoomAndPinLockMembershipAndCrossRoomAreAuthoritative() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 3, true);
        error("WRONG_PIN", () -> service.lookup(principal(2), room.joinCode(), "9999"));
        error("ROOM_NOT_STARTED", () -> join(2, "member-b", "join-member-00002", room));
        RoomJoinReply host = join(1, "host-a", "join-host-000001", room);
        assertEquals(RoomRole.HOST, host.room().participants().getFirst().role());
        error("ROOM_LOCKED", () -> join(2, "member-b", "join-member-00002", room));
        service.setLocked("host-a", false); RoomJoinReply member = join(2, "member-b", "join-member-00002", room);
        assertEquals(2, member.room().participants().size());
        error("HOST_REQUIRED", () -> service.setLocked("member-b", true));
        error("CROSS_ROOM_FORBIDDEN", () -> service.assertMember("member-b", "r_wrong-room"));
        error("PARTICIPANT_NOT_FOUND", () -> service.kick("host-a", "p_other-room"));
        error("ALREADY_JOINED", () -> join(2, "second-b", "join-member-00003", room));
        assertEquals(2, count("Thanh_Vien_Phong"));
        assertEquals(2, service.getRoom("host-a", room.roomId()).participants().size());
    }

    @Test
    void concurrentLastSeatIsAdmittedOnce() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 2, false); join(1, "host-a", "join-host-000001", room);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> raceJoin(2, room, ready, start));
            var second = workers.submit(() -> raceJoin(3, room, ready, start));
            assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown();
            List<String> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter("OK"::equals).count()); assertEquals(1, results.stream().filter("ROOM_FULL"::equals).count());
        }
        assertEquals(2, service.participantCount()); assertEquals(2, count("Thanh_Vien_Phong"));
    }

    @Test
    void retryJoinDoesNotAddHistoryAndRejoinCreatesNewIdentity() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 3, false); join(1, "host-a", "join-host-000001", room);
        RoomJoinReply first = join(2, "member-b", "join-member-00002", room);
        RoomJoinReply replay = join(2, "member-b", "join-member-00002", room);
        assertEquals(first.selfParticipantId(), replay.selfParticipantId()); assertEquals(2, count("Thanh_Vien_Phong"));
        service.leave("member-b", "TU_ROI"); service.leave("member-b", "TU_ROI");
        error("REQUEST_ALREADY_ENDED", () -> join(2, "member-b", "join-member-00002", room));
        RoomJoinReply next = join(2, "member-b", "join-member-00003", room);
        assertNotEquals(first.selfParticipantId(), next.selfParticipantId()); assertEquals(3, count("Thanh_Vien_Phong"));
        assertEquals(1, countWhere("Thanh_Vien_Phong", "Roi_Phong_Luc IS NOT NULL"));
    }

    @Test
    void hostStopOnlyDisablesRequestedMediaAndHostLeaveTransfersOwnership() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 3, false); join(1, "host-a", "join-host-000001", room);
        RoomJoinReply member = join(2, "member-b", "join-member-00002", room);
        service.stopPublish("host-a", member.selfParticipantId(), "AUDIO");
        MediaState stopped = service.assertMember("member-b", room.roomId()).mediaState();
        assertFalse(stopped.micRequested()); assertTrue(stopped.cameraRequested());
        assertFalse(stopped.audioPublishing()); assertFalse(stopped.videoPublishing());
        service.leave("host-a", "TU_ROI");
        assertEquals(1, service.participantCount()); assertEquals(1, service.roomCount());
        assertEquals(RoomRole.HOST, service.assertMember("member-b", room.roomId()).role());
        assertEquals(1, countWhere("Thanh_Vien_Phong", "Roi_Phong_Luc IS NOT NULL AND Ly_Do_Roi='TU_ROI'"));
        assertEquals("DANG_HOAT_DONG", scalar("SELECT Trang_Thai FROM Phong_Hop"));
        assertEquals("2", scalar("SELECT CAST(Ma_Chu_Phong AS VARCHAR) FROM Phong_Hop"));
        assertFalse(events.stream().anyMatch(event -> event.recipientConnectionId().equals("member-b") && event.type().equals("ROOM_CLOSED")));
    }

    @Test
    void logoutAndExpiryRemovePermissionsWithoutRestoringMedia() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 3, false); join(1, "host-a", "join-host-000001", room);
        join(2, "member-b", "join-member-00002", room);
        service.revokeSession(principal(2).tokenHash()); assertEquals(1, service.participantCount());
        error("NOT_A_MEMBER", () -> service.assertMember("member-b", room.roomId()));
        clock.value = NOW.plus(Duration.ofDays(1)); service.maintenanceTick();
        assertEquals(0, service.participantCount()); assertEquals(0, service.roomCount());
    }

    @Test
    void dbSessionRevocationPreventsCreateAndAllCreationRollsBackWhenReceiptFails() throws Exception {
        execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc=CURRENT_TIMESTAMP WHERE Ma_Phien=1");
        error("SESSION_INVALID", () -> create(1, "create-room-00001", 3, false)); assertEquals(0, count("Phong_Hop"));
        execute("ALTER TABLE MMR_Yeu_Cau_Phong ADD CONSTRAINT reject_receipts CHECK(Result_Code<>'OK')");
        assertThrows(SQLException.class, () -> create(2, "create-room-00002", 3, false));
        assertEquals(0, count("Phong_Hop")); assertEquals(0, count("MMR_Phong_Van_Hanh")); assertEquals(0, count("MMR_Yeu_Cau_Phong"));
        assertEquals(0, service.roomCount());
    }

    @Test
    void leaveRevokesImmediatelyEvenWhenDatabaseFailsAndLaterRepairsHistory() throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean();
        repository = new RoomRepository(() -> {
            if (unavailable.get()) throw new SQLException("private-database-unavailable");
            return DriverManager.getConnection(url);
        });
        service = newService("instance-b", events::add); service.start();
        RoomSnapshot room = create(1, "create-room-00001", 3, false); join(1, "host-a", "join-host-000001", room);
        join(2, "member-b", "join-member-00002", room);
        unavailable.set(true); service.leave("member-b", "TU_ROI");
        assertEquals(1, service.participantCount());
        error("NOT_A_MEMBER", () -> service.assertMember("member-b", room.roomId()));
        assertEquals(2, countWhere("Thanh_Vien_Phong", "Roi_Phong_Luc IS NULL"));
        unavailable.set(false); service.maintenanceTick();
        assertEquals(1, countWhere("Thanh_Vien_Phong", "Roi_Phong_Luc IS NULL"));
        assertEquals("TU_ROI", scalar("SELECT Ly_Do_Roi FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung=2"));
    }

    @Test
    void restartClosesOnlyOwnedMarkersAndOldReceiptDoesNotRecreateRoom() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 3, false); join(1, "host-a", "join-host-000001", room);
        execute("""
                INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia,Ten_Phong,Ma_Chu_Phong,Trang_Thai,Da_Khoa,So_Nguoi_Toi_Da,Ngay_Tao)
                VALUES ('LEGACY_ROOM','Unrelated',6,'DANG_HOAT_DONG',FALSE,3,CURRENT_TIMESTAMP)
                """);
        long unrelated = Long.parseLong(scalar("SELECT MAX(Ma_Phong) FROM Phong_Hop"));
        execute("INSERT INTO Thanh_Vien_Phong(Ma_Phong,Ma_Nguoi_Dung,Vai_Tro,Tham_Gia_Luc) VALUES ("
                + unrelated + ",6,'CHU_PHONG',CURRENT_TIMESTAMP)");
        RoomService restarted = newService("instance-a", events::add); restarted.start();
        assertEquals("DA_DONG", scalar("SELECT Trang_Thai FROM Phong_Hop WHERE Ma_Phong_Tham_Gia='" + room.joinCode() + "'"));
        assertEquals("DANG_HOAT_DONG", scalar("SELECT Trang_Thai FROM Phong_Hop WHERE Ma_Phong=" + unrelated));
        assertEquals(1, countWhere("Thanh_Vien_Phong", "Ma_Phong=" + unrelated + " AND Roi_Phong_Luc IS NULL"));
        RoomSnapshot replay = restarted.create(principal(1), "create-room-00001", new CreateRoomParams("Room", "1234", 3, false));
        assertEquals(room.roomId(), replay.roomId()); assertEquals(RoomStatus.CLOSED, replay.status()); assertEquals(2, count("Phong_Hop"));
    }

    @Test
    void listenersRunOutsideStateLockAndEventRevisionIsOrdered() throws Exception {
        AtomicReference<RoomService> reference = new AtomicReference<>(); AtomicBoolean callbackOutsideLock = new AtomicBoolean();
        List<RoomEvent> received = new CopyOnWriteArrayList<>();
        try (var worker = Executors.newSingleThreadExecutor()) {
            RoomService tested = newService("instance-b", event -> {
                received.add(event);
                try { worker.submit(() -> reference.get().participantCount()).get(1, TimeUnit.SECONDS); callbackOutsideLock.set(true); }
                catch (Exception failure) { callbackOutsideLock.set(false); }
            });
            reference.set(tested); tested.start();
            RoomSnapshot room = tested.create(principal(1), "create-room-00001", new CreateRoomParams("Room", null, 3, false));
            tested.join(principal(1), "host-a", 1, "join-host-000001", room.joinCode(), null, true, true);
            tested.join(principal(2), "member-b", 1, "join-member-00002", room.joinCode(), null, true, true);
            tested.setMediaState("member-b", false, true, false); tested.setMediaState("member-b", true, false, false);
            assertTrue(callbackOutsideLock.get());
            List<Long> revisions = received.stream().filter(event -> event.recipientConnectionId().equals("host-a")).map(RoomEvent::revision).toList();
            assertEquals(List.of(2L, 3L, 4L), revisions);
        }
    }

    @Test
    void missingMigrationFailsPreflightAndPendingRoomExpiresWithoutMembership() throws Exception {
        RoomSnapshot room = create(1, "create-room-00001", 3, false);
        clock.value = NOW.plus(Duration.ofMinutes(11)); service.maintenanceTick();
        assertEquals(0, service.roomCount()); assertEquals("DA_DONG", scalar("SELECT Trang_Thai FROM Phong_Hop"));
        assertEquals(0, count("Thanh_Vien_Phong"));
        execute("DROP TABLE MMR_Phong_Van_Hanh");
        RoomService missing = newService("instance-c", events::add);
        assertThrows(SQLException.class, missing::start); assertEquals(0, missing.participantCount());
    }

    private String raceJoin(int user, RoomSnapshot room, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); start.await();
        try { join(user, "member-" + user, "join-member-0000" + user, room); return "OK"; }
        catch (RoomException failure) { return failure.code(); }
    }
    private RoomSnapshot create(int user, String request, int capacity, boolean locked) throws SQLException {
        return service.create(principal(user), request, new CreateRoomParams("Room", "1234", capacity, locked));
    }
    private RoomJoinReply join(int user, String connection, String request, RoomSnapshot room) throws SQLException {
        return service.join(principal(user), connection, 1, request, room.joinCode(), "1234", true, true);
    }
    private RoomService newService(String instance, java.util.function.Consumer<RoomEvent> consumer) {
        RoomService created = new RoomService(repository, clock, instance, KEY, 10, consumer); owned.add(created); return created;
    }
    private SessionPrincipal principal(int user) { return new SessionPrincipal(user, user, "User " + user, NOW.plus(Duration.ofDays(1)), hashFor(user)); }
    private static String hashFor(int user) {
        byte[] bytes = new byte[32]; bytes[31] = (byte) user;
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
    private int count(String table) throws SQLException { return countWhere(table, "1=1"); }
    private int countWhere(String table, String condition) throws SQLException { return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + table + " WHERE " + condition)); }
    private String scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
    private byte[] scalarBytes(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getBytes(1);
        }
    }
    private static void error(String code, Throwing operation) {
        assertEquals(code, assertThrows(RoomException.class, operation::run).code());
    }
    private interface Throwing { void run() throws Exception; }
    private static final class MutableClock extends Clock {
        volatile Instant value = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
