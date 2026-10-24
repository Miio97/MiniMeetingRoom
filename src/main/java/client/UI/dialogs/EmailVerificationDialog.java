package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.components.Ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.util.Duration;

import java.util.Objects;

/** Authenticated verification of the saved account email, with delivery outside the FX thread. */
public final class EmailVerificationDialog extends Dialog<Boolean> {
    private final SceneManager manager;
    private final String email, token;
    private final Runnable verified;
    private final TextField code = new TextField();
    private final Label status =
            Ui.label("Chọn Gửi mã OTP để nhận mã xác thực gồm 6 số.", "caption");
    private final Button send = Ui.button("Gửi mã OTP", "secondary", this::send);
    private final Button submit;
    private Timeline cooldown;
    private long resendAfter;
    private boolean pending, hidden, sent, complete;

    public EmailVerificationDialog(SceneManager manager, Runnable verified) {
        this.manager = manager;
        this.verified = verified;
        email = manager.email();
        token = manager.sessionToken();
        initOwner(manager.stage());
        setTitle("Xác thực email");
        setHeaderText("Xác thực email tài khoản");
        Ui.style(this);
        code.setId("email-verification-code");
        code.setPromptText("Nhập mã OTP gồm 6 số");
        code.setTextFormatter(
                new TextFormatter<String>(
                        change ->
                                change.getControlNewText().matches("[0-9]{0,6}") ? change : null));
        send.setId("email-verification-send");
        status.setId("email-verification-status");
        status.setWrapText(true);
        var content =
                Ui.column(
                        12,
                        Ui.label(email, "strong"),
                        Ui.field("Mã OTP", code),
                        send,
                        status,
                        Ui.label(
                                "Mã có hiệu lực 5 phút. Gửi lại sau 60 giây, tối đa 5 lần mỗi giờ.",
                                "caption"));
        content.setPrefWidth(440);
        getDialogPane().setContent(content);
        ButtonType confirm = new ButtonType("Xác thực", ButtonBar.ButtonData.OK_DONE);
        getDialogPane()
                .getButtonTypes()
                .addAll(confirm, new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE));
        submit = (Button) getDialogPane().lookupButton(confirm);
        submit.setId("email-verification-submit");
        submit.getStyleClass().add("primary");
        submit.addEventFilter(
                ActionEvent.ACTION,
                event -> {
                    event.consume();
                    verify();
                });
        setResultConverter(button -> false);
        setOnHidden(
                event -> {
                    hidden = true;
                    code.clear();
                    stopCooldown();
                });
        refreshButtons();
    }

    private boolean current() {
        return !hidden && Objects.equals(token, manager.sessionToken());
    }

    private void send() {
        if (!current() || pending || complete || remaining() > 0) return;
        pending = true;
        refreshButtons();
        status("Đang gửi mã OTP…", true);
        manager.runAuthRequest(
                () -> manager.authClient().requestEmailVerification(token, email),
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
                    refreshButtons();
                },
                failure -> {
                    if (!current()) return;
                    pending = false;
                    status(SceneManager.connectionError(failure), false);
                    refreshButtons();
                });
    }

    private void verify() {
        if (!current() || pending || complete) return;
        if (!code.getText().matches("[0-9]{6}")) {
            status("Mã OTP phải gồm 6 chữ số.", false);
            return;
        }
        String otp = code.getText();
        pending = true;
        refreshButtons();
        status("Đang xác thực email…", true);
        manager.runAuthRequest(
                () -> manager.authClient().verifyEmail(token, email, otp),
                response -> {
                    if (!current() || manager.sessionExpired(response)) return;
                    pending = false;
                    if (response.success()) {
                        manager.updateAccount(response.user());
                        complete = true;
                        code.clear();
                        stopCooldown();
                        verified.run();
                    }
                    status(response.message(), response.success());
                    refreshButtons();
                },
                failure -> {
                    if (!current()) return;
                    pending = false;
                    status(SceneManager.connectionError(failure), false);
                    refreshButtons();
                });
    }

    private void status(String text, boolean success) {
        status.setText(text);
        status.getStyleClass().setAll(success ? "success-text" : "error-text");
    }

    private long remaining() {
        return Math.max(0, (resendAfter - System.nanoTime() + 999_999_999L) / 1_000_000_000L);
    }

    private void refreshButtons() {
        long seconds = remaining();
        send.setText(
                seconds > 0 ? "Gửi lại mã (" + seconds + "s)" : sent ? "Gửi lại mã" : "Gửi mã OTP");
        send.setDisable(pending || complete || seconds > 0);
        submit.setDisable(pending || complete);
        code.setDisable(pending || complete);
        if (seconds == 0) stopCooldown();
    }

    private void startCooldown() {
        stopCooldown();
        resendAfter = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        cooldown = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshButtons()));
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
