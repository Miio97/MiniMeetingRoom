package client.UI.components;

import client.media.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AudioControlsTest {
    @TempDir Path temporary;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }
    @Test void openingViewNeverAsksPermissionAndDenyCanBeRetried() throws Exception {
        var store=new MicrophoneConsentStore(temporary.resolve("mic.properties")); AtomicInteger asks=new AtomicInteger();
        var audio=new AudioCallController((type,body,id) -> CompletableFuture.failedFuture(new IllegalStateException("NO_NETWORK")),
                () -> null,() -> "self",true,store);
        var view=FxTestRuntime.call(() -> { var controls=new AudioControls(audio,owner -> {
            asks.incrementAndGet(); return CompletableFuture.completedFuture(false);
        },false);new Scene(controls);return controls; });
        try {
            FxTestRuntime.await(() -> !((Button)view.lookup("#audio-microphone-toggle")).isDisabled());assertEquals(0,asks.get());
            FxTestRuntime.call(() -> { ((Button)view.lookup("#audio-microphone-toggle")).fire();return null; });
            FxTestRuntime.await(() -> asks.get()==1 && !((Button)view.lookup("#audio-microphone-toggle")).isDisabled());
            assertEquals(MicrophoneConsentStore.Access.DENIED,store.read().access());
            FxTestRuntime.call(() -> { view.toggle();return null; });
            FxTestRuntime.await(() -> asks.get()==2);assertFalse(FxTestRuntime.call(() -> audio.requestedProperty().get()));
        } finally { FxTestRuntime.call(() -> {view.dispose();return null;});audio.close();audio.closeComplete().get(5,TimeUnit.SECONDS); }
    }
    @Test void storedMicrophoneChoiceIsIsolatedAndCanBeReadAfterRestart() throws Exception {
        Path file=temporary.resolve("remember.properties");var store=new MicrophoneConsentStore(file);
        assertEquals(MicrophoneConsentStore.Access.ASK,store.read().access());store.access(true);store.device("test-device");
        var reopened=new MicrophoneConsentStore(file);assertEquals(MicrophoneConsentStore.Access.ALLOWED,reopened.read().access());
        assertEquals("test-device",reopened.read().preferredDevice());reopened.access(false);
        assertEquals(MicrophoneConsentStore.Access.DENIED,store.read().access());
    }
    @Test void closingTheRealPermissionDialogKeepsMicrophoneOff() throws Exception {
        var store=new MicrophoneConsentStore(temporary.resolve("real-dialog.properties"));
        var audio=new AudioCallController((type,body,id) -> CompletableFuture.failedFuture(new IllegalStateException("NO_NETWORK")),
                () -> null,() -> "self",true,store);
        var view=FxTestRuntime.call(() -> {var controls=new AudioControls(audio,null,false);new Scene(controls);return controls;});
        try {
            FxTestRuntime.await(() -> !((Button)view.lookup("#audio-microphone-toggle")).isDisabled());
            FxTestRuntime.call(() -> {view.toggle();return null;});
            FxTestRuntime.await(() -> javafx.stage.Window.getWindows().stream().anyMatch(window -> window.getScene()!=null
                    && window.getScene().getRoot() instanceof DialogPane pane && "Quyền sử dụng microphone".equals(window instanceof javafx.stage.Stage stage ? stage.getTitle() : "")));
            FxTestRuntime.call(() -> {
                for(var window:java.util.List.copyOf(javafx.stage.Window.getWindows())) if(window.getScene()!=null
                        && window.getScene().getRoot() instanceof DialogPane pane && pane.getHeaderText()!=null && pane.getHeaderText().contains("microphone")) {
                    var cancel=pane.getButtonTypes().stream().filter(button -> button.getButtonData()==ButtonBar.ButtonData.CANCEL_CLOSE).findFirst().orElseThrow();
                    ((Button)pane.lookupButton(cancel)).fire();
                }
                return null;
            });
            FxTestRuntime.await(() -> !((Button)view.lookup("#audio-microphone-toggle")).isDisabled());
            assertEquals(MicrophoneConsentStore.Access.DENIED,store.read().access());assertFalse(FxTestRuntime.call(() -> audio.requestedProperty().get()));
        } finally {FxTestRuntime.call(() -> {view.dispose();return null;});audio.close();audio.closeComplete().get(5,TimeUnit.SECONDS);}
    }
}
