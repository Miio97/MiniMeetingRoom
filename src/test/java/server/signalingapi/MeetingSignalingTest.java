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
import java.util.concurrent.CompletableFuture;
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
    private final java.util.concurrent.atomic.AtomicInteger sessionDbQueries = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean databaseUnavailable=new java.util.concurrent.atomic.AtomicBoolean();
    private MeetingHandler meetingHandler;
    private final java.util.Map<String,String> catalogWatchIds=new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeAll static void certificates() throws Exception { tls = RoomTestFixture.certificates(temporary); }

    @BeforeEach void start() throws Exception {
        fixture = new RoomTestFixture(tls);
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "")
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String statement : migration.split(";")) if (!statement.isBlank()) fixture.execute(statement);
        fixture.execute("""
                CREATE TABLE IF NOT EXISTS Tin_Nhan (
                    Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop(Ma_Phong) ON DELETE CASCADE,
                    Ma_Nguoi_Gui BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Loai_Tin_Nhan VARCHAR(20) NOT NULL DEFAULT 'VAN_BAN',
                    Noi_Dung TEXT,
                    Ngay_Gui TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Ngay_Chinh_Sua TIMESTAMP NULL,
                    Ngay_Xoa TIMESTAMP NULL
                )
                """);
        server.auth.ConnectionProvider countingProvider = () -> {
            sessionDbQueries.incrementAndGet();
            if(databaseUnavailable.get()) throw new java.sql.SQLException("TEST_DATABASE_UNAVAILABLE");
            return fixture.connection();
        };
        AtomicReference<SignalingServer> current = new AtomicReference<>();
        rooms = new RoomService(new RoomRepository(countingProvider), Clock.systemUTC(), "test-instance",
                new byte[32], 20, event -> {
                    SignalingServer target = current.get();
                    if (target != null) target.event(event.recipientConnectionId(), event.type(), event.roomId(),
                            event.revision(), JSON.toJsonTree(event.body()).getAsJsonObject());
                });
        rooms.start();
        meetingHandler=new MeetingHandler(rooms,null,countingProvider,null);
        server = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(countingProvider, Clock.systemUTC()), new SignalingServer.Handler() {
                    private final MeetingHandler delegate = meetingHandler;
                    @Override public java.util.concurrent.CompletionStage<SignalMessage> handleAsync(SignalingServer.Connection connection,SignalMessage request) throws Exception {
                        return delegate.handleAsync(connection,request);
                    }
                    public SignalMessage handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
                        if(request.type().equals("ROOM_CATALOG_WATCH") && request.body().has("watchId")) catalogWatchIds.put(connection.id(),request.body().get("watchId").getAsString());
                        if (request.type().equals("GET_ROOM")) snapshotRequests.incrementAndGet();
                        return delegate.handle(connection, request);
                    }
                    public void disconnected(SignalingServer.Connection connection, String reason) { delegate.disconnected(connection, reason); }
                    @Override public void bindServer(SignalingServer s) { delegate.bindServer(s); }
                },
                rooms.serverEpoch(), SignalingServer.Options.defaults());
        current.set(server); server.start();
    }

    @AfterEach void close() throws Exception {
        clients.forEach(SignalingClient::close);
        if (server != null) server.close();
        if(meetingHandler!=null) meetingHandler.close();
        if (rooms != null) rooms.close();
        if (fixture != null) fixture.close();
    }

    @Test void screenUnsubscribeIsRoutedThroughTlsAndMeetingHandler() throws Exception {
        var host=login("screenCleanup",new CopyOnWriteArrayList<>());
        var room=create(host,3,UUID.randomUUID().toString());join(host,room,"",UUID.randomUUID().toString());
        var body=new JsonObject();body.add("transports",new com.google.gson.JsonArray());
        // This fixture intentionally has no media engine. MEDIA_NOT_READY proves the
        // authenticated action reached the media branch rather than UNKNOWN_OPERATION.
        code("MEDIA_NOT_READY",() -> request(host,"SCREEN_UNSUBSCRIBE",room.roomId(),body));
        for(String action:List.of("SCREEN_AUDIO_PUBLISH","SCREEN_AUDIO_SUBSCRIBE","SCREEN_AUDIO_STATE","SCREEN_AUDIO_STOP","SCREEN_AUDIO_UNSUBSCRIBE"))
            code("MEDIA_NOT_READY",() -> request(host,action,room.roomId(),body));
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
        request(host, "LEAVE_ROOM", room.roomId(), new JsonObject());
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

    @Test void newerLoginTakesOverHostRoomAndOldDeviceCannotControlOrChat() throws Exception {
        var account = fixture.account("deviceHost", "Host on two devices");
        var oldEvents = new CopyOnWriteArrayList<SignalMessage>();
        var oldDevice = connect(account.token(), oldEvents);
        var room = create(oldDevice, 3, UUID.randomUUID().toString());
        var initial = JSON.fromJson(join(oldDevice, room, "1234", UUID.randomUUID().toString()).body().get("join"),
                common.meeting.RoomJoinReply.class);
        var secondLogin = fixture.client().login("deviceHost", RoomTestFixture.PASSWORD);
        assertTrue(secondLogin.success());
        var newDevice = connect(secondLogin.token(), new CopyOnWriteArrayList<>());
        var replaced = JSON.fromJson(join(newDevice, room, "1234", UUID.randomUUID().toString()).body().get("join"),
                common.meeting.RoomJoinReply.class);
        assertEquals(initial.selfParticipantId(), replaced.selfParticipantId());
        assertEquals(1, replaced.room().participants().size());
        assertEquals(common.meeting.RoomRole.HOST, replaced.room().participants().getFirst().role());
        await(() -> oldEvents.stream().anyMatch(event -> event.type().equals("ROOM_LEFT")
                && event.body().has("reason") && event.body().get("reason").getAsString().equals("DEVICE_REPLACED")));
        code("NOT_A_MEMBER", () -> get(oldDevice, room.roomId()));
        var chat = new JsonObject(); chat.addProperty("content", "stale old device");
        code("NOT_A_MEMBER", () -> request(oldDevice, "CHAT_SEND", room.roomId(), chat));
        code("DEVICE_REPLACED", () -> join(oldDevice, room, "1234", UUID.randomUUID().toString()));
        assertEquals("HEARTBEAT_ACK", request(oldDevice, "HEARTBEAT", null, new JsonObject()).type());
        oldDevice.close();
        assertEquals(1, get(newDevice, room.roomId()).participants().size());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        assertEquals(2, fixture.count("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NULL"));
    }

    @Test void plainLeaveTransfersHostBeforeTransportCloses() throws Exception {
        var host = login("closeHost", new CopyOnWriteArrayList<>());
        var guest = login("closeGuest", new CopyOnWriteArrayList<>());
        var room = create(host, 3, UUID.randomUUID().toString());
        join(host, room, "1234", UUID.randomUUID().toString());
        join(guest, room, "1234", UUID.randomUUID().toString());
        assertEquals("ROOM_LEFT", request(host, "LEAVE_ROOM", room.roomId(), new JsonObject()).type());
        var remaining = get(guest, room.roomId());
        assertEquals(1, remaining.participants().size());
        assertEquals(common.meeting.RoomRole.HOST, remaining.participants().getFirst().role());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Ly_Do_Roi='TU_ROI'"));
        assertEquals(0, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Ly_Do_Roi='MAT_KET_NOI'"));
        host.close();
        assertEquals(remaining.participants(), get(guest, room.roomId()).participants());
    }

    @Test void onlyHostReopensClosedRoomAndOldPreviewCannotJoinNewMeetingRun() throws Exception {
        SignalingClient host = login("host", new CopyOnWriteArrayList<>());
        SignalingClient guest = login("guest", new CopyOnWriteArrayList<>());
        RoomSnapshot original = create(host, 3, UUID.randomUUID().toString());
        join(host, original, "", UUID.randomUUID().toString());
        join(guest, original, "1234", UUID.randomUUID().toString());
        var chatBody=new JsonObject();chatBody.addProperty("content","only in the original run");
        request(host,"CHAT_SEND",original.roomId(),chatBody);
        assertEquals(1,meetingHandler.chatRoomCount());assertEquals(1,meetingHandler.chatReceiptCount());
        JsonObject body = new JsonObject(); body.addProperty("joinCode", original.joinCode());
        rooms.leave(host.connectionId(), "PHONG_DONG");
        assertEquals(0,meetingHandler.chatRoomCount());assertEquals(0,meetingHandler.chatReceiptCount());
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
        assertEquals(0,request(host,"CHAT_HISTORY",reopened.roomId(),new JsonObject()).body().getAsJsonArray("messages").size());
        code("CROSS_ROOM_FORBIDDEN",() -> request(host,"CHAT_HISTORY",original.roomId(),new JsonObject()));
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

    @Test void twoClientsChatRealtimeHistoryAndNoExtraGetRoom() throws Exception {
        List<SignalMessage> hostEvents = new CopyOnWriteArrayList<>();
        List<SignalMessage> guestEvents = new CopyOnWriteArrayList<>();
        SignalingClient host = login("hostChat", hostEvents);
        SignalingClient guest = login("guestChat", guestEvents);
        RoomSnapshot room = create(host, 5, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        join(guest, room, "1234", UUID.randomUUID().toString());

        await(() -> hostEvents.stream().anyMatch(e -> "PARTICIPANT_JOINED".equals(e.type())));
        snapshotRequests.set(0);

        JsonObject send1 = new JsonObject();
        send1.addProperty("content", "Hello from host");
        SignalMessage res1 = request(host, "CHAT_SEND", room.roomId(), send1);
        assertEquals("CHAT_SENT", res1.type());
        assertTrue(res1.body().get("ok").getAsBoolean());
        long id1 = res1.body().get("id").getAsLong();

        await(() -> hostEvents.stream().anyMatch(e -> "CHAT_MESSAGE".equals(e.type())));
        await(() -> guestEvents.stream().anyMatch(e -> "CHAT_MESSAGE".equals(e.type())));

        SignalMessage hostMsg1 = hostEvents.stream().filter(e -> "CHAT_MESSAGE".equals(e.type())).findFirst().orElseThrow();
        SignalMessage guestMsg1 = guestEvents.stream().filter(e -> "CHAT_MESSAGE".equals(e.type())).findFirst().orElseThrow();
        assertEquals("Hello from host", hostMsg1.body().get("content").getAsString());
        assertEquals(hostMsg1.body().get("content").getAsString(), guestMsg1.body().get("content").getAsString());
        assertEquals(id1, hostMsg1.body().get("id").getAsLong());
        assertEquals(id1, guestMsg1.body().get("id").getAsLong());

        JsonObject send2 = new JsonObject();
        send2.addProperty("content", "Hi back from guest");
        SignalMessage res2 = request(guest, "CHAT_SEND", room.roomId(), send2);
        assertEquals("CHAT_SENT", res2.type());
        long id2 = res2.body().get("id").getAsLong();

        await(() -> hostEvents.stream().filter(e -> "CHAT_MESSAGE".equals(e.type())).count() >= 2);
        await(() -> guestEvents.stream().filter(e -> "CHAT_MESSAGE".equals(e.type())).count() >= 2);

        // History query
        JsonObject histReq = new JsonObject();
        histReq.addProperty("limit", 10);
        SignalMessage histRes = request(guest, "CHAT_HISTORY", room.roomId(), histReq);
        assertEquals("CHAT_HISTORY_RESULT", histRes.type());
        var msgs = histRes.body().getAsJsonArray("messages");
        assertEquals(2, msgs.size());
        assertEquals(id1, msgs.get(0).getAsJsonObject().get("id").getAsLong());
        assertEquals("Hello from host", msgs.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals(id2, msgs.get(1).getAsJsonObject().get("id").getAsLong());
        assertEquals("Hi back from guest", msgs.get(1).getAsJsonObject().get("content").getAsString());

        // Verify that chat events produced NO extra GET_ROOM requests
        assertEquals(0, snapshotRequests.get(), "Chat messaging must not trigger extra GET_ROOM requests");
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
    }

    @Test void chatRejectedWhenNotMemberRateLimitOrContentTooLong() throws Exception {
        SignalingClient host = login("hostReject", new CopyOnWriteArrayList<>());
        SignalingClient outsider = login("outsider", new CopyOnWriteArrayList<>());
        RoomSnapshot room = create(host, 5, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());

        // Outsider cannot send chat
        JsonObject msg = new JsonObject();
        msg.addProperty("content", "I am outsider");
        code("NOT_A_MEMBER", () -> request(outsider, "CHAT_SEND", room.roomId(), msg));
        code("NOT_A_MEMBER", () -> request(outsider, "CHAT_HISTORY", room.roomId(), new JsonObject()));

        // Content too long (> 2000 chars)
        JsonObject longMsg = new JsonObject();
        longMsg.addProperty("content", "A".repeat(2001));
        code("INVALID_REQUEST", () -> request(host, "CHAT_SEND", room.roomId(), longMsg));

        // Empty content
        JsonObject emptyMsg = new JsonObject();
        emptyMsg.addProperty("content", "   \t\n   ");
        code("INVALID_REQUEST", () -> request(host, "CHAT_SEND", room.roomId(), emptyMsg));

        // Rapid rate limit test (burst is 10)
        JsonObject rapid = new JsonObject();
        rapid.addProperty("content", "spam");
        boolean rateLimited = false;
        for (int i = 0; i < 20; i++) {
            try {
                request(host, "CHAT_SEND", room.roomId(), rapid);
            } catch (Exception ex) {
                Throwable cause = ex instanceof ExecutionException ? ex.getCause() : ex;
                if (cause instanceof SignalException se && "RATE_LIMIT".equals(se.code())) {
                    rateLimited = true;
                    break;
                }
            }
        }
        assertTrue(rateLimited, "Burst of >10 messages in zero time must trigger RATE_LIMIT");
    }

    @Test void userWhoLeftRoomDoesNotReceiveChat() throws Exception {
        List<SignalMessage> hostEvents = new CopyOnWriteArrayList<>();
        List<SignalMessage> guestEvents = new CopyOnWriteArrayList<>();
        SignalingClient host = login("hostLeft", hostEvents);
        SignalingClient guest = login("guestLeft", guestEvents);
        RoomSnapshot room = create(host, 5, UUID.randomUUID().toString());
        join(host, room, "", UUID.randomUUID().toString());
        join(guest, room, "1234", UUID.randomUUID().toString());

        await(() -> hostEvents.stream().anyMatch(e -> "PARTICIPANT_JOINED".equals(e.type())));

        // Guest leaves room
        request(guest, "LEAVE_ROOM", room.roomId(), new JsonObject());

        // Host sends a message
        JsonObject msg = new JsonObject();
        msg.addProperty("content", "Only host should see this");
        request(host, "CHAT_SEND", room.roomId(), msg);

        await(() -> hostEvents.stream().anyMatch(e -> "CHAT_MESSAGE".equals(e.type())));
        Thread.sleep(100);

        // Guest did not receive CHAT_MESSAGE
        assertTrue(guestEvents.stream().noneMatch(e -> "CHAT_MESSAGE".equals(e.type())));
    }

    @Test void retiredTelemetryIsAckedWithoutAStatsTableOrExtraSessionQueries() throws Exception {
        SignalingClient host=login("hostRetiredStats",new CopyOnWriteArrayList<>());
        var room=create(host,3,UUID.randomUUID().toString());join(host,room,"",UUID.randomUUID().toString());
        sessionDbQueries.set(0);var sample=new JsonObject();sample.addProperty("rtt",45);
        for(int i=0;i<5;i++) {
            var reply=request(host,"NETWORK_STATS","spoofed_room",sample);
            assertEquals("NETWORK_STATS_ACK",reply.type());assertTrue(reply.body().get("retired").getAsBoolean());assertTrue(reply.body().get("dropped").getAsBoolean());
        }
        assertEquals(0,sessionDbQueries.get(),"Retired telemetry must not hit the database");
        assertEquals(1,fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        request(host,"GET_ROOM",room.roomId(),new JsonObject());assertTrue(sessionDbQueries.get()>0);
    }

    @Test void twentyParticipantsSignalingBenchmarkAndStateSync() throws Exception {
        // 1. Verify host connects and receives maxParticipants = 20 from SERVER_WELCOME and AUTHENTICATED
        List<SignalMessage> hostEvents = new CopyOnWriteArrayList<>();
        SignalingClient host = login("benchHost", hostEvents);
        assertEquals(20, host.serverMaxParticipants(), "Host client must read maxParticipants = 20 from server");

        // Host creates room with capacity 20
        RoomSnapshot room = create(host, 20, UUID.randomUUID().toString());
        assertEquals(20, room.capacity());

        // Host joins
        join(host, room, "", UUID.randomUUID().toString());

        // 2. Concurrently connect and join 19 more participants
        List<SignalingClient> guests = new CopyOnWriteArrayList<>();
        List<List<SignalMessage>> guestEvents = new CopyOnWriteArrayList<>();
        int guestCount = 19;
        for (int i = 0; i < guestCount; i++) {
            guestEvents.add(new CopyOnWriteArrayList<>());
        }

        long startNanos = System.nanoTime();

        // Connect all 19 guests
        List<CompletableFuture<SignalingClient>> loginFutures = new ArrayList<>();
        for (int i = 0; i < guestCount; i++) {
            final int index = i;
            loginFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return login("guestBench" + index, guestEvents.get(index));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (var future : loginFutures) {
            guests.add(future.get(10, TimeUnit.SECONDS));
        }

        // All 19 guests join concurrently
        List<CompletableFuture<SignalMessage>> joinFutures = new ArrayList<>();
        for (int i = 0; i < guestCount; i++) {
            final SignalingClient guest = guests.get(i);
            joinFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return join(guest, room, "1234", UUID.randomUUID().toString());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        for (var future : joinFutures) {
            future.get(10, TimeUnit.SECONDS);
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        // Verify total join benchmark duration is well under 5 seconds (5000 ms)
        assertTrue(durationMs < 5000, "Signaling join for 20 clients must complete in under 5s, took: " + durationMs + "ms");

        // Verify room state is fully synchronized across all 20 participants
        assertEquals(20, rooms.participantCount());
        RoomSnapshot hostView = get(host, room.roomId());
        assertEquals(20, hostView.participants().size());

        // Verify each guest sees 20 participants and received PARTICIPANT_JOINED
        for (SignalingClient guest : guests) {
            RoomSnapshot guestView = get(guest, room.roomId());
            assertEquals(20, guestView.participants().size());
        }

        // Host received participant joined events for guests
        long joinedEventsReceived = hostEvents.stream().filter(e -> "PARTICIPANT_JOINED".equals(e.type())).count();
        assertEquals(19, joinedEventsReceived, "Host must receive PARTICIPANT_JOINED for all 19 guests");
    }

    @Test void catalogPushIsPermissionFilteredRevisionedAndUnsubscribed() throws Exception {
        var events=new CopyOnWriteArrayList<SignalMessage>();var outsiderEvents=new CopyOnWriteArrayList<SignalMessage>();
        var host=login("catalogHost",events);var outsider=login("catalogOther",outsiderEvents);
        var watch=new JsonObject();watch.addProperty("watchId","catalog-test");watch.addProperty("limit",20);
        assertEquals(0,request(host,"ROOM_CATALOG_WATCH",null,watch).body().getAsJsonArray("rooms").size());
        request(outsider,"ROOM_CATALOG_WATCH",null,watch);
        var room=create(host,3,UUID.randomUUID().toString());
        await(() -> events.stream().anyMatch(event -> event.type().equals("ROOM_CATALOG_CHANGED")));
        var delta=events.stream().filter(event -> event.type().equals("ROOM_CATALOG_CHANGED")).findFirst().orElseThrow();
        assertEquals(2,delta.body().get("revision").getAsLong());assertEquals(1,delta.body().getAsJsonArray("rooms").size());
        assertTrue(outsiderEvents.stream().noneMatch(event -> event.type().equals("ROOM_CATALOG_CHANGED")));
        request(host,"ROOM_CATALOG_UNWATCH",null,new JsonObject());events.clear();
        join(host,room,"1234",UUID.randomUUID().toString());Thread.sleep(120);
        assertTrue(events.stream().noneMatch(event -> event.type().equals("ROOM_CATALOG_CHANGED")));
    }
    @Test void chatRetryUsesSameReceiptAndDifferentContentCannotReuseNonce() throws Exception {
        var events=new CopyOnWriteArrayList<SignalMessage>();var account=fixture.account("receiptHost","Host");var host=connect(account.token(),events);var room=create(host,3,UUID.randomUUID().toString());
        var joined=join(host,room,"1234",UUID.randomUUID().toString());String participant=joined.body().getAsJsonObject("join").get("selfParticipantId").getAsString();
        var body=new JsonObject();body.addProperty("content","one saved message");body.addProperty("clientMessageId","stable_nonce");
        var first=request(host,"CHAT_SEND",room.roomId(),body);var second=request(host,"CHAT_SEND",room.roomId(),body);
        assertEquals(first.body().get("id"),second.body().get("id"));
        assertEquals(1,request(host,"CHAT_HISTORY",room.roomId(),new JsonObject()).body().getAsJsonArray("messages").size());
        await(() -> events.stream().anyMatch(event -> event.type().equals("CHAT_MESSAGE")));
        assertEquals(1,events.stream().filter(event -> event.type().equals("CHAT_MESSAGE")).count());
        body.addProperty("content","changed");code("REQUEST_ID_REUSED",() -> request(host,"CHAT_SEND",room.roomId(),body));
        host.close();var resumed=connect(account.token(),new CopyOnWriteArrayList<>());
        var resume=new JsonObject();resume.addProperty("participantId",participant);request(resumed,"RESUME_ROOM",room.roomId(),resume);
        var status=new JsonObject();var ids=new com.google.gson.JsonArray();ids.add("stable_nonce");ids.add("unknown_nonce");status.add("clientMessageIds",ids);
        var confirmed=request(resumed,"CHAT_STATUS",room.roomId(),status).body().getAsJsonArray("messages");assertEquals(1,confirmed.size());
        assertEquals(first.body().get("id"),confirmed.get(0).getAsJsonObject().get("id"));
        assertEquals(1,request(resumed,"CHAT_HISTORY",room.roomId(),new JsonObject()).body().getAsJsonArray("messages").size());
    }
    @Test void catalogCanWatchAnOlderVisibleRoomWithoutLeakingAnotherUsersRoom() throws Exception {
        var account=fixture.account("olderCatalogHost","Host");var events=new CopyOnWriteArrayList<SignalMessage>();var host=connect(account.token(),events);
        var room=create(host,3,UUID.randomUUID().toString());join(host,room,"1234",UUID.randomUUID().toString());
        for(int index=0;index<205;index++) fixture.execute("INSERT INTO Phong_Hop(Ma_Phong_Tham_Gia,Ten_Phong,Ma_Chu_Phong,Trang_Thai,Da_Khoa,So_Nguoi_Toi_Da,Ngay_Tao) VALUES('OLDER-"+index+"','History',"+account.user().id()+",'DA_DONG',FALSE,3,TIMESTAMP '"+java.time.LocalDateTime.ofInstant(java.time.Instant.parse(room.collaborationRoom().createdAt()),java.time.ZoneOffset.UTC).plusDays(1)+"')");
        var outsider=fixture.account("privateCatalogHost","Private");fixture.execute("INSERT INTO Phong_Hop(Ma_Phong,Ma_Phong_Tham_Gia,Ten_Phong,Ma_Chu_Phong,Trang_Thai,Da_Khoa,So_Nguoi_Toi_Da) VALUES(9999,'PRIVATE-ROOM','Private',"+outsider.user().id()+",'DA_DONG',FALSE,3)");
        var body=new JsonObject();body.addProperty("limit",20);body.addProperty("watchId","older-window");
        var ordinary=request(host,"ROOM_CATALOG_WATCH",null,body).body();assertTrue(ordinary.getAsJsonArray("rooms").asList().stream().noneMatch(value -> value.getAsJsonObject().get("id").getAsLong()==room.collaborationRoom().id()));
        var focus=new com.google.gson.JsonArray();focus.add(room.collaborationRoom().id());focus.add(9999);body.add("roomIds",focus);
        var focused=request(host,"ROOM_CATALOG_WATCH",null,body).body();
        assertTrue(focused.getAsJsonArray("rooms").asList().stream().anyMatch(value -> value.getAsJsonObject().get("id").getAsLong()==room.collaborationRoom().id()));
        assertTrue(focused.getAsJsonArray("rooms").asList().stream().noneMatch(value -> value.getAsJsonObject().get("id").getAsLong()==9999));
        events.clear();var lock=new JsonObject();lock.addProperty("locked",true);request(host,"SET_LOCKED",room.roomId(),lock);
        await(() -> events.stream().filter(event -> event.type().equals("ROOM_CATALOG_CHANGED")).flatMap(event -> event.body().getAsJsonArray("rooms").asList().stream()).anyMatch(value -> value.getAsJsonObject().get("id").getAsLong()==room.collaborationRoom().id() && value.getAsJsonObject().get("locked").getAsBoolean()));
    }
    @Test void catalogRevisionGapTriggersSnapshotResyncWithoutPolling() throws Exception {
        support.FxTestRuntime.start();var account=fixture.account("catalogGap","Gap");var updates=new CopyOnWriteArrayList<client.meeting.MeetingSession.CatalogUpdate>();var transport=new AtomicReference<SignalingClient>();
        try(var session=new client.meeting.MeetingSession(account.token(),java.time.Instant.parse(account.expiresAt()),listener -> {
            var client=new SignalingClient(new SignalingClient.Settings("localhost",server.port(),null,SignalWire.DEFAULT_MAX_BYTES),tls.client(),listener);transport.set(client);return client;
        });var watch=session.watchCatalog(updates::add)) {
            await(() -> updates.stream().filter(client.meeting.MeetingSession.CatalogUpdate::snapshot).count()==1);
            var gap=new JsonObject();gap.addProperty("watchId",catalogWatchIds.get(transport.get().connectionId()));gap.addProperty("snapshot",false);gap.addProperty("revision",3);
            server.event(transport.get().connectionId(),"ROOM_CATALOG_CHANGED",null,null,gap);
            await(() -> updates.stream().filter(client.meeting.MeetingSession.CatalogUpdate::snapshot).count()==2);
        }
    }
    @Test void temporaryDatabaseOutageRetainsExistingRoomButRejectsNewWrites() throws Exception {
        var events=new CopyOnWriteArrayList<SignalMessage>();var host=login("databaseGrace",events);var room=create(host,3,UUID.randomUUID().toString());join(host,room,"1234",UUID.randomUUID().toString());
        databaseUnavailable.set(true);
        assertEquals(room.roomId(),get(host,room.roomId()).roomId());
        var body=new JsonObject();body.addProperty("content","RAM chat continues during database grace");body.addProperty("clientMessageId","offline_nonce");
        assertEquals("CHAT_SENT",request(host,"CHAT_SEND",room.roomId(),body).type());
        assertEquals(1,request(host,"CHAT_HISTORY",room.roomId(),new JsonObject()).body().getAsJsonArray("messages").size());
        var status=new JsonObject();var ids=new com.google.gson.JsonArray();ids.add("offline_nonce");status.add("clientMessageIds",ids);
        assertEquals(1,request(host,"CHAT_STATUS",room.roomId(),status).body().getAsJsonArray("messages").size());
        code("DATABASE_UNAVAILABLE",() -> create(host,3,UUID.randomUUID().toString()));
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
        assertEquals(1,rooms.participantCount());
        assertTrue(events.stream().noneMatch(event -> event.type().equals("CONNECTION_CLOSED")));
        databaseUnavailable.set(false);assertEquals(1,get(host,room.roomId()).participants().size());
    }
    @Test void lastLeaveAndAuthorityShutdownRemoveChatWithoutWaitingForDatabaseCleanup() throws Exception {
        var host=login("ramLifecycle",new CopyOnWriteArrayList<>());var room=create(host,3,UUID.randomUUID().toString());
        join(host,room,"1234",UUID.randomUUID().toString());
        var body=new JsonObject();body.addProperty("content","ephemeral");request(host,"CHAT_SEND",room.roomId(),body);
        assertEquals(1,meetingHandler.chatRoomCount());
        request(host,"LEAVE_ROOM",room.roomId(),new JsonObject());
        assertEquals(0,meetingHandler.chatRoomCount());assertEquals(0,meetingHandler.chatReceiptCount());
        var other=create(host,3,UUID.randomUUID().toString());join(host,other,"1234",UUID.randomUUID().toString());
        request(host,"CHAT_SEND",other.roomId(),body);rooms.close();
        assertEquals(0,meetingHandler.chatRoomCount());assertEquals(0,meetingHandler.chatReceiptCount());
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
    }
    @Test void chatCannotCrossRooms() throws Exception {
        var host=login("ramIsolated",new CopyOnWriteArrayList<>());var other=login("ramPrivate",new CopyOnWriteArrayList<>());
        var first=create(host,3,UUID.randomUUID().toString());join(host,first,"1234",UUID.randomUUID().toString());
        var second=create(other,3,UUID.randomUUID().toString());join(other,second,"1234",UUID.randomUUID().toString());
        var body=new JsonObject();body.addProperty("content","private");request(host,"CHAT_SEND",first.roomId(),body);
        code("CROSS_ROOM_FORBIDDEN",() -> request(other,"CHAT_HISTORY",first.roomId(),new JsonObject()));
        assertEquals(0,request(other,"CHAT_HISTORY",second.roomId(),new JsonObject()).body().getAsJsonArray("messages").size());
        assertEquals(0,fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
    }
    @Test void chatStillWorksWhileDatabaseHostTransferIsPending() throws Exception {
        var host=login("ramGraceHost",new CopyOnWriteArrayList<>());var guest=login("ramGraceGuest",new CopyOnWriteArrayList<>());
        var room=create(host,3,UUID.randomUUID().toString());join(host,room,"1234",UUID.randomUUID().toString());join(guest,room,"1234",UUID.randomUUID().toString());
        databaseUnavailable.set(true);rooms.leave(host.connectionId(),"TU_ROI");
        var body=new JsonObject();body.addProperty("content","Host transfer persistence does not block RAM chat");
        assertEquals("CHAT_SENT",request(guest,"CHAT_SEND",room.roomId(),body).type());
        assertEquals(1,request(guest,"CHAT_HISTORY",room.roomId(),new JsonObject()).body().getAsJsonArray("messages").size());
        databaseUnavailable.set(false);rooms.maintenanceTick();
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
