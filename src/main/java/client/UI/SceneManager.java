package client.UI;

import client.UI.mock.MockData;
import client.UI.screens.*;
import client.UI.components.Ui;
import client.auth.AuthClient;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.io.IOException;
import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Điều hướng và dừng animation của màn hình cũ, tránh Timeline chạy ngầm. */
public final class SceneManager {
    private final Stage stage;
    private Runnable cleanup = () -> { };
    private ParallelTransition transition;
    private final AuthClient authClient;
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "mini-meeting-auth"); worker.setDaemon(true); return worker;
    });
    private final ReadOnlyBooleanWrapper logoutPending = new ReadOnlyBooleanWrapper();
    private AuthUser authenticatedUser;
    private String sessionToken;
    private volatile String pendingToken;
    private volatile boolean closing;
    private volatile boolean closed;
    private PauseTransition closeDeadline;
    private MockData.Room room = MockData.rooms().getFirst();
    private boolean mic = true, camera = true, locked;
    private final java.util.Map<String, String> preferences = new java.util.HashMap<>();
    public String preference(String key, String fallback) { return preferences.getOrDefault(key, fallback); }
    public void preferenceSet(String key, String value) { preferences.put(key, value); }
    public SceneManager(Stage stage) { this(stage, new AuthClient()); }
    SceneManager(Stage stage, AuthClient authClient) {
        this.stage = java.util.Objects.requireNonNull(stage);
        this.authClient = java.util.Objects.requireNonNull(authClient);
    }
    public Stage stage() { return stage; }
    public AuthClient authClient() { return authClient; }
    public AuthUser authenticatedUser() { return authenticatedUser; }
    public String user() { return authenticatedUser == null ? "" : authenticatedUser.displayName(); }
    public String username() { return authenticatedUser == null ? "" : authenticatedUser.username(); }
    public String email() { return authenticatedUser == null || authenticatedUser.email() == null ? "" : authenticatedUser.email(); }
    public ReadOnlyBooleanProperty logoutPendingProperty() { return logoutPending.getReadOnlyProperty(); }
    public void acceptSession(AuthResponse response) {
        if (!response.success() || response.user() == null || response.token() == null || response.token().isBlank()) {
            throw new IllegalArgumentException("Phiên đăng nhập không hợp lệ.");
        }
        authenticatedUser = response.user(); sessionToken = response.token(); pendingToken = null;
    }
    /** Gửi mạng ngoài luồng JavaFX; chỉ cập nhật màn hình khi ứng dụng còn hoạt động. */
    public void runAuthRequest(Callable<AuthResponse> request, Consumer<AuthResponse> success, Consumer<Throwable> failure) {
        if (closing) return;
        authWorker.execute(() -> {
            try {
                AuthResponse response = request.call();
                if (response.token() != null && !response.token().isBlank()) pendingToken = response.token();
                if (closing) {
                    revokeQuietly(response.token()); pendingToken = null; return;
                }
                Platform.runLater(() -> {
                    if (!closing) success.accept(response);
                });
            } catch (Exception error) {
                if (!closing) Platform.runLater(() -> { if (!closing) failure.accept(error); });
            }
        });
    }
    public static String connectionError(Throwable error) {
        if (error instanceof SocketTimeoutException) return "Máy chủ phản hồi quá lâu. Vui lòng thử lại.";
        if (error instanceof UnknownHostException) return "Không tìm thấy máy chủ. Kiểm tra địa chỉ trong config/auth-client.properties.";
        if (error instanceof SocketException || error instanceof EOFException) return "Kết nối máy chủ bị gián đoạn. Vui lòng thử lại.";
        if (error instanceof IOException && error.getMessage() != null && !error.getMessage().isBlank()) return error.getMessage();
        return "Không thể kết nối máy chủ. Kiểm tra kết nối và khởi động server rồi thử lại.";
    }
    public void logout() {
        if (closing || logoutPending.get()) return;
        if (sessionToken == null) { clearSession(); auth(); return; }
        logoutPending.set(true); stage.getScene().getRoot().setDisable(true);
        String token = sessionToken;
        runAuthRequest(() -> authClient.logout(token), response -> {
            logoutPending.set(false); stage.getScene().getRoot().setDisable(false);
            if (response.success()) { clearSession(); auth(); }
            else logoutError(response.message());
        }, error -> {
            logoutPending.set(false); stage.getScene().getRoot().setDisable(false); logoutError(connectionError(error));
        });
    }
    private void logoutError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR); alert.initOwner(stage); alert.setTitle("Chưa thể đăng xuất");
        alert.setHeaderText("Phiên đăng nhập vẫn được giữ lại.");
        alert.setContentText((message == null ? "Không thể thu hồi phiên trên máy chủ." : message) + "\nVui lòng thử đăng xuất lại.");
        Ui.style(alert); alert.showAndWait();
    }
    private void clearSession() {
        authenticatedUser = null; sessionToken = null; preferences.clear();
        mic = true; camera = true; locked = false; room = MockData.rooms().getFirst();
    }
    /** Giữ cửa sổ tối đa 6 giây để thử thu hồi phiên trước khi đóng. */
    public void closeApplication() {
        if (closing) return;
        closing = true; dispose();
        if (stage.getScene() != null) {
            stage.getScene().getRoot().setDisable(true);
            if (stage.getScene().getRoot() instanceof AuthScreen auth) auth.clearSecrets();
        }
        closeDeadline = new PauseTransition(Duration.seconds(6)); closeDeadline.setOnFinished(event -> finishClose()); closeDeadline.play();
        String token = sessionToken;
        authWorker.execute(() -> {
            revokeQuietly(token);
            revokeQuietly(pendingToken); pendingToken = null;
            if (!closed) Platform.runLater(() -> { if (!closed) finishClose(); });
        });
    }
    private void revokeQuietly(String token) {
        if (token == null || token.isBlank()) return;
        try { authClient.logout(token); } catch (IOException ignored) { /* Kết nối có timeout; phiên cũng có hạn dùng phía server. */ }
    }
    private void finishClose() {
        if (closed) return;
        closed = true;
        if (closeDeadline != null) closeDeadline.stop();
        clearSession(); pendingToken = null; authWorker.shutdownNow(); stage.hide(); Platform.exit();
    }
    public void shutdown() {
        closing = true; closed = true; dispose();
        if (closeDeadline != null) closeDeadline.stop();
        clearSession(); pendingToken = null; authWorker.shutdownNow();
    }
    public MockData.Room room() { return room; }
    public boolean mic() { return mic; }
    public boolean camera() { return camera; }
    public void mic(boolean value) { mic = value; }
    public void camera(boolean value) { camera = value; }
    public boolean locked() { return locked; }
    public void locked(boolean value) { locked = value; }
    public void auth() {
        if (closing) return;
        if (authenticatedUser != null) { logout(); return; }
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
            if (closing || stage.getScene().getRoot() != view) return;
            Scene scene = stage.getScene();
            stage.setWidth(960 + Math.max(0, stage.getWidth() - scene.getWidth()));
            stage.setHeight(600 + Math.max(0, stage.getHeight() - scene.getHeight()));
            stage.centerOnScreen();
        });
    }
    private boolean hasSession() { if (closing) return false; if (authenticatedUser == null) { auth(); return false; } return true; }
    public void lobby() { if (!hasSession()) return; LobbyScreen view = new LobbyScreen(this); show(view, 1100, 700, true, view::dispose); }
    public void history() { if (hasSession()) show(new HistoryScreen(this), 1100, 700, true, () -> { }); }
    public void settings() { if (hasSession()) show(new SettingsScreen(this, false), 1100, 700, true, () -> { }); }
    public void settingsAccount() { if (hasSession()) show(new SettingsScreen(this, true), 1100, 700, true, () -> { }); }
    public void preJoin(MockData.Room next) {
        if (!hasSession()) return;
        room = next;
        if (Boolean.parseBoolean(preference("muteMic", "false"))) mic = false;
        if (Boolean.parseBoolean(preference("muteCamera", "false"))) camera = false;
        PreJoinScreen view = new PreJoinScreen(this);
        show(view, 1100, 700, true, view::dispose);
    }
    public void meeting() {
        if (!hasSession()) return;
        MeetingScreen view = new MeetingScreen(this);
        show(view, 1280, 800, true, view::dispose);
    }
    public void ended(long seconds, int count) { if (hasSession()) show(new MeetingEndedScreen(this, seconds, count), 1100, 700, true, () -> { }); }
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
