package client.UI;

import client.UI.screens.AuthScreen;
import client.UI.screens.LegacyLobbyScreen;
import client.UI.screens.LegacyMeetingScreen;
import client.UI.screens.MeetingEndedScreen;
import client.auth.AuthClient;
import client.auth.RoomException;
import client.network.NetworkMonitor;
import client.UI.dialogs.NetworkStatsDialog;
import common.auth.AuthResponse;
import common.auth.TlsSupport;
import common.meeting.*;
import common.chat.*;
import client.auth.ChatConnection;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.net.ssl.SSLContext;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import server.auth.AuthService;
import server.auth.PasswordHasher;
import server.meeting.RoomService;
import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX form actions through TLS sockets and the production service, using only an isolated database. */
@Timeout(30)
class DucAuthFlowTest {
    private static final String PASSWORD = "Mật-khẩu-123!";
    private static final String KEY_PASSWORD = "ui-test-certificate-only";
    @TempDir static Path temporary;
    private static SSLContext serverTls;
    private static SSLContext clientTls;
    private String database;
    private AuthServer server;
    private SceneManager manager;
    private Stage stage;
    private final List<SceneManager> extraWindows = new ArrayList<>();
    private final CountDownLatch registrationReceived = new CountDownLatch(1);
    private final CountDownLatch registrationRelease = new CountDownLatch(1);
    private final List<String> actions = new CopyOnWriteArrayList<>();
    private final AtomicLong roomDelayMillis = new AtomicLong();

