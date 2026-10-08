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
        VBox profile = card(); TextField name = new TextField(manager.user()); TextField email = new TextField("hieu.nguyen@example.com"); email.setEditable(false);
        Label result = Ui.label("", "success-text"); result.setManaged(false);
        Button save = Ui.button("Lưu thay đổi", "primary", () -> {
            if (name.getText().isBlank()) { result.setText("Vui lòng nhập tên hiển thị."); result.getStyleClass().setAll("error-text"); result.setManaged(true); return; }
            manager.user(name.getText().strip()); setLeft(new Sidebar(manager, "settings")); result.setText("Đã lưu thay đổi."); result.getStyleClass().setAll("success-text"); result.setManaged(true);
        });
        profile.getChildren().addAll(Ui.field("Tên hiển thị", name), Ui.field("Email", email), Ui.row(12, Ui.button("Đổi mật khẩu", "secondary", () -> passwordDialog(manager)), save), result);
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
    private void passwordDialog(SceneManager manager) {
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(manager.stage()); dialog.setTitle("Đổi mật khẩu"); dialog.setHeaderText("Đổi mật khẩu");
        PasswordField old = new PasswordField(), next = new PasswordField(), repeat = new PasswordField();
        old.setPromptText("Mật khẩu hiện tại"); next.setPromptText("Ít nhất 6 ký tự"); repeat.setPromptText("Nhập lại mật khẩu mới");
        Label error = Ui.label("", "error-text"); error.setManaged(false);
        VBox content = Ui.column(12, Ui.field("Mật khẩu cũ", old), Ui.field("Mật khẩu mới", next), Ui.field("Nhập lại", repeat), error); content.setPrefWidth(380);
        dialog.getDialogPane().setContent(content);
        ButtonType save = new ButtonType("Đổi mật khẩu", ButtonBar.ButtonData.OK_DONE); dialog.getDialogPane().getButtonTypes().addAll(new ButtonType("Hủy", ButtonBar.ButtonData.CANCEL_CLOSE), save); Ui.style(dialog);
        Button submit = (Button) dialog.getDialogPane().lookupButton(save); submit.getStyleClass().add("primary");
        submit.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String message = !manager.matchesPassword(old.getText()) ? "Mật khẩu cũ chưa đúng." : next.getText().length() < 6 ? "Mật khẩu mới cần ít nhất 6 ký tự." : !next.getText().equals(repeat.getText()) ? "Mật khẩu nhập lại chưa khớp." : null;
            if (message != null) { error.setText(message); error.setManaged(true); event.consume(); }
            else manager.password(next.getText());
        }); dialog.showAndWait();
    }
}
