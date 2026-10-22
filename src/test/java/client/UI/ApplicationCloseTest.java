package client.UI;

import client.filetransfer.FileTransferClient;
import client.meeting.MeetingSession;
import client.signalingapi.SignalingClient;
import com.google.gson.Gson;
import common.auth.AuthResponse;
import common.signaling.SignalMessage;
import common.signaling.SignalWire;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.auth.SessionValidator;
import server.meeting.RoomRepository;
import server.meeting.RoomService;
import server.signalingapi.MeetingHandler;
import server.signalingapi.SignalingServer;
import support.FxTestRuntime;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** The close button must finish a real TLS room leave before revoking the login or exiting JavaFX. */
@Timeout(30)
class ApplicationCloseTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private RoomTestFixture fixture;
    private RoomService rooms;
    private SignalingServer signaling;
    private MeetingHandler handler;
    private SceneManager manager;
    private Stage stage;
    private MeetingSession guest;
    private AuthResponse account;
    private final AtomicBoolean exited = new AtomicBoolean();
    private final AtomicBoolean blockLeave = new AtomicBoolean();
    private final CountDownLatch leaveReceived = new CountDownLatch(1), leaveRelease = new CountDownLatch(1);
    private final List<String> actions = new CopyOnWriteArrayList<>();
    private final List<SignalMessage> requests = new CopyOnWriteArrayList<>();

    @BeforeAll @Timeout(60) static void runtime() throws Exception {
        tls = RoomTestFixture.certificates(temporary); FxTestRuntime.start();
    }

    @BeforeEach void start() throws Exception {
        fixture = new RoomTestFixture(tls, request -> actions.add(request.action()));
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\r\n]*", "")
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) fixture.execute(sql);
        var target = new AtomicReference<SignalingServer>(); var json = new Gson();
        rooms = new RoomService(new RoomRepository(fixture::connection), Clock.systemUTC(), "close-test", new byte[32],
                event -> target.get().event(event.recipientConnectionId(), event.type(), event.roomId(), event.revision(),
                        json.toJsonTree(event.body()).getAsJsonObject()));
        rooms.start(); handler = new MeetingHandler(rooms);
        signaling = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(fixture::connection, Clock.systemUTC()), new SignalingServer.Handler() {
                    @Override public SignalMessage handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
                        requests.add(request);
                        if ("LEAVE_ROOM".equals(request.type()) && blockLeave.get()) {
                            leaveReceived.countDown(); assertTrue(leaveRelease.await(10, TimeUnit.SECONDS));
                        }
                        return handler.handle(connection, request);
                    }
                    @Override public void disconnected(SignalingServer.Connection connection, String reason) {
                        handler.disconnected(connection, reason);
                    }
                }, rooms.serverEpoch(), SignalingServer.Options.defaults());
        target.set(signaling); signaling.start();
        account = fixture.account("CloseHost", "Host");
        FxTestRuntime.call(() -> {
            stage = new Stage(); stage.setScene(new Scene(new StackPane(), 300, 200));
            manager = new SceneManager(stage, fixture.client(), new FileTransferClient(), this::session, () -> exited.set(true));
            manager.acceptSession(account); return null;
        });
    }

    private MeetingSession session(String token, Instant expiry) {
        return new MeetingSession(token, expiry, listener -> new SignalingClient(
                new SignalingClient.Settings("localhost", signaling.port(), null, SignalWire.DEFAULT_MAX_BYTES), tls.client(), listener));
    }

    private common.room.RoomInfo create() throws Exception {
        var result = new CompletableFuture<common.room.RoomInfo>();
        FxTestRuntime.call(() -> { manager.requestCreateRoom("Đóng cửa sổ", "1234", 3, false, result::complete,
                result::completeExceptionally); return null; });
        return result.get(6, TimeUnit.SECONDS);
    }

    private void guestJoins(String code) throws Exception {
        var account = fixture.account("CloseGuest", "Guest");
        guest = session(account.token(), Instant.parse(account.expiresAt()));
        guest.lookup(code, "1234").get(5, TimeUnit.SECONDS); guest.join(false, false).get(5, TimeUnit.SECONDS);
    }

    @AfterEach void stop() throws Exception {
        leaveRelease.countDown();
        if (manager != null) FxTestRuntime.call(() -> { manager.shutdown(); stage.hide(); return null; });
        if (guest != null) guest.close();
        if (signaling != null) signaling.close();
        if (handler != null) handler.close();
        if (rooms != null) rooms.close();
        if (fixture != null) fixture.close();
    }

    @Test void closeWaitsForLeaveAcknowledgementThenTransfersHostAndLogsOutOnce() throws Exception {
        var room = create(); guestJoins(room.code()); blockLeave.set(true); actions.clear();
        FxTestRuntime.call(() -> { manager.closeApplication(); manager.closeApplication(); return null; });
        assertTrue(leaveReceived.await(5, TimeUnit.SECONDS));
        FxTestRuntime.call(() -> { assertTrue(stage.getScene().getRoot().isDisabled()); assertFalse(exited.get()); return null; });
        assertEquals(2, rooms.participantCount()); assertFalse(actions.contains("LOGOUT"));
        leaveRelease.countDown(); FxTestRuntime.await(exited::get);
        FxTestRuntime.await(() -> guest.snapshot() != null && guest.isHost() && guest.snapshot().participants().size() == 1);
        assertEquals(1, rooms.participantCount()); assertEquals(1, rooms.roomCount());
        assertEquals(1, requests.stream().filter(request -> "LEAVE_ROOM".equals(request.type())).count());
        assertEquals(1, actions.stream().filter("LOGOUT"::equals).count());
        assertFalse(actions.contains("LEAVE_ROOM"), "Signaling membership must never be removed through the user-only legacy API");
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung=" + account.user().id()
                + " AND Roi_Phong_Luc IS NOT NULL AND Ly_Do_Roi='TU_ROI'"));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Ma_Nguoi_Dung=" + account.user().id() + " AND Thu_Hoi_Luc IS NOT NULL"));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop WHERE Ma_Phong=" + room.id() + " AND Trang_Thai='DANG_HOAT_DONG'"));
    }

    @Test void closingLastHostEndsRoomAsIntentionalDeparture() throws Exception {
        var room = create();
        FxTestRuntime.call(() -> { manager.closeApplication(); return null; }); FxTestRuntime.await(exited::get);
        assertEquals(0, rooms.participantCount()); assertEquals(0, rooms.roomCount());
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NOT NULL AND Ly_Do_Roi='PHONG_DONG'"));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Phong_Hop WHERE Ma_Phong=" + room.id() + " AND Trang_Thai='DA_DONG'"));
    }
}
