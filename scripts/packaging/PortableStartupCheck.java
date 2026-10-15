import client.UI.MainApp;
import client.media.NativeAudioSession;
import common.diagnostics.Diagnostics;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.stage.Stage;

/** Runs with the packaged runtime/dependencies; does not connect an account or open media devices. */
public final class PortableStartupCheck {
    public static void main(String[] args) throws Exception {
        // This class was missing from the broken jlink image. Exercise its actual
        // consumer as well: merely loading client.Main never calls MainApp.init.
        Class.forName("com.sun.management.OperatingSystemMXBean");
        MainApp app = new MainApp();
        app.init();
        if (Diagnostics.current().logPath() == null) throw new IllegalStateException("Diagnostics did not start");
        FutureTask<Void> startup = new FutureTask<>(() -> {
            Stage stage = new Stage();
            stage.setOpacity(0);
            try {
                app.start(stage);
                if (stage.getScene() == null || !stage.isShowing()) {
                    throw new IllegalStateException("Login screen did not start");
                }
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
            } finally {
                stage.hide();
                app.stop();
            }
            return null;
        });
        try {
            Platform.startup(startup);
            startup.get(15, TimeUnit.SECONDS);
            try (var audio = new NativeAudioSession(true, true, null, null)) { }
            System.out.println("PORTABLE_MAINAPP_STARTUP_OK");
        } finally {
            app.stop();
            Platform.exit();
        }
    }
}
