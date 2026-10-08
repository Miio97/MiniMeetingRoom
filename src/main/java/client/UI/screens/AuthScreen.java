package client.UI.screens;

import client.UI.SceneManager;
import client.UI.ThemeManager;
import client.UI.components.*;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
            Ui.label("MINI MEETING ROOM  /  UI PREVIEW", "eyebrow"));
        VBox right = new VBox(18); right.setPadding(new Insets(28, 40, 24, 40)); HBox.setHgrow(right, Priority.ALWAYS);
        right.getChildren().addAll(Ui.label("Chào mừng bạn 👋", "title"), Ui.label("Một cuộc trò chuyện hay bắt đầu từ đây.", "muted"));
        loginTab = Ui.button("Đăng nhập", "auth-tab", () -> switchForm(false));
        registerTab = Ui.button("Đăng ký", "auth-tab", () -> switchForm(true));
        HBox tabs = Ui.row(8, loginTab, registerTab); tabs.getStyleClass().add("tab-strip");
        loginTab.setMaxWidth(Double.MAX_VALUE); registerTab.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(loginTab, Priority.ALWAYS); HBox.setHgrow(registerTab, Priority.ALWAYS);
        ScrollPane formScroll = Ui.scroll(form); VBox.setVgrow(formScroll, Priority.ALWAYS);
        right.getChildren().addAll(tabs, formScroll, Ui.row(8, Ui.label("Bản xem trước • Dữ liệu mô phỏng", "caption"), Ui.spacer(), ThemeManager.toggleButton()));
        getChildren().addAll(brand, right); switchForm(false);
    }
    private void switchForm(boolean value) {
        register = value; inputs.clear(); form.getChildren().clear();
        loginTab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), !value);
        registerTab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), value);
        addInput("Tên đăng nhập", "Nhập tên đăng nhập", false);
        if (value) { addInput("Tên hiển thị", "Ví dụ: Minh Anh", false); addInput("Email", "ban@example.com", false); }
        addInput("Mật khẩu", "Nhập mật khẩu", true);
        if (value) addInput("Nhập lại mật khẩu", "Xác nhận mật khẩu", true);
        Button submit = Ui.button(value ? "Tạo tài khoản" : "Đăng nhập  →", "primary", this::submit); submit.setMaxWidth(Double.MAX_VALUE); submit.setDefaultButton(true);
        form.getChildren().add(submit);
        FadeTransition fade = new FadeTransition(Duration.millis(200), form); fade.setFromValue(.3); fade.setToValue(1); fade.play();
    }
    private void addInput(String name, String hint, boolean secret) {
        TextField input = secret ? new PasswordField() : new TextField(); input.setPromptText(hint); input.setMaxWidth(Double.MAX_VALUE);
        Label error = Ui.label("", "error-text"); error.setVisible(false); error.setManaged(false);
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
        boolean valid = true;
        for (Input i : inputs) if (i.field.getText().isBlank()) { fail(i, "Vui lòng điền thông tin này."); valid = false; }
        if (register && valid) {
            if (!inputs.get(2).field.getText().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) { fail(inputs.get(2), "Email chưa đúng định dạng."); valid = false; }
            if (!inputs.get(3).field.getText().equals(inputs.get(4).field.getText())) { fail(inputs.get(4), "Mật khẩu xác nhận chưa khớp."); valid = false; }
        }
        if (valid) { manager.user(inputs.get(register ? 1 : 0).field.getText().strip()); manager.password(inputs.get(register ? 3 : 1).field.getText()); manager.lobby(); }
    }
    private void fail(Input input, String message) {
        input.field.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
        if (input.field.getProperties().get("plain") instanceof TextField plain) plain.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), true);
        input.error.setText(message); input.error.setVisible(true); input.error.setManaged(true);
    }
    private record Input(TextField field, Label error) { }
}
