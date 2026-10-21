package client.UI;

import client.UI.screens.AuthScreen;
import client.UI.screens.LobbyScreen;
import client.UI.screens.PreJoinScreen;
import client.UI.screens.MeetingScreen;
import client.UI.screens.MeetingEndedScreen;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.auth.TlsSupport;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
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
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import server.ApplicationService;
import server.rooms.RoomService;
import server.auth.AuthService;
import server.auth.PasswordHasher;
import server.filetransfer.FileMetadataRepository;
import server.filetransfer.FileStorageService;
import server.filetransfer.FileTransferServer;
import server.filetransfer.TransferConfig;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX form actions through TLS sockets and the production service, using only an isolated database. */
@Timeout(30)
class AuthFlowTest {
    private static final String PASSWORD = "Mật-khẩu-123!";
    private static final String KEY_PASSWORD = "ui-test-certificate-only";
    @TempDir static Path temporary;
    private static SSLContext serverTls;
    private static SSLContext clientTls;
    private String database;
    private AuthServer server;
    private SceneManager manager;
    private Stage stage;
    private final CountDownLatch registrationReceived = new CountDownLatch(1);
    private final CountDownLatch registrationRelease = new CountDownLatch(1);
    private final List<String> actions = new CopyOnWriteArrayList<>();

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
        FxTestRuntime.start();
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
        AuthService service = new AuthService(() -> DriverManager.getConnection(database), new PasswordHasher(),
                Clock.systemUTC(), Duration.ofHours(24));
        ApplicationService application = new ApplicationService(service,
                new RoomService(() -> DriverManager.getConnection(database), new PasswordHasher(), Clock.systemUTC()));
        server = new AuthServer(serverTls, new InetSocketAddress("127.0.0.1", 0), request -> {
            if ("LIST_ROOMS".equals(request.action())) {
                return AuthResponse.roomsOk("Đã tải danh sách phòng.", List.of());
            }
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
            return application.handle(request);
        });
        server.start();
        fx(() -> {
            stage = new Stage();
            manager = new SceneManager(stage, new AuthClient("localhost", server.port(), clientTls, 5000));
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
        awaitFx(() -> root() instanceof LobbyScreen);
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
    void roomNavigationAndExistingFileTabUseRealTlsMembershipAndMetadata() throws Exception {
        createRoomTables();
        TransferConfig config = new TransferConfig(true, "127.0.0.1", 0, 65536, 4, 2, 5000,
                temporary.resolve("ui-storage-" + UUID.randomUUID()), 1024 * 1024, 8 * 1024 * 1024, 4, Duration.ofHours(1), true);
        FileMetadataRepository repository = new FileMetadataRepository(() -> DriverManager.getConnection(database));
        FileStorageService storage = new FileStorageService(config.storagePath(), config.quotaBytes(), config.maxFileBytes(), 4, config.expiry(), true);
        try (FileTransferServer fileServer = new FileTransferServer(serverTls, config, repository, storage)) {
            fileServer.start();
            AuthClient auth = new AuthClient("localhost", server.port(), clientTls, 5000);
            registrationRelease.countDown(); assertTrue(auth.register("FileUser", "Người chia sẻ", null, PASSWORD).success());
            AuthResponse login = auth.login("FileUser", PASSWORD); assertTrue(login.success());
            FileTransferClient transfers = new FileTransferClient("localhost", fileServer.port(), clientTls, 5000);
            fx(() -> {
                manager.shutdown(); manager = new SceneManager(stage, auth, transfers); manager.acceptSession(login); manager.lobby();
                manager.createRoom("Phòng kiểm thử tệp", "1234", 10, false);
                assertTrue(root().isDisabled()); return null;
            });
            awaitFx(() -> root() instanceof PreJoinScreen);
            long roomId = fx(() -> manager.serverRoom().id());
            assertEquals(1, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
            Path source = temporary.resolve("Báo cáo UI.pdf"); Files.writeString(source, "File transferred through TLS for UI metadata test.");
            var cancellation = new FileTransferClient.Cancellation();
            var upload = transfers.prepareUpload(roomId, source, UUID.randomUUID(), cancellation, null);
            transfers.upload(login.token(), upload, cancellation, null);
            fx(() -> {
                manager.meeting(); root().applyCss(); root().layout();
                TabPane tabs = nodes(root()).filter(TabPane.class::isInstance).map(TabPane.class::cast).findFirst().orElseThrow();
                tabs.getSelectionModel().select(2); root().applyCss(); root().layout(); return null;
            });
            awaitFx(() -> root() instanceof MeetingScreen && hasLabel("Báo cáo UI.pdf") && hasLabel("Sẵn sàng tải"));
            snapshotMeeting();
            fx(() -> { assertFalse(stage.isShowing()); manager.ended(5, 1); return null; });
            awaitFx(() -> root() instanceof MeetingEndedScreen);
            assertEquals(0, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
            assertEquals(1, count("SELECT COUNT(*) FROM Tep_Dinh_Kem"));
            fx(() -> { manager.logout(); return null; });
            awaitFx(() -> root() instanceof AuthScreen);
            assertEquals(1, count("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NOT NULL"));
        }
    }

    @Test
    void logoutFromJoinedRoomLeavesViaAuthChannelBeforeRevokingSession() throws Exception {
        createRoomTables(); registrationRelease.countDown();
        AuthClient auth = new AuthClient("localhost", server.port(), clientTls, 5000);
        assertTrue(auth.register("LogoutRoomUser", "Người đăng xuất", null, PASSWORD).success());
        AuthResponse login = auth.login("LogoutRoomUser", PASSWORD);
        var created = auth.createRoom(login.token(), "Phòng đăng xuất", "1234", 10);
        assertTrue(created.success());
        fx(() -> {
            manager.acceptSession(login); manager.preJoin(created.room());
            assertTrue(manager.isRoomJoined()); manager.logout();
            assertTrue(manager.logoutPendingProperty().get()); return null;
        });
        awaitFx(() -> root() instanceof AuthScreen && !manager.logoutPendingProperty().get());
        assertEquals(0, count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NOT NULL"));
        assertEquals(List.of("REGISTER", "LOGIN", "CREATE_ROOM", "LEAVE_ROOM", "LOGOUT"), actions);
        fx(() -> { assertFalse(manager.isRoomJoined()); assertNull(manager.roomInfo()); assertNull(manager.sessionToken()); return null; });
    }

    @Test void accountSettingsSavesAllFieldsUpdatesSidebarAndPreservesSession() throws Exception {
        AuthClient client = accountClient();
        var login = accountLogin(client);
        fx(() -> {
            manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout();
            assertTrue(node("account-username", TextField.class).isEditable());
            assertFalse(node("account-forgot-password", Button.class).isDisabled());
            assertFalse(node("account-verify-email", Button.class).isDisabled());
            assertTrue(node("account-change-password", Button.class).isVisible());
            assertTrue(node("account-change-password", Button.class).isManaged());
            assertTrue(node("account-change-password", Button.class).isDisabled());
            node("account-username", TextField.class).setText("NewLogin");
            node("account-display-name", TextField.class).setText("Tên mới 日本語");
            node("account-email", TextField.class).setText("updated@example.com");
            node("account-save", Button.class).fire(); node("account-save", Button.class).fire();
            assertTrue(node("account-save", Button.class).isDisabled());
            assertTrue(node("account-forgot-password", Button.class).isDisabled()); return null;
        });
        awaitFx(() -> "NewLogin".equals(manager.username()) && !node("account-save", Button.class).isDisabled());
        fx(() -> {
            assertEquals("Tên mới 日本語", manager.user()); assertEquals("updated@example.com", manager.email());
            assertEquals(login.token(), manager.sessionToken());
            assertTrue(hasLabel("Đã lưu thông tin tài khoản."));
            assertTrue(node("account-change-password", Button.class).isVisible());
            assertTrue(node("account-change-password", Button.class).isDisabled());
            assertTrue(nodes(root()).filter(Label.class::isInstance).map(Label.class::cast)
                    .anyMatch(label -> label.getStyleClass().contains("user-name") && "Tên mới 日本語".equals(label.getText())));
            return null;
        });
        assertEquals(1, actions.stream().filter("UPDATE_PROFILE"::equals).count());
        assertEquals("INVALID_CREDENTIALS", client.login("AccountUser", PASSWORD).code());
        assertEquals("updated@example.com", client.login("NewLogin", PASSWORD).user().email());
        snapshotAccount();
    }

    @Test void accountSettingsReportsValidationAndDuplicatesAndAllowsRetry() throws Exception {
        AuthClient client = accountClient(); var login = accountLogin(client);
        assertTrue(client.register("TakenUser", "Taken", "taken@example.com", PASSWORD).success());
        fx(() -> {
            manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout();
            node("account-username", TextField.class).setText("bad name"); node("account-save", Button.class).fire();
            assertTrue(hasLabel("Tên đăng nhập không được chứa khoảng trắng."));
            assertFalse(node("account-save", Button.class).isDisabled());
            node("account-username", TextField.class).setText("TakenUser"); node("account-save", Button.class).fire(); return null;
        });
        awaitFx(() -> hasLabel("Tên đăng nhập đã được sử dụng."));
        fx(() -> {
            assertEquals("AccountUser", manager.username());
            node("account-username", TextField.class).setText("AccountUser");
            node("account-email", TextField.class).setText("taken@example.com"); node("account-save", Button.class).fire(); return null;
        });
        awaitFx(() -> hasLabel("Email đã được sử dụng."));
        fx(() -> { node("account-email", TextField.class).clear(); node("account-save", Button.class).fire(); return null; });
        awaitFx(() -> hasLabel("Đã lưu thông tin tài khoản."));
        assertEquals("", fx(manager::email));
    }

    @Test void passwordDialogValidatesConfirmationHandlesWrongPasswordAndChangesCredentials() throws Exception {
        AuthClient client = accountClient(); var login = accountLogin(client);
        var another = client.login("AccountUser", PASSWORD);
        var dialog = fx(() -> {
            // A trusted verified snapshot models the future OTP result; production never grants it locally.
            var user = login.user();
            manager.acceptSession(AuthResponse.ok(login.message(), new common.auth.AuthUser(user.id(), user.username(),
                    user.displayName(), user.email(), true), login.token(), login.expiresAt()));
            return new client.UI.dialogs.ChangePasswordDialog(manager);
        });
        String next = "New-password-456!";
        try {
            fx(() -> {
                ((PasswordField) dialog.getDialogPane().lookup("#password-current")).setText(PASSWORD);
                ((PasswordField) dialog.getDialogPane().lookup("#password-new")).setText(next);
                ((PasswordField) dialog.getDialogPane().lookup("#password-confirm")).setText("mismatch");
                passwordSubmit(dialog).fire();
                assertEquals("Mật khẩu xác nhận chưa khớp.", ((Label) dialog.getDialogPane().lookup("#password-status")).getText());
                assertFalse(actions.contains("CHANGE_PASSWORD"));
                ((PasswordField) dialog.getDialogPane().lookup("#password-current")).setText("Wrong-password-123!");
                ((PasswordField) dialog.getDialogPane().lookup("#password-confirm")).setText(next);
                passwordSubmit(dialog).fire(); return null;
            });
            awaitFx(() -> "Mật khẩu hiện tại không đúng.".equals(((Label) dialog.getDialogPane().lookup("#password-status")).getText()));
            fx(() -> {
                assertEquals("", ((PasswordField) dialog.getDialogPane().lookup("#password-current")).getText());
                ((PasswordField) dialog.getDialogPane().lookup("#password-current")).setText(PASSWORD);
                passwordSubmit(dialog).fire(); return null;
            });
            awaitFx(() -> ((Label) dialog.getDialogPane().lookup("#password-status")).getText().startsWith("Đã đổi mật khẩu."));
            fx(() -> {
                for (String id : List.of("password-current", "password-new", "password-confirm"))
                    assertEquals("", ((PasswordField) dialog.getDialogPane().lookup("#" + id)).getText());
                assertTrue(passwordSubmit(dialog).isDisabled());
                assertEquals(login.token(), manager.sessionToken()); return null;
            });
            assertEquals("INVALID_CREDENTIALS", client.login("AccountUser", PASSWORD).code());
            assertTrue(client.login("AccountUser", next).success());
            assertEquals("SESSION_EXPIRED", client.updateProfile(another.token(), "AccountUser", "Rejected", null).code());
            assertTrue(client.updateProfile(login.token(), "AccountUser", "Accepted", null).success());
        } finally { fx(() -> { dialog.close(); return null; }); }
    }

    @Test void expiredAccountSessionReturnsToLoginAndForgotPasswordOpensTheOtpForm() throws Exception {
        AuthClient client = accountClient(); var login = accountLogin(client);
        fx(() -> { manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout(); return null; });
        assertTrue(client.logout(login.token()).success());
        fx(() -> { node("account-save", Button.class).fire(); return null; });
        awaitFx(() -> root() instanceof AuthScreen);
        fx(() -> {
            assertNull(manager.sessionToken());
            root().applyCss(); root().layout();
            Button forgot = node("auth-forgot-password", Button.class);
            assertFalse(forgot.isDisabled());
            long requestsBefore = actions.size();
            forgot.fire();
            assertTrue(root() instanceof AuthScreen);
            assertNotNull(node("auth-email", TextField.class));
            assertNotNull(button("Gửi mã OTP"));
            assertNull(root().lookup("#auth-password"));
            assertEquals(requestsBefore, actions.size(), "Opening recovery must wait for the user to submit an email");
            return null;
        });
    }

    @Test void passwordButtonStaysVisibleAndIsDisabledUntilEmailIsVerified() throws Exception {
        var login = accountLogin(accountClient());
        fx(() -> {
            manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout();
            Button password = node("account-change-password", Button.class);
            Button otp = node("account-verify-email", Button.class);
            assertTrue(otp.isVisible()); assertFalse(otp.isDisabled());
            assertSame(node("account-email", TextField.class).getParent(), otp.getParent());
            assertTrue(password.isVisible()); assertTrue(password.isManaged()); assertTrue(password.isDisabled());
            password.fire();
            assertFalse(manager.emailVerified()); assertFalse(actions.contains("CHANGE_PASSWORD"));

            var user = login.user();
            manager.updateAccount(new common.auth.AuthUser(user.id(), user.username(), user.displayName(), user.email(), true));
            assertTrue(otp.isDisabled());
            assertTrue(password.isVisible()); assertTrue(password.isManaged()); assertFalse(password.isDisabled());
            node("account-email", TextField.class).setText("new@example.com");
            assertTrue(password.isVisible()); assertTrue(password.isManaged()); assertTrue(password.isDisabled());
            node("account-email", TextField.class).setText(user.email());
            assertTrue(password.isVisible()); assertFalse(password.isDisabled());
            manager.updateAccount(user);
            assertTrue(password.isVisible()); assertTrue(password.isDisabled());
            return null;
        });
    }

    private AuthClient accountClient() { return new AuthClient("localhost", server.port(), clientTls, 5000); }
    private Button passwordSubmit(client.UI.dialogs.ChangePasswordDialog dialog) {
        return (Button) dialog.getDialogPane().lookupButton(dialog.getDialogPane().getButtonTypes().getFirst());
    }
    private AuthResponse accountLogin(AuthClient client) throws Exception {
        registrationRelease.countDown();
        assertTrue(client.register("AccountUser", "Tên ban đầu", "account@example.com", PASSWORD).success());
        return client.login("AccountUser", PASSWORD);
    }
    private <T extends Node> T node(String id, Class<T> type) {
        return type.cast(nodes(root()).filter(node -> id.equals(node.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing node: " + id)));
    }
    private void snapshotAccount() throws Exception {
        WritableImage image = fx(() -> {
            Parent view = root(); view.resize(1280, 800); view.setOpacity(1); view.applyCss(); view.layout();
            return view.snapshot(new SnapshotParameters(), null);
        });
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path output = Path.of("target", "qa", "account-settings.png"); Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(png, "png", output.toFile()));
    }

    private void createRoomTables() throws Exception {
        String schema = Files.readString(Path.of("database/schema.sql"))
                .replaceAll("(?m)^\\s*--.*$", "")
                .replaceFirst("(?s)CREATE DATABASE IF NOT EXISTS Mini_Meeting_Room.*?;", "")
                .replace("USE Mini_Meeting_Room;", "").replace("ENGINE = InnoDB", "");
        for (String sql : schema.split(";")) if (!sql.isBlank()
                && !sql.strip().startsWith("CREATE TABLE Nguoi_Dung") && !sql.strip().startsWith("CREATE TABLE Phien_Dang_Nhap")) execute(sql);
    }

    private void snapshotMeeting() throws Exception {
        WritableImage image = fx(() -> {
            Parent view = root(); view.resize(1280, 800); view.setOpacity(1); view.applyCss(); view.layout();
            return view.snapshot(new SnapshotParameters(), null);
        });
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path output = Path.of("target", "qa", "file-tab.png"); Files.createDirectories(output.getParent());
        assertTrue(ImageIO.write(png, "png", output.toFile()));
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
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(AuthFlowTest::nodes))
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
