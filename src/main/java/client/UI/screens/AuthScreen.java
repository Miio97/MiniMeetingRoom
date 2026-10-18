package client.UI.screens;

import client.UI.SceneManager;
import client.UI.ThemeManager;
import client.UI.components.*;
import common.auth.AuthResponse;
import common.auth.AuthValidation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Existing JavaFX authentication form, including email verification and password recovery. */
public final class AuthScreen extends HBox {
    private enum Step { LOGIN, REGISTER, REGISTER_OTP, RESET_EMAIL, RESET_PASSWORD }
    private final SceneManager manager;
    private final VBox form = new VBox(10);
    private final List<Input> inputs = new ArrayList<>();
    private Step step = Step.LOGIN;
    private boolean pending, disposed;
    private long generation, resendAfter;
    private String submitText = "", pendingEmail = "", pendingUsername = "", pendingRegistrationChallenge = "";
    private Button submitButton, resendButton;
    private Timeline cooldown;
    private final Label status = Ui.label("", "error-text");
    private final Button loginTab, registerTab;

    public AuthScreen(SceneManager manager) {
        getStyleClass().add("auth-screen");
        this.manager = manager;
        VBox brand = new VBox(24); brand.getStyleClass().add("branding"); brand.setPadding(new Insets(48, 40, 40, 40));
        brand.setPrefWidth(430); brand.setMinWidth(430);
        brand.getChildren().addAll(Ui.row(12, IconButton.icon("camera"), Ui.label("mini meeting", "brand-name")), Ui.spacer(),
            Ui.label("Không gian nhỏ.\nÝ tưởng lớn.", "hero-title"), Ui.label("Họp nhóm nhỏ, kết nối thật nhanh", "brand-subtitle"),
            Ui.row(12, IconButton.icon("users"), Ui.label("Cùng nhau, ở bất cứ đâu", "brand-subtitle")),
            Ui.row(12, IconButton.icon("share"), Ui.label("Chia sẻ ý tưởng dễ dàng", "brand-subtitle")), Ui.spacer(),
            Ui.label("MINI MEETING ROOM", "eyebrow"));
        VBox right = new VBox(18); right.setPadding(new Insets(28, 40, 24, 40)); HBox.setHgrow(right, Priority.ALWAYS);
        right.getChildren().addAll(Ui.label("Chào mừng bạn 👋", "title"), Ui.label("Một cuộc trò chuyện hay bắt đầu từ đây.", "muted"));
        loginTab = Ui.button("Đăng nhập", "auth-tab", () -> switchForm(false));
        registerTab = Ui.button("Đăng ký", "auth-tab", () -> switchForm(true));
        HBox tabs = Ui.row(8, loginTab, registerTab); tabs.getStyleClass().add("tab-strip");
        loginTab.setMaxWidth(Double.MAX_VALUE); registerTab.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(loginTab, Priority.ALWAYS); HBox.setHgrow(registerTab, Priority.ALWAYS);
        ScrollPane formScroll = Ui.scroll(form); VBox.setVgrow(formScroll, Priority.ALWAYS);
        right.getChildren().addAll(tabs, formScroll, Ui.row(8, Ui.label("Kết nối bằng tài khoản của bạn", "caption"), Ui.spacer(), ThemeManager.toggleButton()));
        getChildren().addAll(brand, right); switchForm(false);
    }

    private void switchForm(boolean registering) {
        if (pending || disposed) return;
        pendingEmail = ""; pendingUsername = ""; pendingRegistrationChallenge = "";
        beginStep(registering ? Step.REGISTER : Step.LOGIN);
        addInput("Tên đăng nhập", registering ? "Không có khoảng trắng; phân biệt hoa/thường" : "Nhập đúng chữ hoa/thường", false, "auth-username");
        if (registering) {
            addInput("Tên hiển thị", "Ví dụ: Minh Anh", false, "auth-display-name");
            addInput("Email", "ban@example.com", false, "auth-email");
        }
        addInput("Mật khẩu", registering ? "8–128 ký tự, gồm a, A, 0 và !" : "Nhập mật khẩu", true, "auth-password");
        if (registering) addInput("Nhập lại mật khẩu", "Xác nhận mật khẩu", true, "auth-confirm-password");
        finishStep(registering ? "Tạo tài khoản" : "Đăng nhập  →", this::submitCredentials);
        if (!registering) {
            Button forgot = Ui.button("Quên mật khẩu?", "small-button", this::showResetEmail);
            forgot.setId("auth-forgot-password"); form.getChildren().add(forgot);
        }
    }

