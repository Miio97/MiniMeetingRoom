package client.UI;

import client.UI.dialogs.ChangePasswordDialog;
import client.UI.screens.AuthScreen;
import client.UI.screens.LobbyScreen;
import client.UI.screens.SettingsScreen;
import client.auth.AuthClient;
import common.auth.AuthResponse;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import server.auth.AuthService;
import server.auth.EmailOtpService;
import server.auth.PasswordHasher;
import server.auth.SessionRevocationListener;
import support.FxTestRuntime;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real OTP verification, TLS and account controls, with delivery captured only inside this fixture. */
@Timeout(45)
class OtpAccountSettingsUiTest {
    private static final String EMAIL = "verified@example.com", PASSWORD = "Initial-password-123!";
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private final Map<String, String> codes = new ConcurrentHashMap<>();
    private final AtomicBoolean failNextResetMail = new AtomicBoolean();
    private final AtomicInteger logoutAttempts = new AtomicInteger();
    private String database;
    private AuthServer server;
    private AuthClient client;
    private SceneManager manager;
    private Stage stage;
    private ChangePasswordDialog passwordDialog;

    @BeforeAll @Timeout(60)
    static void runtime() throws Exception {
        tls = RoomTestFixture.certificates(temporary); FxTestRuntime.start();
    }

