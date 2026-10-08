package client.UI;

import javafx.application.Application;
import javafx.stage.Stage;

public class MainApp extends Application {
    private SceneManager manager;
    @Override public void start(Stage stage) {
        stage.setTitle("Mini Meeting Room · UI Demo");
        manager = new SceneManager(stage);
        stage.setOnCloseRequest(event -> manager.dispose());
        manager.auth();
        stage.show();
    }
    @Override public void stop() { if (manager != null) manager.dispose(); }
}
