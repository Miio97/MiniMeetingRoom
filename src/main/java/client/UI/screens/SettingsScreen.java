package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.mock.MockData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import java.util.List;

public final class SettingsScreen extends BorderPane {
    public SettingsScreen(SceneManager manager, boolean account) {
        setLeft(new Sidebar(manager, "settings"));
        VBox main = new VBox(22); main.setPadding(new Insets(32));
        TabPane tabs = new TabPane(); tabs.getStyleClass().add("settings-tabs"); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox devices = card();
        ComboBox<String> camera = device(manager, "cameraDevice", MockData.cameras());
        ComboBox<String> mic = device(manager, "micDevice", MockData.microphones());
        ComboBox<String> speaker = device(manager, "speakerDevice", MockData.speakers());
        ProgressBar level = new ProgressBar(.58); level.setMaxWidth(Double.MAX_VALUE);
        Slider volume = new Slider(0, 100, Double.parseDouble(manager.preference("volume", "75"))); volume.setShowTickLabels(true); volume.setMajorTickUnit(25); volume.setBlockIncrement(5);
        volume.valueProperty().addListener((o, old, value) -> manager.preferenceSet("volume", value.toString()));
        devices.getChildren().addAll(Ui.field("Camera", camera), Ui.field("Microphone", mic), Ui.field("Loa", speaker), Ui.field("Mức microphone (demo)", level), Ui.field("Âm lượng", volume));
        VBox notifications = card();
        notifications.getChildren().addAll(toggle(manager, "notification", "Âm thanh thông báo", true), toggle(manager, "muteMic", "Tự tắt mic khi vào phòng", false),
            toggle(manager, "muteCamera", "Tự tắt camera khi vào phòng", false), Ui.field("Chất lượng video tối đa", device(manager, "quality", List.of("360p", "720p", "Tự động"))));
        VBox profile = card();
        TextField username = new TextField(manager.username()), name = new TextField(manager.user()), email = new TextField(manager.email());
        username.setEditable(false); name.setEditable(false); email.setEditable(false);
        Button password = Ui.button("Đổi mật khẩu", "secondary", () -> { }); password.setDisable(true);
        Button save = Ui.button("Lưu thay đổi", "primary", () -> { }); save.setDisable(true);
        profile.getChildren().addAll(Ui.field("Tên đăng nhập", username), Ui.field("Tên hiển thị", name), Ui.field("Email", email),
            Ui.row(12, password, save), Ui.label("Thông tin từ tài khoản đã đăng nhập. Chỉnh sửa hồ sơ và đổi mật khẩu sẽ được bổ sung sau.", "caption"));
        tabs.getTabs().addAll(new Tab("Thiết bị", Ui.scroll(devices)), new Tab("Giao diện & thông báo", Ui.scroll(notifications)), new Tab("Tài khoản", Ui.scroll(profile)));
        if (account) tabs.getSelectionModel().select(2);
        VBox.setVgrow(tabs, Priority.ALWAYS); main.getChildren().addAll(Ui.label("Cài đặt", "title"), Ui.label("Thiết lập trải nghiệm họp theo cách của bạn.", "muted"), tabs); setCenter(main);
    }
    private VBox card() { VBox box = new VBox(20); box.getStyleClass().add("card"); VBox.setMargin(box, new Insets(18, 0, 0, 0)); box.setMaxWidth(Double.MAX_VALUE); return box; }
    private ComboBox<String> device(SceneManager manager, String key, List<String> choices) {
        ComboBox<String> combo = Ui.devices(choices); combo.setValue(manager.preference(key, choices.getFirst()));
        combo.valueProperty().addListener((o, old, value) -> manager.preferenceSet(key, value)); return combo;
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
