package client.UI;

import client.UI.screens.PreJoinScreen;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import client.meeting.MeetingSession;
import common.auth.*;
import common.meeting.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import javafx.geometry.Bounds;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

/** Real CSS/skins and layout negotiation, without hardware, credentials or a server. */
class PreJoinLayoutTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @Test void bothMeetingPathsKeepFooterAndAspectRatioAcrossWidthsAndThemes() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            try {
                for (boolean nativeVideo : new boolean[] {false, true}) {
                    Stage stage = new Stage();
                    SceneManager manager = new SceneManager(stage, new AuthClient(), new FileTransferClient());
                    manager.acceptSession(AuthResponse.ok("fixture", new AuthUser(99, "test", "Test", "test@test.invalid"),
                            "layout-fixture", Instant.now().plusSeconds(3600).toString()));
                    if (nativeVideo) {
                        MeetingSession session = manager.sfuSession();
                        field(session, "snapshot", new RoomSnapshot("layout-room", "ABC", "Layout", RoomStatus.READY, false, 10, "self", 1, List.of()));
                        field(session, "self", "self"); field(manager, "usingSfuRoom", true);
                    }
                    PreJoinScreen screen = new PreJoinScreen(manager);
                    try {
                        Scene scene = new Scene(screen, 1000, 700); stage.setScene(scene);
                        scene.getStylesheets().add(PreJoinScreen.class.getResource("/styles/app.css").toExternalForm());
                        ThemeManager.attach(screen);
                        for (boolean light : new boolean[] {true, false, true}) {
                            ThemeManager.setLight(light);
                            for (int[] size : new int[][] {{1000,700},{1180,780},{1280,720},{1366,768},{1600,900},{700,520},{480,440},{1000,700}}) {
                                screen.resize(size[0], size[1]); pulse(screen);
                                Region footer = (Region) screen.lookup("#prejoin-footer");
                                ScrollPane scroll = (ScrollPane) screen.lookup("#prejoin-scroll");
                                Region preview = (Region) screen.lookup("#prejoin-preview");
                                Region settings = (Region) screen.lookup("#prejoin-settings");
                                assertSame(screen, footer.getParent());
                                assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getHbarPolicy());
                                Bounds f = sceneBounds(footer);
                                assertTrue(f.getMaxY() <= size[1] + 1, "Footer outside viewport");
                                assertTrue(f.getMinY() >= 0);
                                for (String id : new String[] {"enter-meeting", "cancel-prejoin"}) {
                                    Button button = (Button) screen.lookup("#" + id); Bounds b = sceneBounds(button);
                                    assertTrue(b.getMaxY() <= size[1] + 1 && b.getMinX() >= 0 && b.getMaxX() <= size[0] + 1, id);
                                    assertEquals(44, button.getHeight(), 1);
                                }
                                assertEquals(16.0 / 9, preview.getWidth() / preview.getHeight(), .025);
                                Bounds p = sceneBounds(preview), s = sceneBounds(settings);
                                assertTrue(s.getMaxX() <= size[0] + 1, "Settings overflow");
                                if (size[0] >= 1000) assertTrue(s.getMinX() >= p.getMaxX(), "Expected two columns");
                                else assertTrue(s.getMinY() >= p.getMaxY(), "Expected stacked layout");
                                assertFalse(scroll.getContent().isResizable() && ((Region) scroll.getContent()).getWidth() > scroll.getViewportBounds().getWidth() + 1);
                                if (nativeVideo) {
                                    ImageView image = (ImageView) preview.getChildrenUnmodifiable().stream().filter(ImageView.class::isInstance).findFirst().orElseThrow();
                                    image.imageProperty().unbind(); image.visibleProperty().unbind(); image.setVisible(true);
                                    for (int[] source : new int[][] {{640,480},{1920,1080},{480,640}}) {
                                        image.setImage(new WritableImage(source[0], source[1])); pulse(screen);
                                        assertTrue(image.isPreserveRatio());
                                        assertEquals((double) source[0] / source[1], image.getBoundsInLocal().getWidth() / image.getBoundsInLocal().getHeight(), .01);
                                        assertTrue(image.getBoundsInParent().getMaxX() <= preview.getWidth() + 1);
                                        assertTrue(image.getBoundsInParent().getMaxY() <= preview.getHeight() + 1);
                                        assertEquals(preview.getWidth() / 2, image.getBoundsInParent().getCenterX(), 1);
                                    }
                                    image.setVisible(false);
                                }
                                if (size[0] == 1280) snapshot(screen, (nativeVideo ? "video" : "legacy") + (light ? "-light" : "-dark"));
                            }
                        }
                    } finally { screen.dispose(); manager.shutdown(); stage.close(); }
                }
            } finally { ThemeManager.setLight(original); }
            return null;
        });
    }
    @Test void realWindowPulsesKeepFooterThroughMaximizeRestoreAndNarrowResize() throws Exception {
        Stage[] window = new Stage[1]; SceneManager[] owner = new SceneManager[1]; PreJoinScreen[] view = new PreJoinScreen[1];
        try {
            FxTestRuntime.call(() -> {
                window[0] = new Stage(); owner[0] = new SceneManager(window[0], new AuthClient(), new FileTransferClient());
                owner[0].acceptSession(AuthResponse.ok("fixture", new AuthUser(99, "test", "Test", "test@test.invalid"),
                        "layout-fixture", Instant.now().plusSeconds(3600).toString()));
                MeetingSession session = owner[0].sfuSession();
                field(session, "snapshot", new RoomSnapshot("layout-room", "ABC", "Layout", RoomStatus.READY, false, 10, "self", 1, List.of()));
                field(session, "self", "self"); field(owner[0], "usingSfuRoom", true);
                view[0] = new PreJoinScreen(owner[0]);
                Scene scene = new Scene(view[0], 1000, 700);
                scene.getStylesheets().add(PreJoinScreen.class.getResource("/styles/app.css").toExternalForm());
                window[0].setScene(scene); ThemeManager.attach(view[0]); window[0].show(); return null;
            });
            FxTestRuntime.call(() -> { window[0].setMaximized(true); return null; });
            FxTestRuntime.await(() -> window[0].isMaximized() && view[0].getWidth() > 1000);
            FxTestRuntime.call(() -> {
                pulse(view[0]); assertFooter(view[0]);
                Files.writeString(Path.of("target/qa/prejoin/maximized-viewport.txt"), view[0].getWidth() + " x " + view[0].getHeight());
                snapshot(view[0], "maximized"); window[0].setMaximized(false); return null;
            });
            FxTestRuntime.await(() -> !window[0].isMaximized() && view[0].getWidth() <= 1001);
            FxTestRuntime.call(() -> { assertFooter(view[0]); window[0].setWidth(700); window[0].setHeight(520); return null; });
            FxTestRuntime.await(() -> view[0].getWidth() < 750);
            FxTestRuntime.call(() -> {
                pulse(view[0]); assertFooter(view[0]); snapshot(view[0], "small-window");
                window[0].setWidth(1180); window[0].setHeight(780); return null;
            });
            FxTestRuntime.await(() -> view[0].getWidth() > 1100);
            FxTestRuntime.call(() -> { pulse(view[0]); assertFooter(view[0]); return null; });
        } finally {
            FxTestRuntime.call(() -> { if (view[0] != null) view[0].dispose(); if (owner[0] != null) owner[0].shutdown(); if (window[0] != null) window[0].close(); return null; });
        }
    }
    private static void assertFooter(PreJoinScreen screen) {
        for (String id : new String[] {"enter-meeting", "cancel-prejoin"}) {
            Bounds button = sceneBounds(screen.lookup("#" + id));
            assertTrue(button.getMaxY() <= screen.getHeight() + 1 && button.getMinY() >= 0, id);
            assertTrue(button.getMaxX() <= screen.getWidth() + 1 && button.getMinX() >= 0, id);
        }
        assertFalse(screen.isNeedsLayout(), "Layout should settle after pulses");
    }
    private static Bounds sceneBounds(Node node) { return node.localToScene(node.getBoundsInLocal()); }
    private static void pulse(Parent root) { for (int i = 0; i < 4; i++) { root.applyCss(); root.layout(); } }
    private static void field(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void snapshot(Parent root, String name) throws Exception {
        var image = root.snapshot(null, null);
        var png = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path path = Path.of("target", "qa", "prejoin", name + ".png"); Files.createDirectories(path.getParent()); javax.imageio.ImageIO.write(png, "png", path.toFile());
    }
}
