package client.UI;

import client.UI.components.Sidebar;
import client.UI.components.Ui;
import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.HBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

/** CSS and scene checks without showing a native window or contacting a server. */
class NavigationThemeRegressionTest {
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void exactlyOneNavRemainsActiveAcrossAllInteractionStatesAndThemes(boolean initialLight) throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            Stage stage = new Stage(); SceneManager manager = new SceneManager(stage);
            try {
                for (String selected : new String[] {"lobby", "settings"}) {
                    Sidebar root = new Sidebar(manager, selected);
                    Scene scene = new Scene(root, 260, 700);
                    scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                    ThemeManager.attach(root);
                    for (boolean light : new boolean[] {initialLight, !initialLight, initialLight}) {
                        ThemeManager.setLight(light); root.applyCss();
                        var buttons = root.lookupAll(".workspace-nav");
                        assertEquals(2, buttons.size());
                        assertEquals(1, buttons.stream().filter(n -> n.getStyleClass().contains("workspace-nav-active")).count());
                        Button active = (Button) root.lookup(".workspace-nav-active");
                        assertEquals(switch (selected) {
                            case "lobby" -> "Sảnh chính";
                            default -> "Cài đặt";
                        }, active.getText());
                        for (String state : new String[] {"focused", "hover", "pressed"}) {
                            for (boolean enabled : new boolean[] {true, false}) {
                                buttons.forEach(n -> n.pseudoClassStateChanged(PseudoClass.getPseudoClass(state), enabled));
                                root.applyCss();
                                assertEquals(1, buttons.stream().filter(n -> hasVisibleLeftBorder((Region) n)).count(),
                                        selected + "/" + light + "/" + state + "/" + enabled);
                                assertEquals(Color.web("#6C5CE7"), active.getBorder().getStrokes().getFirst().getLeftStroke());
                                assertEquals(3, active.getBorder().getStrokes().getFirst().getWidths().getLeft());
                                var activeFill = active.getBackground().getFills().getFirst().getFill();
                                buttons.stream().filter(n -> n != active).forEach(n ->
                                        assertNotEquals(activeFill, ((Button) n).getBackground().getFills().getFirst().getFill()));
                                if (state.equals("focused")) buttons.stream().filter(n -> n != active).forEach(n ->
                                        assertEquals(Color.TRANSPARENT, ((Button) n).getBackground().getFills().getFirst().getFill(),
                                                "Focus must not leave a faded active background"));
                            }
                        }
                    }
                }
            } finally { manager.shutdown(); stage.close(); ThemeManager.setLight(original); }
            return null;
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void sidebarCollapseKeepsControlsInsideAndRapidTogglesRestoreExpandedLayout(boolean light) throws Exception {
        boolean original = ThemeManager.isLight();
        Sidebar[] sidebar = new Sidebar[1];
        SceneManager[] manager = new SceneManager[1];
        Stage[] stage = new Stage[1];
        HBox[] root = new HBox[1];
        try {
            FxTestRuntime.call(() -> {
                ThemeManager.setLight(light);
                stage[0] = new Stage(); manager[0] = new SceneManager(stage[0]);
                sidebar[0] = new Sidebar(manager[0], "lobby");
                root[0] = new HBox(sidebar[0]);
                Scene scene = new Scene(root[0], 1000, 700);
                scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                stage[0].setScene(scene); ThemeManager.attach(root[0]);
                root[0].applyCss(); root[0].layout();
                assertEquals(260, sidebar[0].getWidth(), .1);
                ((Button) sidebar[0].lookup("#sidebar-collapse-button")).fire();
                return null;
            });
            FxTestRuntime.await(() -> sidebar[0].getPrefWidth() == 72
                    && sidebar[0].getMinWidth() == 72 && sidebar[0].getMaxWidth() == 72);
            FxTestRuntime.call(() -> {
                root[0].applyCss(); root[0].layout();
                assertEquals(72, sidebar[0].getWidth(), .1);
                var buttons = sidebar[0].lookupAll(".workspace-nav");
                assertEquals(2, buttons.size());
                assertEquals(1, sidebar[0].lookupAll(".workspace-nav-active").size());
                for (var node : buttons) {
                    Button button = (Button) node;
                    assertEquals(ContentDisplay.GRAPHIC_ONLY, button.getContentDisplay());
                    assertEquals(button.getText(), button.getTooltip().getText());
                    assertEquals(44, button.getWidth(), .1);
                    assertEquals(44, button.getHeight(), .1);
                    assertEquals(0, button.getPadding().getLeft(), .01);
                    assertEquals(0, button.getBorder().getStrokes().getFirst().getWidths().getLeft(), .01);
                    var icon = button.lookup(".sidebar-icon");
                    var bounds = button.sceneToLocal(icon.localToScene(icon.getBoundsInLocal()));
                    assertEquals(button.getWidth() / 2, bounds.getCenterX(), .6, button.getText());
                    assertEquals(button.getHeight() / 2, bounds.getCenterY(), .6, button.getText());
                }
                for (String selector : new String[] {".workspace-nav", ".brand-logo", "#sidebar-collapse-button", ".logout-button", ".sidebar-user"}) {
                    for (var node : sidebar[0].lookupAll(selector)) {
                        var bounds = sidebar[0].sceneToLocal(node.localToScene(node.getLayoutBounds()));
                        assertTrue(bounds.getMinX() >= -.1 && bounds.getMaxX() <= 72.1, selector);
                        assertTrue(bounds.getMinY() >= -.1 && bounds.getMaxY() <= sidebar[0].getHeight() + .1, selector);
                    }
                }
                sidebar[0].setCollapsed(false);
                sidebar[0].setCollapsed(true);
                sidebar[0].setCollapsed(false);
                return null;
            });
            FxTestRuntime.await(() -> sidebar[0].getPrefWidth() == 260
                    && sidebar[0].getMinWidth() == 260 && sidebar[0].getMaxWidth() == 260);
            FxTestRuntime.call(() -> {
                root[0].applyCss(); root[0].layout();
                assertEquals(260, sidebar[0].getWidth(), .1);
                for (var node : sidebar[0].lookupAll(".workspace-nav")) {
                    Button button = (Button) node;
                    assertEquals(ContentDisplay.LEFT, button.getContentDisplay());
                    assertEquals(javafx.geometry.Pos.CENTER_LEFT, button.getAlignment());
                }
                for (String selector : new String[] {".brand-name-small", ".eyebrow", ".user-name", ".user-caption"}) {
                    var label = sidebar[0].lookup(selector);
                    assertTrue(label.isVisible() && label.isManaged(), selector);
                }
                return null;
            });
        } finally {
            FxTestRuntime.call(() -> {
                if (manager[0] != null) manager[0].shutdown();
                if (stage[0] != null) stage[0].close();
                ThemeManager.setLight(original); return null;
            });
        }
    }