    @BeforeEach void setup() throws Exception {
        database = "jdbc:h2:mem:otp_account_ui_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("""
                CREATE TABLE Nguoi_Dung (
                  Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                  Ten_Dang_Nhap VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                  Mat_Khau_Bam VARCHAR(255) NOT NULL, Ten_Hien_Thi VARCHAR(100) NOT NULL,
                  Email VARCHAR_IGNORECASE(255) UNIQUE, Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG',
                  Lan_Dang_Nhap_Cuoi TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                  Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,
                  Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                  Token_Bam VARCHAR(255) NOT NULL UNIQUE, Ngay_Tao TIMESTAMP NOT NULL,
                  Het_Han_Luc TIMESTAMP NOT NULL, Thu_Hoi_Luc TIMESTAMP NULL)
                """);
        String migration = Files.readString(Path.of("database/migrations/002-email-otp.sql"))
                .replace(" COLLATE utf8mb4_bin", "")
                .replaceAll("(?m)\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;", ");");
        try (Connection connection = connection()) { RunScript.execute(connection, new StringReader(migration)); }
        PasswordHasher passwords = new PasswordHasher(); Clock clock = Clock.systemUTC();
        EmailOtpService otp = new EmailOtpService(this::connection, passwords, clock,
                (email, purpose, code) -> {
                    if ("RESET".equals(purpose) && failNextResetMail.getAndSet(false)) throw new server.auth.OtpMailSender.MailDeliveryException();
                    codes.put(email + ":" + purpose, code);
                }, SessionRevocationListener.NONE);
        AuthService accounts = new AuthService(this::connection, passwords, clock, Duration.ofHours(24),
                SessionRevocationListener.NONE, otp);
        server = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), request -> {
            if ("LIST_ROOMS".equals(request.action())) return AuthResponse.roomsOk("Đã tải danh sách phòng.", java.util.List.of());
            if ("LOGOUT".equals(request.action())) {
                logoutAttempts.incrementAndGet();
            }
            return accounts.handle(request);
        });
        server.start(); client = new AuthClient("localhost", server.port(), tls.client(), 5000);
        fx(() -> {
            stage = new Stage(); manager = new SceneManager(stage, client); manager.auth();
            root().applyCss(); root().layout(); assertFalse(stage.isShowing()); return null;
        });
    }

    @AfterEach void cleanup() throws Exception {
        try {
            if (manager != null) fx(() -> {
                if (passwordDialog != null) passwordDialog.close();
                for (Window window : java.util.List.copyOf(Window.getWindows())) {
                    if (window instanceof Stage dialog && dialog.getOwner() == stage) dialog.hide();
                }
                manager.shutdown(); stage.hide(); return null;
            });
        } finally {
            if (server != null) server.close();
            if (database != null) execute("DROP ALL OBJECTS");
        }
    }

    @Test void verifiedOtpRegistrationEnablesAccountPasswordChangeAndSettingsRecoveryRevokesSessions() throws Exception {
        fx(() -> {
            nodes(root()).filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button -> "Đăng ký".equals(button.getText())).findFirst().orElseThrow().fire();
            field("auth-username").setText("VerifiedMember"); field("auth-display-name").setText("Thành viên OTP");
            field("auth-email").setText(EMAIL); field("auth-password").setText(PASSWORD);
            field("auth-confirm-password").setText(PASSWORD); button("auth-submit").fire(); return null;
        });
        awaitFx(() -> root().lookup("#auth-otp") != null);
        assertEquals(0, count("SELECT COUNT(*) FROM Nguoi_Dung"));
        fx(() -> { field("auth-otp").setText(codes.get(EMAIL + ":REGISTER")); button("auth-submit").fire(); return null; });
        awaitFx(() -> root().lookup("#auth-password") != null && hasLabel("Tạo tài khoản thành công."));
        fx(() -> { field("auth-password").setText(PASSWORD); button("auth-submit").fire(); return null; });
        awaitFx(() -> root() instanceof LobbyScreen);
        String originalToken = fx(manager::sessionToken);
        AuthResponse anotherSession = client.login("VerifiedMember", PASSWORD);
        assertTrue(anotherSession.success()); assertTrue(anotherSession.user().emailVerified());
        fx(() -> {
            assertTrue(manager.emailVerified()); manager.settingsAccount(); root().applyCss(); root().layout();
            assertFalse(button("account-change-password").isDisabled());
            field("account-email").setText("unsaved@example.com"); assertTrue(button("account-change-password").isDisabled());
            field("account-email").setText(EMAIL); assertFalse(button("account-change-password").isDisabled());
            field("account-display-name").setText("Tên mới đã xác thực"); button("account-save").fire(); return null;
        });
        awaitFx(() -> hasLabel("Đã lưu thông tin tài khoản.") && !button("account-save").isDisabled());
        fx(() -> {
            assertTrue(manager.emailVerified()); assertEquals(originalToken, manager.sessionToken());
            assertFalse(button("account-change-password").isDisabled());
            passwordDialog = new ChangePasswordDialog(manager);
            dialogField("password-current").setText(PASSWORD); dialogField("password-new").setText("Changed-password-456!");
            dialogField("password-confirm").setText("Changed-password-456!");
            passwordSubmit().fire(); return null;
        });
        awaitFx(() -> ((Label) passwordDialog.getDialogPane().lookup("#password-status")).getText().startsWith("Đã đổi mật khẩu."));
        assertEquals("SESSION_EXPIRED", client.updateProfile(anotherSession.token(), "VerifiedMember", "Rejected", EMAIL).code());
        assertTrue(client.updateProfile(originalToken, "VerifiedMember", "Tên mới đã xác thực", EMAIL).success());
        AuthResponse beforeReset = client.login("VerifiedMember", "Changed-password-456!"); assertTrue(beforeReset.success());
        fx(() -> {
            passwordDialog.close();
            assertFalse(button("account-forgot-password").isDisabled()); button("account-forgot-password").fire();
            return null;
        });
        awaitFx(() -> recoveryWindow() != null);
        fx(() -> {
            assertTrue(root() instanceof SettingsScreen); assertEquals(originalToken, manager.sessionToken());
            assertFalse(manager.logoutPendingProperty().get()); assertFalse(root().isDisabled());
            assertNull(codes.get(EMAIL + ":RESET"), "Opening recovery must not send an email");
            recoveryButton("account-reset-send").fire(); return null;
        });
        awaitFx(() -> codes.get(EMAIL + ":RESET") != null && recoveryStatus().startsWith("Đã gửi mã OTP"));
        fx(() -> {
            recoveryField("account-reset-code").setText(codes.get(EMAIL + ":RESET"));
            recoveryField("account-reset-password").setText("Recovered-password-789!");
            recoveryField("account-reset-confirm").setText("Mismatch-password-789!");
            recoveryButton("account-reset-submit").fire(); assertEquals("Mật khẩu xác nhận chưa khớp.", recoveryStatus());
            recoveryField("account-reset-confirm").setText("Recovered-password-789!");
            recoveryButton("account-reset-submit").fire(); return null;
        });
        awaitFx(() -> recoveryStatus().startsWith("Đặt lại mật khẩu thành công."));
        snapshotDialog(fx(this::recoveryWindow), "account-password-recovery.png");
        fx(() -> {
            assertTrue(root() instanceof SettingsScreen); assertEquals(originalToken, manager.sessionToken()); assertTrue(manager.emailVerified());
            for (String id : java.util.List.of("account-reset-code", "account-reset-password", "account-reset-confirm"))
                assertTrue(recoveryField(id).getText().isEmpty());
            assertTrue(recoveryButton("account-reset-submit").isDisabled()); closeDialog(recoveryWindow());
            assertFalse(button("account-save").isDisabled()); return null;
        });
        assertEquals(0, logoutAttempts.get());
        assertTrue(client.updateProfile(originalToken, "VerifiedMember", "Tên mới đã xác thực", EMAIL).success());
        assertEquals("SESSION_EXPIRED", client.updateProfile(beforeReset.token(), "VerifiedMember", "Rejected", EMAIL).code());
        assertEquals("INVALID_CREDENTIALS", client.login("VerifiedMember", "Changed-password-456!").code());
        AuthResponse recovered = client.login("VerifiedMember", "Recovered-password-789!");
        assertTrue(recovered.success()); assertTrue(recovered.user().emailVerified());
    }

    @Test void failedRecoveryDeliveryDoesNotLogOutAndTheDialogCanCloseAndReopen() throws Exception {
        AuthResponse registration = client.register("RetryMember", "Thành viên thử lại", EMAIL, PASSWORD);
        assertTrue(registration.success()); assertEquals("OTP_REQUIRED", registration.code());
        assertTrue(client.verifyRegistration(EMAIL, codes.get(EMAIL + ":REGISTER"), registration.registrationChallenge()).success());
        AuthResponse login = client.login("RetryMember", PASSWORD); assertTrue(login.success());
        Parent previousSettings = fx(() -> {
            manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout();
            Parent previous = root(); failNextResetMail.set(true); button("account-forgot-password").fire();
            assertFalse(manager.logoutPendingProperty().get()); assertFalse(root().isDisabled()); return previous;
        });
        awaitFx(() -> recoveryWindow() != null);
        fx(() -> { recoveryButton("account-reset-send").fire(); return null; });
        awaitFx(() -> recoveryStatus().startsWith("Chưa gửi được email OTP"));
        fx(() -> {
            assertEquals(login.token(), manager.sessionToken()); assertTrue(manager.emailVerified());
            assertSame(previousSettings, root());
            assertFalse(root().isDisabled()); assertFalse(button("account-forgot-password").isDisabled());
            closeDialog(recoveryWindow()); return null;
        });
        assertEquals(0, logoutAttempts.get());
        assertTrue(client.updateProfile(login.token(), "RetryMember", "Thành viên thử lại", EMAIL).success(),
                "A delivery failure must preserve the server session");
        assertNull(codes.get(EMAIL + ":RESET"));
        fx(() -> { button("account-forgot-password").fire(); return null; });
        awaitFx(() -> recoveryWindow() != null);
        fx(() -> {
            assertEquals(login.token(), manager.sessionToken()); assertTrue(manager.emailVerified());
            assertSame(previousSettings, root()); assertTrue(recoveryField("account-reset-code").getText().isEmpty());
            assertTrue(recoveryField("account-reset-password").getText().isEmpty()); closeDialog(recoveryWindow()); return null;
        });
        assertEquals(0, logoutAttempts.get());
        assertNull(codes.get(EMAIL + ":RESET"), "Reopening recovery must not automatically send email");
    }

    @Test void existingAccountSavesAndVerifiesItsEmailFromSettingsBeforeChangingPassword() throws Exception {
        var legacy = new AuthService(this::connection, new PasswordHasher(), Clock.systemUTC(), Duration.ofHours(24));
        assertTrue(legacy.handle(new common.auth.AuthRequest("REGISTER", "ExistingMember", PASSWORD, "Thành viên cũ", null, null)).success());
        AuthResponse login = client.login("ExistingMember", PASSWORD); assertTrue(login.success());
        fx(() -> {
            manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout();
            assertFalse(hasLabel("Email (không bắt buộc)")); assertTrue(hasLabel("Email"));
            assertTrue(button("account-change-password").isDisabled());
            assertFalse(button("account-verify-email").isDisabled());
            button("account-forgot-password").fire(); assertTrue(hasLabel("Vui lòng nhập và lưu email"));
            assertNull(recoveryWindow()); assertEquals(login.token(), manager.sessionToken());
            button("account-verify-email").fire(); assertTrue(hasLabel("Vui lòng nhập và lưu email"));
            field("account-email").setText(EMAIL); button("account-verify-email").fire();
            assertTrue(hasLabel("Vui lòng lưu thay đổi email"));
            button("account-forgot-password").fire(); assertTrue(hasLabel("Vui lòng lưu thay đổi email")); assertNull(recoveryWindow());
            button("account-save").fire(); return null;
        });
        awaitFx(() -> hasLabel("Đã lưu thông tin tài khoản.") && !button("account-save").isDisabled());
        fx(() -> { button("account-verify-email").fire(); return null; });
        awaitFx(() -> verificationWindow() != null);
        fx(() -> {
            assertNull(codes.get(EMAIL + ":VERIFY"), "Opening verification must wait for explicit delivery");
            verificationButton("email-verification-submit").fire();
            assertTrue(verificationStatus().contains("6 chữ số"));
            verificationButton("email-verification-send").fire(); return null;
        });
        awaitFx(() -> codes.get(EMAIL + ":VERIFY") != null && verificationStatus().startsWith("Đã gửi mã OTP"));
        String otp = codes.get(EMAIL + ":VERIFY");
        fx(() -> {
            assertTrue(verificationButton("email-verification-send").isDisabled());
            assertTrue(verificationButton("email-verification-send").getText().contains("s)"));
            verificationField().setText("000000".equals(otp) ? "000001" : "000000");
            verificationButton("email-verification-submit").fire(); return null;
        });
        awaitFx(() -> verificationStatus().startsWith("Mã OTP không đúng"));
        fx(() -> { verificationField().setText(otp); verificationButton("email-verification-submit").fire(); return null; });
        awaitFx(() -> manager.emailVerified() && verificationStatus().equals("Email đã được xác thực."));
        snapshotVerificationDialog();
        fx(() -> {
            assertEquals(login.token(), manager.sessionToken()); assertTrue(verificationField().getText().isEmpty());
            assertFalse(button("account-change-password").isDisabled()); assertTrue(button("account-verify-email").isDisabled());
            assertTrue(hasLabel("Email đã xác thực."));
            var dialog = verificationWindow();
            DialogPane pane = (DialogPane) dialog.getScene().getRoot();
            ButtonType close = pane.getButtonTypes().stream().filter(type -> type.getButtonData().isCancelButton()).findFirst().orElseThrow();
            ((Button) pane.lookupButton(close)).fire();
            button("account-change-password").fire(); return null;
        });
        awaitFx(() -> passwordWindow() != null);
        fx(() -> {
            Parent pane = passwordWindow().getScene().getRoot();
            ((TextField) pane.lookup("#password-current")).setText(PASSWORD);
            ((TextField) pane.lookup("#password-new")).setText("Changed-after-verify-123!");
            ((TextField) pane.lookup("#password-confirm")).setText("Changed-after-verify-123!");
            ((Button) pane.lookup("#password-submit")).fire(); return null;
        });
        awaitFx(() -> ((Label) passwordWindow().getScene().getRoot().lookup("#password-status")).getText().startsWith("Đã đổi mật khẩu."));
        assertEquals("INVALID_CREDENTIALS", client.login("ExistingMember", PASSWORD).code());
        assertTrue(client.login("ExistingMember", "Changed-after-verify-123!").user().emailVerified());
    }

    private Stage verificationWindow() { return ownedWindow("Xác thực email"); }
    private Stage passwordWindow() { return ownedWindow("Đổi mật khẩu"); }
    private Stage recoveryWindow() { return ownedWindow("Quên mật khẩu"); }
    private Stage ownedWindow(String title) {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(window -> window.getOwner() == stage && title.equals(window.getTitle())).findFirst().orElse(null);
    }
    private Button verificationButton(String id) { return (Button) verificationWindow().getScene().getRoot().lookup("#" + id); }
    private TextField verificationField() { return (TextField) verificationWindow().getScene().getRoot().lookup("#email-verification-code"); }
    private String verificationStatus() { return ((Label) verificationWindow().getScene().getRoot().lookup("#email-verification-status")).getText(); }
    private Button recoveryButton(String id) { return (Button) recoveryWindow().getScene().getRoot().lookup("#" + id); }
    private TextField recoveryField(String id) { return (TextField) recoveryWindow().getScene().getRoot().lookup("#" + id); }
    private String recoveryStatus() { return ((Label) recoveryWindow().getScene().getRoot().lookup("#account-reset-status")).getText(); }
    private void closeDialog(Stage window) {
        DialogPane pane = (DialogPane) window.getScene().getRoot();
        ButtonType close = pane.getButtonTypes().stream().filter(type -> type.getButtonData().isCancelButton()).findFirst().orElseThrow();
        ((Button) pane.lookupButton(close)).fire();
    }

    private void snapshotVerificationDialog() throws Exception {
        snapshotDialog(fx(this::verificationWindow), "account-email-verification.png");
    }
    private void snapshotDialog(Stage dialog, String filename) throws Exception {
        var snapshot = fx(() -> dialog.getScene().getRoot().snapshot(null, null));
        var png = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++)
            png.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        Path output = Path.of("target/qa", filename); Files.createDirectories(output.getParent());
        javax.imageio.ImageIO.write(png, "png", output.toFile());
    }

    private Parent root() { return stage.getScene().getRoot(); }
    private TextField field(String id) { return (TextField) root().lookup("#" + id); }
    private Button button(String id) { return (Button) root().lookup("#" + id); }
    private TextField dialogField(String id) { return (TextField) passwordDialog.getDialogPane().lookup("#" + id); }
    private Button passwordSubmit() {
        return (Button) passwordDialog.getDialogPane().lookupButton(passwordDialog.getDialogPane().getButtonTypes().getFirst());
    }
    private Stage logoutErrorWindow() {
        return Window.getWindows().stream().filter(Stage.class::isInstance).map(Stage.class::cast)
                .filter(window -> window.getOwner() == stage && "Chưa thể đăng xuất".equals(window.getTitle()))
                .findFirst().orElse(null);
    }
    private boolean hasLabel(String text) {
        return nodes(root()).filter(Label.class::isInstance).map(Label.class::cast)
                .anyMatch(label -> label.isVisible() && label.getText().startsWith(text));
    }
    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent
                ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(OtpAccountSettingsUiTest::nodes))
                : Stream.of(node);
    }
    private Connection connection() throws java.sql.SQLException { return DriverManager.getConnection(database); }
    private void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
    private int count(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getInt(1);
        }
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> future = new FutureTask<>(operation); Platform.runLater(future); return future.get(10, TimeUnit.SECONDS);
    }
    private void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline) {
            if (fx(() -> {
                // Hidden stages do not receive layout pulses after navigation by themselves.
                root().applyCss(); root().layout();
                return condition.getAsBoolean();
            })) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for OTP/account integration UI state");
    }
}
