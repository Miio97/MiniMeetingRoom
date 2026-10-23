package server.meeting;

import common.auth.AuthResponse;
import common.meeting.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.auth.*;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed server clock, temporary DB; expiry is tested without waiting five minutes. */
class RoomAdmissionTest {
    @TempDir static Path temporary;
    static RoomTestFixture.Tls tls;
    RoomTestFixture database; RoomService rooms; TestClock clock; SessionValidator validator;
    AuthResponse host, guest, third; RoomSnapshot room; RoomJoinReply joined;
    @BeforeAll static void tls() throws Exception { tls = RoomTestFixture.certificates(temporary); }
    @BeforeEach void start() throws Exception {
        database = new RoomTestFixture(tls); clock = new TestClock();
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) database.execute(sql);
        rooms = new RoomService(new RoomRepository(database::connection), clock, "admission-test", new byte[32], 10, event -> { }); rooms.start();
        validator = new SessionValidator(database::connection, clock);
        host = database.account("admissionHost", "Host"); guest = database.account("admissionGuest", "Guest"); third = database.account("admissionThird", "Third");
        room = rooms.create(principal(host), UUID.randomUUID().toString(), new CreateRoomParams("Admission", "1234", 4, false));
        joined = rooms.join(principal(host), "host", 1, UUID.randomUUID().toString(), room.joinCode(), "1234", false, false);
        assertTrue(settings(null, true).success());
    }
    @AfterEach void stop() throws Exception { if (rooms != null) rooms.close(); if (database != null) database.close(); }
    SessionPrincipal principal(AuthResponse account) throws Exception { return validator.validate(account.token()).orElseThrow(); }
    RoomResponse settings(Boolean lock, Boolean approval) throws Exception { return rooms.handle(RoomRequest.settings(host.token(), room.collaborationRoom().id(), joined.selfParticipantId(), lock, approval)); }
    RoomJoinReply join(AuthResponse account, String connection) throws Exception { return rooms.join(principal(account), connection, 1, UUID.randomUUID().toString(), room.joinCode(), "1234", false, false); }
    RoomResponse pending() throws Exception { return rooms.handle(RoomRequest.command("ROOM_POLL", host.token(), room.collaborationRoom().id(), joined.selfParticipantId(), 0)); }
    RoomResponse decide(String action, AuthResponse account) throws Exception { return rooms.handle(RoomRequest.command(action, host.token(), room.collaborationRoom().id(), joined.selfParticipantId(), account.user().id())); }
    void code(String code, Throwing operation) { assertEquals(code, assertThrows(RoomException.class, operation::run).code()); }
    interface Throwing { void run() throws Exception; }
    @Test void pendingExpiresAtFiveMinutesEvenIfItsConnectionKeepsPolling() throws Exception {
        code("APPROVAL_PENDING", () -> join(guest, "guest"));
        Instant created = clock.now;
        for (int seconds : new int[]{30, 60, 120, 240, 299}) {
            clock.now = created.plusSeconds(seconds); rooms.maintenanceTick();
            code("APPROVAL_PENDING", () -> rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()));
            assertEquals(1, pending().waiting().size());
        }
        clock.now = created.plusSeconds(300); rooms.maintenanceTick();
        assertTrue(pending().waiting().isEmpty());
        code("ADMISSION_EXPIRED", () -> rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()));
        code("APPROVAL_PENDING", () -> join(guest, "guest")); assertEquals(1, pending().waiting().size());
    }
    @Test void lockAndApprovalHaveConsistentPriorityAndAdmissionIsBoundToTheOriginalConnection() throws Exception {
        settings(true, null); code("ROOM_LOCKED", () -> join(guest, "guest")); assertTrue(pending().waiting().isEmpty());
        settings(false, null); code("APPROVAL_PENDING", () -> join(guest, "guest"));
        code("ADMISSION_FORBIDDEN", () -> rooms.admissionStatus(principal(guest), "another", 1, room.roomId()));
        code("ADMISSION_FORBIDDEN", () -> rooms.admissionStatus(principal(guest), "guest", 2, room.roomId()));
        code("ADMISSION_EXPIRED", () -> rooms.admissionStatus(principal(third), "third", 1, room.roomId()));
        settings(true, null); assertEquals("ROOM_LOCKED", decide("ROOM_PENDING_APPROVE", guest).code());
        code("APPROVAL_PENDING", () -> rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()));
        settings(false, null); assertTrue(decide("ROOM_PENDING_APPROVE", guest).success());
        var admitted = rooms.admissionStatus(principal(guest), "guest", 1, room.roomId());
        assertEquals(admitted.selfParticipantId(), rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()).selfParticipantId());
        assertEquals(2, rooms.participantCount()); assertTrue(pending().waiting().isEmpty());
        rooms.leaveByChoice("guest", room.roomId(), null, false); assertEquals(1, rooms.participantCount());
    }
    @Test void decisionsMadeBeforeFiveMinutesAreStillDeliveredAfterThePendingDeadline() throws Exception {
        code("APPROVAL_PENDING", () -> join(guest, "guest")); code("APPROVAL_PENDING", () -> join(third, "third"));
        Instant created = clock.now; clock.now = created.plusSeconds(299);
        assertTrue(decide("ROOM_PENDING_APPROVE", guest).success()); assertTrue(decide("ROOM_PENDING_REJECT", third).success());
        assertTrue(pending().waiting().isEmpty()); clock.now = created.plusSeconds(301); rooms.maintenanceTick();
        assertNotNull(rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()));
        code("ADMISSION_REJECTED", () -> rooms.admissionStatus(principal(third), "third", 1, room.roomId()));
        assertEquals(2, rooms.participantCount());
    }
    @Test void explicitHostDepartureUsesTheSelectedMemberAndRejectsStaleTargetsWithoutRemovingHost() throws Exception {
        settings(null, false); var first = join(guest, "guest"); var last = join(third, "third");
        code("HOST_REQUIRED", () -> rooms.leaveByChoice("guest", room.roomId(), last.selfParticipantId(), false));
        code("PARTICIPANT_NOT_FOUND", () -> rooms.leaveByChoice("host", room.roomId(), "missing", false));
        code("HOST_DECISION_REQUIRED", () -> rooms.leaveByChoice("host", room.roomId(), null, false));
        assertEquals(joined.selfParticipantId(), rooms.getRoom("host", room.roomId()).hostParticipantId());
        rooms.leaveByChoice("host", room.roomId(), last.selfParticipantId(), false);
        var state = rooms.getRoom("guest", room.roomId());
        assertEquals(last.selfParticipantId(), state.hostParticipantId()); assertEquals(third.user().id(), state.collaborationRoom().hostId());
        assertEquals(RoomRole.MEMBER, state.participants().stream().filter(p -> p.participantId().equals(first.selfParticipantId())).findFirst().orElseThrow().role());
        assertEquals(2, rooms.participantCount());
        assertEquals(third.user().id(), database.count("SELECT Ma_Chu_Phong FROM Phong_Hop"));
        assertEquals(1, database.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Vai_Tro='CHU_PHONG' AND Roi_Phong_Luc IS NULL"));
    }
    @Test void hostCanCloseTheWholeRoomIncludingItsPendingQueue() throws Exception {
        code("APPROVAL_PENDING", () -> join(guest, "guest"));
        rooms.leaveByChoice("host", room.roomId(), null, true);
        assertEquals(0, rooms.participantCount()); assertEquals(0, rooms.roomCount());
        code("ROOM_NOT_ACTIVE", () -> rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()));
        assertEquals(1, database.count("SELECT COUNT(*) FROM Phong_Hop WHERE Trang_Thai='DA_DONG'"));
    }
    @Test void pendingCancellationIsBoundToItsSessionConnectionAndGeneration() throws Exception {
        code("APPROVAL_PENDING", () -> join(guest, "guest"));
        assertFalse(rooms.cancelAdmission(principal(third), "guest", 1, room.roomId()));
        assertFalse(rooms.cancelAdmission(principal(guest), "another", 1, room.roomId()));
        assertFalse(rooms.cancelAdmission(principal(guest), "guest", 2, room.roomId()));
        assertEquals(1, pending().waiting().size());
        assertTrue(rooms.cancelAdmission(principal(guest), "guest", 1, room.roomId()));
        assertFalse(rooms.cancelAdmission(principal(guest), "guest", 1, room.roomId()));
        assertTrue(pending().waiting().isEmpty());
        code("ADMISSION_EXPIRED", () -> rooms.admissionStatus(principal(guest), "guest", 1, room.roomId()));
        assertEquals(1, rooms.participantCount());
    }
    private static final class TestClock extends Clock {
        Instant now = Instant.now(); public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; } public Instant instant() { return now; }
    }
}
