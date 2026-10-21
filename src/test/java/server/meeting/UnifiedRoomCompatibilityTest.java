package server.meeting;

import client.auth.AuthClient;
import client.auth.ChatConnection;
import client.meeting.MeetingSession;
import client.signalingapi.SignalingClient;
import com.google.gson.Gson;
import common.auth.AuthResponse;
import common.chat.*;
import common.meeting.*;
import common.signaling.SignalWire;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import server.auth.SessionValidator;
import server.filetransfer.FileMetadataRepository;
import server.signalingapi.*;
import support.FxTestRuntime;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Both transports use the SAME live room and durable membership, entirely in temporary H2/TLS. */
class UnifiedRoomCompatibilityTest {
    @TempDir static Path temporary;
    static RoomTestFixture.Tls tls;
    RoomTestFixture database;
    RoomService rooms;
    SignalingServer signaling;
    AuthServer collaboration;
    AuthClient transport;
    final List<MeetingSession> sessions = new ArrayList<>();
    final List<ChatConnection> chats = new ArrayList<>();
    final TestClock clock = new TestClock();
    volatile boolean emulateOldAdmissionServer;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); tls = RoomTestFixture.certificates(temporary); }
    @BeforeEach void start() throws Exception {
        database = new RoomTestFixture(tls);
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\r\n]*", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) database.execute(sql);
        database.execute("CREATE TABLE Tin_Nhan (Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT NOT NULL, Ma_Nguoi_Gui BIGINT NOT NULL, Loai_Tin_Nhan VARCHAR(20), Noi_Dung VARCHAR(2000), Ngay_Gui TIMESTAMP, Ngay_Xoa TIMESTAMP)");
        AtomicReference<SignalingServer> signal = new AtomicReference<>(); Gson json = new Gson();
        rooms = new RoomService(new RoomRepository(database::connection), clock, "unified-test", new byte[32], 16, event -> {
            var server = signal.get(); if (server != null) server.event(event.recipientConnectionId(), event.type(), event.roomId(), event.revision(), json.toJsonTree(event.body()).getAsJsonObject());
        }); rooms.start();
        var handler = new MeetingHandler(rooms);
        signaling = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), new SessionValidator(database::connection, Clock.systemUTC()),
                new SignalingServer.Handler() {
                    public common.signaling.SignalMessage handle(SignalingServer.Connection connection, common.signaling.SignalMessage request) throws Exception {
                        if (emulateOldAdmissionServer && "ADMISSION_STATUS".equals(request.type())) throw new common.signaling.SignalException("UNKNOWN_OPERATION");
                        return handler.handle(connection, request);
                    }
                    public void disconnected(SignalingServer.Connection connection, String reason) { handler.disconnected(connection, reason); }
                    public void validated(SignalingServer.Connection connection) { handler.validated(connection); }
                    public void bindServer(SignalingServer server) { handler.bindServer(server); }
                }, rooms.serverEpoch(), SignalingServer.Options.defaults()); signal.set(signaling); signaling.start();
        collaboration = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), request -> common.auth.AuthResponse.error("INVALID_REQUEST", "Unsupported"), rooms);
        collaboration.start(); transport = new AuthClient("localhost", collaboration.port(), tls.client(), 5000);
    }
    @AfterEach void close() throws Exception {
        chats.forEach(ChatConnection::close); sessions.forEach(MeetingSession::close);
        if (signaling != null) signaling.close(); if (collaboration != null) collaboration.close();
        if (rooms != null) rooms.close(); if (database != null) database.close();
    }
    MeetingSession session(AuthResponse account) {
        MeetingSession session = new MeetingSession(account.token(), Instant.parse(account.expiresAt()), listener -> new SignalingClient(
                new SignalingClient.Settings("localhost", signaling.port(), null, SignalWire.DEFAULT_MAX_BYTES), tls.client(), listener));
        sessions.add(session); return session;
    }
    @Test void guestCannotManageRoomAndKickedGuestClosesMediaThenRejoinsWithCodeAndPin() throws Exception {
        var host = database.account("moderator", "Host"); var guest = database.account("moderated", "Guest");
        var a = session(host); var b = session(guest);
        a.create(new CreateRoomParams("Moderation", "1234", 3, false)).get(10, TimeUnit.SECONDS);
        a.join(false, false).get(10, TimeUnit.SECONDS);
        String code = a.snapshot().joinCode();
        b.lookup(code, "1234").get(10, TimeUnit.SECONDS); b.join(false, false).get(10, TimeUnit.SECONDS);
        String previous = b.selfParticipantId(); var media = b.video();
        for (String action : List.of("ROOM_SETTINGS", "ROOM_PENDING_APPROVE", "ROOM_PENDING_REJECT",
                "ROOM_TRANSFER", "ROOM_KICK", "ROOM_MIC_OFF", "ROOM_CAMERA_OFF")) {
            var denied = assertThrows(client.auth.RoomException.class,
                    () -> transport.meeting(command(action, guest, b, host.user().id())));
            assertEquals("HOST_REQUIRED", denied.code(), action);
        }
        assertTrue(transport.meeting(command("ROOM_KICK", host, a, guest.user().id())).success());
        waitFor(() -> b.snapshot() == null && a.snapshot().participants().size() == 1);
        media.closeComplete().get(5, TimeUnit.SECONDS);
        b.lookup(code, "1234").get(10, TimeUnit.SECONDS); b.join(false, false).get(10, TimeUnit.SECONDS);
        assertNotEquals(previous, b.selfParticipantId());
        waitFor(() -> a.snapshot().participants().size() == 2);
    }
    @Test void chosenSuccessorRatherThanEarliestJoinerKeepsRoomAndFileRightsUntilExplicitClose() throws Exception {
        var host = database.account("choiceHost", "Host"); var early = database.account("choiceEarly", "Early"); var chosen = database.account("choiceChosen", "Chosen");
        var a = session(host); var b = session(early); var c = session(chosen);
        var cleared = new java.util.concurrent.atomic.AtomicInteger();
        var received = new java.util.concurrent.atomic.AtomicInteger();
        a.addChatListener(new MeetingSession.ChatListener() {
            public void onChatMessage(common.chat.ChatMessage message) { received.incrementAndGet(); }
            public void onChatCleared() { cleared.incrementAndGet(); }
        });
        a.create(new CreateRoomParams("Chosen host", "1234", 4, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        String code = a.snapshot().joinCode(); long id = a.snapshot().collaborationRoom().id();
        b.lookup(code, "1234").get(10, TimeUnit.SECONDS); b.join(false, false).get(10, TimeUnit.SECONDS);
        c.lookup(code, "1234").get(10, TimeUnit.SECONDS); c.join(false, false).get(10, TimeUnit.SECONDS);
        a.sendChat("Chat before explicit host transfer").get(10, TimeUnit.SECONDS); waitFor(() -> received.get() == 1);
        var failed = assertThrows(ExecutionException.class, () -> a.leave("missing", false).get(10, TimeUnit.SECONDS));
        assertEquals("PARTICIPANT_NOT_FOUND", ((common.signaling.SignalException) failed.getCause()).code()); assertTrue(a.isHost());
        assertEquals(0, cleared.get(), "Rejected host departure must preserve the chat");
        a.leave(c.selfParticipantId(), false).get(10, TimeUnit.SECONDS); waitFor(c::isHost); assertFalse(b.isHost());
        assertEquals(1, cleared.get(), "Explicit host departure must clear chat as the new chat transport expects");
        var files = new FileMetadataRepository(database::connection); files.authorize(early.user().id(), id); files.authorize(chosen.user().id(), id);
        assertThrows(common.filetransfer.TransferException.class, () -> files.authorize(host.user().id(), id));
        assertTrue(transport.chat(ChatRequest.send(chosen.token(), id, c.selfParticipantId(), "new host can chat")).success());
        c.leave(null, true).get(10, TimeUnit.SECONDS); waitFor(() -> b.snapshot() == null);
        assertEquals(0, rooms.participantCount()); assertThrows(common.filetransfer.TransferException.class, () -> files.authorize(early.user().id(), id));
    }
    @Test void pendingAdmissionCanBeApprovedAfterMorePollsThanThePinAttemptBudget() throws Exception {
        var host = database.account("longWaitHost", "Host"); var guest = database.account("longWaitGuest", "Guest");
        var a = session(host); var b = session(guest);
        a.create(new CreateRoomParams("Long wait", "1234", 3, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        transport.meeting(RoomRequest.settings(host.token(), a.snapshot().collaborationRoom().id(), a.selfParticipantId(), null, true));
        b.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS); var joining = b.join(false, false);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size() == 1; } catch (Exception error) { throw new RuntimeException(error); } });
        Thread.sleep(7000); assertFalse(joining.isDone(), "Pending polls must not consume the PIN attempt budget");
        transport.meeting(command("ROOM_PENDING_APPROVE", host, a, guest.user().id()));
        joining.get(10, TimeUnit.SECONDS); assertEquals(2, rooms.participantCount());
    }
    @Test @Timeout(340) void pendingAdmissionRemainsUntilFiveMinutesThenExpiresAndAllowsANewRequest() throws Exception {
        var host = database.account("ttlHost", "Host"); var guest = database.account("ttlGuest", "Guest");
        var a = session(host); var b = session(guest);
        a.create(new CreateRoomParams("Five minute wait", "1234", 3, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        assertTrue(transport.meeting(RoomRequest.settings(host.token(), a.snapshot().collaborationRoom().id(), a.selfParticipantId(), null, true)).success());
        b.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS); var joining = b.join(false, false);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size() == 1; } catch (Exception error) { throw new RuntimeException(error); } });
        Instant created = clock.instant(); long started = System.nanoTime();
        for (int second : new int[]{4, 30, 60, 120, 240, 299}) {
            if (Boolean.getBoolean("mmr.admission.test.realWait")) TimeUnit.NANOSECONDS.sleep(Math.max(0, TimeUnit.SECONDS.toNanos(second) - (System.nanoTime() - started)));
            clock.now = created.plusSeconds(second);
            assertEquals(1, transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size(), "Request disappeared at second " + second);
            assertFalse(joining.isDone(), "Waiting must survive without a host decision at second " + second);
            assertEquals(1, rooms.participantCount(), "Pending guests must not receive membership");
        }
        if (Boolean.getBoolean("mmr.admission.test.realWait")) TimeUnit.NANOSECONDS.sleep(Math.max(0, TimeUnit.SECONDS.toNanos(300) - (System.nanoTime() - started)));
        clock.now = created.plusSeconds(300); rooms.maintenanceTick();
        assertTrue(transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().isEmpty());
        var expired = assertThrows(ExecutionException.class, () -> joining.get(10, TimeUnit.SECONDS));
        assertEquals("ADMISSION_EXPIRED", ((common.signaling.SignalException) expired.getCause()).code());
        var retried = b.join(false, false);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size() == 1; } catch (Exception error) { throw new RuntimeException(error); } });
        assertTrue(transport.meeting(command("ROOM_PENDING_APPROVE", host, a, guest.user().id())).success());
        retried.get(10, TimeUnit.SECONDS); assertEquals(2, rooms.participantCount());
    }
    @Test void oldServerReportsThatItNeedsRestartingInsteadOfAGenericConnectionError() throws Exception {
        emulateOldAdmissionServer = true;
        var host = database.account("oldHost", "Host"); var guest = database.account("oldGuest", "Guest");
        var a = session(host); var b = session(guest);
        a.create(new CreateRoomParams("Old server", "1234", 3, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        transport.meeting(RoomRequest.settings(host.token(), a.snapshot().collaborationRoom().id(), a.selfParticipantId(), null, true));
        b.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS);
        var failed = assertThrows(ExecutionException.class, () -> b.join(false, false).get(10, TimeUnit.SECONDS));
        assertEquals("SERVER_UPDATE_REQUIRED", ((common.signaling.SignalException) failed.getCause()).code());
        assertTrue(client.UI.SceneManager.connectionError(failed).contains("khởi động lại server"));
    }
    private static final class TestClock extends Clock {
        volatile Instant now = Instant.now();
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
    RoomRequest command(String action, AuthResponse account, MeetingSession session, long target) {
        return RoomRequest.command(action, account.token(), session.snapshot().collaborationRoom().id(), session.selfParticipantId(), target);
    }
    static void waitFor(java.util.function.BooleanSupplier check) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) { if (check.getAsBoolean()) return; Thread.sleep(30); }
        fail("Timed out waiting for unified room state");
    }
    @Test void approvalChatTransferAndFilesShareExactlyOneMembership() throws Exception {
        var host = database.account("host", "Host"); var guest = database.account("guest", "Guest");
        var a = session(host); var b = session(guest);
        a.create(new CreateRoomParams("Unified", "1234", 3, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        long id = a.snapshot().collaborationRoom().id(); var files = new FileMetadataRepository(database::connection);
        assertTrue(transport.meeting(RoomRequest.settings(host.token(), id, a.selfParticipantId(), null, true)).success());
        b.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS);
        var joining = b.join(false, false);
        waitFor(() -> {
            try { return transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size() == 1; }
            catch (Exception error) { throw new RuntimeException(error); }
        });
        assertFalse(joining.isDone()); assertEquals(1, rooms.participantCount());
        assertThrows(common.filetransfer.TransferException.class, () -> files.authorize(guest.user().id(), id));
        assertThrows(common.chat.ChatException.class, () -> transport.chat(ChatRequest.send(guest.token(), id, "forged-ticket", "not admitted")));
        transport.meeting(command("ROOM_PENDING_APPROVE", host, a, guest.user().id()));
        joining.get(10, TimeUnit.SECONDS); assertEquals(2, rooms.participantCount()); files.authorize(guest.user().id(), id);
        var received = new CopyOnWriteArrayList<ChatMessage>(); var connected = new CountDownLatch(1);
        chats.add(transport.listenChat(ChatRequest.listen(guest.token(), id, b.selfParticipantId()), response -> { received.addAll(response.messages()); connected.countDown(); }, error -> { }));
        assertTrue(connected.await(5, TimeUnit.SECONDS));
        transport.chat(ChatRequest.send(host.token(), id, a.selfParticipantId(), "same video room"));
        waitFor(() -> received.stream().anyMatch(message -> message.text().equals("same video room")));
        a.leave().get(10, TimeUnit.SECONDS);
        waitFor(b::isHost); assertEquals(1, rooms.participantCount()); files.authorize(guest.user().id(), id);
        assertThrows(common.filetransfer.TransferException.class, () -> files.authorize(host.user().id(), id));
        assertTrue(transport.chat(ChatRequest.send(guest.token(), id, b.selfParticipantId(), "still active after transfer")).success());
        assertTrue(transport.meeting(RoomRequest.settings(guest.token(), id, b.selfParticipantId(), true, null)).success());
        transport.meeting(command("ROOM_CLOSE", guest, b, 0)); waitFor(() -> rooms.participantCount() == 0);
        assertThrows(common.chat.ChatException.class, () -> transport.chat(ChatRequest.send(guest.token(), id, b.selfParticipantId(), "closed")));
        assertThrows(common.filetransfer.TransferException.class, () -> files.authorize(guest.user().id(), id));
        assertEquals(1, database.count("SELECT COUNT(*) FROM Phong_Hop"));
    }
    @Test void transferredHostCanReopenWithFreshRightsAfterContentCleanup() throws Exception {
        var owner = database.account("owner", "Owner"); var successor = database.account("successor", "Successor");
        var a = session(owner); var b = session(successor);
        a.create(new CreateRoomParams("Reopened unified room", "1234", 3, false)).get(10, TimeUnit.SECONDS);
        a.join(false, false).get(10, TimeUnit.SECONDS);
        var original = a.snapshot(); long id = original.collaborationRoom().id(); String code = original.joinCode();
        b.lookup(code, "1234").get(10, TimeUnit.SECONDS); b.join(false, false).get(10, TimeUnit.SECONDS);
        String oldTicket = b.selfParticipantId();
        transport.chat(ChatRequest.send(owner.token(), id, a.selfParticipantId(), "before reopening"));
        a.leave().get(10, TimeUnit.SECONDS); waitFor(b::isHost);
        transport.meeting(command("ROOM_CLOSE", successor, b, 0));
        waitFor(() -> b.snapshot() == null && rooms.participantCount() == 0);
        var forbidden = assertThrows(ExecutionException.class, () -> a.reopen(code).get(10, TimeUnit.SECONDS));
        assertEquals("HOST_REQUIRED", ((common.signaling.SignalException) forbidden.getCause()).code());
        var cleaning=assertThrows(ExecutionException.class,() -> b.reopen(code).get(10,TimeUnit.SECONDS));
        assertEquals("ROOM_CLEANING",((common.signaling.SignalException)cleaning.getCause()).code());
        database.execute("CREATE TABLE IF NOT EXISTS Tep_Dinh_Kem(Ma_Tep BIGINT PRIMARY KEY,Ma_Tin_Nhan BIGINT)");
        try(var cleanup=new ImmediateRoomCleanup(database::connection,"unified-test",room -> {},room -> false,() -> {})) {assertTrue(cleanup.clean(id));}
        var preview = b.reopen(code).get(10, TimeUnit.SECONDS);
        assertNotEquals(original.roomId(), preview.roomId()); assertEquals(code, preview.joinCode());
        var files = new FileMetadataRepository(database::connection);
        assertThrows(common.filetransfer.TransferException.class, () -> files.authorize(successor.user().id(), id));
        b.join(false, false).get(10, TimeUnit.SECONDS);
        assertTrue(b.isHost()); assertEquals(id, b.snapshot().collaborationRoom().id());
        assertEquals(original.collaborationRoom().createdAt(), b.snapshot().collaborationRoom().createdAt());
        assertNotEquals(oldTicket, b.selfParticipantId()); files.authorize(successor.user().id(), id);
        assertThrows(common.chat.ChatException.class, () -> transport.chat(ChatRequest.send(successor.token(), id, oldTicket, "stale ticket")));
        transport.chat(ChatRequest.send(successor.token(), id, b.selfParticipantId(), "after reopening"));
        var history = new CopyOnWriteArrayList<ChatMessage>();
        chats.add(transport.listenChat(ChatRequest.listen(successor.token(), id, b.selfParticipantId()), response -> history.addAll(response.messages()), error -> { }));
        waitFor(() -> history.stream().anyMatch(message -> message.text().equals("after reopening")));
        assertFalse(history.stream().anyMatch(message -> message.text().equals("before reopening")));
        var numericJoin = assertThrows(ExecutionException.class, () -> a.lookup(Long.toString(id), "1234").get(10, TimeUnit.SECONDS));
        assertEquals("ROOM_NOT_FOUND", ((common.signaling.SignalException) numericJoin.getCause()).code());
        a.lookup(code, "1234").get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        assertFalse(a.isHost()); assertEquals(2, rooms.participantCount());
        assertEquals(1, database.count("SELECT COUNT(*) FROM Phong_Hop"));
    }
    @Test void explicitTransferKickAndAdmissionCancelRevokeRights() throws Exception {
        var host = database.account("host", "Host"); var guest = database.account("guest", "Guest"); var pending = database.account("waiter", "Waiter");
        var a = session(host); var b = session(guest); var c = session(pending);
        a.create(new CreateRoomParams("Unified", "1234", 4, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        b.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS); b.join(false, false).get(10, TimeUnit.SECONDS);
        long id = a.snapshot().collaborationRoom().id();
        transport.meeting(command("ROOM_TRANSFER", host, a, guest.user().id())); waitFor(b::isHost);
        assertThrows(client.auth.RoomException.class, () -> transport.meeting(RoomRequest.settings(host.token(), id, a.selfParticipantId(), true, null)));
        transport.meeting(RoomRequest.settings(guest.token(), id, b.selfParticipantId(), null, true));
        c.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS); var joining = c.join(false, false);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", guest, b, 0)).waiting().size() == 1; } catch (Exception e) { throw new RuntimeException(e); } });
        c.close(); waitFor(joining::isDone);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", guest, b, 0)).waiting().isEmpty(); } catch (Exception e) { throw new RuntimeException(e); } });
        transport.meeting(command("ROOM_KICK", guest, b, host.user().id()));
        assertThrows(common.chat.ChatException.class, () -> transport.chat(ChatRequest.send(host.token(), id, a.selfParticipantId(), "kicked")));
        assertThrows(common.filetransfer.TransferException.class, () -> new FileMetadataRepository(database::connection).authorize(host.user().id(), id));
    }
    @Test void rejectedAdmissionNeverGrantsChatOrFilesAndCanBeRetried() throws Exception {
        var host = database.account("host", "Host"); var guest = database.account("guest", "Guest");
        var a = session(host); var b = session(guest);
        a.create(new CreateRoomParams("Approval", "1234", 3, false)).get(10, TimeUnit.SECONDS); a.join(false, false).get(10, TimeUnit.SECONDS);
        long id = a.snapshot().collaborationRoom().id();
        transport.meeting(RoomRequest.settings(host.token(), id, a.selfParticipantId(), null, true));
        b.lookup(a.snapshot().joinCode(), "1234").get(10, TimeUnit.SECONDS); var joining = b.join(false, false);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size() == 1; } catch (Exception e) { throw new RuntimeException(e); } });
        transport.meeting(command("ROOM_PENDING_REJECT", host, a, guest.user().id()));
        var failure = assertThrows(ExecutionException.class, () -> joining.get(10, TimeUnit.SECONDS));
        assertEquals("ADMISSION_REJECTED", ((common.signaling.SignalException) failure.getCause()).code());
        assertEquals(1, rooms.participantCount());
        assertThrows(common.filetransfer.TransferException.class, () -> new FileMetadataRepository(database::connection).authorize(guest.user().id(), id));
        var retry = b.join(false, false);
        waitFor(() -> { try { return transport.meeting(command("ROOM_POLL", host, a, 0)).waiting().size() == 1; } catch (Exception e) { throw new RuntimeException(e); } });
        transport.meeting(RoomRequest.settings(host.token(), id, a.selfParticipantId(), null, false));
        retry.get(10, TimeUnit.SECONDS); assertEquals(2, rooms.participantCount());
    }

}
