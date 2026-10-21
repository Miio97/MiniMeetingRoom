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
                for (String selected : new String[] {"lobby", "history", "settings"}) {
                    Sidebar root = new Sidebar(manager, selected);
                    Scene scene = new Scene(root, 260, 700);
                    scene.getStylesheets().add(Ui.class.getResource("/styles/app.css").toExternalForm());
                    ThemeManager.attach(root);
                    for (boolean light : new boolean[] {initialLight, !initialLight, initialLight}) {
                        ThemeManager.setLight(light); root.applyCss();
                        var buttons = root.lookupAll(".workspace-nav");
                        assertEquals(3, buttons.size());
                        assertEquals(1, buttons.stream().filter(n -> n.getStyleClass().contains("workspace-nav-active")).count());
                        Button active = (Button) root.lookup(".workspace-nav-active");
                        assertEquals(switch (selected) {
                            case "lobby" -> "Sảnh chính";
                            case "history" -> "Lịch sử";
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