    private void beginStep(Step next) {
        clearSecrets(); stopCooldown(); generation++;
        step = next; inputs.clear(); form.getChildren().clear(); resendButton = null;
        form.setSpacing(next == Step.RESET_PASSWORD ? 6 : 10);
        boolean registration = next == Step.REGISTER || next == Step.REGISTER_OTP;
        loginTab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), !registration);
        registerTab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), registration);
        status.setText(""); status.setVisible(false); status.setManaged(false);
    }

    private void finishStep(String text, Runnable action) {
        submitText = text;
        submitButton = Ui.button(text, "primary", action); submitButton.setId("auth-submit");
        submitButton.setMaxWidth(Double.MAX_VALUE); submitButton.setDefaultButton(true);
        form.getChildren().addAll(submitButton, status);
        FadeTransition fade = new FadeTransition(Duration.millis(200), form); fade.setFromValue(.3); fade.setToValue(1); fade.play();
    }

    private void addInput(String name, String hint, boolean secret, String id) {
        TextField input = secret ? new PasswordField() : new TextField(); input.setId(id);
        input.setPromptText(hint); input.setMaxWidth(Double.MAX_VALUE);
        Label error = Ui.label("", "error-text"); error.setWrapText(true); error.setVisible(false); error.setManaged(false);
        HBox line = Ui.row(8, input); HBox.setHgrow(input, Priority.ALWAYS);
        if (secret) {
            TextField plain = new TextField(); plain.setId(id + "-visible"); plain.setPromptText(hint); plain.textProperty().bindBidirectional(input.textProperty());
            StackPane stack = new StackPane(input, plain); HBox.setHgrow(stack, Priority.ALWAYS); plain.setVisible(false); plain.setManaged(false);
            Button eye = Ui.button("Hiện", "small-button", () -> { }); eye.setMinWidth(48);
            eye.setOnAction(e -> { boolean visible = !plain.isVisible(); plain.setVisible(visible); plain.setManaged(visible); input.setVisible(!visible); input.setManaged(!visible); eye.setText(visible ? "Ẩn" : "Hiện"); });
            line.getChildren().setAll(stack, eye); input.getProperties().put("plain", plain);
        }
        input.textProperty().addListener((obs, old, text) -> {
            input.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), false);
            if (input.getProperties().get("plain") instanceof TextField plain) plain.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), false);
            error.setVisible(false); error.setManaged(false);
        });
        form.getChildren().add(Ui.column(4, Ui.label(name, "field-label"), line, error)); inputs.add(new Input(input, error));
    }

    private void addOtp() {
        addInput("Mã OTP", "Nhập 6 chữ số trong email", false, "auth-otp");
        inputs.getLast().field.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
    }

    private void submitCredentials() {
        if (pending || disposed) return;
        hideStatus();
        boolean registering = step == Step.REGISTER;
        String username = inputs.getFirst().field.getText();
        String password = inputs.get(registering ? 3 : 1).field.getText();
        String usernameError = registering ? AuthValidation.registrationUsernameError(username) : AuthValidation.loginUsernameError(username);
        String passwordError = registering ? AuthValidation.registrationPasswordError(password) : AuthValidation.loginPasswordError(password);
        boolean valid = usernameError == null && passwordError == null;
        if (usernameError != null) fail(inputs.getFirst(), usernameError);
        if (passwordError != null) fail(inputs.get(registering ? 3 : 1), passwordError);
        String displayName = registering ? inputs.get(1).field.getText().strip() : "";
        String email = registering ? inputs.get(2).field.getText().strip() : "";
        if (registering) {
            valid = validate(inputs.get(1), displayName, 100, "Tên hiển thị") && valid;
            valid = validateEmail(inputs.get(2), email) && valid;
            if (!password.equals(inputs.get(4).field.getText())) { fail(inputs.get(4), "Mật khẩu xác nhận chưa khớp."); valid = false; }
        }
        if (!valid) return;
        request(() -> registering ? manager.authClient().register(username, displayName, email, password)
                : manager.authClient().login(username, password), response -> {
            if (!response.success()) { showStatus(response.message(), false); return; }
            clearSecrets();
            if (registering) {
                if ("OTP_REQUIRED".equals(response.code())) {
                    if (response.registrationChallenge() == null || response.registrationChallenge().isBlank()) {
                        showStatus("Máy chủ trả về yêu cầu xác minh không hợp lệ. Vui lòng đăng ký lại.", false); return;
                    }
                    pendingEmail = email; pendingUsername = username; pendingRegistrationChallenge = response.registrationChallenge();
                    showRegistrationOtp(); startCooldown();
                    showStatus(response.message(), true);
                } else registrationComplete(username); // Compatibility with explicitly injected legacy servers.
            } else {
                if (response.user() == null || response.token() == null || response.token().isBlank()) {
                    showStatus("Máy chủ trả về phiên đăng nhập không hợp lệ. Vui lòng thử lại.", false); return;
                }
                manager.acceptSession(response); manager.lobby();
            }
        });
    }

    private void showRegistrationOtp() {
        beginStep(Step.REGISTER_OTP);
        description("Xác minh email", "Nhập mã được gửi đến " + pendingEmail + ". Tài khoản được tạo sau khi xác minh thành công.");
        addOtp();
        finishStep("Xác minh và tạo tài khoản", () -> {
            if (!validateOtp(inputs.getFirst())) return;
            String otp = inputs.getFirst().field.getText(); String email = pendingEmail; String username = pendingUsername;
            String challenge = pendingRegistrationChallenge;
            request(() -> manager.authClient().verifyRegistration(email, otp, challenge), response -> {
                if (response.success()) registrationComplete(username); else showStatus(response.message(), false);
            });
        });
        String resendEmail = pendingEmail, resendChallenge = pendingRegistrationChallenge;
        addResend(() -> manager.authClient().resendRegistrationOtp(resendEmail, resendChallenge));
        addBack("Quay lại đăng ký", () -> switchForm(true));
    }

    private void registrationComplete(String username) {
        switchForm(false); inputs.getFirst().field.setText(username);
        showStatus("Tạo tài khoản thành công. Bạn có thể đăng nhập ngay.", true); inputs.get(1).field.requestFocus();
    }

    /** Open the existing recovery form after the manager has revoked any authenticated session. */
    public void showPasswordReset() {
        showResetEmail();
    }

    private void showResetEmail() {
        if (pending || disposed) return;
        pendingEmail = ""; pendingUsername = ""; pendingRegistrationChallenge = ""; beginStep(Step.RESET_EMAIL);
        description("Quên mật khẩu", "Nhập email đã đăng ký để nhận mã đặt lại mật khẩu.");
        addInput("Email", "ban@example.com", false, "auth-email");
        finishStep("Gửi mã OTP", () -> {
            String email = inputs.getFirst().field.getText().strip();
            if (!validateEmail(inputs.getFirst(), email)) return;
            request(() -> manager.authClient().requestPasswordReset(email), response -> {
                if (!response.success()) { showStatus(response.message(), false); return; }
                pendingEmail = email; showResetPassword(); startCooldown(); showStatus(response.message(), true);
            });
        });
        addBack("Quay lại đăng nhập", () -> switchForm(false));
    }

    private void showResetPassword() {
        beginStep(Step.RESET_PASSWORD);
        description("Đặt lại mật khẩu", "OTP cho " + pendingEmail + ". Kiểm tra cả thư rác.");
        addOtp();
        addInput("Mật khẩu mới", "8–128 ký tự, gồm a, A, 0 và !", true, "auth-new-password");
        addInput("Nhập lại mật khẩu mới", "Xác nhận mật khẩu mới", true, "auth-confirm-password");
        finishStep("Đổi mật khẩu", () -> {
            boolean valid = validateOtp(inputs.getFirst());
            String password = inputs.get(1).field.getText();
            String passwordError = AuthValidation.registrationPasswordError(password);
            if (passwordError != null) { fail(inputs.get(1), passwordError); valid = false; }
            if (!password.equals(inputs.get(2).field.getText())) { fail(inputs.get(2), "Mật khẩu xác nhận chưa khớp."); valid = false; }
            if (!valid) return;
            String otp = inputs.getFirst().field.getText(); String email = pendingEmail;
            request(() -> manager.authClient().resetPassword(email, otp, password), response -> {
                if (!response.success()) { showStatus(response.message(), false); return; }
                switchForm(false); showStatus("Đổi mật khẩu thành công. Hãy đăng nhập bằng mật khẩu mới.", true);
            });
        });
        String resendEmail = pendingEmail;
        addResend(() -> manager.authClient().requestPasswordReset(resendEmail));
        Button changeEmail = Ui.button("Đổi email", "small-button", this::showResetEmail);
        Button backToLogin = Ui.button("Quay lại đăng nhập", "small-button", () -> switchForm(false));
        form.getChildren().remove(resendButton);
        form.getChildren().add(Ui.row(6, resendButton, changeEmail, backToLogin));
    }

    private void description(String title, String detail) {
        Label body = Ui.label(detail, "muted"); body.setWrapText(true);
        form.getChildren().addAll(Ui.label(title, "field-label"), body);
    }
    private void addBack(String text, Runnable action) {
        form.getChildren().add(Ui.button(text, "small-button", action));
    }
    private void addResend(Callable<AuthResponse> operation) {
        resendButton = Ui.button("Gửi lại mã", "small-button", () -> {
            if (pending || disposed || System.nanoTime() < resendAfter) return;
            request(operation, response -> {
                if (response.success()) { inputs.getFirst().field.clear(); startCooldown(); }
                showStatus(response.message(), response.success());
            });
        });
        resendButton.setId("auth-resend-otp"); form.getChildren().add(resendButton);
    }
    private void startCooldown() {
        stopCooldown(); resendAfter = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        updateCooldown(); cooldown = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateCooldown()));
        cooldown.setCycleCount(Timeline.INDEFINITE); cooldown.play();
    }
    private void updateCooldown() {
        if (resendButton == null || disposed) { stopCooldown(); return; }
        long seconds = Math.max(0, (resendAfter - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
        resendButton.setText(seconds > 0 ? "Gửi lại mã (" + seconds + "s)" : "Gửi lại mã");
        resendButton.setDisable(seconds > 0);
        if (seconds == 0) stopCooldown();
    }
    private void stopCooldown() {
        if (cooldown != null) { cooldown.stop(); cooldown = null; }
    }
    private void request(Callable<AuthResponse> operation, Consumer<AuthResponse> success) {
        if (pending || disposed) return;
        hideStatus(); long current = generation; setPending(true);
        manager.runAuthRequest(operation, response -> {
            if (disposed || current != generation) return;
            setPending(false); success.accept(response);
        }, failure -> {
            if (disposed || current != generation) return;
            setPending(false); showStatus(SceneManager.connectionError(failure), false);
        });
    }
    private boolean validateEmail(Input input, String email) {
        if (email.isBlank()) { fail(input, "Vui lòng nhập email để nhận mã OTP."); return false; }
        if (email.length() > 255 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            fail(input, "Email chưa đúng định dạng hoặc vượt quá 255 ký tự."); return false;
        }
        return true;
    }
    private boolean validateOtp(Input input) {
        if (!input.field.getText().matches("[0-9]{6}")) { fail(input, "Mã OTP phải gồm 6 chữ số."); return false; }
        return true;
    }
    private boolean validate(Input input, String value, int limit, String name) {
        if (value.isBlank()) { fail(input, "Vui lòng điền thông tin này."); return false; }
        if (value.length() > limit) { fail(input, name + " không được vượt quá " + limit + " ký tự."); return false; }
        return true;
    }
    private void setPending(boolean value) {
        pending = value; form.setDisable(value); loginTab.setDisable(value); registerTab.setDisable(value);
        submitButton.setText(value ? "Đang xử lý…" : submitText);
    }
    private void hideStatus() { status.setVisible(false); status.setManaged(false); }
    private void showStatus(String message, boolean success) {
        status.setText(message == null || message.isBlank() ? "Không thể thực hiện yêu cầu. Vui lòng thử lại." : message);
        status.getStyleClass().setAll(success ? "success-text" : "error-text"); status.setVisible(true); status.setManaged(true);
    }
    public void clearSecrets() {
        for (Input input : inputs) if (input.field instanceof PasswordField || "auth-otp".equals(input.field.getId())) input.field.clear();
    }
    public void dispose() {
        disposed = true; generation++; clearSecrets(); stopCooldown(); pendingEmail = ""; pendingUsername = ""; pendingRegistrationChallenge = "";
    }
    private void fail(Input input, String message) {
        input.field.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
        if (input.field.getProperties().get("plain") instanceof TextField plain) plain.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
        input.error.setText(message); input.error.setVisible(true); input.error.setManaged(true);
    }
    private record Input(TextField field, Label error) { }
}
