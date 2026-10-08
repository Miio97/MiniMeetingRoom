package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.components.Ui;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/** Quản lý tài khoản trong phiên demo; không lưu mật khẩu xuống tệp hay gửi mạng. */
public final class ProfileDialog extends Dialog<Boolean> {
    public ProfileDialog(SceneManager manager) {
        initOwner(manager.stage()); setTitle("Tài khoản của tôi"); setHeaderText("Thông tin cá nhân & mật khẩu"); Ui.style(this);
        TextField name = new TextField(manager.user());
        PasswordField current = new PasswordField(), next = new PasswordField(), confirm = new PasswordField();
        current.setPromptText("Mật khẩu đã dùng khi đăng nhập"); next.setPromptText("Ít nhất 6 ký tự"); confirm.setPromptText("Nhập lại mật khẩu mới");
        Label error = Ui.label("", "error-text");
        VBox content = Ui.column(14, Ui.field("Tên hiển thị", name), new Separator(), Ui.label("Đổi mật khẩu", "subtitle"), Ui.label("Để trống cả 3 ô nếu chỉ đổi tên.", "caption"), Ui.field("Mật khẩu hiện tại", current), Ui.field("Mật khẩu mới", next), Ui.field("Xác nhận mật khẩu mới", confirm), error, Ui.label("Thay đổi chỉ áp dụng trong phiên UI demo.", "caption"));
        content.setPrefWidth(420); getDialogPane().setContent(content);
        ButtonType save = new ButtonType("Lưu thay đổi", ButtonBar.ButtonData.OK_DONE); getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, save);
        ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        Button submit = (Button) getDialogPane().lookupButton(save); submit.getStyleClass().add("primary");
        submit.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            String message = "";
            boolean changingPassword = !current.getText().isEmpty() || !next.getText().isEmpty() || !confirm.getText().isEmpty();
            if (name.getText().isBlank()) message = "Tên hiển thị không được để trống.";
            else if (changingPassword && !manager.matchesPassword(current.getText())) message = "Mật khẩu hiện tại chưa đúng.";
            else if (changingPassword && next.getText().length() < 6) message = "Mật khẩu mới cần ít nhất 6 ký tự.";
            else if (changingPassword && !next.getText().equals(confirm.getText())) message = "Mật khẩu xác nhận chưa khớp.";
            if (!message.isEmpty()) { error.setText(message); e.consume(); }
        });
        setResultConverter(type -> {
            if (type != save) return false;
            manager.user(name.getText().strip()); if (!next.getText().isEmpty()) manager.password(next.getText());
            return true;
        });
    }
}
