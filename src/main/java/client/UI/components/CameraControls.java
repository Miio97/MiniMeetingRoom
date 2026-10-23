package client.UI.components;

import client.media.*;
import dev.onvoid.webrtc.media.video.VideoDevice;
import java.util.concurrent.*;
import java.util.function.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Ask on explicit camera start only; remembered consent allows discovery without another prompt. */
public final class CameraControls extends VBox {
    private final VideoCallController video;
    private final boolean previewOnly;
    private final ComboBox<VideoDevice> cameras = new ComboBox<>();
    private final Button toggle;
    private final Label status = Ui.label("", "error-text");
    private final javafx.beans.property.ReadOnlyBooleanWrapper permissionGranted = new javafx.beans.property.ReadOnlyBooleanWrapper();
    private final Function<Window, CompletableFuture<Boolean>> prompt;
    private final ChangeListener<Boolean> listener = (observable, before, after) -> captureChanged(after);
    private CameraConsentStore.Access access = CameraConsentStore.Access.ASK;
    private String preferred = "";
    private boolean loading, prompting, applying, disposed, started, initialized;
    private Alert permissionDialog;
    private CompletableFuture<Void> cameraOperation;
    public CameraControls(VideoCallController video, boolean previewOnly) { this(video, previewOnly, null); }
    public CameraControls(VideoCallController video, boolean previewOnly, Function<Window, CompletableFuture<Boolean>> prompt) {
        this(video, previewOnly, prompt, false);
    }
    public CameraControls(VideoCallController video, boolean previewOnly, Function<Window, CompletableFuture<Boolean>> prompt, boolean selectionOnly) {
        super(6); this.video = video; this.previewOnly = previewOnly; this.prompt = prompt == null ? this::ask : prompt;
        setId("camera-controls"); cameras.setId("video-camera-device"); cameras.setPromptText("Chưa cấp quyền camera"); cameras.setPrefWidth(240);
        status.setId("camera-device-status");
        cameras.setPlaceholder(Ui.label("Không có camera", "caption"));
        DeviceChoiceStyle.apply(cameras, VideoDevice::getName);
        cameras.setConverter(new javafx.util.StringConverter<>() {
            public String toString(VideoDevice device) { return device == null ? "" : device.getName(); }
            public VideoDevice fromString(String value) { return null; }
        });
        toggle = Ui.button("Bật camera", "primary", this::toggle); toggle.setId("video-camera-toggle");
        toggle.setVisible(!selectionOnly); toggle.setManaged(!selectionOnly);
        cameras.valueProperty().addListener((observable, before, after) -> {
            controls();
            if (!applying && after != null && access == CameraConsentStore.Access.ALLOWED) {
                preferred = after.getDescriptor();
                video.preferredCamera(after).whenComplete((value, error) -> Platform.runLater(() -> {
                    if (!disposed && error != null) status.setText("Chưa lưu được camera đã chọn cho lần sau.");
                }));
            }
        });
        cameras.setOnShowing(event -> loadDevices(false));
        status.setWrapText(true); video.cameraRequestedProperty().addListener(listener); video.cameraPendingProperty().addListener(listener);
        status.visibleProperty().bind(status.textProperty().isNotEmpty()); status.managedProperty().bind(status.visibleProperty());
        getChildren().addAll(Ui.row(10, cameras, toggle), status);
        sceneProperty().addListener((observable, before, after) -> { if (after != null) Platform.runLater(this::initialize); });
        Platform.runLater(this::initialize);
        controls();
    }
    private void captureChanged(boolean active) { controls(); }
    public javafx.beans.property.ReadOnlyBooleanProperty permissionGrantedProperty() { return permissionGranted.getReadOnlyProperty(); }
    private void initialize() {
        if (started || disposed) return; started = true;
        video.cameraChoice().whenComplete((choice, error) -> Platform.runLater(() -> {
            if (disposed) return;
            initialized = true;
            if (error != null) { status.setText(CameraErrors.message(error)); controls(); return; }
            access = choice.access(); preferred = choice.preferredDevice(); controls();
            if (access == CameraConsentStore.Access.ALLOWED) loadDevices(false);
            else denied();
        }));
    }
    private CompletableFuture<Boolean> ask(Window owner) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ButtonType allow = new ButtonType("Cho phép", ButtonBar.ButtonData.YES);
        ButtonType deny = new ButtonType("Không cho phép", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION, "", allow, deny); permissionDialog = dialog;
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle("Quyền sử dụng camera"); dialog.setHeaderText("Cho phép MiniMeetingRoom sử dụng camera của bạn?");
        dialog.setContentText("Bạn cần cho phép camera để vào phòng họp. Lựa chọn được ghi nhớ trên máy này.");
        Ui.style(dialog); dialog.setOnHidden(event -> { permissionDialog = null; result.complete(dialog.getResult() == allow); }); dialog.show();
        return result;
    }
    private void requestPermission() {
        if (video.cameraPendingProperty().get() || prompting || disposed) return; prompting = true; controls();
        prompt.apply(getScene() == null ? null : getScene().getWindow()).whenComplete((allowed, error) -> Platform.runLater(() -> {
            if (disposed) return;
            if (error != null) { prompting = false; controls(); status.setText("Chưa nhận được lựa chọn quyền camera."); return; }
            boolean accepted = Boolean.TRUE.equals(allowed);
            video.cameraAccess(accepted).thenCompose(value -> accepted ? CompletableFuture.completedFuture(null) : video.stopCamera())
                    .whenComplete((value, failure) -> Platform.runLater(() -> {
                        if (disposed) return; prompting = false;
                        if (failure != null) { controls(); status.setText("Chưa lưu được quyền camera. Vui lòng thử lại."); return; }
                        access = accepted ? CameraConsentStore.Access.ALLOWED : CameraConsentStore.Access.DENIED;
                        controls(); if (accepted) loadDevices(true); else { denied(); status.setText("Bạn cần cho phép camera để vào phòng họp."); }
                    }));
        }));
    }
    private void denied() {
        applying = true; cameras.getItems().clear(); cameras.setValue(null); applying = false;
        cameras.setPromptText("Chọn camera"); controls();
    }
    private void loadDevices(boolean startAfter) {
        if (loading || disposed || prompting || access != CameraConsentStore.Access.ALLOWED) return;
        loading = true; if (cameras.getItems().isEmpty()) cameras.setPromptText("Đang tải camera…"); controls();
        video.cameras().whenComplete((devices, error) -> Platform.runLater(() -> {
            if (disposed) return; loading = false;
            if (error != null) { cameras.setPromptText("Không đọc được camera"); status.setText(CameraErrors.message(error)); controls(); return; }
            String selected = cameras.getValue() == null ? preferred : cameras.getValue().getDescriptor();
            var ordered = devices;
            applying = true; cameras.getItems().setAll(ordered); cameras.setValue(CameraSelection.choose(ordered, selected)); applying = false;
            cameras.setPromptText(devices.isEmpty() ? "Không có camera" : "Chọn camera");
            status.setText("");
            controls();
            if (startAfter && cameras.getValue() != null) toggle();
        }));
    }
    public void toggle() {
        if (!initialized || prompting || disposed) return;
        if (video.cameraRequestedProperty().get()) switchCamera();
        else if (access != CameraConsentStore.Access.ALLOWED) requestPermission();
        else if (cameras.getValue() == null) loadDevices(true);
        else switchCamera();
    }
    private void switchCamera() {
        var operation = video.toggleCamera(cameras.getValue(), previewOnly);
        if (operation == cameraOperation) return;
        cameraOperation = operation; status.setText("");
        operation.whenComplete((value, error) -> Platform.runLater(() -> {
            if (!disposed && cameraOperation == operation && error != null) status.setText(CameraErrors.message(error));
        }));
    }
    public void focusDevices() { cameras.requestFocus(); cameras.show(); }
    /** Rehome the existing toggle beside the preview, retaining its action and state. */
    public Button separatePreviewToggle() {
        ((javafx.scene.layout.HBox) getChildren().getFirst()).getChildren().remove(toggle);
        getChildren().setAll(Ui.field("Camera", cameras), status);
        return toggle;
    }
    public javafx.beans.property.ReadOnlyStringProperty errorProperty() { return status.textProperty(); }
    private void controls() {
        permissionGranted.set(initialized && access == CameraConsentStore.Access.ALLOWED && !prompting && !loading);
        boolean active = video.cameraRequestedProperty().get(), pending = video.cameraPendingProperty().get();
        toggle.setText(prompting ? "Đang xin quyền…" : loading ? "Đang tải…" : active ? "Tắt camera" : "Bật camera");
        toggle.setDisable(!initialized || prompting || loading && !active && cameras.getItems().isEmpty());
        cameras.setDisable(pending || prompting || active || access != CameraConsentStore.Access.ALLOWED || loading && cameras.getItems().isEmpty());
    }
    public void dispose() {
        disposed = true; video.cameraRequestedProperty().removeListener(listener); video.cameraPendingProperty().removeListener(listener);
        if (permissionDialog != null) permissionDialog.close();
    }
}
