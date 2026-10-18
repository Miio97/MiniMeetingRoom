package client.UI;

import client.UI.screens.AuthScreen;
import client.auth.AuthClient;
import common.auth.AuthRequest;
import common.auth.AuthResponse;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import support.FxTestRuntime;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX controls and TLS wire; deterministic responses avoid sending real email in tests. */
@Timeout(30)
class EmailOtpAuthUiTest {
    private static final String PASSWORD = "Ui-password-123!";
    private static final String REGISTRATION_CHALLENGE = "flow-ui-only-original-registration-challenge";
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private final List<AuthRequest> requests = new CopyOnWriteArrayList<>();
    private final CountDownLatch registrationReceived = new CountDownLatch(1);
    private final CountDownLatch registrationRelease = new CountDownLatch(1);
    private AuthServer server;
    private SceneManager manager;
    private Stage stage;
    private volatile boolean omitRegistrationChallenge;

    @BeforeAll @Timeout(60)
    static void runtime() throws Exception {
        tls = RoomTestFixture.certificates(temporary); FxTestRuntime.start();
    }
    @BeforeEach
    void fixture() throws Exception {
        server = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), request -> {
            requests.add(request);
            if ("REGISTER".equals(request.action())) {
                registrationReceived.countDown();
                try {
                    if (!registrationRelease.await(10, TimeUnit.SECONDS)) return AuthResponse.error("TIMEOUT", "Timed out");
                } catch (InterruptedException error) { Thread.currentThread().interrupt(); return AuthResponse.error("INTERRUPTED", "Interrupted"); }
            }
            return switch (request.action()) {
                case "REGISTER" -> new AuthResponse(true, "OTP_REQUIRED", "Hãy kiểm tra email để nhận mã OTP.",
                    null, null, null, null, List.of(), omitRegistrationChallenge ? null : REGISTRATION_CHALLENGE);
                case "RESEND_REGISTRATION_OTP" -> new AuthResponse(true, "OTP_REQUIRED", "Đã gửi lại mã OTP.",
                    null, null, null, null, List.of(), "different-challenge-must-never-replace-original");
                case "REQUEST_PASSWORD_RESET" ->
                    new AuthResponse(true, "OTP_REQUIRED", "Hãy kiểm tra email để nhận mã OTP.", null, null, null);
                case "VERIFY_REGISTRATION", "RESET_PASSWORD" -> "654321".equals(request.otp())
                    ? AuthResponse.ok("Đã xác minh.", null, null, null)
                    : AuthResponse.error("OTP_INVALID", "Mã OTP không đúng hoặc đã hết hạn.");
                default -> AuthResponse.error("INVALID_REQUEST", "Unsupported test action");
            };
        });
        server.start();
        fx(() -> {
            stage = new Stage(); manager = new SceneManager(stage, new AuthClient("localhost", server.port(), tls.client(), 5000));
            manager.auth(); root().applyCss(); root().layout(); assertFalse(stage.isShowing()); return null;
        });
    }
    @AfterEach
    void close() throws Exception {
        registrationRelease.countDown();
        try { if (manager != null) fx(() -> { manager.shutdown(); stage.hide(); return null; }); }
        finally { if (server != null) server.close(); }
    }

    @Test
    void registrationRequiresEmailThenWaitsForOtpAndClearsTheInitialPassword() throws Exception {
        List<PasswordField> oldSecrets = fx(() -> {
            button("Đăng ký").fire(); fillRegistration();
            field("auth-email").clear(); button("Tạo tài khoản").fire();
            assertTrue(hasLabel("Vui lòng nhập email")); assertTrue(requests.isEmpty());
            field("auth-email").setText("member@example.com");
            List<PasswordField> old = nodes(root()).filter(PasswordField.class::isInstance).map(PasswordField.class::cast).toList();
            button("Tạo tài khoản").fire();
            assertTrue(button("Đang xử lý…").isDisabled()); assertTrue(button("Đăng nhập").isDisabled());
            return old;
        });
        assertTrue(registrationReceived.await(5, TimeUnit.SECONDS));
        fx(() -> { assertTrue(root() instanceof AuthScreen); assertNull(manager.authenticatedUser()); return null; });
        registrationRelease.countDown();
        awaitFx(() -> root().lookup("#auth-otp") != null);
        fx(() -> {
            assertTrue(oldSecrets.stream().allMatch(secret -> secret.getText().isEmpty()));
            assertNull(manager.authenticatedUser());
            Button resend = (Button) root().lookup("#auth-resend-otp"); assertTrue(resend.isDisabled()); resend.fire();
            field("auth-otp").setText("123"); button("Xác minh và tạo tài khoản").fire();
            assertTrue(hasLabel("Mã OTP phải gồm 6 chữ số."));
            field("auth-otp").setText("000000"); button("Xác minh và tạo tài khoản").fire(); return null;
        });
        awaitFx(() -> hasLabel("Mã OTP không đúng") && !button("Xác minh và tạo tài khoản").isDisabled());
        fx(() -> { field("auth-otp").clear(); return null; });
        snapshot("auth-register-otp-preview.png");
        fx(() -> {
            // Advance only the local countdown deadline; no production timing hook or minute-long test sleep.
            var deadline = AuthScreen.class.getDeclaredField("resendAfter"); deadline.setAccessible(true); deadline.setLong(root(), 0);
            Button resend = (Button) root().lookup("#auth-resend-otp"); resend.setDisable(false); resend.fire(); return null;
        });
        awaitFx(() -> hasLabel("Đã gửi lại mã OTP.") && !button("Xác minh và tạo tài khoản").isDisabled());
        fx(() -> {
            assertNull(manager.authenticatedUser()); field("auth-otp").setText("654321");
            button("Xác minh và tạo tài khoản").fire(); return null;
        });
        awaitFx(() -> hasLabel("Tạo tài khoản thành công."));
        fx(() -> {
            assertEquals("OtpMember", field("auth-username").getText()); assertTrue(field("auth-password").getText().isEmpty());
            assertNull(manager.authenticatedUser()); assertFalse(stage.isShowing()); return null;
        });
        assertEquals(List.of("REGISTER", "VERIFY_REGISTRATION", "RESEND_REGISTRATION_OTP", "VERIFY_REGISTRATION"), actions());
        assertTrue(requests.stream().filter(request -> !"REGISTER".equals(request.action()))
                .allMatch(request -> REGISTRATION_CHALLENGE.equals(request.registrationChallenge())));
    }

    @Test
    void registrationDoesNotContinueWhenTheServerOmitsTheChallenge() throws Exception {
        omitRegistrationChallenge = true; registrationRelease.countDown();
        fx(() -> { button("Đăng ký").fire(); fillRegistration(); button("Tạo tài khoản").fire(); return null; });
        awaitFx(() -> hasLabel("Máy chủ trả về yêu cầu xác minh không hợp lệ."));
        fx(() -> {
            assertNull(root().lookup("#auth-otp")); assertNotNull(root().lookup("#auth-email"));
            assertTrue(field("auth-password").getText().isEmpty()); assertNull(manager.authenticatedUser()); return null;
        });
        assertEquals(List.of("REGISTER"), actions());
    }

    @Test
    void forgotPasswordValidatesEmailOtpAndPasswordBeforeResettingAndReturningToLogin() throws Exception {
        fx(() -> {
            button("Quên mật khẩu?").fire(); field("auth-email").setText("invalid"); button("Gửi mã OTP").fire();
            assertTrue(hasLabel("Email chưa đúng định dạng")); assertTrue(requests.isEmpty());
            field("auth-email").setText("member@example.com"); button("Gửi mã OTP").fire(); return null;
        });
        awaitFx(() -> root().lookup("#auth-new-password") != null);
        fx(() -> {
            assertTrue(((Button) root().lookup("#auth-resend-otp")).isDisabled());
            field("auth-otp").setText("654321"); field("auth-new-password").setText("weak"); field("auth-confirm-password").setText("weak");
            button("Đổi mật khẩu").fire(); assertTrue(hasLabel("Mật khẩu cần 8–128 ký tự"));
            field("auth-new-password").setText(PASSWORD); button("Đổi mật khẩu").fire();
            assertTrue(hasLabel("Mật khẩu xác nhận chưa khớp."));
            assertEquals(List.of("REQUEST_PASSWORD_RESET"), actions());
            field("auth-confirm-password").setText(PASSWORD); field("auth-otp").setText("000000"); button("Đổi mật khẩu").fire(); return null;
        });
        awaitFx(() -> hasLabel("Mã OTP không đúng") && !button("Đổi mật khẩu").isDisabled());
        fx(() -> { field("auth-otp").clear(); return null; });
        snapshot("auth-reset-password-preview.png");
        List<TextField> oldSecrets = fx(() -> {
            List<TextField> secrets = nodes(root()).filter(TextField.class::isInstance).map(TextField.class::cast).toList();
            field("auth-otp").setText("654321"); button("Đổi mật khẩu").fire(); return secrets;
        });
        awaitFx(() -> hasLabel("Đổi mật khẩu thành công."));
        fx(() -> {
            assertTrue(oldSecrets.stream().allMatch(secret -> secret.getText().isEmpty()));
            assertNotNull(root().lookup("#auth-forgot-password")); assertTrue(field("auth-password").getText().isEmpty());
            assertNull(manager.authenticatedUser()); return null;
        });
        assertEquals(List.of("REQUEST_PASSWORD_RESET", "RESET_PASSWORD", "RESET_PASSWORD"), actions());
    }

    @Test
    void replacingTheAuthScreenClearsSecretsAndIgnoresAnOlderOtpResponse() throws Exception {
        AuthScreen previous = fx(() -> {
            button("Đăng ký").fire(); fillRegistration(); AuthScreen previousView = (AuthScreen) root();
            button("Tạo tài khoản").fire(); return previousView;
        });
        assertTrue(registrationReceived.await(5, TimeUnit.SECONDS));
        AuthScreen replacement = fx(() -> {
            manager.auth(); root().applyCss(); root().layout();
            assertTrue(nodes(previous).filter(PasswordField.class::isInstance).map(PasswordField.class::cast).allMatch(field -> field.getText().isEmpty()));
            return (AuthScreen) root();
        });
        registrationRelease.countDown();
        // Queue a second wire request after the first; authWorker serial ordering ensures its UI callback is delivered afterwards.
        CountDownLatch drained = new CountDownLatch(1);
        fx(() -> {
            manager.runAuthRequest(() -> manager.authClient().requestPasswordReset("probe@example.com"), response -> drained.countDown(), failure -> drained.countDown());
            return null;
        });
        assertTrue(drained.await(8, TimeUnit.SECONDS));
        fx(() -> {
            assertSame(replacement, root()); assertNull(root().lookup("#auth-otp")); assertNotNull(root().lookup("#auth-forgot-password"));
            assertNull(manager.authenticatedUser()); return null;
        });
    }

    private void fillRegistration() {
        field("auth-username").setText("OtpMember"); field("auth-display-name").setText("Thành viên OTP");
        field("auth-email").setText("member@example.com"); field("auth-password").setText(PASSWORD); field("auth-confirm-password").setText(PASSWORD);
    }
    private List<String> actions() { return requests.stream().map(AuthRequest::action).toList(); }
    private Parent root() { return stage.getScene().getRoot(); }
    private TextField field(String id) { return (TextField) root().lookup("#" + id); }
    private Button button(String text) {
        return nodes(root()).filter(Button.class::isInstance).map(Button.class::cast).filter(button -> text.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button: " + text));
    }
    private boolean hasLabel(String text) {
        return nodes(root()).filter(Label.class::isInstance).map(Label.class::cast).anyMatch(label -> label.isVisible() && label.getText().startsWith(text));
    }
    private static Stream<Node> nodes(Node node) {
        return node instanceof Parent parent ? Stream.concat(Stream.of(node), parent.getChildrenUnmodifiable().stream().flatMap(EmailOtpAuthUiTest::nodes)) : Stream.of(node);
    }
    private static <T> T fx(Callable<T> operation) throws Exception {
        FutureTask<T> future = new FutureTask<>(operation); Platform.runLater(future); return future.get(10, TimeUnit.SECONDS);
    }
    private static void awaitFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (fx(condition::getAsBoolean)) return; Thread.sleep(20); }
        fail("Timed out waiting for OTP form state");
    }
    private void snapshot(String filename) throws Exception {
        WritableImage image = fx(() -> {
            Parent view = root(); view.resize(960, 600); view.setOpacity(1); view.applyCss(); view.layout();
            nodes(view).filter(node -> node instanceof javafx.scene.layout.VBox && node.getOpacity() < 1).forEach(node -> node.setOpacity(1));
            return view.snapshot(new SnapshotParameters(), null);
        });
        BufferedImage png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path output = Path.of("target", filename); Files.createDirectories(output.getParent()); assertTrue(ImageIO.write(png, "png", output.toFile()));
    }
}
