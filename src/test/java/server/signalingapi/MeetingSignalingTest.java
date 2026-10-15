package server.signalingapi;

import client.signalingapi.SignalingClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import common.auth.AuthResponse;
import common.meeting.RoomSnapshot;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import common.signaling.SignalWire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.auth.SessionValidator;
import server.meeting.RoomRepository;
import server.meeting.RoomService;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Actual framed TLS clients, real validators and room persistence; no Railway records. */
class MeetingSignalingTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private static final Gson JSON = new Gson();
    private RoomTestFixture fixture;
    private RoomService rooms;
    private SignalingServer server;
    private final List<SignalingClient> clients = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger snapshotRequests = new java.util.concurrent.atomic.AtomicInteger();

    @BeforeAll static void certificates() throws Exception { tls = RoomTestFixture.certificates(temporary); }

    @BeforeEach void start() throws Exception {
        fixture = new RoomTestFixture(tls);
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "")
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String statement : migration.split(";")) if (!statement.isBlank()) fixture.execute(statement);
        AtomicReference<SignalingServer> current = new AtomicReference<>();
        rooms = new RoomService(new RoomRepository(fixture::connection), Clock.systemUTC(), "test-instance",
                new byte[32], 10, event -> {
                    SignalingServer target = current.get();
                    if (target != null) target.event(event.recipientConnectionId(), event.type(), event.roomId(),
                            event.revision(), JSON.toJsonTree(event.body()).getAsJsonObject());
                });
        rooms.start();
        server = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(fixture::connection, Clock.systemUTC()), new SignalingServer.Handler() {
                    private final MeetingHandler delegate = new MeetingHandler(rooms);
                    public SignalMessage handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
                        if (request.type().equals("GET_ROOM")) snapshotRequests.incrementAndGet();
                        return delegate.handle(connection, request);
                    }
                    public void disconnected(SignalingServer.Connection connection, String reason) { delegate.disconnected(connection, reason); }
                },
                rooms.serverEpoch(), SignalingServer.Options.defaults());
        current.set(server); server.start();
    }

    @AfterEach void close() throws Exception {
        clients.forEach(SignalingClient::close);
        if (server != null) server.close();
        if (rooms != null) rooms.close();
        if (fixture != null) fixture.close();
    }

    @Test void twoTlsClientsReceiveMembershipStateAndLeave() throws Exception {
        List<SignalMessage> events = new CopyOnWriteArrayList<>();
        SignalingClient host = login("host", events);
        SignalingClient guest = login("guest", new CopyOnWriteArrayList<>());
        RoomSnapshot room = create(host, 3, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        join(guest, room, "1234", UUID.randomUUID().toString());
        await(() -> events.stream().anyMatch(e -> "PARTICIPANT_JOINED".equals(e.type())));
        RoomSnapshot shared = get(host, room.roomId());
        assertEquals(shared, get(guest, room.roomId())); assertEquals(2, shared.participants().size());
        JsonObject media = new JsonObject(); media.addProperty("mic", true); media.addProperty("camera", false);
        request(guest, "SET_MEDIA_STATE", room.roomId(), media);
        await(() -> events.stream().anyMatch(e -> "PARTICIPANT_STATE".equals(e.type())));
        assertTrue(get(host, room.roomId()).participants().stream().anyMatch(p -> p.mediaState().micRequested()));
        assertTrue(get(host, room.roomId()).participants().stream().noneMatch(p -> p.mediaState().audioPublishing()));
        request(guest, "LEAVE_ROOM", room.roomId(), new JsonObject());
        assertEquals(1, get(host, room.roomId()).participants().size());
        assertEquals("HEARTBEAT_ACK", request(host, "HEARTBEAT", null, new JsonObject()).type());
        host.close(); await(() -> rooms.participantCount() == 0);
        assertEquals(2, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NOT NULL"));
    }

    @Test void retriesAreIdempotentAndChangedIntentIsRejected() throws Exception {
        SignalingClient host = login("host", new CopyOnWriteArrayList<>());
        String createId = UUID.randomUUID().toString();
        RoomSnapshot room = create(host, 3, createId);
        assertEquals(room.roomId(), create(host, 3, createId).roomId());
        code("REQUEST_ID_REUSED", () -> create(host, 2, createId));
        String joinId = UUID.randomUUID().toString();
        join(host, room, "", joinId); join(host, room, "", joinId);
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop"));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
    }

    @Test void onlyHostReopensClosedRoomAndOldPreviewCannotJoinNewMeetingRun() throws Exception {
        SignalingClient host = login("host", new CopyOnWriteArrayList<>());
        SignalingClient guest = login("guest", new CopyOnWriteArrayList<>());
        RoomSnapshot original = create(host, 3, UUID.randomUUID().toString());
        join(host, original, "", UUID.randomUUID().toString());
        join(guest, original, "1234", UUID.randomUUID().toString());
        JsonObject body = new JsonObject(); body.addProperty("joinCode", original.joinCode());
        rooms.leave(host.connectionId(), "PHONG_DONG");
        code("HOST_REQUIRED", () -> request(guest, "REOPEN_ROOM", null, body));
        String requestId = UUID.randomUUID().toString();
        SignalMessage response = host.request("REOPEN_ROOM", null, body, requestId).get(6, TimeUnit.SECONDS);
        assertEquals("ROOM_REOPENED", response.type());
        RoomSnapshot reopened = JSON.fromJson(response.body().get("room"), RoomSnapshot.class);
        assertNotEquals(original.roomId(), reopened.roomId()); assertEquals(original.joinCode(), reopened.joinCode());
        assertEquals(original.collaborationRoom().id(), reopened.collaborationRoom().id());
        assertEquals(common.meeting.RoomStatus.READY, reopened.status()); assertTrue(reopened.participants().isEmpty());
        RoomSnapshot replay = JSON.fromJson(host.request("REOPEN_ROOM", null, body, requestId)
                .get(6, TimeUnit.SECONDS).body().get("room"), RoomSnapshot.class);
        assertEquals(reopened, replay);
        code("STALE_ROOM", () -> join(guest, original, "1234", UUID.randomUUID().toString()));
        join(host, reopened, "", UUID.randomUUID().toString());
        code("STALE_ROOM", () -> join(guest, original, "1234", UUID.randomUUID().toString()));
        join(guest, reopened, "1234", UUID.randomUUID().toString());
        assertEquals(2, get(host, reopened.roomId()).participants().size());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop"));
        assertEquals(4, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
        request(host, "LEAVE_ROOM", reopened.roomId(), new JsonObject());
    }

    @Test void wrongPinCapacityCrossRoomAndHostChecks() throws Exception {
        SignalingClient host = login("host", new CopyOnWriteArrayList<>());
        SignalingClient guest = login("guest", new CopyOnWriteArrayList<>());
        SignalingClient third = login("third", new CopyOnWriteArrayList<>());
        RoomSnapshot room = create(host, 2, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        code("WRONG_PIN", () -> join(guest, room, "9999", UUID.randomUUID().toString()));
        join(guest, room, "1234", UUID.randomUUID().toString());
        code("ROOM_FULL", () -> join(third, room, "1234", UUID.randomUUID().toString()));
        code("CROSS_ROOM_FORBIDDEN", () -> get(guest, "r_aaaaaaaaaaaaaaaaaaaaaa"));
        JsonObject lock = new JsonObject(); lock.addProperty("locked", true);
        code("HOST_REQUIRED", () -> request(guest, "SET_LOCKED", room.roomId(), lock));
        code("MEDIA_NOT_READY", () -> request(guest, "PUBLISH", room.roomId(), new JsonObject()));
    }

    @Test void lookupAndJoinRejectRoomIdsAndAcceptOnlyPublicCodeWithPin() throws Exception {
        SignalingClient host = login("host", new CopyOnWriteArrayList<>());
        RoomSnapshot room = create(host, 3, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        List<String> invalid = List.of(Long.toString(room.collaborationRoom().id()), "999999999", room.roomId());
        for (int index = 0; index < invalid.size(); index++) {
            SignalingClient attempt = login("invalid" + index, new CopyOnWriteArrayList<>());
            JsonObject body = new JsonObject(); body.addProperty("joinCode", invalid.get(index)); body.addProperty("pin", "1234");
            code("ROOM_NOT_FOUND", () -> request(attempt, "LOOKUP_ROOM", null, body));
            code("ROOM_NOT_FOUND", () -> request(attempt, "JOIN_ROOM", room.roomId(), body));
            assertEquals(1, rooms.participantCount());
            assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
        }
        SignalingClient guest = login("guest", new CopyOnWriteArrayList<>());
        JsonObject body = new JsonObject();
        body.addProperty("joinCode", "  " + room.joinCode().toLowerCase(java.util.Locale.ROOT) + "  ");
        body.addProperty("pin", "9999");
        code("WRONG_PIN", () -> request(guest, "LOOKUP_ROOM", null, body));
        body.addProperty("pin", "1234");
        var preview = JSON.fromJson(request(guest, "LOOKUP_ROOM", null, body).body().get("preview"), common.meeting.RoomPreview.class);
        assertEquals(room.roomId(), preview.roomId()); assertEquals(room.joinCode(), preview.joinCode());
        request(guest, "JOIN_ROOM", preview.roomId(), body);
        assertEquals(2, get(host, room.roomId()).participants().size());
        assertEquals(2, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
        request(guest, "LEAVE_ROOM", room.roomId(), new JsonObject()); assertEquals(1, rooms.participantCount());
    }

    @Test void expiredRevokedInactiveAndInvalidTokensCannotAuthenticate() throws Exception {
        AuthResponse account = fixture.account("expired", "Expired");
        String expiredToken = account.token();
        fixture.execute("UPDATE Phien_Dang_Nhap SET Het_Han_Luc=TIMESTAMP '2000-01-01 00:00:00'");
        code("SESSION_INVALID", () -> connect(expiredToken, new CopyOnWriteArrayList<>()));
        account = fixture.account("revoked", "Revoked");
        String revokedToken = account.token();
        fixture.execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc=CURRENT_TIMESTAMP WHERE Ma_Nguoi_Dung=" + account.user().id());
        code("SESSION_INVALID", () -> connect(revokedToken, new CopyOnWriteArrayList<>()));
        account = fixture.account("inactive", "Inactive"); String inactiveToken = account.token();
        fixture.execute("UPDATE Nguoi_Dung SET Trang_Thai='VO_HIEU_HOA' WHERE Ma_Nguoi_Dung=" + account.user().id());
        code("SESSION_INVALID", () -> connect(inactiveToken, new CopyOnWriteArrayList<>()));
        code("SESSION_INVALID", () -> connect("invalid", new CopyOnWriteArrayList<>()));
    }

    @Test void logoutRevalidationClosesLiveTransportAndRemovesMembership() throws Exception {
        AuthResponse account = fixture.account("host", "Host");
        SignalingClient host = connect(account.token(), new CopyOnWriteArrayList<>());
        RoomSnapshot room = create(host, 2, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        assertTrue(fixture.client().logout(account.token()).success());
        await(() -> host.state() == SignalingClient.State.CLOSED && rooms.participantCount() == 0);
    }

    @Test void slowDatabaseRevalidationDoesNotCloseAValidLiveConnection() throws Exception {
        server.close();
        var delay = new java.util.concurrent.atomic.AtomicBoolean();
        var started = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(1);
        server = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(() -> {
                    if (delay.compareAndSet(true, false)) {
                        started.countDown();
                        try { Thread.sleep(6000); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.sql.SQLException("TEST_INTERRUPTED"); }
                        finally { completed.countDown(); }
                    }
                    return fixture.connection();
                }, Clock.systemUTC()), new MeetingHandler(rooms), rooms.serverEpoch(), SignalingServer.Options.defaults());
        server.start();
        SignalingClient client = login("slow", new CopyOnWriteArrayList<>());
        delay.set(true);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(completed.await(8, TimeUnit.SECONDS));
        assertEquals(SignalingClient.State.CONNECTED, client.state());
        assertEquals("HEARTBEAT_ACK", request(client, "HEARTBEAT", null, new JsonObject()).type());
    }

    @Test void oldConnectionIdentityCannotAuthenticateOrAccessRooms() throws Exception {
        try (SSLSocket socket = (SSLSocket) tls.client().getSocketFactory().createSocket("localhost", server.port())) {
            socket.setSoTimeout(5000); socket.startHandshake();
            var input = new DataInputStream(socket.getInputStream()); var output = new DataOutputStream(socket.getOutputStream());
            SignalMessage welcome = SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES);
            SignalWire.write(output, new SignalMessage(1, "AUTHENTICATE", UUID.randomUUID().toString(),
                    welcome.serverEpoch(), "old-connection", welcome.generation(), null, null, null, new JsonObject()), SignalWire.DEFAULT_MAX_BYTES);
            assertEquals("STALE_CONNECTION", SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES).body().get("code").getAsString());
            SignalWire.write(output, new SignalMessage(1, "CREATE_ROOM", UUID.randomUUID().toString(),
                    welcome.serverEpoch(), welcome.connectionId(), welcome.generation(), null, null, null, new JsonObject()), SignalWire.DEFAULT_MAX_BYTES);
            assertEquals("AUTH_REQUIRED", SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES).body().get("code").getAsString());
            SignalWire.write(output, new SignalMessage(1, "REOPEN_ROOM", UUID.randomUUID().toString(),
                    welcome.serverEpoch(), welcome.connectionId(), welcome.generation(), null, null, null, new JsonObject()), SignalWire.DEFAULT_MAX_BYTES);
            assertEquals("AUTH_REQUIRED", SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES).body().get("code").getAsString());
        }
    }

    @Test void invalidAuthenticationFrameLimitDoesNotGrantSession() throws Exception {
        AuthResponse account = fixture.account("badframe", "Bad frame");
        try (SSLSocket socket = (SSLSocket) tls.client().getSocketFactory().createSocket("localhost", server.port())) {
            socket.setSoTimeout(5000); socket.startHandshake();
            var input = new DataInputStream(socket.getInputStream()); var output = new DataOutputStream(socket.getOutputStream());
            SignalMessage welcome = SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES);
            JsonObject body = new JsonObject(); body.addProperty("token", account.token()); body.addProperty("maxFrameBytes", 1);
            SignalWire.write(output, new SignalMessage(1, "AUTHENTICATE", UUID.randomUUID().toString(),
                    welcome.serverEpoch(), welcome.connectionId(), welcome.generation(), null, null, null, body), SignalWire.DEFAULT_MAX_BYTES);
            assertEquals("FRAME_LIMIT", SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES).body().get("code").getAsString());
            SignalWire.write(output, new SignalMessage(1, "CREATE_ROOM", UUID.randomUUID().toString(),
                    welcome.serverEpoch(), welcome.connectionId(), welcome.generation(), null, null, null, new JsonObject()), SignalWire.DEFAULT_MAX_BYTES);
            assertEquals("AUTH_REQUIRED", SignalWire.read(input, SignalWire.DEFAULT_MAX_BYTES).body().get("code").getAsString());
        }
    }

    @Test void authRoomChannelCannotChangeSignalingMembership() throws Exception {
        AuthResponse account = fixture.account("host", "Host");
        SignalingClient host = connect(account.token(), new CopyOnWriteArrayList<>());
        RoomSnapshot room = create(host, 2, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        var legacy = new server.rooms.RoomService(fixture::connection, new server.auth.PasswordHasher(), Clock.systemUTC(), true);
        assertNotNull(room.collaborationRoom()); assertEquals(account.user().id(), room.collaborationRoom().hostId());
        var listed = legacy.handle(new common.auth.AuthRequest("LIST_ROOMS", null, null, null, null, account.token(), null));
        assertTrue(listed.success());
        assertTrue(listed.rooms().stream().anyMatch(item -> item.id() == room.collaborationRoom().id()), "The main lobby must list the same signaling room");
        var response = legacy.handle(new common.auth.AuthRequest("LEAVE_ROOM", null, null, null, null,
                account.token(), new common.room.RoomRequest(null, null,
                Long.toString(fixture.count("SELECT Ma_Phong FROM MMR_Phong_Van_Hanh")), 0)));
        assertFalse(response.success()); assertEquals(1, rooms.participantCount());
        var reopen = legacy.handle(new common.auth.AuthRequest("REOPEN_ROOM", null, null, null, null,
                account.token(), new common.room.RoomRequest(null, null, Long.toString(room.collaborationRoom().id()), 0)));
        assertFalse(reopen.success()); assertEquals(1, rooms.participantCount());
    }

    @Test void twoJavaFxScreensCreateJoinAndRenderServerMembership() throws Exception {
        support.FxTestRuntime.start();
        AuthResponse hostAccount = fixture.account("fxhost", "FX Host");
        AuthResponse guestAccount = fixture.account("fxguest", "FX Guest");
        var hostSession = session(hostAccount); var guestSession = session(guestAccount);
        var managers = new ArrayList<client.UI.SceneManager>();
        var screens = support.FxTestRuntime.call(() -> {
            var hostManager = new client.UI.SceneManager(new javafx.stage.Stage());
            var guestManager = new client.UI.SceneManager(new javafx.stage.Stage());
            hostManager.acceptSession(hostAccount); guestManager.acceptSession(guestAccount);
            managers.add(hostManager); managers.add(guestManager);
            var host = new client.UI.screens.SfuRoomScreen(hostManager, hostSession);
            var guest = new client.UI.screens.SfuRoomScreen(guestManager, guestSession);
            new javafx.scene.Scene(host, 1100, 700); new javafx.scene.Scene(guest, 1100, 700);
            host.applyCss(); host.layout(); guest.applyCss(); guest.layout();
            return List.of(host, guest);
        });
        try {
            support.FxTestRuntime.call(() -> {
                ((javafx.scene.control.TextField) screens.get(0).lookup("#sfu-room-name")).setText("Actual TLS room");
                ((javafx.scene.control.PasswordField) screens.get(0).lookup("#sfu-create-pin")).setText("1234");
                ((javafx.scene.control.Button) screens.get(0).lookup("#sfu-create-submit")).fire(); return null;
            });
            support.FxTestRuntime.await(() -> hostSession.roomProperty().get() != null);
            String code = hostSession.snapshot().joinCode();
            support.FxTestRuntime.call(() -> {
                ((javafx.scene.control.TextField) screens.get(1).lookup("#sfu-join-code")).setText(code);
                ((javafx.scene.control.PasswordField) screens.get(1).lookup("#sfu-join-pin")).setText("1234");
                ((javafx.scene.control.Button) screens.get(1).lookup("#sfu-join-submit")).fire(); return null;
            });
            support.FxTestRuntime.await(() -> hostSession.participants().size() == 2 && guestSession.participants().size() == 2);
            support.FxTestRuntime.call(() -> {
                assertEquals(2, ((javafx.scene.layout.VBox) screens.get(0).lookup("#sfu-participants")).getChildren().size());
                assertFalse(((javafx.scene.control.Button) screens.get(0).lookup("#sfu-lock")).isDisabled());
                assertTrue(((javafx.scene.control.Button) screens.get(1).lookup("#sfu-lock")).isDisabled());
                ((javafx.scene.control.Button) screens.get(1).lookup("#sfu-leave")).fire(); return null;
            });
            support.FxTestRuntime.await(() -> guestSession.roomProperty().get() == null && hostSession.participants().size() == 1);
            assertEquals(0, snapshotRequests.get(), "Join/participant events must not need extra GET_ROOM requests");
        } finally {
            support.FxTestRuntime.call(() -> {
                screens.forEach(client.UI.screens.SfuRoomScreen::dispose);
                hostSession.close(); guestSession.close(); managers.forEach(client.UI.SceneManager::shutdown); return null;
            });
        }
    }

    private client.meeting.MeetingSession session(AuthResponse account) {
        return new client.meeting.MeetingSession(account.token(), java.time.Instant.parse(account.expiresAt()),
                listener -> new SignalingClient(new SignalingClient.Settings("localhost", server.port(), null,
                        SignalWire.DEFAULT_MAX_BYTES), tls.client(), listener));
    }

    @Test void tlsRejectsUnknownCertificateAndWrongHostname() throws Exception {
        var untrusted = javax.net.ssl.SSLContext.getInstance("TLS"); untrusted.init(null, null, null);
        var client = new SignalingClient(new SignalingClient.Settings("localhost", server.port(), null,
                SignalWire.DEFAULT_MAX_BYTES), untrusted, ignored -> { }); clients.add(client);
        code("CONNECTION_FAILED", () -> client.connect("unused"));
        try (java.net.Socket transport = new java.net.Socket("127.0.0.1", server.port());
                SSLSocket socket = (SSLSocket) tls.client().getSocketFactory().createSocket(transport, "wrong.example", server.port(), true)) {
            var parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters); socket.setSoTimeout(5000);
            assertThrows(javax.net.ssl.SSLHandshakeException.class, socket::startHandshake);
        }
    }

    @RepeatedTest(3) void shutdownCleansRunningAndQueuedRequestsExactlyOnce() throws Exception {
        server.close();
        var entered = new java.util.concurrent.CountDownLatch(8);
        var release = new java.util.concurrent.CountDownLatch(1);
        var disconnected = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var cleanupCount = new java.util.concurrent.atomic.AtomicInteger();
        server = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(fixture::connection, Clock.systemUTC()), new SignalingServer.Handler() {
                    public SignalMessage handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
                        entered.countDown(); release.await(20, TimeUnit.SECONDS);
                        return connection.message("DONE", request.requestId(), null, null, new JsonObject());
                    }
                    public void disconnected(SignalingServer.Connection connection, String reason) {
                        disconnected.add(connection.id()); cleanupCount.incrementAndGet();
                    }
                }, rooms.serverEpoch(), SignalingServer.Options.defaults());
        server.start();
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<SignalMessage>>();
        // Authenticate all connections before occupying the business workers.
        for (int i = 0; i < 9; i++) login("queued" + i, new CopyOnWriteArrayList<>());
        for (SignalingClient client : clients) futures.add(client.request("BLOCK", null, new JsonObject()));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            server.close();
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (disconnected.size() != 9 && System.nanoTime() < cleanupDeadline) Thread.sleep(20);
            assertEquals(9, disconnected.size(), () -> "Every admitted connection must receive cleanup; " + server.lifecycleState());
            assertEquals(9, cleanupCount.get());
            await(() -> futures.stream().allMatch(java.util.concurrent.CompletableFuture::isDone));
        } finally { release.countDown(); }
    }

    private SignalingClient login(String user, List<SignalMessage> events) throws Exception {
        return connect(fixture.account(user, user).token(), events);
    }
    private SignalingClient connect(String token, List<SignalMessage> events) throws Exception {
        SignalingClient client = new SignalingClient(new SignalingClient.Settings("localhost", server.port(), null,
                SignalWire.DEFAULT_MAX_BYTES), tls.client(), events::add);
        clients.add(client); client.connect(token); return client;
    }
    private RoomSnapshot create(SignalingClient client, int capacity, String requestId) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("name", "Test room"); body.addProperty("pin", "1234");
        body.addProperty("capacity", capacity); body.addProperty("locked", false);
        return JSON.fromJson(client.request("CREATE_ROOM", null, body, requestId).get(6, TimeUnit.SECONDS).body().get("room"), RoomSnapshot.class);
    }
    private SignalMessage join(SignalingClient client, RoomSnapshot room, String pin, String requestId) throws Exception {
        JsonObject body = new JsonObject(); body.addProperty("joinCode", room.joinCode()); body.addProperty("pin", pin);
        return client.request("JOIN_ROOM", room.roomId(), body, requestId).get(6, TimeUnit.SECONDS);
    }
    private RoomSnapshot get(SignalingClient client, String roomId) throws Exception {
        return JSON.fromJson(request(client, "GET_ROOM", roomId, new JsonObject()).body().get("room"), RoomSnapshot.class);
    }
    private SignalMessage request(SignalingClient client, String type, String room, JsonObject body) throws Exception {
        return client.request(type, room, body).get(6, TimeUnit.SECONDS);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for signaling lifecycle");
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void code(String expected, Operation operation) {
        Exception error = assertThrows(Exception.class, operation::run);
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        assertInstanceOf(SignalException.class, cause); assertEquals(expected, ((SignalException) cause).code());
    }
}
