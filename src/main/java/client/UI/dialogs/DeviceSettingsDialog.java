package client.UI.dialogs;

import client.UI.components.Ui;
import client.UI.ThemeManager;
import client.UI.mock.MockData;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class DeviceSettingsDialog extends Dialog<Void> {
    public DeviceSettingsDialog(Window owner) {
        this(owner, null, null, null, null);
    }
    public DeviceSettingsDialog(Window owner, common.meeting.RoomView room,
            java.util.function.Consumer<Boolean> lock, java.util.function.Consumer<Boolean> approval, Runnable end) {
        initOwner(owner); setTitle("Cài đặt"); setHeaderText("Tùy chỉnh trải nghiệm"); Ui.style(this);
        Slider volume = new Slider(0, 100, 75); Label level = Ui.label("75%", "accent-text"); level.textProperty().bind(volume.valueProperty().asString("%.0f%%"));
        CheckBox noise = new CheckBox("Khử tiếng ồn (mô phỏng)"); noise.setSelected(true);
        VBox appearance = Ui.column(8, Ui.label("Giao diện", "subtitle"), Ui.row(12, Ui.label("Chế độ hiển thị", "field-label"), Ui.spacer(), ThemeManager.selector()));
        VBox content = Ui.column(16, appearance, new Separator(), Ui.label("Âm thanh & hình ảnh", "subtitle"), Ui.field("Camera", Ui.devices(MockData.cameras())), Ui.field("Microphone", Ui.devices(MockData.microphones())), Ui.field("Loa", Ui.devices(MockData.speakers())), Ui.row(12, Ui.label("Âm lượng", "field-label"), Ui.spacer(), level), volume, noise, Ui.label("Thiết bị giả lập, chưa truy cập camera hay microphone.", "caption"));
        if (room != null) {
            CheckBox locked = new CheckBox("Khóa phòng"); locked.setSelected(room.locked()); locked.setOnAction(event -> lock.accept(locked.isSelected()));
            CheckBox required = new CheckBox("Duyệt thành viên trước khi vào"); required.setSelected(room.approval()); required.setOnAction(event -> approval.accept(required.isSelected()));
            Button closeRoom = Ui.button("Đóng phòng", "danger", () -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION); confirm.initOwner(getDialogPane().getScene().getWindow());
                confirm.setTitle("Đóng phòng"); confirm.setHeaderText("Kết thúc phòng cho mọi người?");
                Ui.style(confirm);
                confirm.showAndWait().filter(ButtonType.OK::equals).ifPresent(value -> { close(); end.run(); });
            });
            content.getChildren().addAll(new Separator(), Ui.label("Quản lý phòng", "subtitle"), locked, required, closeRoom);
        }
        content.setPrefWidth(420); getDialogPane().setContent(content); getDialogPane().getButtonTypes().add(ButtonType.CLOSE); ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Xong");
    }
}
