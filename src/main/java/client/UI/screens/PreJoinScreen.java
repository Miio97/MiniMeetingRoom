package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.mock.MockData;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

public final class PreJoinScreen extends BorderPane {
    private final Timeline meter;
    private boolean disposed;
    private CameraControls cameraControls;
    private javafx.scene.image.ImageView nativePreview;
    private Label entryError;
    public boolean cameraPermissionGranted() { return cameraControls != null && cameraControls.permissionGrantedProperty().get(); }
    public void requireCameraPermission() { if (entryError != null) entryError.setText("Bạn cần cho phép camera để vào phòng họp."); }
    public PreJoinScreen(SceneManager manager) {
        setPadding(new Insets(32));
        setTop(Ui.column(8, Ui.label("TRƯỚC KHI VÀO PHÒNG", "eyebrow"), Ui.label("Bạn đã sẵn sàng?", "title"), Ui.label(manager.room().name() + "  ·  " + manager.room().code(), "muted")));
        if (manager.hasVideoRoom()) { meter = new Timeline(); buildVideoPreview(manager); return; }
        StackPane preview = new StackPane(); preview.getStyleClass().add("preview"); preview.setMinHeight(300);
        Label state = Ui.label("Camera xem trước · mô phỏng", "preview-caption"); StackPane.setAlignment(state, Pos.BOTTOM_CENTER); StackPane.setMargin(state, new Insets(24));
        preview.getChildren().addAll(Ui.avatar(manager.user(), 128), state);
        IconButton mic = new IconButton(manager.mic() ? "mic" : "mic-off", "Bật/tắt microphone"); mic.active(manager.mic());
        IconButton camera = new IconButton(manager.camera() ? "camera" : "camera-off", "Bật/tắt camera"); camera.active(manager.camera());
        mic.setOnAction(e -> { manager.mic(!manager.mic()); mic.setIcon(manager.mic() ? "mic" : "mic-off"); mic.active(manager.mic()); });
        Runnable cameraState = () -> { camera.setIcon(manager.camera() ? "camera" : "camera-off"); camera.active(manager.camera()); state.setText(manager.camera() ? "Camera xem trước · mô phỏng" : "Camera đang tắt"); };
        camera.setOnAction(e -> { manager.camera(!manager.camera()); cameraState.run(); }); cameraState.run();
        HBox toggles = Ui.row(16, mic, camera); toggles.setAlignment(Pos.CENTER);
        VBox left = Ui.column(20, preview, toggles, Ui.label("Chỉ có bạn nhìn thấy bản xem trước này.", "caption")); left.setAlignment(Pos.CENTER); VBox.setVgrow(preview, Priority.ALWAYS); HBox.setHgrow(left, Priority.ALWAYS);
        ProgressBar level = new ProgressBar(.5); level.setMaxWidth(Double.MAX_VALUE);
        Label error = Ui.label("", "error-text"); error.setWrapText(true); error.setId("prejoin-error");
        Button enter = Ui.button("Vào phòng họp  →", "primary", manager::meeting); enter.setId("enter-meeting");
        Button back = Ui.button("←  Quay lại sảnh", "text-button", () -> {
            if (disposed) return; setDisable(true); error.setText("Đang rời phòng…");
            manager.leaveRoom(() -> { if (!disposed) manager.lobby(); }, message -> {
                if (!disposed) { setDisable(false); error.setText(message); }
            });
        }); back.setId("cancel-prejoin");
        VBox right = Ui.column(18, Ui.label("Kiểm tra thiết bị", "subtitle"), Ui.label("Phòng cũ chưa có video WebRTC; tạo phòng mới để dùng camera thật.", "caption"), Ui.field("Microphone", Ui.devices(MockData.microphones())), Ui.field("Loa", Ui.devices(MockData.speakers())), Ui.row(12, Ui.label("Mức âm thanh", "field-label"), Ui.spacer(), Ui.label("Mô phỏng", "caption")), level, Ui.spacer(), error, enter, back);
        right.getStyleClass().add("card"); right.setPrefWidth(340); right.setMinWidth(320);
        HBox body = Ui.row(28, left, right); body.setPadding(new Insets(28, 0, 0, 0)); setCenter(body);
        // Animation biểu diễn level mic, không lấy tín hiệu từ thiết bị thật.
        meter = new Timeline(new KeyFrame(Duration.millis(150), e -> level.setProgress(manager.mic() ? .15 + Math.random() * .7 : 0))); meter.setCycleCount(Animation.INDEFINITE); meter.play();
    }
    private void buildVideoPreview(SceneManager manager) {
        var session = manager.sfuSession(); var video = session.video();
        StackPane preview = new StackPane(); preview.getStyleClass().add("preview"); preview.setMinHeight(300);
        nativePreview = new javafx.scene.image.ImageView(); nativePreview.setPreserveRatio(true);
        nativePreview.fitWidthProperty().bind(preview.widthProperty().subtract(24)); nativePreview.fitHeightProperty().bind(preview.heightProperty().subtract(48));
        nativePreview.imageProperty().bind(video.frames(session.selfParticipantId()).imageProperty());
        preview.getChildren().add(nativePreview);
        cameraControls = new CameraControls(video, true);
        Label error = Ui.label("", "error-text"); error.setId("prejoin-error"); error.setWrapText(true);
        entryError = error;
        error.visibleProperty().bind(error.textProperty().isNotEmpty()); error.managedProperty().bind(error.visibleProperty());
        Button enter = Ui.button("Vào phòng họp  →", "primary", manager::meeting); enter.setId("enter-meeting");
        enter.disableProperty().bind(cameraControls.permissionGrantedProperty().not());
        enter.setTooltip(new Tooltip("Nhấn Bật camera và cho phép để vào phòng họp."));
        cameraControls.permissionGrantedProperty().addListener((observable, before, allowed) -> {
            enter.setTooltip(allowed ? null : new Tooltip("Nhấn Bật camera và cho phép để vào phòng họp."));
            if (allowed) error.setText("");
        });
        Button back = Ui.button("←  Quay lại sảnh", "text-button", () -> {
            if (disposed) return; setDisable(true);
            manager.leaveRoom(() -> { if (!disposed) manager.lobby(); }, message -> { if (!disposed) { setDisable(false); error.setText(message); } });
        }); back.setId("cancel-prejoin");
        VBox body = Ui.column(18, preview, cameraControls, error,
                Ui.row(12, enter, back)); body.setPadding(new Insets(28, 0, 0, 0)); VBox.setVgrow(preview, Priority.ALWAYS); setCenter(body);
    }
    public void dispose() {
        disposed = true; meter.stop();
        if (cameraControls != null) cameraControls.dispose();
        if (nativePreview != null) nativePreview.imageProperty().unbind();
    }
}
