package client.UI.components;

import client.UI.mock.MockData;
import javafx.animation.*;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

public final class VideoTile extends StackPane {
    private final Label name, cameraState;
    private final StackPane mic = new StackPane();
    private final Timeline pulse;
    private final Region border = new Region();
    public VideoTile(MockData.Participant participant, boolean self) {
        getStyleClass().add("video-tile"); setMinSize(90, 100); setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setStyle("-fx-background-color: linear-gradient(to bottom right, " + participant.color() + ", #181b29);");
        // Avatar thu nhỏ theo chiều cao tile để lưới 10 người không đè lên tên.
        var avatarSize = Bindings.min(76, Bindings.max(24, heightProperty().subtract(64)));
        Circle circle = new Circle(); circle.radiusProperty().bind(avatarSize.divide(2)); circle.setStyle("-fx-fill: " + MockData.avatarColor(participant.name()));
        Label letters = Ui.label(MockData.initials(participant.name()), "avatar-text");
        letters.styleProperty().bind(Bindings.concat("-fx-font-size: ", avatarSize.multiply(.32), "px;"));
        StackPane avatar = new StackPane(circle, letters); avatar.minWidthProperty().bind(avatarSize); avatar.maxWidthProperty().bind(avatarSize); avatar.minHeightProperty().bind(avatarSize); avatar.maxHeightProperty().bind(avatarSize);
        cameraState = Ui.label("", "caption");
        cameraState.visibleProperty().bind(heightProperty().greaterThan(170)); cameraState.managedProperty().bind(cameraState.visibleProperty());
        VBox center = Ui.column(10, avatar, cameraState); center.setAlignment(Pos.CENTER); center.setMaxHeight(USE_PREF_SIZE); StackPane.setMargin(center, new Insets(8, 8, 40, 8));
        name = Ui.label(participant.name() + (self ? " (Bạn)" : ""), "tile-name");
        name.setWrapText(false); name.setMinWidth(0); name.setTooltip(new Tooltip(name.getText())); HBox.setHgrow(name, Priority.ALWAYS);
        HBox bottom = Ui.row(8, mic, name, Ui.spacer()); bottom.setMaxHeight(USE_PREF_SIZE); bottom.getStyleClass().add("tile-footer"); StackPane.setAlignment(bottom, Pos.BOTTOM_CENTER);
        Label tag = Ui.label(participant.host() ? "HOST" : "", "tile-tag"); tag.setVisible(participant.host()); StackPane.setAlignment(tag, Pos.TOP_LEFT); StackPane.setMargin(tag, new Insets(14));
        border.getStyleClass().add("speaking-border"); border.setMouseTransparent(true); border.setVisible(false);
        getChildren().addAll(center, bottom, tag, border);
        setMuted(participant.muted()); setCamera(participant.camera());
        pulse = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(border.opacityProperty(), .5)), new KeyFrame(Duration.millis(900), new KeyValue(border.opacityProperty(), 1)));
        pulse.setAutoReverse(true); pulse.setCycleCount(Animation.INDEFINITE);
    }
    public void setMuted(boolean muted) { mic.getChildren().setAll(IconButton.icon(muted ? "mic-off" : "mic")); mic.getStyleClass().setAll(muted ? "muted-mic" : "tile-mic"); }
    public void setCamera(boolean on) { cameraState.setText(on ? "Video mô phỏng" : "Camera đang tắt"); }
    public void setSpeaking(boolean speaking) { border.setVisible(speaking); if (speaking) pulse.play(); else pulse.stop(); }
    public void pinAction(String participantName, boolean pinned, Runnable action) {
        IconButton pin = new IconButton("pin", (pinned ? "Bỏ ghim " : "Ghim ") + participantName);
        pin.getStyleClass().add("pin-action"); pin.active(pinned); pin.setOnAction(e -> action.run());
        StackPane.setAlignment(pin, Pos.TOP_RIGHT); StackPane.setMargin(pin, new Insets(8)); getChildren().add(pin);
    }
    public void dispose() { pulse.stop(); }
}
