package client.UI.dialogs;

import client.UI.ThemeManager;
import javafx.scene.control.*;

public final class HostMenuPopup extends ContextMenu {
    public HostMenuPopup(boolean micOn, boolean cameraOn, Runnable mute, Runnable stopCamera, Runnable remove, Runnable transfer) {
        MenuItem mic = new MenuItem("Tắt microphone"); mic.setDisable(!micOn); mic.setOnAction(event -> mute.run());
        MenuItem camera = new MenuItem("Tắt camera"); camera.setDisable(!cameraOn); camera.setOnAction(event -> stopCamera.run());
        MenuItem change = new MenuItem("Chuyển chủ phòng"); change.setOnAction(event -> transfer.run());
        MenuItem kick = new MenuItem("Xóa thành viên"); kick.setOnAction(event -> remove.run());
        getItems().addAll(mic, camera, new SeparatorMenuItem(), change, kick);
        setOnShowing(event -> ThemeManager.attach(getScene().getRoot()));
    }
}
