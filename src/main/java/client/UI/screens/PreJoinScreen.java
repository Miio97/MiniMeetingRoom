package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.mock.MockData;
import javafx.animation.*;
import javafx.beans.binding.Bindings;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;

/** Media remains session-owned; this view owns only layout, controls and frame bindings. */
public final class PreJoinScreen extends BorderPane {
    private Timeline meter;
    private boolean disposed, entering;
    private CameraControls cameraControls;
    private AudioControls audioControls;
    private ImageView nativePreview;
    private Label placeholder;
    private final Label entryError = Ui.label("", "error-text");

    public boolean cameraPermissionGranted() { return cameraControls != null && cameraControls.permissionGrantedProperty().get(); }
    public void requireCameraPermission() { entryError.setText("Bạn cần cho phép camera để vào phòng họp."); }

    public PreJoinScreen(SceneManager manager) {
        getStyleClass().add("prejoin-screen");
        VBox heading = Ui.column(4, Ui.label("TRƯỚC KHI VÀO PHÒNG", "eyebrow"),
                Ui.label("Bạn đã sẵn sàng?", "title"),
                Ui.label(manager.room().name() + "  ·  " + manager.room().code(), "muted"));
        heading.getStyleClass().add("prejoin-heading"); setTop(heading);
        PreviewPane preview = new PreviewPane(); preview.setId("prejoin-preview");
        placeholder = Ui.label("Camera đang tắt", "prejoin-placeholder");
        placeholder.setMaxWidth(360); placeholder.setAlignment(Pos.CENTER);
        HBox toggles;
        VBox settings = Ui.column(14, Ui.label("THIẾT LẬP THIẾT BỊ", "field-label"));
        settings.setId("prejoin-settings"); settings.getStyleClass().add("prejoin-settings");
        settings.setMinWidth(0); settings.setMaxWidth(460); settings.setMaxHeight(USE_PREF_SIZE);
        if (manager.hasVideoRoom()) {
            var session = manager.sfuSession(); var video = session.video();
            nativePreview = new ImageView(); nativePreview.setPreserveRatio(true);
            // Source image dimensions must never participate in parent size negotiation.
            nativePreview.fitWidthProperty().bind(Bindings.max(0, preview.widthProperty().subtract(2)));
            nativePreview.fitHeightProperty().bind(Bindings.max(0, preview.heightProperty().subtract(2)));
            nativePreview.imageProperty().bind(video.frames(session.selfParticipantId()).imageProperty());
            nativePreview.visibleProperty().bind(video.cameraRequestedProperty().and(video.cameraPendingProperty().not()).and(nativePreview.imageProperty().isNotNull()));
            cameraControls = new CameraControls(video, true);
            audioControls = new AudioControls(video.audio());
            toggles = Ui.row(12, cameraControls.separatePreviewToggle(), audioControls.separatePreviewToggle());
            settings.getChildren().addAll(cameraControls, audioControls);
            placeholder.textProperty().bind(Bindings.createStringBinding(() -> {
                if (!cameraControls.errorProperty().get().isBlank()) return cameraControls.errorProperty().get();
                if (!video.statusProperty().get().isBlank()) return video.statusProperty().get();
                if (video.cameraPendingProperty().get()) return "Đang chuẩn bị camera…";
                return video.cameraRequestedProperty().get() ? "Đang chờ hình ảnh từ camera…" : "Camera đang tắt · Nhấn Bật camera để xem trước";
            }, cameraControls.errorProperty(), video.statusProperty(), video.cameraPendingProperty(), video.cameraRequestedProperty()));
            placeholder.visibleProperty().bind(nativePreview.visibleProperty().not());
            preview.getChildren().add(nativePreview);
        } else {
            preview.getChildren().add(Ui.avatar(manager.user(), 96));
            StackPane.setAlignment(placeholder, Pos.BOTTOM_CENTER);
            StackPane.setMargin(placeholder, new javafx.geometry.Insets(16));
            Button mic = new Button(), camera = new Button();
            mic.getStyleClass().add("secondary"); camera.getStyleClass().add("secondary");
            Runnable update = () -> {
                mic.setText(manager.mic() ? "Tắt mic" : "Bật mic");
                camera.setText(manager.camera() ? "Tắt camera" : "Bật camera");
                placeholder.setText(manager.camera() ? "Camera xem trước · mô phỏng" : "Camera đang tắt");
            };
            mic.setOnAction(e -> { manager.mic(!manager.mic()); update.run(); });
            camera.setOnAction(e -> { manager.camera(!manager.camera()); update.run(); }); update.run();
            toggles = Ui.row(12, camera, mic);
            ProgressBar level = new ProgressBar(0); level.setMaxWidth(Double.MAX_VALUE);
            settings.getChildren().addAll(Ui.label("Phòng cũ dùng thiết bị mô phỏng; tạo phòng mới để dùng camera thật.", "caption"),
                    Ui.field("Camera", Ui.devices(MockData.cameras())),
                    Ui.field("Microphone", Ui.devices(MockData.microphones())),
                    Ui.field("Mức microphone · Mô phỏng", level), Ui.field("Loa", Ui.devices(MockData.speakers())));
            meter = new Timeline(new KeyFrame(Duration.millis(150), e -> level.setProgress(manager.mic() ? .15 + Math.random() * .7 : 0)));
            meter.setCycleCount(Animation.INDEFINITE); meter.play();
        }
        preview.getChildren().add(placeholder);
        toggles.setAlignment(Pos.CENTER);
        VBox left = Ui.column(12, preview, toggles, Ui.label("Chỉ có bạn nhìn thấy bản xem trước này.", "caption"));
        left.setAlignment(Pos.TOP_CENTER); left.setMinWidth(0);
        ResponsiveBody body = new ResponsiveBody(left, settings);
        StackPane content = new StackPane(body); content.setAlignment(Pos.TOP_CENTER);
        content.getStyleClass().add("prejoin-content");
        ScrollPane scroll = Ui.scroll(content); scroll.setId("prejoin-scroll"); scroll.setMinSize(0, 0);
        scroll.getStyleClass().add("prejoin-scroll"); setCenter(scroll);
        entryError.setId("prejoin-error");
        entryError.visibleProperty().bind(entryError.textProperty().isNotEmpty());
        entryError.managedProperty().bind(entryError.visibleProperty());
        Button enter = Ui.button("Vào phòng họp  →", "primary", () -> {
            if (disposed || entering) return;
            entering = true;
            try { manager.meeting(); } finally { if (!disposed) entering = false; }
        }); enter.setId("enter-meeting"); enter.setDefaultButton(true);
        if (cameraControls != null) {
            enter.disableProperty().bind(cameraControls.permissionGrantedProperty().not());
            Tooltip tip = new Tooltip("Nhấn Bật camera và cho phép để vào phòng họp.");
            enter.tooltipProperty().bind(Bindings.when(cameraControls.permissionGrantedProperty()).then((Tooltip) null).otherwise(tip));
        }
        Button back = Ui.button("←  Quay lại sảnh", "secondary", () -> {
            if (disposed || entering) return;
            setDisable(true);
            manager.leaveRoom(() -> { if (!disposed) manager.lobby(); }, message -> {
                if (!disposed) { setDisable(false); entryError.setText(message); }
            });
        }); back.setId("cancel-prejoin");
        for (Button button : new Button[] {enter, back}) {
            button.getStyleClass().add("prejoin-action"); button.setMinWidth(Region.USE_PREF_SIZE);
        }
        FlowPane actions = new FlowPane(12, 8, back, enter); actions.setAlignment(Pos.CENTER_RIGHT);
        VBox footer = Ui.column(8, entryError, actions); footer.setId("prejoin-footer");
        footer.getStyleClass().add("prejoin-footer"); setBottom(footer);
    }

