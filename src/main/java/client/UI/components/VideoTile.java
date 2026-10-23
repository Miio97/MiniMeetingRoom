package client.UI.components;

import client.UI.mock.MockData;
import java.util.Objects;
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
    private final Label name, cameraState, hostTag;
    private final StackPane mic = new StackPane();
    private final Timeline pulse;
    private final Region border = new Region();
    private boolean live;
    private Boolean currentMuted;
    private javafx.scene.image.ImageView realVideo;
    private IconButton pinButton;

    public VideoTile(common.meeting.ParticipantView participant, boolean self, javafx.beans.property.ReadOnlyObjectProperty<javafx.scene.image.Image> image) {
        live = true; getStyleClass().add("video-tile"); setMinSize(90, 100); setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        setStyle("-fx-background-color: #181b29;");
        realVideo = new javafx.scene.image.ImageView(); realVideo.setPreserveRatio(true);
        realVideo.fitWidthProperty().bind(Bindings.max(1, widthProperty().subtract(16)));
        realVideo.fitHeightProperty().bind(Bindings.max(1, heightProperty().subtract(44)));
        realVideo.imageProperty().bind(image);
        cameraState = Ui.label("Camera đang tắt hoặc đang chờ video", "caption");
        cameraState.visibleProperty().bind(image.isNull()); cameraState.managedProperty().bind(cameraState.visibleProperty());
        name = Ui.label(participant.displayName() + (self ? " (Bạn)" : ""), "tile-name");
        setMuted(!participant.mediaState().audioPublishing());
        hostTag = Ui.label(participant.role() == common.meeting.RoomRole.HOST ? "HOST" : "", "tile-tag");
        HBox footer = Ui.row(8, mic, name, Ui.spacer(), hostTag);
        footer.setMaxHeight(USE_PREF_SIZE); footer.getStyleClass().add("tile-footer"); StackPane.setAlignment(footer, Pos.BOTTOM_CENTER);
        getChildren().addAll(realVideo, cameraState, footer); pulse = new Timeline();
    }

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
        hostTag = Ui.label(participant.host() ? "HOST" : "", "tile-tag"); hostTag.setVisible(participant.host()); StackPane.setAlignment(hostTag, Pos.TOP_LEFT); StackPane.setMargin(hostTag, new Insets(14));
        border.getStyleClass().add("speaking-border"); border.setMouseTransparent(true); border.setVisible(false);
        getChildren().addAll(center, bottom, hostTag, border);
        setMuted(participant.muted()); setCamera(participant.camera());
        pulse = new Timeline(new KeyFrame(Duration.ZERO, new KeyValue(border.opacityProperty(), .5)), new KeyFrame(Duration.millis(900), new KeyValue(border.opacityProperty(), 1)));
        pulse.setAutoReverse(true); pulse.setCycleCount(Animation.INDEFINITE);
    }

    @Override protected double computePrefWidth(double height) { return 160; }
    @Override protected double computePrefHeight(double width) { return 100; }

    public void updateParticipant(common.meeting.ParticipantView participant, boolean self) {
        String display = participant.displayName() + (self ? " (Bạn)" : "");
        if (!Objects.equals(name.getText(), display)) name.setText(display);
        setMuted(!participant.mediaState().audioPublishing());
        String roleText = participant.role() == common.meeting.RoomRole.HOST ? "HOST" : "";
        if (!Objects.equals(hostTag.getText(), roleText)) hostTag.setText(roleText);
    }

    public void setMuted(boolean muted) {
        if (currentMuted != null && currentMuted == muted) return;
        currentMuted = muted;
        mic.getChildren().setAll(IconButton.icon(muted ? "mic-off" : "mic"));
        mic.getStyleClass().setAll(muted ? "muted-mic" : "tile-mic");
    }

    public void setCamera(boolean on) { cameraState.setText(on ? live ? "Đang nhận video" : "Chưa có luồng video" : "Camera đang tắt"); }
    public void setSpeaking(boolean speaking) { border.setVisible(speaking); if (speaking) pulse.play(); else pulse.stop(); }

    public void pinAction(String participantName, boolean pinned, Runnable action) {
        String tip = (pinned ? "Bỏ ghim " : "Ghim ") + participantName;
        if (pinButton == null) {
            pinButton = new IconButton("pin", tip);
            pinButton.getStyleClass().add("pin-action");
            StackPane.setAlignment(pinButton, Pos.TOP_RIGHT); StackPane.setMargin(pinButton, new Insets(8));
            getChildren().add(pinButton);
        } else if (pinButton.getTooltip() != null && !Objects.equals(pinButton.getTooltip().getText(), tip)) {
            pinButton.getTooltip().setText(tip);
        }
        pinButton.active(pinned);
        pinButton.setOnAction(e -> action.run());
    }

    public void dispose() {
        pulse.stop();
        if (realVideo != null) {
            realVideo.imageProperty().unbind(); realVideo.setImage(null);
            realVideo.fitWidthProperty().unbind(); realVideo.fitHeightProperty().unbind();
            cameraState.visibleProperty().unbind(); cameraState.managedProperty().unbind();
        }
    }
}
