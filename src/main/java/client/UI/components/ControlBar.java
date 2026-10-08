package client.UI.components;

import javafx.geometry.Pos;
import javafx.scene.layout.*;

public final class ControlBar extends HBox {
    public final IconButton mic, camera, share, chat, users;
    public ControlBar(boolean micOn, boolean cameraOn, Runnable onMic, Runnable onCamera, Runnable onShare, Runnable onChat, Runnable onUsers, Runnable onSettings, Runnable onLeave) {
        super(12); setAlignment(Pos.CENTER); getStyleClass().add("control-bar"); setMaxWidth(USE_PREF_SIZE);
        mic = add("mic", "Mic", onMic); camera = add("camera", "Camera", onCamera);
        Region divider = new Region(); divider.getStyleClass().add("control-divider"); getChildren().add(divider);
        share = add("share", "Chia sẻ", onShare); chat = add("chat", "Chat", onChat); users = add("users", "Thành viên", onUsers); add("settings", "Cài đặt", onSettings);
        IconButton leave = add("phone", "Rời phòng", onLeave); leave.getStyleClass().add("danger-icon");
        mic(micOn); camera(cameraOn);
    }
    private IconButton add(String icon, String text, Runnable action) {
        IconButton b = new IconButton(icon, text); b.setOnAction(e -> action.run());
        VBox item = Ui.column(6, b, Ui.label(text, "control-label")); item.setAlignment(Pos.CENTER); getChildren().add(item); return b;
    }
    public void mic(boolean on) { mic.setIcon(on ? "mic" : "mic-off"); mic.active(on); }
    public void camera(boolean on) { camera.setIcon(on ? "camera" : "camera-off"); camera.active(on); }
}
