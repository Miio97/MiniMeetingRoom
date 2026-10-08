package client.UI;

import javafx.application.Application;
import javafx.stage.Stage;

public class MainApp extends Application {
    private SceneManager manager;
    @Override public void start(Stage stage) {
        stage.setTitle("Mini Meeting Room");
        manager = new SceneManager(stage);
        stage.setOnCloseRequest(event -> { event.consume(); manager.closeApplication(); });
        manager.auth();
        stage.show();
    }
    @Override public void stop() { if (manager != null) manager.shutdown(); }
}
