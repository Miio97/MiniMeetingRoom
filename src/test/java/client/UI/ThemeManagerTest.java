package client.UI;

import client.UI.components.Ui;
import client.UI.dialogs.CameraSettingsDialog;
import client.media.VideoCallController;
import com.google.gson.JsonObject;
import java.util.concurrent.*;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

class ThemeManagerTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }
    @Test void settingsSelectorChangesTheOpenDialogAndItsMeetingOwnerInPlace() throws Exception {
        var video = new VideoCallController((type, body, id) -> CompletableFuture.completedFuture(new JsonObject()), () -> null, () -> null);
        try {
            FxTestRuntime.call(() -> {
                boolean original = ThemeManager.isLight(); Stage stage = new Stage();
                VBox meeting = Ui.column(12, Ui.label("Cuộc họp", "meeting-title"));
                meeting.getStyleClass().add("theme-dark"); // Legacy forced class must no longer block preference.
                Scene scene = new Scene(meeting, 900, 600); scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                stage.setScene(scene); ThemeManager.attach(meeting);
                CameraSettingsDialog settings = new CameraSettingsDialog(stage, video);
                try {
                    settings.show(); ThemeManager.setLight(true); meeting.applyCss(); settings.getDialogPane().applyCss();
                    var lightMeeting = meeting.getBackground().getFills().getFirst().getFill();
                    var lightDialog = settings.getDialogPane().getBackground().getFills().getFirst().getFill();
                    ((Button) settings.getDialogPane().lookup("#theme-dark")).fire(); meeting.applyCss(); settings.getDialogPane().applyCss();
                    assertFalse(ThemeManager.isLight()); assertFalse(meeting.getStyleClass().contains("light-mode"));
                    assertNotEquals(lightMeeting, meeting.getBackground().getFills().getFirst().getFill());
                    assertNotEquals(lightDialog, settings.getDialogPane().getBackground().getFills().getFirst().getFill());
                    ((Button) settings.getDialogPane().lookup("#theme-light")).fire(); meeting.applyCss(); settings.getDialogPane().applyCss();
                    assertEquals(lightMeeting, meeting.getBackground().getFills().getFirst().getFill());
                    assertEquals(lightDialog, settings.getDialogPane().getBackground().getFills().getFirst().getFill());
                    assertSame(scene, stage.getScene());
                } finally { settings.close(); stage.close(); ThemeManager.setLight(original); }
                return null;
            });
        } finally { video.close(); video.closeComplete().get(5, TimeUnit.SECONDS); }
    }
}
