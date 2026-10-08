package client.UI;

import client.UI.mock.MockData;
import client.UI.screens.*;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Điều hướng và dừng animation của màn hình cũ, tránh Timeline chạy ngầm. */
public final class SceneManager {
    private final Stage stage;
    private Runnable cleanup = () -> { };
    private ParallelTransition transition;
    private String user = "Nguyễn Minh Hiếu";
    private String password = "demo";
    private MockData.Room room = MockData.rooms().getFirst();
    private boolean mic = true, camera = true, locked;
    private final java.util.Map<String, String> preferences = new java.util.HashMap<>();
    public String preference(String key, String fallback) { return preferences.getOrDefault(key, fallback); }
    public void preferenceSet(String key, String value) { preferences.put(key, value); }
    public SceneManager(Stage stage) { this.stage = stage; }
    public Stage stage() { return stage; }
    public String user() { return user; }
    public void user(String value) { user = value; }
    public boolean matchesPassword(String value) { return password.equals(value); }
    public void password(String value) { password = value; }
    public MockData.Room room() { return room; }
    public boolean mic() { return mic; }
    public boolean camera() { return camera; }
    public void mic(boolean value) { mic = value; }
    public void camera(boolean value) { camera = value; }
    public boolean locked() { return locked; }
    public void locked(boolean value) { locked = value; }
    public void auth() {
        boolean restoreWindow = stage.isShowing() && (stage.isMaximized() || stage.isFullScreen());
        // Windows có thể áp lại kích thước Lobby sau sự kiện unmaximize.
        // Ẩn cửa sổ khi thoát trạng thái mở rộng để dựng lại ở kích thước Auth.
        if (restoreWindow) stage.hide();
        stage.setMinWidth(0); stage.setMinHeight(0);
        stage.setFullScreen(false); stage.setMaximized(false);
        AuthScreen view = new AuthScreen(this); view.setPrefSize(960, 600);
        show(view, 960, 600, false, () -> { });
        if (restoreWindow) stage.show();
        // Window manager khôi phục cửa sổ sau khi thoát fullscreen/maximized.
        // Đặt lại kích thước theo form khi sự kiện đó đã được xử lý.
        javafx.application.Platform.runLater(() -> {
            if (stage.getScene().getRoot() != view) return;
            Scene scene = stage.getScene();
            stage.setWidth(960 + Math.max(0, stage.getWidth() - scene.getWidth()));
            stage.setHeight(600 + Math.max(0, stage.getHeight() - scene.getHeight()));
            stage.centerOnScreen();
        });
    }
    public void lobby() { LobbyScreen view = new LobbyScreen(this); show(view, 1100, 700, true, view::dispose); }
    public void history() { show(new HistoryScreen(this), 1100, 700, true, () -> { }); }
    public void settings() { show(new SettingsScreen(this, false), 1100, 700, true, () -> { }); }
    public void settingsAccount() { show(new SettingsScreen(this, true), 1100, 700, true, () -> { }); }
    public void preJoin(MockData.Room next) {
        room = next;
        if (Boolean.parseBoolean(preference("muteMic", "false"))) mic = false;
        if (Boolean.parseBoolean(preference("muteCamera", "false"))) camera = false;
        PreJoinScreen view = new PreJoinScreen(this);
        show(view, 1100, 700, true, view::dispose);
    }
    public void meeting() {
        MeetingScreen view = new MeetingScreen(this);
        show(view, 1280, 800, true, view::dispose);
    }
    public void ended(long seconds, int count) { show(new MeetingEndedScreen(this, seconds, count), 1100, 700, true, () -> { }); }
    private void show(Parent root, int width, int height, boolean resize, Runnable onDispose) {
        dispose(); cleanup = onDispose;
        boolean expanded = stage.isMaximized() || stage.isFullScreen();
        stage.setMinWidth(0); stage.setMinHeight(0);
        if (!expanded) stage.setResizable(resize);
        Scene scene = stage.getScene();
        if (scene == null) {
            scene = new Scene(root, width, height);
            scene.getStylesheets().add(SceneManager.class.getResource("/styles/app.css").toExternalForm());
            stage.setScene(scene);
        } else scene.setRoot(root);
        ThemeManager.attach(root);
        if (!stage.isShowing()) stage.sizeToScene();
        else if (!expanded) {
            stage.setWidth(width + Math.max(0, stage.getWidth() - scene.getWidth()));
            stage.setHeight(height + Math.max(0, stage.getHeight() - scene.getHeight()));
        }
        if (resize) { stage.setMinWidth(1000); stage.setMinHeight(650); }
        // Không translate root Scene: có thể làm vùng nội dung hụt chiều cao khi resize.
        root.setOpacity(0); root.setTranslateY(0);
        FadeTransition fade = new FadeTransition(Duration.millis(200), root); fade.setToValue(1);
        transition = new ParallelTransition(fade); transition.play();
    }
    public void dispose() { cleanup.run(); cleanup = () -> { }; if (transition != null) transition.stop(); }
}
