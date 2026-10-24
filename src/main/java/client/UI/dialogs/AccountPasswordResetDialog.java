package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.components.Ui;

import common.auth.AuthValidation;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.Objects;

/**
 * OTP recovery inside settings. The current token is retained only by the authenticated server
 * command.
 */
public final class AccountPasswordResetDialog extends Dialog<Boolean> {
    private final SceneManager manager;
    private final String token, email;
    private final TextField code = new TextField();
    private final PasswordField password = new PasswordField(), confirmation = new PasswordField();
    private final Label status =
            Ui.label("Chọn Gửi mã OTP để nhận mã đặt lại mật khẩu.", "caption");
    private final Button send = Ui.button("Gửi mã OTP", "secondary", this::send);
    private final Button submit;
    private Timeline cooldown;
    private long resendAfter;
    private boolean pending, hidden, sent, complete;

    public AccountPasswordResetDialog(SceneManager manager) {
        this.manager = manager;
        token = manager.sessionToken();
        email = manager.email();
        initOwner(manager.stage());
        setTitle("Quên mật khẩu");
        setHeaderText("Đặt lại mật khẩu bằng email OTP");
        Ui.style(this);
        code.setId("account-reset-code");
        code.setPromptText("Nhập mã OTP gồm 6 số");
        code.setTextFormatter(
                new TextFormatter<String>(
                        change ->
                                change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
        password.setId("account-reset-password");
        confirmation.setId("account-reset-confirm");
        send.setId("account-reset-send");
        status.setId("account-reset-status");
        status.setMinHeight(42);
        status.setPrefHeight(42);
        status.setMaxWidth(Double.MAX_VALUE);
        Label sessionNote =
                Ui.label(
                        "Bạn vẫn đăng nhập trên máy này. Các phiên đăng nhập khác sẽ được đăng xuất"
                                + " sau khi đặt lại thành công.",
                        "caption");
        sessionNote.setMinHeight(Region.USE_PREF_SIZE);
        sessionNote.setMaxWidth(Double.MAX_VALUE);
        var content =
                Ui.column(
                        12,
                        Ui.label(email, "strong"),
                        Ui.field("Mã OTP", code),
                        send,
                        Ui.field("Mật khẩu mới", password),
                        Ui.field("Nhập lại mật khẩu mới", confirmation),
                        Ui.label(AuthValidation.PASSWORD_REQUIREMENTS, "caption"),
                        sessionNote,
                        status);
        content.setPrefWidth(460);
        content.setMinHeight(Region.USE_PREF_SIZE);
        getDialogPane().setContent(content);
        ButtonType confirm = new ButtonType("Đặt lại mật khẩu", ButtonBar.ButtonData.OK_DONE);
        getDialogPane()
                .getButtonTypes()
                .addAll(confirm, new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE));
        submit = (Button) getDialogPane().lookupButton(confirm);
        submit.setId("account-reset-submit");
        submit.getStyleClass().add("primary");
        submit.addEventFilter(
                ActionEvent.ACTION,
                event -> {
                    event.consume();
                    reset();
                });
        setResultConverter(button -> false);
        setOnHidden(
                event -> {
                    hidden = true;
                    clearSecrets();
                    stopCooldown();
                });
        refresh();
    }

    private boolean current() {
        return !hidden && Objects.equals(token, manager.sessionToken());
    }

    private void send() {
        if (!current() || pending || complete || remaining() > 0) return;
        pending = true;
        refresh();
        status("Đang gửi mã OTP…", true);
        manager.runAuthRequest(
                () -> manager.authClient().requestAccountPasswordReset(token, email),
                response -> {
                    if (!current() || manager.sessionExpired(response)) return;
                    pending = false;
                    if (response.success()) {
                        sent = true;
                        code.clear();
                        startCooldown();
                    } else if ("OTP_COOLDOWN".equals(response.code())
                            || "MAIL_UNAVAILABLE".equals(response.code())) startCooldown();
                    status(response.message(), response.success());
                    refresh();
                },
                failure -> {
                    if (!current()) return;
                    pending = false;
                    status(SceneManager.connectionError(failure), false);
                    refresh();
                });
    }

    private void reset() {
        if (!current() || pending || complete) return;
        if (!code.getText().matches("[0-9]{6}")) {
            status("Mã OTP phải gồm 6 chữ số.", false);
            return;
        }
        String next = password.getText();
        String error = AuthValidation.registrationPasswordError(next);
        if (error == null && !next.equals(confirmation.getText()))
            error = "Mật khẩu xác nhận chưa khớp.";
        if (error != null) {
            status(error, false);
            return;
        }
        String otp = code.getText();
        pending = true;
        refresh();
        status("Đang đặt lại mật khẩu…", true);
        manager.runAuthRequest(
                () -> manager.authClient().resetAccountPassword(token, email, otp, next),
                response -> {
                    if (!Objects.equals(token, manager.sessionToken())
                            || manager.sessionExpired(response)) return;
                    if (response.success()) manager.updateAccount(response.user());
                    if (hidden) return;
                    pending = false;
                    if (response.success()) {
                        complete = true;
                        clearSecrets();
                        stopCooldown();
                    }
                    status(response.message(), response.success());
                    refresh();
                },
                failure -> {
                    if (!current()) return;
                    pending = false;
                    status(SceneManager.connectionError(failure), false);
                    refresh();
                });
    }

    private void status(String text, boolean success) {
        status.setText(text);
        status.getStyleClass().setAll(success ? "success-text" : "error-text");
    }

    private void clearSecrets() {
        code.clear();
        password.clear();
        confirmation.clear();
    }

    private long remaining() {
        return Math.max(0, (resendAfter - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
    }

    private void refresh() {
        long seconds = remaining();
        send.setText(
                complete
                        ? "Đã gửi mã OTP"
                        : seconds > 0
                                ? "Gửi lại mã (" + seconds + "s)"
                                : sent ? "Gửi lại mã" : "Gửi mã OTP");
        send.setDisable(pending || complete || seconds > 0);
        submit.setDisable(pending || complete);
        code.setDisable(pending || complete);
        password.setDisable(pending || complete);
        confirmation.setDisable(pending || complete);
        if (seconds == 0) stopCooldown();
    }

    private void startCooldown() {
        stopCooldown();
        resendAfter = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        cooldown = new Timeline(new KeyFrame(Duration.seconds(1), event -> refresh()));
        cooldown.setCycleCount(Timeline.INDEFINITE);
        cooldown.play();
    }

    private void stopCooldown() {
        if (cooldown != null) {
            cooldown.stop();
            cooldown = null;
        }
    }
}