    /** Width drives height, with a bounded 16:9 surface independent of source resolution. */
    private static final class PreviewPane extends StackPane {
        PreviewPane() {
            getStyleClass().add("prejoin-preview");
            setMinWidth(0); setMinHeight(USE_PREF_SIZE); setMaxSize(420 * 16.0 / 9, 420);
        }
        @Override public Orientation getContentBias() { return Orientation.HORIZONTAL; }
        @Override protected double computePrefHeight(double width) { return Math.min(420, Math.max(0, width) * 9 / 16); }
        @Override protected double computePrefWidth(double height) { return 640; }
    }

    /** Reposition existing nodes at a breakpoint; no resize listeners or media rebuilds. */
    private static final class ResponsiveBody extends GridPane {
        private final Node preview, settings;
        private boolean wide;
        ResponsiveBody(Node preview, Node settings) {
            this.preview = preview; this.settings = settings;
            setMaxWidth(1440); setHgap(24); setVgap(20); setMinWidth(0);
            getChildren().addAll(preview, settings); configure(false);
        }
        private void configure(boolean next) {
            wide = next; getColumnConstraints().clear();
            ColumnConstraints left = new ColumnConstraints(); left.setMinWidth(0); left.setHgrow(Priority.ALWAYS);
            getColumnConstraints().add(left);
            if (wide) {
                left.setPercentWidth(60);
                ColumnConstraints right = new ColumnConstraints(); right.setMinWidth(0); right.setPercentWidth(40);
                getColumnConstraints().add(right);
            }
            setConstraints(preview, 0, 0); setConstraints(settings, wide ? 1 : 0, wide ? 0 : 1);
            setValignment(preview, javafx.geometry.VPos.TOP); setValignment(settings, javafx.geometry.VPos.TOP);
            setHalignment(settings, javafx.geometry.HPos.CENTER);
        }
        @Override protected double computePrefHeight(double width) {
            if (wide != (width >= 860)) configure(width >= 860);
            return super.computePrefHeight(width);
        }
        @Override protected void layoutChildren() {
            if (wide != (getWidth() >= 860)) configure(getWidth() >= 860);
            super.layoutChildren();
        }
    }

    public void dispose() {
        disposed = true;
        if (meter != null) meter.stop();
        if (cameraControls != null) cameraControls.dispose();
        if (audioControls != null) audioControls.dispose();
        placeholder.textProperty().unbind(); placeholder.visibleProperty().unbind();
        if (nativePreview != null) {
            nativePreview.imageProperty().unbind(); nativePreview.setImage(null);
            nativePreview.fitWidthProperty().unbind(); nativePreview.fitHeightProperty().unbind();
            nativePreview.visibleProperty().unbind();
        }
    }
}
