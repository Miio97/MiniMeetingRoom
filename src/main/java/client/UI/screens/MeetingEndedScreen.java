package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.mock.MockData;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.geometry.Pos;
import javafx.scene.layout.*;

public final class MeetingEndedScreen extends StackPane {
    private boolean disposed, reopening;
    private AutoCloseable catalogWatch;
    public MeetingEndedScreen(SceneManager manager, long seconds, int count) {
        StackPane check = IconButton.icon("check"); check.getStyleClass().add("ended-icon"); check.setMinSize(80, 80); check.setMaxSize(80, 80);
        VBox duration = Ui.column(8, Ui.label(MockData.duration(seconds), "title"), Ui.label("Thời lượng", "muted")); duration.setAlignment(Pos.CENTER);
        VBox participants = Ui.column(8, Ui.label(String.valueOf(count), "title"), Ui.label("Người tham gia", "muted")); participants.setAlignment(Pos.CENTER);
        HBox stats = Ui.row(64, duration, participants); stats.setAlignment(Pos.CENTER); stats.getStyleClass().add("ended-stats");
        var room = manager.roomInfo();
        javafx.beans.property.BooleanProperty canReopen = new javafx.beans.property.SimpleBooleanProperty(
                room != null && "DA_DONG".equals(room.status()) && manager.authenticatedUser() != null
                        && room.hostId() == manager.authenticatedUser().id());
        Label error = Ui.label("", "error-text"); error.setId("reopen-room-error"); error.setWrapText(true);
        error.visibleProperty().bind(error.textProperty().isNotEmpty()); error.managedProperty().bind(error.visibleProperty());
        Button reopen = Ui.button("Vào lại phòng", "secondary", () -> {
            if (disposed || reopening) return;
            if (!canReopen.get()) { manager.preJoin(manager.room()); return; }
            reopening = true; setDisable(true); error.setText("Đang mở lại phòng…");
            manager.requestReopenRoom(room.id(), reopened -> {
                if (disposed) return;
                reopening = false; setDisable(false); manager.preJoin(reopened);
            }, cause -> {
                if (disposed) return;
                reopening = false; setDisable(false); error.setText(SceneManager.connectionError(cause));
            });
        }); reopen.setId("reopen-room-submit");
        reopen.textProperty().bind(javafx.beans.binding.Bindings.when(canReopen).then("Mở lại phòng").otherwise("Vào lại phòng"));
        if(room!=null && manager.usesSignalingRooms() && !Boolean.getBoolean("mmr.test.disableCatalog")) {
            var cleaning=new javafx.beans.property.SimpleBooleanProperty();
            reopen.textProperty().unbind();reopen.textProperty().bind(javafx.beans.binding.Bindings.when(cleaning).then("Đang dọn dữ liệu…").otherwise(javafx.beans.binding.Bindings.when(canReopen).then("Mở lại phòng").otherwise("Vào lại phòng")));
            reopen.disableProperty().bind(cleaning);
            catalogWatch=manager.sfuSession().watchCatalog(update -> javafx.application.Platform.runLater(() -> {
                if(disposed) return;cleaning.set(update.cleaning().contains(room.id()));
                update.rooms().stream().filter(latest -> latest.id()==room.id()).findFirst().ifPresent(latest -> {
                    manager.updateRoom(latest);canReopen.set("DA_DONG".equals(latest.status()) && manager.authenticatedUser()!=null && latest.hostId()==manager.authenticatedUser().id());
                });
            }),java.util.List.of(room.id()));
        }
        String ownerToken = manager.sessionToken();
        if (room != null) manager.runBackgroundRoomRequest(() -> manager.authClient().listRooms(ownerToken), response -> {
            if (disposed || manager.authenticatedUser() == null) return;
            if (manager.sessionExpired(response) || !response.success()) return;
            response.rooms().stream().filter(latest -> latest.id() == room.id()).findFirst().ifPresent(latest -> {
                manager.updateRoom(latest);
                canReopen.set("DA_DONG".equals(latest.status()) && latest.hostId() == manager.authenticatedUser().id());
            });
        }, cause -> { if (!disposed) error.setText(SceneManager.connectionError(cause)); });
        HBox actions = Ui.row(12, Ui.button("Quay lại sảnh", "primary", manager::lobby), reopen); actions.setAlignment(Pos.CENTER);
        VBox card = Ui.column(24, check, Ui.label("Đã rời phòng", "title"), Ui.label(manager.endReason().isBlank() ? "Cảm ơn bạn đã dành thời gian kết nối." : manager.endReason(), "muted"), Ui.label(manager.room().name(), "strong"), stats, actions, error, Ui.label("MINI MEETING ROOM  ·  HẸN GẶP LẠI BẠN", "eyebrow"));
        card.setAlignment(Pos.CENTER); card.setMaxWidth(580); card.setMaxHeight(USE_PREF_SIZE); card.getStyleClass().add("ended-card"); getChildren().add(card);
    }
    public void dispose() { disposed = true;if(catalogWatch!=null) try {catalogWatch.close();} catch(Exception ignored) {} }
}
