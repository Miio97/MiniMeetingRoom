package client.UI;

import client.UI.components.Ui;
import client.UI.screens.LobbyScreen;
import client.UI.screens.SettingsScreen;
import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.room.RoomInfo;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import javax.imageio.ImageIO;
import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

/** Real JavaFX CSS/skins and production lobby; assertions use semantic contrast, not pixels. */
class WorkspaceThemeTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @Test void lobbyKeepsReadableRoomsProfilesAndStatesAcrossSwitchesAndRecreation() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            Stage stage = new Stage();
            SceneManager manager = manager(stage);
            LobbyScreen lobby = null;
            try {
                for (boolean light : new boolean[] {true, false, true}) {
                    ThemeManager.setLight(light);
                    if (lobby != null) lobby.dispose();
                    manager.cacheRecentRooms(List.of(room(1, "TestALLv1", "DANG_HOAT_DONG"), room(2, "Testvoice10", "DA_DONG")));
                    lobby = new LobbyScreen(manager);
                    Scene scene = new Scene(lobby, 1380, 915);
                    scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                    stage.setScene(scene); ThemeManager.attach(lobby);
                    css(lobby);
                    for (boolean switched : new boolean[] {!light, light}) {
                        ThemeManager.setLight(switched); css(lobby);
                        Color surface = background((Region) lobby.lookup(".room-row"));
                        for (var node : lobby.lookupAll(".room-title")) readable(((Label) node).getTextFill(), surface);
                        for (var node : lobby.lookupAll(".room-meta")) readable(((Label) node).getTextFill(), surface);
                        Region profile = (Region) lobby.lookup(".sidebar-user");
                        readable(((Label) lobby.lookup(".user-name")).getTextFill(), background(profile));
                        readable(((Label) lobby.lookup(".user-caption")).getTextFill(), background(profile));
                        assertEquals(switched, background(profile).getBrightness() > .5);
                        Label ended = (Label) lobby.lookup(".room-ended");
                        readable(ended.getTextFill(), background(ended));
                        Button closed = (Button) lobby.lookup("#room-action-2");
                        assertTrue(closed.isDisabled()); assertEquals(1, closed.getOpacity());
                        readable(closed.getTextFill(), background(closed));
                        closed.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true); css(lobby);
                        readable(closed.getTextFill(), background(closed));
                        closed.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), false);
                        Button active = (Button) lobby.lookup(".workspace-nav-active");
                        active.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true); css(lobby);
                        readable(active.getTextFill(), background(active));
                        active.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), false);
                    }
                    snapshot(lobby, light ? "lobby-light" : "lobby-dark");
                }
            } finally {
                if (lobby != null) lobby.dispose(); manager.shutdown(); stage.close(); ThemeManager.setLight(original);
            }
            return null;
        });
    }

    @Test void realAccountMenuHasTransparentOuterRootAndTracksThemeWhileOpen() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight(); Stage stage = new Stage(); SceneManager manager = manager(stage);
            manager.cacheRecentRooms(List.of()); LobbyScreen lobby = new LobbyScreen(manager);
            try {
                Scene scene = new Scene(lobby, 1380, 915); scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                stage.setScene(scene); ThemeManager.attach(lobby); stage.show(); css(lobby);
                HBox hit = (HBox) lobby.lookup(".profile-hit");
                for (boolean light : new boolean[] {true, false, true, false}) {
                    ThemeManager.setLight(light); css(lobby); hit.getOnMouseClicked().handle(null);
                    ContextMenu menu = Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast).findFirst().orElseThrow();
                    Parent root = menu.getScene().getRoot(); css(root);
                    Region surface = root.lookupAll(".context-menu").stream().filter(Region.class::isInstance).map(Region.class::cast)
                            .filter(region -> region.getBorder() != null).findFirst().orElseThrow();
                    assertNotNull(surface);
                    assertEquals(Color.TRANSPARENT, background((Region) root));
                    assertEquals(1, surface.getBorder().getStrokes().getFirst().getWidths().getTop());
                    var anchor = hit.getParent().localToScreen(hit.getParent().getBoundsInLocal());
                    assertTrue(menu.getY() + menu.getHeight() <= anchor.getMinY(), "Menu must sit above the whole profile card");
                    for (boolean switched : new boolean[] {!light, light}) {
                        ThemeManager.setLight(switched); css(lobby); css(root);
                        assertEquals(switched, background(surface).getBrightness() > .5);
                        for (var node : root.lookupAll(".menu-item .label")) readable(((Label) node).getTextFill(), background(surface));
                    }
                    snapshot(root, light ? "account-light" : "account-dark"); menu.hide();
                }
            } finally { lobby.dispose(); manager.shutdown(); stage.close(); ThemeManager.setLight(original); }
            return null;
        });
    }

    @Test void comboBoxPopupAndTooltipFollowThemeAndInputsRemainReadable() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight(); Stage stage = new Stage();
            TextField field = new TextField("Tên hiển thị"); PasswordField password = new PasswordField(); password.setText("1234");
            ComboBox<String> combo = Ui.devices(List.of("Camera mặc định", "Camera thứ hai"));
            Tooltip tip = new Tooltip("Chọn thiết bị");
            VBox root = Ui.column(12, field, password, combo, ThemeManager.selector());
            Scene scene = new Scene(root, 460, 300); scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
            stage.setScene(scene); ThemeManager.attach(root);
            try {
                stage.show();
                for (boolean light : new boolean[] {true, false}) {
                    ThemeManager.setLight(light); css(root);
                    readable(((javafx.scene.text.Text) field.lookup(".text")).getFill(), background(field));
                    field.setDisable(true); password.setDisable(true); combo.setDisable(true); css(root);
                    readable(((javafx.scene.text.Text) field.lookup(".text")).getFill(), background(field));
                    assertEquals(1, field.getOpacity()); assertEquals(1, combo.getOpacity());
                    field.setDisable(false); password.setDisable(false); combo.setDisable(false); css(root);
                    combo.show();
                    Parent popup = Window.getWindows().stream().filter(w -> w instanceof javafx.stage.PopupWindow).findFirst().orElseThrow().getScene().getRoot();
                    css(popup);
                    for (boolean switched : new boolean[] {!light, light}) {
                        ThemeManager.setLight(switched); css(root); css(popup);
                        assertEquals(switched, popup.getStyleClass().contains("light-mode"));
                        for (var node : popup.lookupAll(".list-cell")) {
                            ListCell<?> cell = (ListCell<?>) node;
                            if (!cell.isEmpty()) readable(cell.getTextFill(), background(cell));
                        }
                    }
                    combo.hide(); tip.show(field, stage.getX() + 30, stage.getY() + 80); css(tip.getScene().getRoot());
                    ThemeManager.setLight(!light); css(tip.getScene().getRoot());
                    assertEquals(!light, tip.getScene().getRoot().getStyleClass().contains("light-mode"));
                    tip.hide(); ThemeManager.setLight(light); snapshot(root, light ? "controls-light" : "controls-dark");
                }
            } finally { combo.hide(); tip.hide(); stage.close(); ThemeManager.setLight(original); }
            return null;
        });
    }

    @Test void settingsTabsAndSwitchesKeepTheirThemeInPlace() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight(); Stage stage = new Stage(); SceneManager manager = manager(stage);
            SettingsScreen settings = new SettingsScreen(manager, false);
            try {
                Scene scene = new Scene(settings, 1380, 915); scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                stage.setScene(scene); ThemeManager.attach(settings); css(settings);
                TabPane tabs = (TabPane) settings.lookup(".settings-tabs");
                for (boolean light : new boolean[] {true, false}) {
                    ThemeManager.setLight(light);
                    for (int tab = 0; tab < 3; tab++) {
                        tabs.getSelectionModel().select(tab); css(settings);
                        if (tab == 0) for (var node : settings.lookupAll(".combo-box:disabled")) {
                            ComboBox<?> box = (ComboBox<?>) node;
                            for (var child : box.lookupAll(".list-cell")) {
                                ListCell<?> cell = (ListCell<?>) child;
                                assertEquals(1, cell.getOpacity());
                                readable(cell.getTextFill(), background(box));
                            }
                        }
                        if (tab == 1) {
                            ((Button) settings.lookup(light ? "#theme-dark" : "#theme-light")).fire(); css(settings);
                            assertEquals(!light, ThemeManager.isLight());
                            ((Button) settings.lookup(light ? "#theme-light" : "#theme-dark")).fire(); css(settings);
                            for (var node : settings.lookupAll(".settings-switch")) {
                                ToggleButton toggle = (ToggleButton) node;
                                toggle.setSelected(!toggle.isSelected()); css(settings);
                                assertEquals(Color.TRANSPARENT, background(toggle));
                                toggle.setSelected(!toggle.isSelected());
                            }
                        }
                        snapshot(settings, "settings-" + (light ? "light-" : "dark-") + tab);
                    }
                }
                assertSame(scene, stage.getScene());
            } finally { settings.dispose(); manager.shutdown(); stage.close(); ThemeManager.setLight(original); }
            return null;
        });
    }

    private static SceneManager manager(Stage stage) {
        var manager = new SceneManager(stage, new AuthClient(), new FileTransferClient());
        manager.acceptSession(AuthResponse.ok("UI fixture", new AuthUser(99, "toan", "Toan", "toan@test.invalid"),
                "theme-fixture-token", Instant.now().plusSeconds(3600).toString()));
        return manager;
    }
    private static RoomInfo room(long id, String name, String state) {
        return new RoomInfo(id, name, "MMR-ABCDEFGH", 1, "2026-10-16T01:00:00Z", state, false, 10, 2, List.of());
    }
    private static void css(Parent root) { root.applyCss(); root.layout(); }
    private static Color background(Region region) { return (Color) region.getBackground().getFills().getFirst().getFill(); }
    private static void readable(javafx.scene.paint.Paint foreground, Color background) {
        double a = luminance((Color) foreground), b = luminance(background);
        assertTrue((Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 4.5, foreground + " on " + background);
    }
    private static double luminance(Color color) { return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue()); }
    private static double linear(double channel) { return channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4); }
    private static void snapshot(Parent root, String name) throws Exception {
        css(root); var image = root.snapshot(null, null);
        var png = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Path path = Path.of("target", "qa", "themes", name + ".png"); Files.createDirectories(path.getParent()); ImageIO.write(png, "png", path.toFile());
    }
}
