package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.components.Ui;

import common.auth.AuthValidation;

import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.Objects;

public final class ChangePasswordDialog extends Dialog<Boolean> {
    private final SceneManager manager;
    private final PasswordField current = field("password-current");
    private final PasswordField next = field("password-new");
    private final PasswordField confirmation = field("password-confirm");
    private final Label status = Ui.label("", "error-text");
    private final Button submit;
    private final VBox form;
    private boolean pending, hidden;

    public ChangePasswordDialog(SceneManager manager) {
        this.manager = manager;
        initOwner(manager.stage());
        setTitle("Đổi mật khẩu");
        setHeaderText("Đặt mật khẩu mới cho tài khoản");
        Ui.style(this);
        status.setId("password-status");
        form =
                Ui.column(
                        12,
                        Ui.field("Mật khẩu hiện tại", current),
                        Ui.field("Mật khẩu mới", next),
                        Ui.field("Nhập lại mật khẩu mới", confirmation),
                        Ui.label(AuthValidation.PASSWORD_REQUIREMENTS, "caption"),
                        Ui.label(
                                "Đổi mật khẩu sẽ đăng xuất các phiên khác và giữ phiên trên máy"
                                        + " này.",
                                "caption"),
                        status);
        form.setPrefWidth(440);
        getDialogPane().setContent(form);
        ButtonType save = new ButtonType("Đổi mật khẩu", ButtonBar.ButtonData.OK_DONE);
        getDialogPane()
                .getButtonTypes()
                .addAll(save, new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE));
        submit = (Button) getDialogPane().lookupButton(save);
        submit.setId("password-submit");
        submit.getStyleClass().add("primary");
        submit.setDisable(!manager.emailVerified());
        submit.addEventFilter(
                ActionEvent.ACTION,
                event -> {
                    event.consume();
                    submit();
                });
        setResultConverter(button -> false);
        setOnHidden(
                event -> {
                    hidden = true;
                    clearSecrets();
                });
    }

    private static PasswordField field(String id) {
        PasswordField field = new PasswordField();
        field.setId(id);
        return field;
    }

    private void submit() {
        if (pending || hidden) return;
        if (!manager.emailVerified()) {
            status.setText("Vui lòng xác thực email trước khi đổi mật khẩu.");
            return;
        }
        String oldPassword = current.getText(), newPassword = next.getText();
        String error = AuthValidation.loginPasswordError(oldPassword);
        if (error == null) error = AuthValidation.registrationPasswordError(newPassword);
        if (error == null && !newPassword.equals(confirmation.getText()))
            error = "Mật khẩu xác nhận chưa khớp.";
        if (error == null && oldPassword.equals(newPassword))
            error = "Mật khẩu mới phải khác mật khẩu hiện tại.";
        if (error != null) {
            status.setText(error);
            return;
        }
        String ownerToken = manager.sessionToken();
        pending(true);
        status.setText("Đang đổi mật khẩu…");
        manager.runAuthRequest(
                () -> manager.authClient().changePassword(ownerToken, oldPassword, newPassword),
                response -> {
                    if (!Objects.equals(ownerToken, manager.sessionToken())
                            || manager.sessionExpired(response)
                            || hidden) return;
                    pending(false);
                    status.setText(response.message());
                    if (response.success()) {
                        clearSecrets();
                        form.setDisable(true);
                        submit.setDisable(true);
                        status.getStyleClass().setAll("success-text");
                    } else if ("WRONG_PASSWORD".equals(response.code())) {
                        current.clear();
                        current.requestFocus();
                    }
                },
                failure -> {
                    if (hidden || !Objects.equals(ownerToken, manager.sessionToken())) return;
                    pending(false);
                    status.setText(SceneManager.connectionError(failure));
                });
    }

    private void pending(boolean value) {
        pending = value;
        current.setDisable(value);
        next.setDisable(value);
        confirmation.setDisable(value);
        submit.setDisable(value || !manager.emailVerified());
        submit.setText(value ? "Đang đổi…" : "Đổi mật khẩu");
    }

    private void clearSecrets() {
        current.clear();
        next.clear();
        confirmation.clear();
    }
}
