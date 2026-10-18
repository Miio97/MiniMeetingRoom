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
    private final AtomicBoolean failNextLogout = new AtomicBoolean();
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
                (email, purpose, code) -> codes.put(email + ":" + purpose, code), SessionRevocationListener.NONE);
        AuthService accounts = new AuthService(this::connection, passwords, clock, Duration.ofHours(24),
                SessionRevocationListener.NONE, otp);
        server = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), request -> {
            if ("LIST_ROOMS".equals(request.action())) return AuthResponse.roomsOk("Đã tải danh sách phòng.", java.util.List.of());
            if ("LOGOUT".equals(request.action())) {
                logoutAttempts.incrementAndGet();
                if (failNextLogout.getAndSet(false)) return AuthResponse.error("TEST_RETRY", "Chưa thể thu hồi phiên, hãy thử lại.");
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
        awaitFx(() -> root() instanceof AuthScreen && root().lookup("#auth-email") != null);
        fx(() -> {
            assertNull(manager.sessionToken()); assertFalse(manager.emailVerified());
            assertNull(root().lookup("#auth-password"));
            assertNull(codes.get(EMAIL + ":RESET"), "Opening recovery must not send an email");
            field("auth-email").setText(EMAIL); button("auth-submit").fire(); return null;
        });
        assertEquals("SESSION_EXPIRED", client.updateProfile(originalToken, "VerifiedMember", "Rejected", EMAIL).code());
        awaitFx(() -> root().lookup("#auth-new-password") != null);
        fx(() -> {
            field("auth-otp").setText(codes.get(EMAIL + ":RESET")); field("auth-new-password").setText("Recovered-password-789!");
            field("auth-confirm-password").setText("Recovered-password-789!"); button("auth-submit").fire(); return null;
        });
        awaitFx(() -> root().lookup("#auth-password") != null && hasLabel("Đổi mật khẩu thành công."));
        assertEquals("SESSION_EXPIRED", client.updateProfile(beforeReset.token(), "VerifiedMember", "Rejected", EMAIL).code());
        assertEquals("INVALID_CREDENTIALS", client.login("VerifiedMember", "Changed-password-456!").code());
        AuthResponse recovered = client.login("VerifiedMember", "Recovered-password-789!");
        assertTrue(recovered.success()); assertTrue(recovered.user().emailVerified());
    }

    @Test void failedSettingsLogoutPreservesTheSessionAndRebuildsAWorkingRecoveryButtonForRetry() throws Exception {
        AuthResponse registration = client.register("RetryMember", "Thành viên thử lại", EMAIL, PASSWORD);
        assertTrue(registration.success()); assertEquals("OTP_REQUIRED", registration.code());
        assertTrue(client.verifyRegistration(EMAIL, codes.get(EMAIL + ":REGISTER"), registration.registrationChallenge()).success());
        AuthResponse login = client.login("RetryMember", PASSWORD); assertTrue(login.success());
        Parent previousSettings = fx(() -> {
            manager.acceptSession(login); manager.settingsAccount(); root().applyCss(); root().layout();
            Parent previous = root(); failNextLogout.set(true); button("account-forgot-password").fire();
            assertTrue(manager.logoutPendingProperty().get()); assertTrue(root().isDisabled()); return previous;
        });
        awaitFx(() -> root() instanceof SettingsScreen && root() != previousSettings
                && !manager.logoutPendingProperty().get() && logoutErrorWindow() != null);
        fx(() -> {
            assertEquals(login.token(), manager.sessionToken()); assertTrue(manager.emailVerified());
            assertFalse(root().isDisabled()); assertFalse(button("account-forgot-password").isDisabled());
            Stage error = logoutErrorWindow(); assertNotNull(error);
            DialogPane pane = nodes(error.getScene().getRoot()).filter(DialogPane.class::isInstance)
                    .map(DialogPane.class::cast).findFirst().orElseThrow();
            assertTrue(pane.getContentText().contains("hãy thử lại"));
            ((Button) pane.lookupButton(ButtonType.OK)).fire(); return null;
        });
        assertEquals(1, logoutAttempts.get());
        assertTrue(client.updateProfile(login.token(), "RetryMember", "Thành viên thử lại", EMAIL).success(),
                "The failed logout must preserve the server session");
        assertNull(codes.get(EMAIL + ":RESET"));
        fx(() -> { button("account-forgot-password").fire(); return null; });
        awaitFx(() -> root() instanceof AuthScreen && root().lookup("#auth-email") != null);
        fx(() -> {
            assertNull(manager.sessionToken()); assertFalse(manager.emailVerified()); assertNull(root().lookup("#auth-password"));
            assertFalse(button("auth-submit").isDisabled()); assertNull(logoutErrorWindow()); return null;
        });
        assertEquals(2, logoutAttempts.get());
        assertEquals("SESSION_EXPIRED", client.updateProfile(login.token(), "RetryMember", "Rejected", EMAIL).code());
        assertNull(codes.get(EMAIL + ":RESET"), "Recovery must wait for an explicit email submission after retry");
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
