package client.UI.components;

import client.media.*;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import java.util.concurrent.*;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Permission is requested only by an explicit toggle, never by opening a view. */
public final class AudioControls extends VBox {
    private final AudioCallController audio;
    private final Function<Window,CompletableFuture<Boolean>> prompt;
    private final Button toggle;
    private final Label error=Ui.label("", "error-text");
    private final ChangeListener<String> statusChanged=(o,b,a) -> error.setText(a==null ? "" : a);
    private final ChangeListener<Boolean> changed=(observable,before,after) -> update();
    private final CompletableFuture<MicrophoneConsentStore.Choice> initialChoice;
    private MicrophoneConsentStore.Access access=MicrophoneConsentStore.Access.ASK;
    private boolean disposed, loaded, prompting;
    private Alert permissionDialog;
    private CompletableFuture<Void> operation;
    public AudioControls(AudioCallController audio) { this(audio,null,true); }
    public AudioControls(AudioCallController audio, Function<Window,CompletableFuture<Boolean>> prompt, boolean devices) {
        super(8); this.audio=audio; this.prompt=prompt==null ? this::ask : prompt;
        toggle=Ui.button("Bật mic", "primary", this::toggle); toggle.setId("audio-microphone-toggle"); error.setWrapText(true);
        error.visibleProperty().bind(error.textProperty().isNotEmpty()); error.managedProperty().bind(error.visibleProperty());
        getChildren().addAll(toggle,Ui.label("Mức mic: Chưa có dữ liệu", "caption"),error);
        audio.requestedProperty().addListener(changed);
        audio.pendingProperty().addListener(changed);
        audio.statusProperty().addListener(statusChanged);
        update();
        initialChoice = audio.choice();
        initialChoice.whenComplete((choice,failure) -> Platform.runLater(() -> {
            if(disposed) return; loaded=true; if(failure==null) access=choice.access(); else error.setText(CameraErrors.audioMessage(failure)); update();
        }));
        if(devices) { addDevices(true); addDevices(false); }
    }
    private void addDevices(boolean input) {
        ComboBox<AudioDevice> choices=new ComboBox<>(); choices.setPrefWidth(320); choices.setId(input ? "audio-input-device" : "audio-output-device");
        DeviceChoiceStyle.apply(choices, AudioDevice::getName);
        choices.setPlaceholder(Ui.label(input ? "Không có microphone" : "Không có loa", "caption"));
        choices.setPromptText(input ? "Microphone mặc định Windows" : "Loa mặc định Windows");
        choices.setConverter(new javafx.util.StringConverter<>() {
            public String toString(AudioDevice device) { return device==null ? "" : device.getName(); }
            public AudioDevice fromString(String value) { return null; }
        });
        choices.setOnShowing(event -> (input ? audio.microphones() : audio.speakers()).whenComplete((list,failure) -> Platform.runLater(() -> {
            if(disposed) return; if(failure!=null) error.setText("Không đọc được danh sách thiết bị âm thanh."); else choices.getItems().setAll(list);
        })));
        if(input) choices.disableProperty().bind(audio.requestedProperty().or(audio.pendingProperty()));
        choices.setOnAction(event -> {
            if(choices.getValue()==null) return;
            (input ? audio.selectMicrophone(choices.getValue()) : audio.selectSpeaker(choices.getValue())).whenComplete((value,failure) -> Platform.runLater(() -> {
                if(!disposed && failure!=null) error.setText("Tắt mic trước khi đổi microphone.");
            }));
        });
        getChildren().add(Ui.field(input ? "Microphone" : "Loa",choices));
    }
    private CompletableFuture<Boolean> ask(Window owner) {
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        ButtonType allow=new ButtonType("Cho phép",ButtonBar.ButtonData.YES), deny=new ButtonType("Không cho phép",ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog=new Alert(Alert.AlertType.CONFIRMATION,"",allow,deny); permissionDialog=dialog;
        if(owner!=null) dialog.initOwner(owner);
        dialog.setTitle("Quyền sử dụng microphone"); dialog.setHeaderText("Cho phép MiniMeetingRoom sử dụng microphone?");
        dialog.setContentText("Lựa chọn được ghi nhớ trên máy. Windows vẫn kiểm soát quyền Microphone dành cho desktop apps."); Ui.style(dialog);
        dialog.setOnHidden(event -> { permissionDialog=null; result.complete(dialog.getResult()==allow); }); dialog.show(); return result;
    }
    public void toggle() {
        if(disposed || prompting || (operation != null && !operation.isDone()) || audio.pendingProperty().get()) return;
        if(!loaded) {
            initialChoice.whenComplete((choice,failure) -> Platform.runLater(() -> {
                if(!disposed && loaded && !prompting && (operation == null || operation.isDone()) && !audio.pendingProperty().get()) toggle();
            }));
            return;
        }
        if(audio.requestedProperty().get() || access==MicrophoneConsentStore.Access.ALLOWED) { apply(); return; }
        prompting=true; update();
        prompt.apply(getScene()==null ? null : getScene().getWindow()).whenComplete((allowed,failure) -> Platform.runLater(() -> {
            if(disposed) return;
            if(failure!=null) { prompting=false; update(); return; }
            boolean accepted=Boolean.TRUE.equals(allowed);
            audio.access(accepted).whenComplete((value,saveFailure) -> Platform.runLater(() -> {
                if(disposed) return; prompting=false; update();
                if(saveFailure!=null) { error.setText("Chưa lưu được quyền microphone."); return; }
                access=accepted ? MicrophoneConsentStore.Access.ALLOWED : MicrophoneConsentStore.Access.DENIED;
                if(accepted) apply(); else error.setText("Microphone đang tắt. Nhấn Bật mic để thử lại.");
            }));
        }));
    }
    private void apply() {
        CompletableFuture<Void> next=audio.toggle(); if(operation==next) return; operation=next; error.setText(""); update();
        next.whenComplete((value,failure) -> Platform.runLater(() -> { if(!disposed && operation==next) { error.setText(failure==null ? "" : CameraErrors.audioMessage(failure)); update(); } }));
    }
    /** Move only the existing button; permission/device ownership stays with this component. */
    public Button separatePreviewToggle() {
        getChildren().remove(toggle);
        // Fields first, then the honest unavailable-level message and any error.
        var level = getChildren().removeFirst();
        getChildren().remove(error);
        getChildren().add(1, level);
        getChildren().add(error);
        return toggle;
    }
    private void update() {
        boolean busy = (operation != null && !operation.isDone()) || audio.pendingProperty().get();
        toggle.setText(prompting ? "Đang xin quyền…" : busy ? "Đang xử lý…" : audio.requestedProperty().get() ? "Tắt mic" : "Bật mic");
        toggle.setDisable(!loaded || prompting || busy);
    }
    public javafx.beans.property.ReadOnlyStringProperty errorProperty() { return error.textProperty(); }
    public void dispose() {
        disposed=true;
        audio.requestedProperty().removeListener(changed);
        audio.pendingProperty().removeListener(changed);
        audio.statusProperty().removeListener(statusChanged);
        if(permissionDialog!=null) permissionDialog.close();
    }
}
