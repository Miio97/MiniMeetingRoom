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
    public PreJoinScreen(SceneManager manager) {
        setPadding(new Insets(32));
        setTop(Ui.column(8, Ui.label("TRƯỚC KHI VÀO PHÒNG", "eyebrow"), Ui.label("Bạn đã sẵn sàng?", "title"), Ui.label(manager.room().name() + "  ·  " + manager.room().code(), "muted")));
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
        VBox right = Ui.column(18, Ui.label("Kiểm tra thiết bị", "subtitle"), Ui.field("Camera", Ui.devices(MockData.cameras())), Ui.field("Microphone", Ui.devices(MockData.microphones())), Ui.field("Loa", Ui.devices(MockData.speakers())), Ui.row(12, Ui.label("Mức âm thanh", "field-label"), Ui.spacer(), Ui.label("Mô phỏng", "caption")), level, Ui.spacer(), Ui.button("Vào phòng họp  →", "primary", manager::meeting), Ui.button("←  Quay lại sảnh", "text-button", manager::lobby));
        right.getStyleClass().add("card"); right.setPrefWidth(340); right.setMinWidth(320);
        HBox body = Ui.row(28, left, right); body.setPadding(new Insets(28, 0, 0, 0)); setCenter(body);
        // Animation biểu diễn level mic, không lấy tín hiệu từ thiết bị thật.
        meter = new Timeline(new KeyFrame(Duration.millis(150), e -> level.setProgress(manager.mic() ? .15 + Math.random() * .7 : 0))); meter.setCycleCount(Animation.INDEFINITE); meter.play();
    }
    public void dispose() { meter.stop(); }
}
