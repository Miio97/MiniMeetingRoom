package client.UI.screens;

import client.UI.SceneManager;
import client.UI.ThemeManager;
import client.UI.components.*;
import common.auth.AuthValidation;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.css.PseudoClass;
import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AuthScreen extends HBox {
    private final SceneManager manager;
    private final VBox form = new VBox(10);
    private final List<Input> inputs = new ArrayList<>();
    private boolean register;
    private boolean pending;
    private Button submitButton;
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
    private void switchForm(boolean value) {
        if (pending) return;
        clearSecrets();
        register = value; inputs.clear(); form.getChildren().clear();
        loginTab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), !value);
        registerTab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), value);
        addInput("Tên đăng nhập", value ? "Không có khoảng trắng; phân biệt hoa/thường" : "Nhập đúng chữ hoa/thường", false);
        if (value) { addInput("Tên hiển thị", "Ví dụ: Minh Anh", false); addInput("Email (không bắt buộc)", "ban@example.com", false); }
        addInput("Mật khẩu", value ? "8–128 ký tự, gồm a, A, 0 và !" : "Nhập mật khẩu", true);
        if (value) addInput("Nhập lại mật khẩu", "Xác nhận mật khẩu", true);
        submitButton = Ui.button(value ? "Tạo tài khoản" : "Đăng nhập  →", "primary", this::submit); submitButton.setMaxWidth(Double.MAX_VALUE); submitButton.setDefaultButton(true);
        status.setText(""); status.setVisible(false); status.setManaged(false);
        form.getChildren().addAll(submitButton, status);
        FadeTransition fade = new FadeTransition(Duration.millis(200), form); fade.setFromValue(.3); fade.setToValue(1); fade.play();
    }
    private void addInput(String name, String hint, boolean secret) {
        TextField input = secret ? new PasswordField() : new TextField(); input.setPromptText(hint); input.setMaxWidth(Double.MAX_VALUE);
        Label error = Ui.label("", "error-text"); error.setWrapText(true); error.setVisible(false); error.setManaged(false);
        HBox line = Ui.row(8, input); HBox.setHgrow(input, Priority.ALWAYS);
        if (secret) {
            TextField plain = new TextField(); plain.setPromptText(hint); plain.textProperty().bindBidirectional(input.textProperty());
            StackPane stack = new StackPane(input, plain); HBox.setHgrow(stack, Priority.ALWAYS); plain.setVisible(false); plain.setManaged(false);
            Button eye = Ui.button("Hiện", "small-button", () -> { boolean visible = !plain.isVisible(); plain.setVisible(visible); plain.setManaged(visible); input.setVisible(!visible); input.setManaged(!visible); });
            eye.setMinWidth(48); eye.setOnAction(e -> { boolean visible = !plain.isVisible(); plain.setVisible(visible); plain.setManaged(visible); input.setVisible(!visible); input.setManaged(!visible); eye.setText(visible ? "Ẩn" : "Hiện"); });
            line.getChildren().setAll(stack, eye);
            input.getProperties().put("plain", plain);
        }
        input.textProperty().addListener((obs, old, text) -> {
            input.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), false);
            if (input.getProperties().get("plain") instanceof TextField plain) plain.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), false);
            error.setVisible(false); error.setManaged(false);
        });
        form.getChildren().add(Ui.column(4, Ui.label(name, "field-label"), line, error)); inputs.add(new Input(input, error));
    }
    private void submit() {
        if (pending) return;
        status.setVisible(false); status.setManaged(false);
        String username = inputs.getFirst().field.getText();
        String password = inputs.get(register ? 3 : 1).field.getText();
        String usernameError = register ? AuthValidation.registrationUsernameError(username)
                : AuthValidation.loginUsernameError(username);
        String passwordError = register ? AuthValidation.registrationPasswordError(password)
                : AuthValidation.loginPasswordError(password);
        boolean valid = usernameError == null && passwordError == null;
        if (usernameError != null) fail(inputs.getFirst(), usernameError);
        if (passwordError != null) fail(inputs.get(register ? 3 : 1), passwordError);
        String displayName = register ? inputs.get(1).field.getText().strip() : "";
        String email = register ? inputs.get(2).field.getText().strip() : "";
        if (register) {
            valid = validate(inputs.get(1), displayName, 100, "Tên hiển thị") && valid;
            if (email.length() > 255 || (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
                fail(inputs.get(2), "Email chưa đúng định dạng hoặc vượt quá 255 ký tự."); valid = false;
            }
            if (!password.equals(inputs.get(4).field.getText())) { fail(inputs.get(4), "Mật khẩu xác nhận chưa khớp."); valid = false; }
        }
        if (!valid) return;
        boolean registering = register;
        setPending(true);
        manager.runAuthRequest(() -> registering
            ? manager.authClient().register(username, displayName, email, password)
            : manager.authClient().login(username, password), response -> {
                setPending(false);
                if (!response.success()) { showStatus(response.message(), false); return; }
                clearSecrets();
                if (registering) {
                    switchForm(false); inputs.getFirst().field.setText(username);
                    showStatus("Tạo tài khoản thành công. Bạn có thể đăng nhập ngay.", true);
                    inputs.get(1).field.requestFocus();
                } else {
                    if (response.user() == null || response.token() == null || response.token().isBlank()) {
                        showStatus("Máy chủ trả về phiên đăng nhập không hợp lệ. Vui lòng thử lại.", false); return;
                    }
                    manager.acceptSession(response);
                    manager.lobby();
                }
            }, failure -> {
                setPending(false); showStatus(SceneManager.connectionError(failure), false);
            });
    }
    private boolean validate(Input input, String value, int limit, String name) {
        if (value.isBlank()) { fail(input, "Vui lòng điền thông tin này."); return false; }
        if (value.length() > limit) { fail(input, name + " không được vượt quá " + limit + " ký tự."); return false; }
        return true;
    }
    private void setPending(boolean value) {
        pending = value; form.setDisable(value); loginTab.setDisable(value); registerTab.setDisable(value);
        submitButton.setText(value ? "Đang xử lý…" : register ? "Tạo tài khoản" : "Đăng nhập  →");
    }
    private void showStatus(String message, boolean success) {
        status.setText(message == null || message.isBlank() ? "Không thể thực hiện yêu cầu. Vui lòng thử lại." : message);
        status.getStyleClass().setAll(success ? "success-text" : "error-text"); status.setVisible(true); status.setManaged(true);
    }
    public void clearSecrets() {
        for (Input input : inputs) if (input.field instanceof PasswordField) input.field.clear();
    }
    private void fail(Input input, String message) {
        input.field.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
        if (input.field.getProperties().get("plain") instanceof TextField plain) plain.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
        input.error.setText(message); input.error.setVisible(true); input.error.setManaged(true);
    }
    private record Input(TextField field, Label error) { }
}
