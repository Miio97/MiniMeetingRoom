package client.UI.dialogs;

import client.UI.ThemeManager;
import client.UI.components.CameraControls;
import client.UI.components.AudioControls;
import client.UI.components.Ui;
import client.media.VideoCallController;
import javafx.scene.control.*;
import javafx.stage.Window;

/** Actual device catalog from this call; closing settings only removes the view. */
public final class CameraSettingsDialog extends Dialog<Void> {
    public CameraSettingsDialog(Window owner, VideoCallController video) {
        initOwner(owner); setTitle("Cài đặt cuộc họp"); setHeaderText("Camera, âm thanh và giao diện"); Ui.style(this);
        CameraControls camera = new CameraControls(video, false);
        AudioControls audio = new AudioControls(video.audio());
        var status = Ui.label("", "muted"); status.textProperty().bind(video.statusProperty());
        var content = Ui.column(16, audio, new Separator(), Ui.label("Camera trên máy của bạn", "subtitle"), camera, status,
                Ui.label("Tắt camera trước khi chọn thiết bị khác. Webcam đang được ứng dụng khác sử dụng có thể không mở được.", "caption"),
                Ui.label("Danh sách chỉ hiển thị camera vật lý; các nguồn camera ảo OBS/ByteCast được ẩn.", "caption"),
                new Separator(), Ui.label("Giao diện", "subtitle"), ThemeManager.selector());
        content.getChildren().stream().filter(Label.class::isInstance).map(Label.class::cast).forEach(label -> label.setWrapText(true));
        content.setPrefWidth(600); getDialogPane().setContent(content); getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Xong");
        setOnHidden(event -> { camera.dispose(); audio.dispose(); status.textProperty().unbind(); });
    }
}
