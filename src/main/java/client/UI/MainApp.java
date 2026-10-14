package client.UI;

import javafx.application.Application;
import javafx.stage.Stage;
import common.diagnostics.Diagnostics;
import java.util.Map;

public class MainApp extends Application {
    private SceneManager manager;
    private Diagnostics diagnostics;
    private Thread diagnosticsShutdown;
    @Override public void init() {
        diagnostics = Diagnostics.start("client");
        diagnosticsShutdown = new Thread(diagnostics::close, "client-diagnostics-shutdown");
        Runtime.getRuntime().addShutdownHook(diagnosticsShutdown);
    }
    @Override public void start(Stage stage) {
        stage.setTitle("Mini Meeting Room");
        manager = new SceneManager(stage);
        stage.setOnCloseRequest(event -> { event.consume(); manager.closeApplication(); });
        manager.auth();
        stage.show();
        diagnostics.event("client_ready", Map.of("media_status", "video_available"));
    }
    @Override public void stop() {
        try { if (manager != null) manager.shutdown(); }
        finally {
            if (diagnostics != null) diagnostics.close();
            if (diagnosticsShutdown != null) {
                try { Runtime.getRuntime().removeShutdownHook(diagnosticsShutdown); }
                catch (IllegalStateException ignored) { /* The JVM is already shutting down. */ }
            }
        }
    }
}
