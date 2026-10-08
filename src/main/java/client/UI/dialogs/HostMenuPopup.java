package client.UI.dialogs;

import client.UI.ThemeManager;
import javafx.scene.control.*;

public final class HostMenuPopup extends ContextMenu {
    public HostMenuPopup(boolean hostRow, boolean muted, boolean camera, Runnable mute, Runnable stopCamera, Runnable remove, Runnable lock, Runnable close) {
        MenuItem mic = new MenuItem(muted ? "Mic đã tắt" : "Tắt microphone"); mic.setDisable(muted); mic.setOnAction(e -> mute.run());
        MenuItem cam = new MenuItem(camera ? "Tắt camera" : "Camera đã tắt"); cam.setDisable(!camera); cam.setOnAction(e -> stopCamera.run());
        getItems().addAll(mic, cam, new SeparatorMenuItem());
        if (hostRow) {
            MenuItem toggle = new MenuItem("Khóa / mở khóa phòng"); toggle.setOnAction(e -> lock.run());
            MenuItem end = new MenuItem("Đóng phòng"); end.setOnAction(e -> close.run());
            getItems().addAll(toggle, end);
        } else {
            MenuItem kick = new MenuItem("Xóa thành viên"); kick.setOnAction(e -> remove.run());
            getItems().add(kick);
        }
        setOnShowing(e -> ThemeManager.attach(getScene().getRoot()));
    }
}
