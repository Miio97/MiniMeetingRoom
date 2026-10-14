package client.UI.components;

import client.media.*;
import com.google.gson.JsonObject;
import dev.onvoid.webrtc.media.video.VideoDevice;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.Scene;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated consent and deterministic device catalog; never enumerates or opens physical hardware. */
@Timeout(20)
class CameraControlsTest {
    @TempDir Path temporary;
    private final List<VideoCallController> controllers = new ArrayList<>();
    private final List<CameraControls> views = new ArrayList<>();
    private static final class Device extends VideoDevice { Device(String name, String id) { super(name, id); } }
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }
    private VideoCallController controller(CameraConsentStore store, java.util.function.Supplier<List<VideoDevice>> devices) {
        var video = new VideoCallController((type, body, id) -> CompletableFuture.completedFuture(new JsonObject()),
                () -> null, () -> "self", store, devices); controllers.add(video); return video;
    }
    private CameraControls view(VideoCallController video, java.util.function.Function<javafx.stage.Window, CompletableFuture<Boolean>> prompt) throws Exception {
        return FxTestRuntime.call(() -> { var view = new CameraControls(video, true, prompt); views.add(view); new Scene(view, 800, 180); return view; });
    }
    @SuppressWarnings("unchecked") private ComboBox<VideoDevice> combo(CameraControls view) { return (ComboBox<VideoDevice>) view.lookup("#video-camera-device"); }
    @AfterEach void close() throws Exception {
        FxTestRuntime.call(() -> { views.forEach(CameraControls::dispose); return null; });
        controllers.forEach(VideoCallController::close);
        for (var video : controllers) video.closeComplete().get(5, TimeUnit.SECONDS);
    }
    @Test void savedPermissionLoadsDevicesAndChoiceSurvivesANewControllerWithoutCapture() throws Exception {
        var store = new CameraConsentStore(temporary.resolve("camera.properties"));
        store.access(true);
        var usb = new Device("USB Webcam", "usb-test"); var builtin = new Device("Integrated Camera", "builtin-test");
        AtomicInteger prompts = new AtomicInteger(), discoveries = new AtomicInteger();
        var first = controller(store, () -> { assertFalse(javafx.application.Platform.isFxApplicationThread()); discoveries.incrementAndGet(); return List.of(usb, builtin); });
        var controls = view(first, owner -> { prompts.incrementAndGet(); return CompletableFuture.completedFuture(false); });
        FxTestRuntime.await(() -> combo(controls).getValue() == builtin); assertEquals(0, prompts.get());
        assertEquals(CameraConsentStore.Access.ALLOWED, new CameraConsentStore(temporary.resolve("camera.properties")).read().access());
        FxTestRuntime.call(() -> {
            assertFalse(first.publishingProperty().get(), "Consent and selection must not start capture");
            assertNull(controls.lookup("#video-camera-list")); combo(controls).setValue(usb); return null;
        });
        assertEquals(usb.getDescriptor(), first.cameraChoice().get(5, TimeUnit.SECONDS).preferredDevice());
        FxTestRuntime.call(() -> { controls.dispose(); return null; });
        var reopened = controller(new CameraConsentStore(temporary.resolve("camera.properties")), () -> List.of(usb, builtin));
        var next = view(reopened, owner -> { prompts.incrementAndGet(); return CompletableFuture.completedFuture(false); });
        FxTestRuntime.await(() -> combo(next).getValue() == usb); assertEquals(0, prompts.get());
    }
    @Test void permissionIsAskedOnlyOnStartAndDenialCanBeRetriedFromTheSameButton() throws Exception {
        var store = new CameraConsentStore(temporary.resolve("camera.properties"));
        AtomicInteger discoveries = new AtomicInteger(), prompts = new AtomicInteger();
        var video = controller(store, () -> { discoveries.incrementAndGet(); return List.of(); });
        var first = view(video, owner -> { prompts.incrementAndGet(); return CompletableFuture.completedFuture(false); });
        FxTestRuntime.await(() -> !((Button) first.lookup("#video-camera-toggle")).isDisabled());
        assertEquals(0, prompts.get());
        FxTestRuntime.call(() -> { assertNull(first.lookup("#camera-permission")); ((Button) first.lookup("#video-camera-toggle")).fire(); return null; });
        FxTestRuntime.await(() -> prompts.get() == 1 && !((Button) first.lookup("#video-camera-toggle")).isDisabled());
        assertFalse(FxTestRuntime.call(() -> first.permissionGrantedProperty().get()));
        assertEquals(CameraConsentStore.Access.DENIED, video.cameraChoice().get(5, TimeUnit.SECONDS).access());
        assertEquals(0, discoveries.get()); assertThrows(ExecutionException.class, () -> video.cameras().get(5, TimeUnit.SECONDS));
        FxTestRuntime.call(() -> { first.dispose(); return null; });
        var nextVideo = controller(new CameraConsentStore(temporary.resolve("camera.properties")), () -> { discoveries.incrementAndGet(); return List.of(); });
        var next = view(nextVideo, owner -> { prompts.incrementAndGet(); return CompletableFuture.completedFuture(true); });
        FxTestRuntime.await(() -> !((Button) next.lookup("#video-camera-toggle")).isDisabled());
        assertEquals(1, prompts.get()); assertEquals(0, discoveries.get());
        FxTestRuntime.call(() -> { ((Button) next.lookup("#video-camera-toggle")).fire(); return null; });
        FxTestRuntime.await(() -> combo(next).getPromptText().equals("Không có camera"));
        assertEquals(2, prompts.get()); assertEquals(1, discoveries.get());
        assertTrue(FxTestRuntime.call(() -> next.permissionGrantedProperty().get()));
    }
    @Test void emptyCatalogShowsNoCameraAndOpeningCatalogRefreshesConnectedDevices() throws Exception {
        var store = new CameraConsentStore(temporary.resolve("camera.properties")); store.access(true);
        var connected = new java.util.concurrent.atomic.AtomicReference<List<VideoDevice>>(List.of());
        var video = controller(store, connected::get); var controls = view(video, owner -> { fail("Saved allowance must not prompt again"); return null; });
        FxTestRuntime.await(() -> combo(controls).getPromptText().equals("Không có camera"));
        var camera = new Device("USB Webcam", "usb-test"); connected.set(List.of(camera));
        FxTestRuntime.call(() -> { combo(controls).getOnShowing().handle(new javafx.event.Event(ComboBox.ON_SHOWING)); return null; });
        FxTestRuntime.await(() -> combo(controls).getValue() == camera);
        assertFalse(FxTestRuntime.call(() -> video.publishingProperty().get()));
    }
    @Test void realPermissionDialogSavesTheAllowButtonWithoutOpeningHardware() throws Exception {
        var store = new CameraConsentStore(temporary.resolve("camera.properties"));
        var video = controller(store, List::of); var controls = view(video, null);
        FxTestRuntime.await(() -> !((Button) controls.lookup("#video-camera-toggle")).isDisabled());
        FxTestRuntime.call(() -> { ((Button) controls.lookup("#video-camera-toggle")).fire(); return null; });
        FxTestRuntime.await(() -> javafx.stage.Window.getWindows().stream().anyMatch(window -> window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane pane && pane.getHeaderText().contains("MiniMeetingRoom")));
        FxTestRuntime.call(() -> {
            DialogPane pane = javafx.stage.Window.getWindows().stream().filter(window -> window.getScene() != null)
                    .map(window -> window.getScene().getRoot()).filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
            ButtonType allow = pane.getButtonTypes().stream().filter(type -> type.getButtonData() == ButtonBar.ButtonData.YES).findFirst().orElseThrow();
            ((Button) pane.lookupButton(allow)).fire(); return null;
        });
        FxTestRuntime.await(() -> combo(controls).getPromptText().equals("Không có camera"));
        assertEquals(CameraConsentStore.Access.ALLOWED, new CameraConsentStore(temporary.resolve("camera.properties")).read().access());
        assertFalse(FxTestRuntime.call(() -> video.publishingProperty().get()));
    }
    @Test void physicalCameraWinsOverVirtualDefaultsAndDiscoveryCannotBlockPreferences() throws Exception {
        var virtual = new Device("ByteCast VirtualCamera2", "virtual-test"); var usb = new Device("USB2.0 HD UVC WebCam", "usb-test");
        assertSame(usb, CameraSelection.choose(List.of(virtual, usb), ""));
        assertSame(virtual, CameraSelection.choose(List.of(virtual, usb), virtual.getDescriptor()), "Explicit user choice remains available");
        var store = new CameraConsentStore(temporary.resolve("camera.properties")); store.access(true);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var video = controller(store, () -> { entered.countDown(); try { release.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } return List.of(); });
        try {
            var discovery = video.cameras(); assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(CameraConsentStore.Access.ALLOWED, video.cameraChoice().get(1, TimeUnit.SECONDS).access());
            video.cameraAccess(false).get(1, TimeUnit.SECONDS); assertFalse(discovery.isDone());
        } finally { release.countDown(); }
    }
    @Test void virtualCamerasAreAbsentFromThePickerAndPhysicalCount() throws Exception {
        var store = new CameraConsentStore(temporary.resolve("camera.properties")); store.access(true); store.device("old-virtual");
        var usb = new Device("USB2.0 HD UVC WebCam", "usb-test");
        var obsbot = new Device("OBSBOT Tiny 2", "usb-obsbot");
        assertEquals(List.of(obsbot, usb), CameraSelection.physicalDevices(List.of(new Device("OBS Virtual Camera", "obs-test"), obsbot, usb)));
        var video = controller(store, () -> List.of(new Device("OBS Virtual Camera", "obs-test"),
                new Device("ByteCast VirtualCamera2", "old-virtual"), usb));
        var controls = view(video, owner -> CompletableFuture.completedFuture(true));
        FxTestRuntime.await(() -> combo(controls).getValue() == usb);
        FxTestRuntime.call(() -> {
            assertEquals(List.of(usb), combo(controls).getItems());
            var status = (Label) controls.lookup("#camera-device-status");
            assertTrue(status.getText().isEmpty()); assertFalse(status.isManaged()); return null;
        });
        assertEquals(List.of(usb), video.cameras().get(2, TimeUnit.SECONDS));
    }
    @Test void leavingTheViewClosesItsPendingDialogWithoutSavingConsent() throws Exception {
        var store = new CameraConsentStore(temporary.resolve("camera.properties"));
        var video = controller(store, () -> { fail("Closing the view must not discover cameras"); return List.of(); });
        var controls = view(video, null);
        FxTestRuntime.await(() -> !((Button) controls.lookup("#video-camera-toggle")).isDisabled());
        FxTestRuntime.call(() -> { ((Button) controls.lookup("#video-camera-toggle")).fire(); return null; });
        FxTestRuntime.await(() -> javafx.stage.Window.getWindows().stream().anyMatch(window -> window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane pane && pane.getHeaderText().contains("MiniMeetingRoom")));
        FxTestRuntime.call(() -> { controls.dispose(); return null; });
        FxTestRuntime.await(() -> javafx.stage.Window.getWindows().stream().noneMatch(window -> window.getScene() != null
                && window.getScene().getRoot() instanceof DialogPane pane && pane.getHeaderText().contains("MiniMeetingRoom")));
        assertEquals(CameraConsentStore.Access.ASK, store.read().access());
    }
}