    @BeforeAll
    @Timeout(60)
    static void startJavaFxAndCertificates() throws Exception {
        Path keys = temporary.resolve("auth-ui.p12");
        Path certificate = temporary.resolve("auth-ui.cer");
        keytool("-genkeypair", "-alias", "ui-test", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
                "-keystore", keys.toString(), "-storepass", KEY_PASSWORD, "-keypass", KEY_PASSWORD,
                "-dname", "CN=localhost", "-validity", "2", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt");
        keytool("-exportcert", "-alias", "ui-test", "-keystore", keys.toString(), "-storepass", KEY_PASSWORD,
                "-rfc", "-file", certificate.toString());
        serverTls = TlsSupport.server(keys, KEY_PASSWORD.toCharArray());
        clientTls = TlsSupport.client(certificate);
        support.FxTestRuntime.start();
    }

    @BeforeEach
    void startIsolatedServerAndHiddenWindow() throws Exception {
        database = "jdbc:h2:mem:auth_ui_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY, Ten_Dang_Nhap VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                    Mat_Khau_Bam VARCHAR(255) NOT NULL, Ten_Hien_Thi VARCHAR(100) NOT NULL, Email VARCHAR(255) UNIQUE,
                    Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG', Lan_Dang_Nhap_Cuoi TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung,
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE, Ngay_Tao TIMESTAMP NOT NULL,
                    Het_Han_Luc TIMESTAMP NOT NULL, Thu_Hoi_Luc TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Phong_Hop (
                    Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong_Tham_Gia VARCHAR(20) UNIQUE,
                    Ten_Phong VARCHAR(100), Ma_PIN_Bam VARCHAR(255), Ma_Chu_Phong BIGINT REFERENCES Nguoi_Dung,
                    Trang_Thai VARCHAR(20), Da_Khoa BOOLEAN, So_Nguoi_Toi_Da SMALLINT, Ngay_Dong TIMESTAMP)
                """);
        execute("""
                CREATE TABLE Thanh_Vien_Phong (
                    Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT REFERENCES Phong_Hop,
                    Ma_Nguoi_Dung BIGINT REFERENCES Nguoi_Dung, Vai_Tro VARCHAR(20),
                    Tham_Gia_Luc TIMESTAMP DEFAULT CURRENT_TIMESTAMP, Roi_Phong_Luc TIMESTAMP, Ly_Do_Roi VARCHAR(20))
                """);
        execute("""
                CREATE TABLE Tin_Nhan (
                    Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY, Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop,
                    Ma_Nguoi_Gui BIGINT NOT NULL REFERENCES Nguoi_Dung, Loai_Tin_Nhan VARCHAR(20) NOT NULL DEFAULT 'VAN_BAN',
                    Noi_Dung TEXT, Ngay_Gui TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, Ngay_Xoa TIMESTAMP)
                """);
        AuthService service = new AuthService(() -> DriverManager.getConnection(database), new PasswordHasher(),
                Clock.systemUTC(), Duration.ofHours(24));
        server = new AuthServer(serverTls, new InetSocketAddress("127.0.0.1", 0), request -> {
            actions.add(request.action());
            if ("REGISTER".equals(request.action())) {
                registrationReceived.countDown();
                try {
                    if (!registrationRelease.await(10, TimeUnit.SECONDS)) {
                        return AuthResponse.error("TEST_TIMEOUT", "Test did not release the registration response");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return AuthResponse.error("TEST_INTERRUPTED", "Test server interrupted");
                }
            }
            return service.handle(request);
        }, new RoomService(() -> {
            try { Thread.sleep(roomDelayMillis.get()); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.sql.SQLException("Interrupted test delay", interrupted); }
            return DriverManager.getConnection(database);
        }, Clock.systemUTC()));
        server.start();
        fx(() -> {
            stage = new Stage();
            manager = new SceneManager(stage, new AuthClient("localhost", server.port(), clientTls, 5000), true);
            manager.auth();
            root().applyCss(); root().layout();
            assertFalse(stage.isShowing(), "The test must not open a visible window");
            return null;
        });
    }

    @AfterEach
    void stopFixture() throws Exception {
        registrationRelease.countDown();
        try {
            for (SceneManager extra : extraWindows) fx(() -> { extra.shutdown(); extra.stage().hide(); return null; });
            if (manager != null) fx(() -> { manager.shutdown(); stage.hide(); return null; });
        } finally {
            if (server != null) server.close();
            if (database != null) execute("DROP ALL OBJECTS");
        }
    }


    @Test
    void registrationRejectsWhitespaceAndWeakPasswordsBeforeSendingRequests() throws Exception {
        fx(() -> {
            button("Đăng ký").fire();
            fields().get(1).setText("Trần Văn Khoa");
            fields().get(3).setText(PASSWORD); fields().get(4).setText(PASSWORD);
            // JavaFX TextField strips tabs itself; raw tab requests are covered by the service tests.
            for (String username : List.of("Tran Khoa", " TranKhoa", "TranKhoa ", "Tran\u00a0Khoa", "Tran\u2003Khoa")) {
                fields().get(0).setText(username);
                button("Tạo tài khoản").fire();
                assertTrue(hasLabel("Tên đăng nhập không được chứa khoảng trắng."), "Missing whitespace error for: " + username);
                assertFalse(button("Tạo tài khoản").isDisabled());
            }
            fields().getFirst().setText("TranKhoa");
            for (String password : List.of("12345678", "abcdefgh", "ABCDEFGH", "TranKhoa123",
                    "trankhoa123!", "TRANKHOA123!", "TranKhoa!!!", "TranKhoa123 ")) {
                fields().get(3).setText(password); fields().get(4).setText(password);
                button("Tạo tài khoản").fire();
                assertTrue(hasLabel("Mật khẩu cần 8–128 ký tự"));
                assertFalse(button("Tạo tài khoản").isDisabled());
            }
            return null;
        });
        assertTrue(actions.isEmpty(), "Invalid registration must not reach the server");
        assertEquals(0, count("SELECT COUNT(*) FROM Nguoi_Dung"));
    }

    @Test
    void existingFormsRegisterLoginAndRevokeTheRealSessionWithoutBlockingJavaFx() throws Exception {
        fx(() -> {
            button("Đăng ký").fire();
            List<TextField> fields = fields();
            assertEquals(5, fields.size());
            fields.get(0).setText("TranKhoa"); fields.get(1).setText("  Trần Văn Khoa  ");
            fields.get(2).setText("khoa.ui@example.com"); fields.get(3).setText(PASSWORD); fields.get(4).setText(PASSWORD);
            return null;
        });
        snapshotAuth("auth-register.png");
        fx(() -> {
            button("Tạo tài khoản").fire();
            assertTrue(button("Đang xử lý…").isDisabled());
            assertTrue(button("Đăng nhập").isDisabled());
            assertTrue(fields().stream().allMatch(Node::isDisabled));
            return null;
        });
        assertTrue(registrationReceived.await(5, TimeUnit.SECONDS), "Registration never reached the TLS server");
        fx(() -> { assertTrue(root() instanceof AuthScreen); assertTrue(button("Đang xử lý…").isDisabled()); return null; });
        registrationRelease.countDown();
        awaitFx(() -> root() instanceof AuthScreen && fields().size() == 2 && hasLabel("Tạo tài khoản thành công."));
        fx(() -> {
            assertEquals("TranKhoa", fields().getFirst().getText());
            assertTrue(fields().get(1).getText().isEmpty());
            assertNull(manager.authenticatedUser());
            assertFalse(button("Đăng nhập  →").isDisabled());
            return null;
        });
        assertEquals(1, count("SELECT COUNT(*) FROM Nguoi_Dung"));
        assertEquals(0, count("SELECT COUNT(*) FROM Phien_Dang_Nhap"));
        snapshotAuth("auth.png");

        AuthScreen login = fx(() -> (AuthScreen) root());
        fx(() -> {
            fields().get(1).setText("wrong-password"); button("Đăng nhập  →").fire();
            assertTrue(button("Đang xử lý…").isDisabled()); return null;
        });
        awaitFx(() -> hasLabel("Tên đăng nhập hoặc mật khẩu không đúng.") && !button("Đăng nhập  →").isDisabled());
        fx(() -> { assertSame(login, root()); assertNull(manager.authenticatedUser()); return null; });
        assertEquals(0, count("SELECT COUNT(*) FROM Phien_Dang_Nhap"));

        fx(() -> {
            fields().getFirst().setText("trankhoa"); fields().get(1).setText(PASSWORD);
            button("Đăng nhập  →").fire(); return null;
        });
        awaitFx(() -> hasLabel("Tên đăng nhập hoặc mật khẩu không đúng.") && !button("Đăng nhập  →").isDisabled());
        assertEquals(0, count("SELECT COUNT(*) FROM Phien_Dang_Nhap"));
        fx(() -> { assertSame(login, root()); assertNull(manager.authenticatedUser()); return null; });

        fx(() -> { fields().getFirst().setText("TranKhoa"); fields().get(1).setText(PASSWORD); button("Đăng nhập  →").fire(); return null; });
        awaitFx(() -> root() instanceof LegacyLobbyScreen);
        fx(() -> {
            assertEquals("TranKhoa", manager.username()); assertEquals("Trần Văn Khoa", manager.user());
            assertEquals("khoa.ui@example.com", manager.email()); assertNotNull(manager.authenticatedUser());
            assertTrue(nodes(login).filter(PasswordField.class::isInstance).map(PasswordField.class::cast)
                    .allMatch(field -> field.getText().isEmpty()), "Successful login must clear the old form password");
            assertFalse(stage.isShowing());
            manager.logout();
            assertTrue(manager.logoutPendingProperty().get()); assertTrue(root().isDisabled());
            return null;
        });
        awaitFx(() -> root() instanceof AuthScreen && !manager.logoutPendingProperty().get());
        fx(() -> {
            assertNull(manager.authenticatedUser()); assertEquals("", manager.user()); assertEquals("", manager.email());
            assertTrue(fields().stream().allMatch(field -> field.getText().isEmpty()));
            assertFalse(stage.isShowing()); return null;
        });
        assertEquals(1, count("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NOT NULL"));
        assertEquals(List.of("REGISTER", "LOGIN", "LOGIN", "LOGIN", "LOGOUT"), actions);
    }

    @Test
    @Timeout(60)
    void threeLocalAccountWindowsSynchronizeMembersMediaAndHostPermissions() throws Exception {
        registrationRelease.countDown();
        AuthResponse host = account("host", "Chủ phòng thật");
        SceneManager guest = accountWindow("guest", "Thành viên thật");
        SceneManager third = accountWindow("third", "Thành viên thứ ba");
        fx(() -> { manager.acceptSession(host); manager.lobby(); return null; });
        RoomResponse created = manager.authClient().meeting(RoomRequest.create(host.token(), "Phòng local", "1234", 10));
        fx(() -> { manager.enterRoom(created, "1234"); return null; });
        joinUi(guest, created.room().code());
        joinUi(third, created.room().code());
        awaitFx(() -> roster(manager).size() == 3 && roster(guest).size() == 3 && roster(third).size() == 3);
        fx(() -> {
            var ids = roster(manager).stream().map(Participant::userId).sorted().toList();
            assertEquals(ids, roster(guest).stream().map(Participant::userId).sorted().toList());
            assertEquals(ids, roster(third).stream().map(Participant::userId).sorted().toList());
            assertEquals(List.of("Chủ phòng thật", "Thành viên thật", "Thành viên thứ ba"), roster(manager).stream().map(Participant::name).toList());
            assertTrue(button(manager, "Mời người dùng").isVisible());
            assertFalse(button(guest, "Mời người dùng").isVisible());
            assertTrue(button(third, "Mời người dùng").isDisabled());
            icon(guest, "Mic").fire(); icon(guest, "Camera").fire();
            return null;
        });
        awaitFx(() -> guest.mic() && guest.camera() && roster(manager).stream()
                .anyMatch(person -> person.userId() == guest.authenticatedUser().id() && person.microphone() && person.camera()));
        snapshotMeeting(manager, "meeting-local-1280.png", 1280, 800);
        snapshotMeeting(guest, "meeting-local-1000.png", 1000, 650);
        long guestId = fx(() -> guest.authenticatedUser().id());
        long thirdId = fx(() -> third.authenticatedUser().id());
        roomCommand(manager, "ROOM_MIC_OFF", guestId);
        roomCommand(manager, "ROOM_CAMERA_OFF", guestId);
        awaitFx(() -> !guest.mic() && !guest.camera());
        roomCommand(manager, "ROOM_TRANSFER", guestId);
        awaitFx(() -> !button(manager, "Mời người dùng").isVisible() && button(guest, "Mời người dùng").isVisible());
        RoomRequest denied = fx(() -> manager.roomCommand("ROOM_KICK", thirdId));
        assertEquals("HOST_REQUIRED", assertThrows(RoomException.class, () -> manager.authClient().meeting(denied)).code());
        roomCommand(guest, "ROOM_KICK", thirdId);
        awaitFx(() -> root(third) instanceof MeetingEndedScreen && roster(manager).size() == 2 && roster(guest).size() == 2);

        roomSettings(guest, true, null);
        awaitFx(() -> manager.locked() && guest.locked());
        fx(() -> { third.lobby(); return null; });
        joinUi(third, created.room().code());
        awaitFx(() -> hasLabel(root(third), "Phòng đang khóa."));
        assertTrue(fx(() -> root(third) instanceof LegacyLobbyScreen));
        roomSettings(guest, false, true);
        joinUi(third, created.room().code());
        awaitFx(() -> hasLabel(root(third), "Đang chờ chủ phòng duyệt.") && guest.meetingState().waiting().size() == 1);
        fx(() -> { assertEquals(2, roster(guest).size()); button(third, "Hủy yêu cầu").fire(); return null; });
        awaitFx(() -> third.meetingState() == null && guest.meetingState().waiting().isEmpty());
        joinUi(third, created.room().code());
        awaitFx(() -> guest.meetingState().waiting().size() == 1);
        fx(() -> { icon(guest, "Chấp nhận Thành viên thứ ba").fire(); return null; });
        awaitFx(() -> root(third) instanceof LegacyMeetingScreen && roster(third).size() == 3 && roster(manager).size() == 3);
        roomCommand(guest, "ROOM_CLOSE", 0);
        awaitFx(() -> root(manager) instanceof MeetingEndedScreen && root(guest) instanceof MeetingEndedScreen && root(third) instanceof MeetingEndedScreen);
        assertEquals(0, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        fx(() -> {
            assertNull(manager.meetingState()); assertNull(guest.meetingState()); assertNull(third.meetingState());
            assertTrue(hasLabel(root(manager), "Chủ phòng đã đóng phòng.")); return null;
        });
    }

    @Test
    void leavingLobbyWhileWaitingCancelsAdmissionWithoutCreatingGhostMembers() throws Exception {
        registrationRelease.countDown();
        AuthResponse host = account("host", "Chủ phòng");
        SceneManager guest = accountWindow("guest", "Người chờ");
        fx(() -> { manager.acceptSession(host); return null; });
        RoomResponse created = manager.authClient().meeting(RoomRequest.create(host.token(), "Phòng duyệt", "1234", 10));
        fx(() -> { manager.enterRoom(created, "1234"); return null; });
        roomSettings(manager, null, true);
        joinUi(guest, created.room().code());
        awaitFx(() -> manager.meetingState().waiting().size() == 1);
        fx(() -> { guest.lobby(); return null; });
        awaitFx(() -> manager.meetingState().waiting().isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        fx(() -> { assertNull(guest.meetingState()); return null; });
    }

    @Test
    @Timeout(60)
    void chatIsPushedToThreeWindowsStoredOnceAndLoadedOnReentry() throws Exception {
        registrationRelease.countDown();
        AuthResponse host = account("host", "Đức");
        SceneManager guest = accountWindow("guest", "Thành viên thật"), third = accountWindow("third", "Người thứ ba");
        fx(() -> { manager.acceptSession(host); return null; });
        RoomResponse room = manager.authClient().meeting(RoomRequest.create(host.token(), "Chat realtime", "1234", 10));
        fx(() -> { manager.enterRoom(room, "1234"); return null; });
        joinUi(guest, room.room().code()); joinUi(third, room.room().code());
        awaitFx(() -> roster(manager).size() == 3 && roster(guest).size() == 3 && roster(third).size() == 3);
        fx(() -> { icon(manager, "Chat").fire(); icon(guest, "Chat").fire(); icon(third, "Chat").fire(); return null; });
        awaitFx(() -> !chatSend(manager).isDisabled() && !chatSend(guest).isDisabled() && !chatSend(third).isDisabled());
        sendChatUi(manager, "Xin chào cả phòng 👋");
        awaitFx(() -> chatTexts(manager).size() == 1 && chatTexts(guest).size() == 1 && chatTexts(third).size() == 1);
        sendChatUi(guest, "Tiếng Việt hiển thị đúng, mình nhận được rồi.");
        awaitFx(() -> chatTexts(manager).size() == 2 && chatTexts(guest).size() == 2 && chatTexts(third).size() == 2);
        fx(() -> {
            assertEquals(chatTexts(manager), chatTexts(guest)); assertEquals(chatTexts(manager), chatTexts(third));
            assertTrue(chatBubbles(manager).getFirst().getStyleClass().contains("bubble-mine"));
            assertTrue(chatBubbles(guest).getFirst().getStyleClass().contains("bubble-other"));
            assertTrue(chatBubbles(guest).get(1).getStyleClass().contains("bubble-mine"));
            assertTrue(hasLabel(root(third), "Đức  ·")); return null;
        });
        assertEquals(2, count("SELECT COUNT(*) FROM Tin_Nhan"));
        snapshotChat(manager, "chat-realtime-1280.png", 1280, 800);
        snapshotChat(guest, "chat-realtime-1000.png", 1000, 650);
        fx(() -> { icon(third, "Rời phòng").fire(); return null; });
        awaitFx(() -> root(third) instanceof MeetingEndedScreen && roster(manager).size() == 2);
        sendChatUi(manager, "Tin nhắn lúc người thứ ba đã rời phòng.");
        awaitFx(() -> chatTexts(manager).size() == 3 && chatTexts(guest).size() == 3);
        fx(() -> { third.lobby(); return null; }); joinUi(third, room.room().code());
        awaitFx(() -> root(third) instanceof LegacyMeetingScreen && chatTexts(third).size() == 3);
        fx(() -> { assertEquals(chatTexts(manager), chatTexts(third)); return null; });
        assertEquals(3, count("SELECT COUNT(*) FROM Tin_Nhan"));
    }

    @Test
    void failedChatWriteRetainsDraftAndNeverCreatesALocalOnlyBubble() throws Exception {
        registrationRelease.countDown();
        AuthResponse host = account("host", "Đức");
        fx(() -> { manager.acceptSession(host); return null; });
        RoomResponse room = manager.authClient().meeting(RoomRequest.create(host.token(), "Chat lỗi DB", "1234", 10));
        fx(() -> { manager.enterRoom(room, "1234"); return null; });
        awaitFx(() -> !chatSend(manager).isDisabled());
        execute("ALTER TABLE Tin_Nhan ADD CONSTRAINT Test_Short_Message CHECK (CHAR_LENGTH(Noi_Dung) < 5)");
        sendChatUi(manager, "cannot-persist");
        awaitFx(() -> hasLabel(root(), "Không thể lưu tin nhắn") && !chatSend(manager).isDisabled());
        fx(() -> { assertEquals("cannot-persist", chatInput(manager).getText()); assertTrue(chatTexts(manager).isEmpty()); return null; });
        assertEquals(0, count("SELECT COUNT(*) FROM Tin_Nhan"));
        sendChatUi(manager, "OK"); awaitFx(() -> chatTexts(manager).equals(List.of("OK")) && chatInput(manager).getText().isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM Tin_Nhan"));
    }

    @Test
    void persistentChatListenersDoNotExhaustAuthenticationWorkersAndLargeHistoryFramesRoundTrip() throws Exception {
        registrationRelease.countDown();
        AuthResponse host = account("host", "Đức");
        fx(() -> { manager.acceptSession(host); return null; });
        RoomResponse room = manager.authClient().meeting(RoomRequest.create(host.token(), "Nhiều kết nối chat", "1234", 10));
        fx(() -> { manager.enterRoom(room, "1234"); return null; });
        List<ChatConnection> listeners = new ArrayList<>(); List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(9);
        try {
            for (int index = 0; index < 9; index++) {
                var first = new java.util.concurrent.atomic.AtomicBoolean();
                listeners.add(manager.authClient().listenChat(ChatRequest.listen(host.token(), room.room().id(), room.ticket()),
                        response -> { if (first.compareAndSet(false, true)) ready.countDown(); }, errors::add));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Persistent listeners did not become ready");
            assertTrue(manager.authClient().login("host", PASSWORD).success(), "Chat must leave auth workers available");
            ChatMessage message = manager.authClient().chat(ChatRequest.send(host.token(), room.room().id(), room.ticket(), "<".repeat(2000))).messages().getFirst();
            List<ChatMessage> history = new CopyOnWriteArrayList<>(); CountDownLatch loaded = new CountDownLatch(1);
            try (ChatConnection late = manager.authClient().listenChat(ChatRequest.listen(host.token(), room.room().id(), room.ticket()), response -> {
                history.addAll(response.messages()); if (!response.messages().isEmpty()) loaded.countDown();
            }, errors::add)) {
                assertTrue(loaded.await(10, TimeUnit.SECONDS)); assertEquals(List.of(message), history);
            }
            assertTrue(errors.isEmpty(), () -> "Chat transport errors: " + errors);
        } finally { listeners.forEach(ChatConnection::close); }
    }

    @Test
    void lobbyRestoresOriginalLayoutWithoutInventingRecentRoomsOrUsers() throws Exception {
        registrationRelease.countDown();
        AuthResponse session = account("duc", "Phạm Gia Đức");
        fx(() -> {
            manager.acceptSession(session); manager.lobby(); root().applyCss(); root().layout();
            assertTrue(hasLabel("Xin chào, Đức"));
            assertTrue(hasLabel("Sẵn sàng kết nối"));
            assertTrue(hasLabel("Tạo phòng mới")); assertTrue(hasLabel("Tham gia phòng")); assertTrue(hasLabel("Phòng gần đây"));
            assertTrue(hasLabel("Chưa có dữ liệu phòng gần đây."));
            assertFalse(hasLabel("Hoàng Nam")); assertFalse(hasLabel("Thảo Linh")); assertFalse(hasLabel("Ngọc Mai"));
            assertNotNull(root().lookup("#lobby-create-card")); assertNotNull(root().lookup("#lobby-join-card"));
            assertTrue(root().lookupAll(".room-row").isEmpty());
            assertFalse(button("Tạo phòng").isDisabled()); assertFalse(button("Tham gia").isDisabled());
            return null;
        });
        snapshotLobby("lobby-restored-1100.png", 1100, 700);
        snapshotLobby("lobby-restored-1000.png", 1000, 650);
        assertEquals(0, count("SELECT COUNT(*) FROM Phong_Hop"));
    }

    @Test
    void networkStatsUseActualTlsRoomAndChatTrafficAndDetectServerFailure() throws Exception {
        registrationRelease.countDown();
        AuthResponse session = account("network_user", "Network User");
        RoomResponse room = manager.authClient().meeting(RoomRequest.create(session.token(), "Network test", "1234", 10));
        LegacyMeetingScreen meeting = fx(() -> {
            manager.acceptSession(session); manager.enterRoom(room, "1234"); return (LegacyMeetingScreen) root();
        });
        NetworkMonitor monitor = meeting.networkMonitor();
        awaitFx(() -> monitor.snapshot().history().size() >= 2);
        var measured = monitor.snapshot();
        assertNotNull(measured.rttMillis()); assertTrue(measured.rttMillis() > 0);
        assertNotNull(measured.jitterMillis()); assertTrue(measured.bytesSent() > 0); assertTrue(measured.bytesReceived() > 0);
        assertTrue(measured.sendBitsPerSecond() > 0); assertTrue(measured.receiveBitsPerSecond() > 0);
        String message = "network traffic ".repeat(100) + "end";
        sendChatUi(manager, message);
        awaitFx(() -> chatTexts(manager).contains(message));
        assertTrue(monitor.snapshot().bytesSent() >= measured.bytesSent() + message.length());
        assertTrue(monitor.snapshot().bytesReceived() >= measured.bytesReceived() + message.length());

        NetworkStatsDialog dialog = fx(() -> new NetworkStatsDialog(stage, monitor));
        List<WritableImage> images = fx(() -> {
            boolean original = ThemeManager.isLight();
            try {
                var pane = dialog.getDialogPane(); pane.resize(540, 660);
                ThemeManager.setLight(false); pane.applyCss(); pane.layout(); dialog.refresh();
                assertTrue(pane.prefHeight(540) <= 620, "Statistics should fit a 650px-high desktop window");
                for (String missing : List.of("loss", "fps", "resolution")) {
                    assertEquals("--", ((Label) pane.lookup("#network-" + missing)).getText());
                }
                assertFalse(((Label) pane.lookup("#network-latency")).getText().equals("--"));
                assertFalse(dialog.getHeaderText().contains("Mô phỏng"));
                WritableImage dark = pane.snapshot(new SnapshotParameters(), null);
                ThemeManager.setLight(true); pane.applyCss(); pane.layout();
                return List.of(dark, pane.snapshot(new SnapshotParameters(), null));
            } finally { ThemeManager.setLight(original); }
        });
        writeSnapshot(images.get(0), "network-real-dark.png"); writeSnapshot(images.get(1), "network-real-light.png");
        fx(() -> { dialog.dispose(); return null; });
        int before = monitor.snapshot().history().size();
        manager.authClient().meeting(RoomRequest.command("ROOM_POLL", session.token(), room.room().id(), room.ticket(), 0));
        assertTrue(monitor.snapshot().history().size() > before, "Closing statistics must not stop collection");

        roomDelayMillis.set(240);
        awaitFx(() -> monitor.snapshot().rttMillis() != null && monitor.snapshot().rttMillis() >= 200);
        WritableImage delayed = fx(() -> {
            meeting.refreshNetwork(); assertTrue(((Button) root().lookup("#network-badge")).getText().contains("Yếu"));
            dialog.refresh(); var pane = dialog.getDialogPane(); pane.applyCss(); pane.layout();
            return pane.snapshot(new SnapshotParameters(), null);
        });
        writeSnapshot(delayed, "network-real-slow.png");
        roomDelayMillis.set(0);
        server.close();
        assertThrows(java.io.IOException.class, () -> manager.authClient().meeting(
                RoomRequest.command("ROOM_POLL", session.token(), room.room().id(), room.ticket(), 0)));
        assertEquals(NetworkMonitor.Quality.DISCONNECTED, monitor.snapshot().quality());
        fx(() -> {
            meeting.refreshNetwork(); dialog.refresh();
            assertTrue(((Button) root().lookup("#network-badge")).getText().contains("Mất kết nối"));
            assertEquals("--", ((Label) dialog.getDialogPane().lookup("#network-latency")).getText());
            manager.ended(5, 1);
            assertEquals(NetworkMonitor.Quality.STOPPED, monitor.snapshot().quality()); return null;
        });
        var stopped = monitor.snapshot(); Thread.sleep(100);
        assertEquals(stopped, monitor.snapshot(), "Departed meetings must not retain live traffic counters");
    }

    @Test
    void networkMonitoringResetDoesNotDetachNewMeetingOrCountServerRejectionsAsPacketLoss() throws Exception {
        registrationRelease.countDown();
        AuthResponse session = account("network_reset", "Network Reset");
        NetworkMonitor first = manager.authClient().startNetworkMonitoring();
        NetworkMonitor next = manager.authClient().startNetworkMonitoring();
        manager.authClient().stopNetworkMonitoring(first);
        assertEquals(NetworkMonitor.Quality.STOPPED, first.snapshot().quality());
        assertFalse(manager.authClient().login("network_reset", "incorrect-password").success());
        assertEquals(1, next.snapshot().requests()); assertEquals(0, next.snapshot().failures());
        assertTrue(next.snapshot().bytesSent() > 0); assertTrue(next.snapshot().bytesReceived() > 0);
        assertTrue(next.snapshot().history().isEmpty(), "Login exchanges must not masquerade as room response times");
        manager.authClient().stopNetworkMonitoring(next);
        assertEquals(NetworkMonitor.Quality.STOPPED, next.snapshot().quality());
        manager.authClient().logout(session.token());
        assertEquals(1, next.snapshot().requests());
    }

    @Test
    void themeSelectorUpdatesOpenAndNewSettingsDialogsAndWorkspace() throws Exception {
        registrationRelease.countDown();
        AuthResponse session = account("theme_user", "Theme User");
        List<WritableImage> images = fx(() -> {
            boolean original = ThemeManager.isLight();
            try {
                ThemeManager.setLight(true);
                manager.acceptSession(session); manager.lobby();
                var room = new RoomView(1, "Theme test", "MMR-THEME", 10, false, true, false);
                var settings = new client.UI.dialogs.DeviceSettingsDialog(stage, room, value -> { }, value -> { }, () -> { });
                var pane = settings.getDialogPane();
                pane.resize(450, 880); pane.applyCss(); pane.layout();
                assertTrue(pane.getStyleClass().contains("light-mode"));
                assertTrue(((javafx.scene.paint.Color) pane.getBackground().getFills().getFirst().getFill()).getBrightness() > .9);
                var choices = nodes(pane.getContent()).filter(Button.class::isInstance).map(Button.class::cast)
                        .filter(button -> button.getStyleClass().contains("theme-choice")).toList();
                Button light = choices.get(0), dark = choices.get(1);
                var fixedDark = new javafx.scene.layout.VBox(); fixedDark.getStyleClass().add("theme-dark");
                ThemeManager.attach(fixedDark);

                dark.fire(); pane.applyCss(); pane.layout(); root().applyCss();
                assertFalse(ThemeManager.isLight());
                assertFalse(pane.getStyleClass().contains("light-mode"));
                assertFalse(root().getStyleClass().contains("light-mode"));
                assertTrue(((javafx.scene.paint.Color) pane.getBackground().getFills().getFirst().getFill()).getBrightness() < .2);
                assertTrue(((javafx.scene.paint.Color) ((javafx.scene.layout.Region) root()).getBackground().getFills().getFirst().getFill()).getBrightness() < .2);
                Label heading = nodes(pane.getContent()).filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> "Giao diện".equals(label.getText())).findFirst().orElseThrow();
                assertTrue(((javafx.scene.paint.Color) heading.getTextFill()).getBrightness() > .8);
                assertTrue(dark.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("selected")));
                assertFalse(light.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("selected")));
                WritableImage darkImage = pane.snapshot(new SnapshotParameters(), null);

                var reopened = new client.UI.dialogs.DeviceSettingsDialog(stage);
                assertFalse(reopened.getDialogPane().getStyleClass().contains("light-mode"));
                light.fire(); pane.applyCss(); pane.layout(); root().applyCss();
                assertTrue(ThemeManager.isLight());
                assertTrue(pane.getStyleClass().contains("light-mode"));
                assertTrue(reopened.getDialogPane().getStyleClass().contains("light-mode"));
                assertTrue(root().getStyleClass().contains("light-mode"));
                assertTrue(((javafx.scene.paint.Color) pane.getBackground().getFills().getFirst().getFill()).getBrightness() > .9);
                assertTrue(((javafx.scene.paint.Color) heading.getTextFill()).getBrightness() < .3);
                assertTrue(light.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("selected")));
                assertFalse(dark.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("selected")));
                assertFalse(fixedDark.getStyleClass().contains("light-mode"));
                assertTrue(fixedDark.getStyleClass().contains("theme-dark"));
                return List.of(darkImage, pane.snapshot(new SnapshotParameters(), null));
            } finally {
                ThemeManager.setLight(original);
            }
        });
        writeSnapshot(images.get(0), "settings-dark.png");
        writeSnapshot(images.get(1), "settings-light.png");
    }

    @Test
    void hostDialogsKeepRoomSettingsAndInvitationSeparateFromParticipantActions() throws Exception {
        fx(() -> {
            List<String> commands = new ArrayList<>();
            var room = new RoomView(1, "Phòng local", "MMR-LOCAL", 10, false, false, false);
            var settings = new client.UI.dialogs.DeviceSettingsDialog(stage, room,
                    locked -> commands.add("locked=" + locked), approval -> commands.add("approval=" + approval), () -> { });
            var content = settings.getDialogPane().getContent();
            var checkboxes = nodes(content).filter(javafx.scene.control.CheckBox.class::isInstance)
                    .map(javafx.scene.control.CheckBox.class::cast).toList();
            checkboxes.stream().filter(box -> "Khóa phòng".equals(box.getText())).findFirst().orElseThrow().fire();
            checkboxes.stream().filter(box -> "Duyệt thành viên trước khi vào".equals(box.getText())).findFirst().orElseThrow().fire();
            assertEquals(List.of("locked=true", "approval=true"), commands);
            assertTrue(nodes(content).filter(Button.class::isInstance).map(Button.class::cast).noneMatch(button -> "Đóng phòng".equals(button.getText())));
            var memberSettings = new client.UI.dialogs.DeviceSettingsDialog(stage);
            assertFalse(nodes(memberSettings.getDialogPane().getContent()).filter(javafx.scene.control.CheckBox.class::isInstance)
                    .map(javafx.scene.control.CheckBox.class::cast).anyMatch(box -> "Khóa phòng".equals(box.getText())));

            var menu = new client.UI.dialogs.HostMenuPopup(true, true,
                    () -> commands.add("mic-off"), () -> commands.add("camera-off"), () -> commands.add("kick"), () -> commands.add("transfer"));
            menu.getItems().get(0).fire(); menu.getItems().get(1).fire(); menu.getItems().get(3).fire(); menu.getItems().get(4).fire();
            assertEquals(List.of("locked=true", "approval=true", "mic-off", "camera-off", "transfer", "kick"), commands);
            assertTrue(menu.getItems().stream().noneMatch(item -> List.of("Khóa phòng", "Mở khóa phòng", "Đóng phòng").contains(item.getText() == null ? "" : item.getText())));
            var offMenu = new client.UI.dialogs.HostMenuPopup(false, false, () -> { }, () -> { }, () -> { }, () -> { });
            assertTrue(offMenu.getItems().get(0).isDisable()); assertTrue(offMenu.getItems().get(1).isDisable());

            var invitation = new client.UI.dialogs.InviteDialog(stage, room.name(), room.code(), "1234");
            List<TextField> credentials = nodes(invitation.getDialogPane().getContent()).filter(TextField.class::isInstance).map(TextField.class::cast).toList();
            assertEquals(List.of("MMR-LOCAL", "1234"), credentials.stream().map(TextField::getText).toList());
            assertTrue(credentials.stream().noneMatch(TextField::isEditable));
            assertTrue(actions.isEmpty(), "Opening invitation must not send a message or create an account");
            return null;
        });
    }

    @Test void newerDeviceReplacesLegacyRoomScreenAndKeepsOldAccountLoggedIn() throws Exception {
        registrationRelease.countDown();
        AuthResponse original = account("moving_legacy_host", "Chủ phòng đổi thiết bị");
        RoomResponse room = manager.authClient().meeting(RoomRequest.create(original.token(), "Đổi thiết bị legacy", "1234", 2));
        fx(() -> { manager.acceptSession(original); manager.enterRoom(room, "1234"); return null; });
        AuthResponse newer = manager.authClient().login("moving_legacy_host", PASSWORD); assertTrue(newer.success());
        RoomResponse replacement = manager.authClient().meeting(RoomRequest.join(newer.token(), room.room().code(), "1234"));
        awaitFx(() -> root() instanceof MeetingEndedScreen && manager.meetingState() == null);
        fx(() -> {
            assertEquals(original.token(), manager.sessionToken());
            assertEquals(original.user().id(), manager.authenticatedUser().id());
            assertTrue(hasLabel("Tài khoản đã vào phòng trên thiết bị khác."));
            return null;
        });
        assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        RoomResponse current = manager.authClient().meeting(RoomRequest.command("ROOM_POLL", newer.token(),
                replacement.room().id(), replacement.ticket(), 0));
        assertTrue(current.members().getFirst().host()); assertEquals(1, current.members().size());
    }

    private AuthResponse account(String username, String name) throws Exception {
        assertTrue(manager.authClient().register(username, name, null, PASSWORD).success());
        AuthResponse login = manager.authClient().login(username, PASSWORD); assertTrue(login.success()); return login;
    }
    private SceneManager accountWindow(String username, String name) throws Exception {
        AuthResponse session = account(username, name);
        return fx(() -> {
            Stage window = new Stage();
            SceneManager extra = new SceneManager(window, new AuthClient("localhost", server.port(), clientTls, 5000), true);
            extraWindows.add(extra); extra.acceptSession(session); extra.lobby();
            assertFalse(window.isShowing()); return extra;
        });
    }
    private void joinUi(SceneManager target, String code) throws Exception {
        fx(() -> {
            root(target).applyCss(); root(target).layout();
            ((TextField) root(target).lookup("#join-room-code")).setText(code);
            ((PasswordField) root(target).lookup("#join-room-pin")).setText("1234");
            button(target, "Tham gia").fire(); return null;
        });
    }
    private void roomCommand(SceneManager target, String action, long user) throws Exception {
        request(target, fx(() -> target.roomCommand(action, user)));
    }
    private void roomSettings(SceneManager target, Boolean locked, Boolean approval) throws Exception {
        request(target, fx(() -> RoomRequest.settings(target.sessionToken(), target.meetingState().room().id(), target.meetingState().ticket(), locked, approval)));
    }
    private void request(SceneManager target, RoomRequest request) throws Exception {
        CompletableFuture<RoomResponse> reply = new CompletableFuture<>();
        fx(() -> { target.runMeetingRequest(request, reply::complete, reply::completeExceptionally); return null; });
        assertTrue(reply.get(10, TimeUnit.SECONDS).success());
    }
    private static List<Participant> roster(SceneManager target) {
        if (!(root(target) instanceof LegacyMeetingScreen)) return List.of();
        ListView<?> list = (ListView<?>) root(target).lookup("#room-members");
        return list.getItems().stream().map(Participant.class::cast).toList();
    }
    private static Parent root(SceneManager target) { return target.stage().getScene().getRoot(); }
    private static Button button(SceneManager target, String text) {
        return nodes(root(target)).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + text));
    }
    private static Button icon(SceneManager target, String accessibleText) {
        return nodes(root(target)).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> accessibleText.equals(button.getAccessibleText())).findFirst().orElseThrow(() -> new AssertionError("Missing icon: " + accessibleText));
    }
    private static boolean hasLabel(Parent root, String text) {
        return nodes(root).filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.isVisible() && label.getText().startsWith(text));
    }
    private void snapshotMeeting(SceneManager target, String filename, int width, int height) throws Exception {
        WritableImage image = fx(() -> {
            Parent screen = root(target); screen.resize(width, height); screen.applyCss(); screen.layout(); screen.setOpacity(1);
            assertEquals(3, roster(target).size());
            return screen.snapshot(new SnapshotParameters(), null);
        });
        writeSnapshot(image, filename);
    }
    private void snapshotLobby(String filename, int width, int height) throws Exception {
        WritableImage image = fx(() -> {
            Parent screen = root(); screen.resize(width, height); screen.applyCss(); screen.layout(); screen.setOpacity(1);
            var create = screen.lookup("#lobby-create-card"); var join = screen.lookup("#lobby-join-card");
            var createBounds = create.localToScene(create.getLayoutBounds()); var joinBounds = join.localToScene(join.getLayoutBounds());
            assertTrue(createBounds.getMaxX() <= joinBounds.getMinX(), "Lobby actions overlap");
            assertEquals(createBounds.getMinY(), joinBounds.getMinY(), 1);
            var recent = screen.lookup("#lobby-recent-rooms"); var recentBounds = recent.localToScene(recent.getLayoutBounds());
            assertTrue(recentBounds.getMinY() >= Math.max(createBounds.getMaxY(), joinBounds.getMaxY()));
            for (Node control : List.of(button("Tham gia"), screen.lookup("#join-room-code"), screen.lookup("#join-room-pin"))) {
                var bounds = control.localToScene(control.getLayoutBounds());
                assertTrue(bounds.getMinX() >= joinBounds.getMinX() && bounds.getMaxX() <= joinBounds.getMaxX(), "Join control exceeds card width");
                assertTrue(bounds.getMinY() >= joinBounds.getMinY() && bounds.getMaxY() <= joinBounds.getMaxY(), "Join control exceeds card height");
            }
            return screen.snapshot(new SnapshotParameters(), null);
        });
        writeSnapshot(image, filename);
    }
    private void sendChatUi(SceneManager target, String text) throws Exception {
        awaitFx(() -> !chatSend(target).isDisabled());
        fx(() -> { chatInput(target).setText(text); chatSend(target).fire(); return null; });
    }
    private static TextField chatInput(SceneManager target) {
        return (TextField) nodes(root(target)).filter(node -> "chat-input".equals(node.getId())).findFirst().orElseThrow();
    }
    private static Button chatSend(SceneManager target) {
        return (Button) nodes(root(target)).filter(node -> "chat-send".equals(node.getId())).findFirst().orElseThrow();
    }
    private static List<Label> chatBubbles(SceneManager target) {
        return nodes(root(target)).filter(Label.class::isInstance).map(Label.class::cast)
                .filter(label -> label.getStyleClass().contains("bubble")).toList();
    }
    private static List<String> chatTexts(SceneManager target) { return chatBubbles(target).stream().map(Label::getText).toList(); }
    private void snapshotChat(SceneManager target, String filename, int width, int height) throws Exception {
        WritableImage image = fx(() -> {
            Parent screen = root(target); screen.resize(width, height); screen.applyCss(); screen.layout(); screen.setOpacity(1);
            return screen.snapshot(new SnapshotParameters(), null);
        });
        writeSnapshot(image, filename);
    }

    private Parent root() { return stage.getScene().getRoot(); }
    private List<TextField> fields() {
        return nodes(root()).filter(TextField.class::isInstance).map(TextField.class::cast)
                .filter(Node::isVisible).toList();
    }
    private Button button(String text) {
        return nodes(root()).filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + text));
    }
    private boolean hasLabel(String text) {
        return nodes(root()).filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.isVisible() && label.getText().startsWith(text));
    }
    private static Stream<Node> nodes(Node node) {
        if (node instanceof javafx.scene.control.TabPane tabs) {
            return Stream.concat(Stream.of(node), tabs.getTabs().stream().map(javafx.scene.control.Tab::getContent)
                    .filter(java.util.Objects::nonNull).flatMap(DucAuthFlowTest::nodes));
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            return Stream.concat(Stream.of(node), nodes(scroll.getContent()));
        }
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(DucAuthFlowTest::nodes))
                : Stream.of(node);
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS);
    }
    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (fx(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for JavaFX authentication state");
    }
    private void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
    private int count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(database); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getInt(1);
        }
    }
    private void snapshotAuth(String filename) throws Exception {
        WritableImage image = fx(() -> {
            AuthScreen screen = (AuthScreen) root(); screen.resize(960, 600); screen.applyCss(); screen.layout();
            // Hidden windows do not advance enter animations. Resolve their initial opacity for the QA image only.
            screen.setOpacity(1);
            nodes(screen).filter(node -> node instanceof javafx.scene.layout.VBox && node.getOpacity() < 1)
                    .forEach(node -> node.setOpacity(1));
            return screen.snapshot(new SnapshotParameters(), null);
        });
        writeSnapshot(image, filename);
    }
    private void writeSnapshot(WritableImage image, String filename) throws Exception {
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) {
            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        }
        Path output = Path.of("target", "qa", filename); Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(png, "png", output.toFile()));
    }
    private static void keytool(String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        if (!Files.isRegularFile(executable)) executable = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new ArrayList<>();
        command.add(Files.isRegularFile(executable) ? executable.toString() : "keytool"); command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "keytool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("TLS test certificate generation timed out"); }
        assertEquals(0, process.exitValue(), () -> "keytool failed; see " + log);
    }
}
