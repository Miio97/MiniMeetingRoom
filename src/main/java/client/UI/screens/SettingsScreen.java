package client.UI.screens;

import client.UI.SceneManager;
import client.UI.ThemeManager;
import client.UI.components.*;
import client.media.AudioCallController;
import client.media.AudioDeviceTester;
import client.media.MicrophoneConsentStore;
import dev.onvoid.webrtc.media.audio.AudioDevice;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.shape.Circle;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class SettingsScreen extends BorderPane {
    private final AccountSettingsPane accountPane;
    private final client.media.VideoCallController cameraOwner = new client.media.VideoCallController(
            (type, body, id) -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("NO_ROOM")), () -> null, () -> null);
    private final CameraControls cameras = new CameraControls(cameraOwner, true, null, true);
    private final AudioCallController audio = cameraOwner.audio();
    private final ComboBox<AudioDevice> microphones = audioDevice("audio-settings-microphone");
    private final ComboBox<AudioDevice> speakers = audioDevice("audio-settings-speaker");
    private final Label audioStatus = Ui.label("Đang tìm thiết bị âm thanh…", "caption");
    private final AudioDeviceTester audioTester = new AudioDeviceTester();
    private final Button microphoneTest = Ui.button("Test mic", "secondary", this::toggleMicrophoneTest);
    private final Button speakerTest = Ui.button("Test loa", "secondary", this::toggleSpeakerTest);
    private final ProgressBar microphoneLevel = new ProgressBar(0);
    private final Label microphoneTestStatus = Ui.label("Nhấn Test mic rồi nói để kiểm tra mức thu.", "caption");
    private final Label speakerTestStatus = Ui.label("Phát âm thử ngắn qua loa/tai nghe đã chọn.", "caption");
    private final AtomicReference<Double> latestMicrophoneLevel = new AtomicReference<>(0.0);
    private final AtomicBoolean levelUpdateQueued = new AtomicBoolean();
    private boolean microphoneTesting, speakerTesting;
    private volatile boolean disposed;
    private PauseTransition microphoneHealthCheck;
    private long microphoneTestToken, speakerTestToken;
    private boolean loadingAudioDevices;

    public SettingsScreen(SceneManager manager, boolean account) {
        setLeft(new Sidebar(manager, "settings"));
        VBox main = new VBox(22);
        main.setPadding(new Insets(32));
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("settings-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox devices = card();
        audioStatus.setWrapText(true);
        microphoneLevel.setMaxWidth(Double.MAX_VALUE);
        microphoneLevel.setId("audio-settings-microphone-level");
        microphoneTest.setId("audio-settings-test-microphone");
        speakerTest.setId("audio-settings-test-speaker");

        devices.getChildren().addAll(
                Ui.field("Giao diện", ThemeManager.selector()),
                Ui.field("Camera trên máy của bạn", cameras),
                Ui.field("Chất lượng video tối đa", device(manager, "quality", List.of("360p", "720p", "Tự động"))),
                Ui.field("Microphone", microphones),
                Ui.field("Loa", speakers),
                Ui.label("Kiểm tra thiết bị ngay trên máy này; âm thử không được gửi vào cuộc họp.", "caption"),
                Ui.row(12, microphoneTest, microphoneTestStatus),
                microphoneLevel,
                Ui.row(12, speakerTest, speakerTestStatus),
                audioStatus,
                toggle(manager, "notification", "Âm thanh thông báo", true),
                toggle(manager, "muteMic", "Tự tắt mic khi vào phòng", false),
                toggle(manager, "muteCamera", "Tự tắt camera khi vào phòng", false)
        );
        loadAudioDevices();

        accountPane = new AccountSettingsPane(manager, () -> setLeft(new Sidebar(manager, "settings")));
        VBox.setMargin(accountPane, new Insets(18, 0, 0, 0));

        tabs.getTabs().addAll(
                new Tab("Thiết bị", Ui.scroll(devices)),
                new Tab("Tài khoản", Ui.scroll(accountPane))
        );
        if (account) {
            tabs.getSelectionModel().select(1);
        }
        VBox.setVgrow(tabs, Priority.ALWAYS);
        main.getChildren().addAll(
                Ui.label("Cài đặt", "title"),
                Ui.label("Thiết lập trải nghiệm họp theo cách của bạn.", "muted"),
                tabs
        );
        setCenter(main);
    }

    private VBox card() {
        VBox box = new VBox(20);
        box.getStyleClass().add("card");
        VBox.setMargin(box, new Insets(18, 0, 0, 0));
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    public void dispose() {
        disposed = true;
        accountPane.dispose();
        microphoneTestToken++;
        speakerTestToken++;
        if (microphoneHealthCheck != null) {
            microphoneHealthCheck.stop();
        }
        audioTester.close();
        cameras.dispose();
        cameraOwner.close();
    }

    private void toggleMicrophoneTest() {
        if (microphoneTesting) {
            stopMicrophoneTest();
            return;
        }
        microphoneTesting = true;
        microphoneTest.setDisable(true);
        speakerTest.setDisable(true);
        setAudioDeviceControlsDisabled(true);
        microphoneTest.setText("Đang chuẩn bị…");
        microphoneTestStatus.setText("Đang mở microphone…");
        long token = ++microphoneTestToken;
        audio.choice().whenComplete((choice, failure) -> Platform.runLater(() -> {
            if (disposed || token != microphoneTestToken) return;
            if (failure != null) {
                finishMicrophoneTest(token, "Không đọc được quyền microphone.");
                return;
            }
            if (choice.access() == MicrophoneConsentStore.Access.ALLOWED) {
                startMicrophoneCapture(token);
            } else {
                askMicrophonePermission(token);
            }
        }));
    }

    private void askMicrophonePermission(long token) {
        ButtonType allow = new ButtonType("Cho phép", ButtonBar.ButtonData.YES);
        ButtonType deny = new ButtonType("Không cho phép", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION,
                "Microphone chỉ được dùng để đo mức âm thanh trên máy này trong lúc kiểm tra.", allow, deny);
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.setTitle("Quyền sử dụng microphone");
        dialog.setHeaderText("Cho phép kiểm tra microphone?");
        Ui.style(dialog);
        dialog.setOnHidden(event -> {
            boolean accepted = dialog.getResult() == allow;
            audio.access(accepted).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (disposed || token != microphoneTestToken) return;
                if (failure != null) {
                    finishMicrophoneTest(token, "Chưa lưu được quyền microphone.");
                } else if (accepted) {
                    startMicrophoneCapture(token);
                } else {
                    finishMicrophoneTest(token, "Đã hủy kiểm tra microphone.");
                }
            }));
        });
        dialog.show();
    }

    private void startMicrophoneCapture(long token) {
        microphoneTestStatus.setText("Đang mở microphone để đo tín hiệu…");
        audioTester.startMicrophone(microphones.getValue(), this::showMicrophoneLevel).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (disposed || token != microphoneTestToken) return;
            if (failure != null) {
                finishMicrophoneTest(token, audioTestError(failure, true));
            } else {
                microphoneTest.setText("Dừng kiểm tra mic");
                microphoneTest.setDisable(false);
                microphoneTestStatus.setText("Đang chờ tiếng nói từ mic; hãy nói gần mic.");
                checkMicrophoneSignal(token);
            }
        }));
    }

    private void checkMicrophoneSignal(long token) {
        if (microphoneHealthCheck != null) {
            microphoneHealthCheck.stop();
        }
        microphoneHealthCheck = new PauseTransition(Duration.seconds(2));
        microphoneHealthCheck.setOnFinished(event -> {
            if (disposed || token != microphoneTestToken || !microphoneTesting) return;
            long frames = audioTester.capturedFrames();
            if (frames == 0) {
                microphoneTestStatus.setText("Chưa nhận được dữ liệu mic. Kiểm tra mic có bị mute không, quyền Windows và thiết bị đầu vào.");
            } else if (!audioTester.signalDetected()) {
                microphoneTestStatus.setText("Đã nhận " + frames + " khung âm thanh; tín hiệu còn nhỏ. Hãy nói gần mic hoặc tăng mức Input trong Windows.");
            } else {
                microphoneTestStatus.setText("Đã nhận tín hiệu âm thanh từ microphone (" + frames + " khung).");
            }
            checkMicrophoneSignal(token);
        });
        microphoneHealthCheck.playFromStart();
    }

    private void showMicrophoneLevel(double level) {
        latestMicrophoneLevel.set(level);
        if (levelUpdateQueued.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                microphoneLevel.setProgress(Math.min(1, latestMicrophoneLevel.get() * 12));
                levelUpdateQueued.set(false);
            });
        }
    }

    private void stopMicrophoneTest() {
        if (microphoneHealthCheck != null) {
            microphoneHealthCheck.stop();
        }
        long token = ++microphoneTestToken;
        microphoneTesting = false;
        microphoneTest.setDisable(true);
        speakerTest.setDisable(true);
        microphoneTest.setText("Đang dừng…");
        audioTester.stop().whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (disposed || token != microphoneTestToken) return;
            microphoneLevel.setProgress(0);
            microphoneTest.setText("Test mic");
            microphoneTest.setDisable(false);
            speakerTest.setDisable(false);
            setAudioDeviceControlsDisabled(false);
            microphoneTestStatus.setText(failure == null ? "Đã dừng kiểm tra microphone." : client.media.CameraErrors.audioMessage(failure));
        }));
    }

    private void finishMicrophoneTest(long token, String message) {
        if (token != microphoneTestToken) return;
        if (microphoneHealthCheck != null) {
            microphoneHealthCheck.stop();
        }
        microphoneTesting = false;
        microphoneLevel.setProgress(0);
        microphoneTest.setText("Test mic");
        microphoneTest.setDisable(false);
        speakerTest.setDisable(false);
        setAudioDeviceControlsDisabled(false);
        microphoneTestStatus.setText(message);
    }

    private void toggleSpeakerTest() {
        if (speakerTesting) {
            long token = ++speakerTestToken;
            speakerTest.setText("Đang dừng…");
            speakerTest.setDisable(true);
            microphoneTest.setDisable(true);
            setAudioDeviceControlsDisabled(true);
            audioTester.stop().whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (disposed || token != speakerTestToken) return;
                speakerTesting = false;
                speakerTest.setText("Test loa");
                speakerTest.setDisable(false);
                microphoneTest.setDisable(false);
                setAudioDeviceControlsDisabled(false);
                speakerTestStatus.setText(failure == null ? "Đã dừng âm thử." : audioTestError(failure, false));
            }));
            return;
        }
        speakerTesting = true;
        microphoneTest.setDisable(true);
        setAudioDeviceControlsDisabled(true);
        speakerTest.setText("Dừng thử loa");
        speakerTestStatus.setText("Đang phát âm thử 440 Hz…");
        long token = ++speakerTestToken;
        audioTester.playSpeakerTest(speakers.getValue()).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (disposed || token != speakerTestToken) return;
            speakerTesting = false;
            speakerTest.setText("Test loa");
            microphoneTest.setDisable(false);
            setAudioDeviceControlsDisabled(false);
            speakerTestStatus.setText(failure == null ? "Đã phát xong âm thử." : audioTestError(failure, false));
        }));
    }

    private void setAudioDeviceControlsDisabled(boolean disabled) {
        microphones.setDisable(disabled);
        speakers.setDisable(disabled);
    }

    private String audioTestError(Throwable failure, boolean microphone) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return microphone ? client.media.CameraErrors.audioMessage(failure)
                : "Không phát được âm thử. Kiểm tra loa/tai nghe, âm lượng Windows và chọn lại thiết bị.";
    }

    private ComboBox<AudioDevice> audioDevice(String id) {
        ComboBox<AudioDevice> combo = new ComboBox<>();
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setId(id);
        combo.setPromptText("Thiết bị mặc định của Windows");
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(AudioDevice device) { return device == null ? "" : device.getName(); }
            @Override public AudioDevice fromString(String text) { return null; }
        });
        combo.setOnAction(event -> {
            if (loadingAudioDevices || combo.getValue() == null) return;
            AudioDevice selected = combo.getValue();
            CompletableFuture<Void> save = combo == microphones
                    ? audio.selectMicrophone(selected) : audio.selectSpeaker(selected);
            save.whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (failure != null) audioStatus.setText("Không lưu được thiết bị âm thanh. Hãy thử lại.");
                else audioStatus.setText("Đã chọn thiết bị. Nếu mic không nhận tiếng, kiểm tra nút mute và mức Input trong Windows.");
            }));
        });
        return combo;
    }

    private void loadAudioDevices() {
        CompletableFuture<MicrophoneConsentStore.Choice> choice = audio.choice();
        CompletableFuture<List<AudioDevice>> inputs = audio.microphones();
        CompletableFuture<AudioDevice> defaultInput = audio.defaultMicrophone();
        CompletableFuture<List<AudioDevice>> outputs = audio.speakers();
        CompletableFuture.allOf(choice, inputs, defaultInput, outputs).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                audioStatus.setText("Không truy cập được thiết bị âm thanh. Kiểm tra driver và quyền microphone của Windows.");
                return;
            }
            loadingAudioDevices = true;
            try {
                microphones.getItems().setAll(inputs.join());
                speakers.getItems().setAll(outputs.join());
                MicrophoneConsentStore.Choice saved = choice.join();
                String microphoneDescriptor = saved.preferredDevice();
                if (microphoneDescriptor == null || microphoneDescriptor.isBlank()) {
                    AudioDevice systemDefault = defaultInput.join();
                    microphoneDescriptor = systemDefault == null ? null : systemDefault.getDescriptor();
                }
                String selectedMicrophoneDescriptor = microphoneDescriptor;
                microphones.getItems().stream().filter(device -> device.getDescriptor().equals(selectedMicrophoneDescriptor)).findFirst().ifPresent(microphones::setValue);
                speakers.getItems().stream().filter(device -> device.getDescriptor().equals(saved.preferredSpeaker())).findFirst().ifPresent(speakers::setValue);
                audioStatus.setText((microphones.getItems().isEmpty() ? "Không tìm thấy microphone. " : "")
                        + (speakers.getItems().isEmpty() ? "Không tìm thấy loa/tai nghe." : "Thiết bị âm thanh đã sẵn sàng; voice trong phòng cần server/SFU đang chạy."));
            } finally {
                loadingAudioDevices = false;
            }
        }));
    }

    private ComboBox<String> device(SceneManager manager, String key, List<String> choices) {
        ComboBox<String> combo = Ui.devices(choices);
        combo.setId("settings-video-quality");
        combo.setValue(manager.preference(key, choices.getFirst()));
        combo.valueProperty().addListener((o, old, value) -> manager.preferenceSet(key, value));
        return combo;
    }
    private HBox toggle(SceneManager manager, String key, String title, boolean initial) {
        ToggleButton button = new ToggleButton(); button.getStyleClass().add("settings-switch"); button.setAccessibleText(title);
        StackPane track = new StackPane(); track.setMinSize(40, 22); track.setMaxSize(40, 22); track.getStyleClass().add("switch-track");
        Circle knob = new Circle(8, javafx.scene.paint.Color.WHITE); track.getChildren().add(knob); button.setGraphic(track);
        button.setSelected(Boolean.parseBoolean(manager.preference(key, String.valueOf(initial)))); knob.setTranslateX(button.isSelected() ? 9 : -9);
        button.selectedProperty().addListener((o, old, selected) -> { knob.setTranslateX(selected ? 9 : -9); manager.preferenceSet(key, selected.toString()); });
        HBox row = Ui.row(16, Ui.label(title, "strong"), Ui.spacer(), button); row.setAlignment(Pos.CENTER_LEFT); return row;
    }
}
