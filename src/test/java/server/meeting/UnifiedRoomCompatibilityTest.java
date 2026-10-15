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
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); tls = RoomTestFixture.certificates(temporary); }
    @BeforeEach void start() throws Exception {
        database = new RoomTestFixture(tls);
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\r\n]*", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) database.execute(sql);
        database.execute("CREATE TABLE Tin_Nhan (Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT NOT NULL, Ma_Nguoi_Gui BIGINT NOT NULL, Loai_Tin_Nhan VARCHAR(20), Noi_Dung VARCHAR(2000), Ngay_Gui TIMESTAMP, Ngay_Xoa TIMESTAMP)");
        AtomicReference<SignalingServer> signal = new AtomicReference<>(); Gson json = new Gson();
        rooms = new RoomService(new RoomRepository(database::connection), Clock.systemUTC(), "unified-test", new byte[32], 16, event -> {
            var server = signal.get(); if (server != null) server.event(event.recipientConnectionId(), event.type(), event.roomId(), event.revision(), json.toJsonTree(event.body()).getAsJsonObject());
        }); rooms.start();
        signaling = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), new SessionValidator(database::connection, Clock.systemUTC()),
                new MeetingHandler(rooms), rooms.serverEpoch(), SignalingServer.Options.defaults()); signal.set(signaling); signaling.start();
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
