package client.UI.dialogs;

import javafx.scene.control.*;

public final class HostMenuPopup extends ContextMenu {
    public HostMenuPopup(boolean micOn, boolean cameraOn, Runnable mute, Runnable stopCamera, Runnable remove, Runnable transfer) {
        this(micOn, cameraOn, null, mute, stopCamera, () -> { }, remove, transfer);
    }
    public HostMenuPopup(boolean micOn, boolean cameraOn, Boolean sharing, Runnable mute, Runnable stopCamera,
                         Runnable stopShare, Runnable remove, Runnable transfer) {
        MenuItem mic = new MenuItem("Tắt microphone"); mic.setDisable(!micOn); mic.setOnAction(event -> mute.run());
        MenuItem camera = new MenuItem("Tắt camera"); camera.setDisable(!cameraOn); camera.setOnAction(event -> stopCamera.run());
        MenuItem change = new MenuItem("Chuyển chủ phòng"); change.setOnAction(event -> transfer.run());
        MenuItem kick = new MenuItem("Xóa thành viên"); kick.setOnAction(event -> remove.run());
        getItems().addAll(mic, camera);
        if (sharing != null) {
            MenuItem screen = new MenuItem("Dừng chia sẻ"); screen.setDisable(!sharing); screen.setOnAction(event -> stopShare.run());
            getItems().add(screen);
        }
        getItems().addAll(new SeparatorMenuItem(), change, kick);
    }
}
