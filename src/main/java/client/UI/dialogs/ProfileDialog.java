package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.components.Ui;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/** Hiển thị thông tin tài khoản do máy chủ trả về. */
public final class ProfileDialog extends Dialog<Boolean> {
    public ProfileDialog(SceneManager manager) {
        initOwner(manager.stage()); setTitle("Tài khoản của tôi"); setHeaderText("Thông tin cá nhân"); Ui.style(this);
        TextField username = new TextField(manager.username()), name = new TextField(manager.user()), email = new TextField(manager.email());
        username.setEditable(false); name.setEditable(false); email.setEditable(false);
        VBox content = Ui.column(14, Ui.field("Tên đăng nhập", username), Ui.field("Tên hiển thị", name), Ui.field("Email", email),
            Ui.label("Chỉnh sửa hồ sơ và đổi mật khẩu sẽ được bổ sung sau.", "caption"));
        content.setPrefWidth(420); getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(new ButtonType("Đóng", ButtonBar.ButtonData.CANCEL_CLOSE));
        setResultConverter(type -> false);
    }
}