    @Test void sidebarKeepsSelectedWidthWhenNavigatingEvenDuringToggleAnimation() throws Exception {
        FxTestRuntime.call(() -> {
            Stage stage = new Stage(); SceneManager manager = new SceneManager(stage);
            manager.acceptSession(common.auth.AuthResponse.ok("UI fixture",
                    new common.auth.AuthUser(99, "test", "Test", "test@test.invalid"),
                    "sidebar-fixture", java.time.Instant.now().plusSeconds(3600).toString()));
            manager.cacheRecentRooms(java.util.List.of());
            try {
                manager.lobby();
                Sidebar sidebar = (Sidebar) stage.getScene().getRoot().lookup(".sidebar");
                assertEquals(260, sidebar.getPrefWidth());
                for (boolean collapse : new boolean[] {true, false}) {
                    // Navigate immediately after toggling, before the old sidebar's animation ends.
                    sidebar.setCollapsed(collapse);
                    for (String destination : new String[] {"Cài đặt", "Sảnh chính"}) {
                        Sidebar previous = sidebar;
                        sidebar.lookupAll(".workspace-nav").stream().map(Button.class::cast)
                                .filter(button -> destination.equals(button.getText())).findFirst().orElseThrow().fire();
                        sidebar = (Sidebar) stage.getScene().getRoot().lookup(".sidebar");
                        assertNotSame(previous, sidebar);
                        assertEquals(collapse ? 72 : 260, sidebar.getPrefWidth());
                        assertEquals(collapse ? 72 : 260, sidebar.getMinWidth());
                        assertEquals(collapse ? 72 : 260, sidebar.getMaxWidth());
                        assertEquals(collapse, manager.sidebarCollapsed());
                        Button active = (Button) sidebar.lookup(".workspace-nav-active");
                        assertEquals(destination, active.getText());
                        assertEquals(collapse ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT, active.getContentDisplay());
                    }
                }
            } finally { manager.shutdown(); stage.close(); }
            return null;
        });
    }

