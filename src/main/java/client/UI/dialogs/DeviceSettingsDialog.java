package client.UI.dialogs;

import client.UI.components.Ui;
import client.UI.ThemeManager;
import client.UI.mock.MockData;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class DeviceSettingsDialog extends Dialog<Void> {
    public DeviceSettingsDialog(Window owner) {
        initOwner(owner); setTitle("Cài đặt"); setHeaderText("Tùy chỉnh trải nghiệm"); Ui.style(this);
        Slider volume = new Slider(0, 100, 75); Label level = Ui.label("75%", "accent-text"); level.textProperty().bind(volume.valueProperty().asString("%.0f%%"));
        CheckBox noise = new CheckBox("Khử tiếng ồn (mô phỏng)"); noise.setSelected(true);
        VBox appearance = Ui.column(8, Ui.label("Giao diện", "subtitle"), Ui.row(12, Ui.label("Chế độ hiển thị", "field-label"), Ui.spacer(), ThemeManager.selector()));
        VBox content = Ui.column(16, appearance, new Separator(), Ui.label("Âm thanh & hình ảnh", "subtitle"), Ui.field("Camera", Ui.devices(MockData.cameras())), Ui.field("Microphone", Ui.devices(MockData.microphones())), Ui.field("Loa", Ui.devices(MockData.speakers())), Ui.row(12, Ui.label("Âm lượng", "field-label"), Ui.spacer(), level), volume, noise, Ui.label("Thiết bị giả lập, chưa truy cập camera hay microphone.", "caption"));
        content.setPrefWidth(420); getDialogPane().setContent(content); getDialogPane().getButtonTypes().add(ButtonType.CLOSE); ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Xong");
    }
}
