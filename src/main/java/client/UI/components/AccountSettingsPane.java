package client.UI.components;

import client.UI.SceneManager;
import client.UI.dialogs.ChangePasswordDialog;
import common.auth.AuthValidation;
import java.util.Objects;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Account forms use the authenticated TLS channel; no database access from the UI. */
public final class AccountSettingsPane extends VBox {
    private final SceneManager manager;
    private final Runnable updated;
    private final TextField username, name, email;
    private final BooleanBinding emailConfirmed;
    private final Label status = Ui.label("", "caption");
    private final Button save = Ui.button("Lưu thay đổi", "primary", this::save);
    private final Button password = Ui.button("Đổi mật khẩu", "secondary", this::password);
    private ChangePasswordDialog passwordDialog;
    private boolean pending, disposed;

    public AccountSettingsPane(SceneManager manager, Runnable updated) {
        super(20);
        this.manager = manager; this.updated = updated;
        getStyleClass().add("card");
        username = field("account-username", manager.username());
        name = field("account-display-name", manager.user());
        email = field("account-email", manager.email());
        email.setMinWidth(0);
        Button verifyEmail = Ui.button("Xác thực email (OTP)", "secondary", () -> { });
        verifyEmail.setId("account-verify-email"); verifyEmail.setDisable(true);
        verifyEmail.setMinWidth(Button.USE_PREF_SIZE);
        verifyEmail.setTooltip(new Tooltip("Chức năng xác thực email bằng OTP chưa được hỗ trợ."));
        HBox emailRow = Ui.row(12, email, verifyEmail); HBox.setHgrow(email, Priority.ALWAYS);
        emailConfirmed = Bindings.createBooleanBinding(() -> manager.emailVerified() && !manager.email().isBlank()
                        && email.getText().strip().equals(manager.email()), manager.emailVerifiedProperty(), email.textProperty());
        password.opacityProperty().bind(Bindings.when(password.disabledProperty()).then(0.55).otherwise(1.0));
        password.setDisable(!emailConfirmed.get());
        emailConfirmed.addListener((observable, before, verified) -> password.setDisable(pending || !verified));
        Label verification = Ui.label("", "caption"); verification.setId("account-email-verification-status");
        verification.textProperty().bind(Bindings.when(emailConfirmed).then("Email đã xác thực.")
                .otherwise("Xác thực email để sử dụng chức năng đổi mật khẩu."));
        save.setId("account-save"); password.setId("account-change-password"); status.setId("account-status");
        Button forgot = Ui.button("Quên mật khẩu", "secondary", () -> { });
        forgot.setId("account-forgot-password"); forgot.setDisable(true);
        forgot.setTooltip(new Tooltip("Chức năng quên mật khẩu chưa được hỗ trợ."));
        FlowPane actions = new FlowPane(12, 12, password, forgot, save);
        getChildren().addAll(Ui.field("Tên đăng nhập", username), Ui.field("Tên hiển thị", name),
                Ui.field("Email (không bắt buộc)", Ui.column(8, emailRow, verification)), actions, status,
                Ui.label("Tên đăng nhập mới sẽ được dùng cho những lần đăng nhập tiếp theo.", "caption"));
    }

    private static TextField field(String id, String value) {
        TextField field = new TextField(value); field.setId(id); field.setMaxWidth(Double.MAX_VALUE); return field;
    }

    private void save() {
        if (pending || disposed) return;
        String nextUsername = username.getText(), nextName = name.getText().strip(), nextEmail = email.getText().strip();
        String error = AuthValidation.profileError(nextUsername, nextName, nextEmail);
        if (error != null) { status(error, false); return; }
        String ownerToken = manager.sessionToken();
        pending(true); status("Đang lưu thông tin…", true);
        manager.runAuthRequest(() -> manager.authClient().updateProfile(ownerToken, nextUsername, nextName, nextEmail), response -> {
            if (!Objects.equals(ownerToken, manager.sessionToken()) || manager.sessionExpired(response)) return;
            if (response.success()) manager.updateAccount(response.user());
            if (disposed) return;
            pending(false);
            if (response.success()) {
                username.setText(manager.username()); name.setText(manager.user()); email.setText(manager.email());
                updated.run();
            }
            status(response.message(), response.success());
        }, failure -> {
            if (disposed || !Objects.equals(ownerToken, manager.sessionToken())) return;
            pending(false); status(SceneManager.connectionError(failure), false);
        });
    }

    private void password() {
        if (disposed || pending || !emailConfirmed.get() || passwordDialog != null && passwordDialog.isShowing()) return;
        passwordDialog = new ChangePasswordDialog(manager);
        passwordDialog.show();
    }

    private void pending(boolean value) {
        pending = value; username.setDisable(value); name.setDisable(value); email.setDisable(value);
        save.setDisable(value); password.setDisable(value || !emailConfirmed.get()); save.setText(value ? "Đang lưu…" : "Lưu thay đổi");
    }

    private void status(String message, boolean success) {
        status.setText(message); status.getStyleClass().setAll(success ? "success-text" : "error-text");
    }

    public void dispose() { disposed = true; emailConfirmed.dispose(); if (passwordDialog != null) passwordDialog.close(); }
}