    @Test void navigationFadesOverThemeFillAndReusesScene() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight();
            Stage stage = new Stage(); SceneManager manager = new SceneManager(stage);
            var show = SceneManager.class.getDeclaredMethod("show", Parent.class, int.class, int.class, boolean.class, Runnable.class);
            show.setAccessible(true);
            try {
                ThemeManager.setLight(false);
                var first = Ui.column(0);
                show.invoke(manager, first, 1000, 700, true, (Runnable) () -> { });
                Scene scene = stage.getScene();
                assertEquals(Color.web("#0F1117"), scene.getFill());
                assertEquals(0, first.getOpacity());
                var second = Ui.column(0);
                second.sceneProperty().addListener((obs, before, after) -> {
                    if (after != null) assertFalse(second.getStyleClass().contains("light-mode"));
                });
                show.invoke(manager, second, 1000, 700, true, (Runnable) () -> { });
                assertSame(scene, stage.getScene()); assertNull(first.getScene());
                assertSame(second, scene.getRoot()); assertEquals(Color.web("#0F1117"), scene.getFill());
                ThemeManager.setLight(true); second.applyCss();
                assertSame(scene, stage.getScene()); assertEquals(Color.web("#F7F8FC"), scene.getFill());
                assertEquals(scene.getFill(), second.getBackground().getFills().getFirst().getFill());
                var third = Ui.column(0);
                third.getStyleClass().add("theme-dark");
                third.sceneProperty().addListener((obs, before, after) -> {
                    if (after != null) assertTrue(third.getStyleClass().contains("light-mode"));
                });
                show.invoke(manager, third, 1000, 700, true, (Runnable) () -> { });
                assertSame(scene, stage.getScene());
                ThemeManager.setLight(false); assertEquals(Color.web("#0F1117"), scene.getFill());
            } finally { manager.shutdown(); stage.close(); ThemeManager.setLight(original); }
            return null;
        });
    }

    @Test void dialogSceneFillTracksThemeAndPaneSceneChangesBeforeShowing() throws Exception {
        FxTestRuntime.call(() -> {
            boolean original = ThemeManager.isLight(); Dialog<Void> dialog = new Dialog<>();
            try {
                ThemeManager.setLight(false); Ui.style(dialog);
                DialogPane pane = dialog.getDialogPane(); Scene scene = pane.getScene();
                assertNotNull(scene); assertEquals(Color.web("#0F1117"), scene.getFill());
                pane.applyCss(); var dark = pane.getBackground().getFills().getFirst().getFill();
                ThemeManager.setLight(true); pane.applyCss();
                assertSame(scene, pane.getScene()); assertEquals(Color.web("#F7F8FC"), scene.getFill());
                assertNotEquals(dark, pane.getBackground().getFills().getFirst().getFill());
                ThemeManager.setLight(false); pane.applyCss();
                assertEquals(dark, pane.getBackground().getFills().getFirst().getFill());
                assertEquals(Color.web("#0F1117"), scene.getFill());
                scene.setRoot(Ui.column(0));
                Scene replacement = new Scene(Ui.column(0));
                replacement.setRoot(pane);
                assertEquals(Color.web("#0F1117"), replacement.getFill());
                ThemeManager.setLight(true); assertEquals(Color.web("#F7F8FC"), replacement.getFill());
            } finally { dialog.close(); ThemeManager.setLight(original); }
            return null;
        });
    }

    private static boolean hasVisibleLeftBorder(Region region) {
        return region.getBorder() != null && region.getBorder().getStrokes().stream().anyMatch(stroke ->
                stroke.getWidths().getLeft() > 0 && stroke.getLeftStroke() instanceof Color color && color.getOpacity() > 0);
    }
}
