package client.UI.dialogs;

import javafx.scene.control.*;

public final class HostMenuPopup extends ContextMenu {
    private final javafx.beans.property.BooleanProperty busy =
            new javafx.beans.property.SimpleBooleanProperty();
    private final javafx.beans.property.BooleanProperty microphoneOn =
            new javafx.beans.property.SimpleBooleanProperty();
    private final javafx.beans.property.BooleanProperty cameraOn =
            new javafx.beans.property.SimpleBooleanProperty();
    private final javafx.beans.property.BooleanProperty sharingOn =
            new javafx.beans.property.SimpleBooleanProperty();

    public void mediaState(boolean mic, boolean camera, boolean sharing) {
        microphoneOn.set(mic);
        cameraOn.set(camera);
        sharingOn.set(sharing);
    }

    public void bindPending(javafx.beans.property.ReadOnlyBooleanProperty pending) {
        busy.bind(pending);
    }

    public HostMenuPopup(
            boolean micOn,
            boolean cameraOn,
            Runnable mute,
            Runnable stopCamera,
            Runnable remove,
            Runnable transfer) {
        this(micOn, cameraOn, null, mute, stopCamera, () -> {}, remove, transfer);
    }

    public HostMenuPopup(
            boolean micOn,
            boolean cameraOn,
            Boolean sharing,
            Runnable mute,
            Runnable stopCamera,
            Runnable stopShare,
            Runnable remove,
            Runnable transfer) {
        mediaState(micOn, cameraOn, Boolean.TRUE.equals(sharing));
        MenuItem mic = new MenuItem("Tắt microphone");
        mic.disableProperty().bind(microphoneOn.not().or(busy));
        mic.setOnAction(event -> mute.run());
        MenuItem camera = new MenuItem("Tắt camera");
        camera.disableProperty().bind(this.cameraOn.not().or(busy));
        camera.setOnAction(event -> stopCamera.run());
        MenuItem change = new MenuItem("Chuyển chủ phòng");
        change.disableProperty().bind(busy);
        change.setOnAction(event -> transfer.run());
        MenuItem kick = new MenuItem("Xóa thành viên");
        kick.disableProperty().bind(busy);
        kick.setOnAction(event -> remove.run());
        getItems().addAll(mic, camera);
        if (sharing != null) {
            MenuItem screen = new MenuItem("Dừng chia sẻ");
            screen.disableProperty().bind(sharingOn.not().or(busy));
            screen.setOnAction(event -> stopShare.run());
            getItems().add(screen);
        }
        SeparatorMenuItem separator = new SeparatorMenuItem();
        separator.disableProperty().bind(busy);
        getItems().addAll(separator, change, kick);
    }
}
