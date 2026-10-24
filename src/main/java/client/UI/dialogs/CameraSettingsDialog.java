package client.UI.dialogs;

import client.UI.ThemeManager;
import client.UI.components.AudioControls;
import client.UI.components.CameraControls;
import client.UI.components.Ui;
import client.media.VideoCallController;

import javafx.scene.control.*;
import javafx.stage.Window;

/** Actual device catalog from this call; closing settings only removes the view. */
public final class CameraSettingsDialog extends Dialog<Void> {
    private final javafx.scene.layout.VBox management = new javafx.scene.layout.VBox(10);
    private final CheckBox locked = new CheckBox("Khóa phòng"),
            approval = new CheckBox("Duyệt thành viên trước khi vào");
    private final Label managementError = Ui.label("", "error-text");
    private common.meeting.RoomResponse confirmed;
    private boolean updating, hidden;

    public CameraSettingsDialog(Window owner, VideoCallController video) {
        this(owner, video, null, false, null);
    }

    public CameraSettingsDialog(
            Window owner,
            VideoCallController video,
            common.meeting.RoomResponse state,
            boolean host,
            java.util.function.BiFunction<
                            Boolean,
                            Boolean,
                            java.util.concurrent.CompletableFuture<common.meeting.RoomResponse>>
                    change) {
        initOwner(owner);
        setTitle("Cài đặt cuộc họp");
        setHeaderText("Thiết bị, giao diện và phòng họp");
        Ui.style(this);
        CameraControls camera = new CameraControls(video, false);
        AudioControls audio = new AudioControls(video.audio());
        locked.setId("room-locked");
        approval.setId("room-approval");
        management.setId("room-settings-section");
        managementError.setWrapText(true);
        managementError.visibleProperty().bind(managementError.textProperty().isNotEmpty());
        managementError.managedProperty().bind(managementError.visibleProperty());
        management
                .getChildren()
                .addAll(
                        Ui.label("Quản lý phòng", "subtitle"),
                        locked,
                        approval,
                        managementError,
                        new Separator());
        locked.setOnAction(event -> changeSettings(locked.isSelected(), null, change));
        approval.setOnAction(event -> changeSettings(null, approval.isSelected(), change));
        roomState(state, host);
        Label quality = Ui.label("", "caption");
        quality.setId("adaptive-video-quality");
        quality.textProperty()
                .bind(
                        javafx.beans.binding.Bindings.createStringBinding(
                                () -> "Chất lượng tự động: " + video.qualityProperty().get().label,
                                video.qualityProperty()));
        var content =
                Ui.column(
                        16,
                        management,
                        audio,
                        new Separator(),
                        Ui.label("Camera", "subtitle"),
                        camera,
                        quality,
                        new Separator(),
                        Ui.label("Giao diện", "subtitle"),
                        ThemeManager.selector());
        content.getChildren().stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .forEach(label -> label.setWrapText(true));
        content.setPrefWidth(600);
        ScrollPane scroll = Ui.scroll(content);
        scroll.setPrefViewportWidth(620);
        scroll.setPrefViewportHeight(500);
        getDialogPane().setContent(scroll);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("Xong");
        setOnHidden(
                event -> {
                    hidden = true;
                    camera.dispose();
                    audio.dispose();
                    quality.textProperty().unbind();
                });
    }

    public void roomState(common.meeting.RoomResponse state, boolean host) {
        confirmed = state;
        management.setVisible(host && state != null);
        management.setManaged(management.isVisible());
        management.setDisable(!host || updating);
        if (!updating && state != null) {
            locked.setSelected(state.room().locked());
            approval.setSelected(state.room().approval());
        }
    }

    private void changeSettings(
            Boolean lock,
            Boolean requireApproval,
            java.util.function.BiFunction<
                            Boolean,
                            Boolean,
                            java.util.concurrent.CompletableFuture<common.meeting.RoomResponse>>
                    change) {
        if (updating || change == null) return;
        updating = true;
        management.setDisable(true);
        managementError.setText("");
        change.apply(lock, requireApproval)
                .whenComplete(
                        (state, failure) ->
                                javafx.application.Platform.runLater(
                                        () -> {
                                            if (hidden) return;
                                            updating = false;
                                            if (failure == null)
                                                roomState(state, management.isVisible());
                                            else {
                                                roomState(confirmed, management.isVisible());
                                                managementError.setText(
                                                        client.UI.SceneManager.connectionError(
                                                                failure));
                                            }
                                        }));
    }
}
