package client.UI;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.collections.ListChangeListener;
import javafx.stage.PopupWindow;
import javafx.stage.Window;
import java.util.WeakHashMap;

/** Theme dùng chung trong phiên chạy; đổi màu tại chỗ, giữ nguyên dữ liệu và control. */
public final class ThemeManager {
    private static final BooleanProperty LIGHT = new SimpleBooleanProperty(true);
    private static final Object SCENE_THEME_LISTENER = new Object();
    // Keep these aligned with the final -bg-main tokens in app.css.
    private static final Color LIGHT_BACKGROUND = Color.web("#F7F8FC");
    private static final Color DARK_BACKGROUND = Color.web("#0F1117");
    // Value is true only for PopupControl CSS bridges that need an explicit dark palette.
    private static final WeakHashMap<Parent, Boolean> ROOTS = new WeakHashMap<>();
    static {
        // Popup windows need the shared stylesheet/palette on their own Scene root as well as owner CSS.
        // Covers ContextMenu, Tooltip and ComboBox skins, including controls created after a switch.
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) if (change.wasAdded()) {
                for (Window window : change.getAddedSubList()) if (window instanceof PopupWindow) {
                    Parent root = window.getScene().getRoot();
                    String css = ThemeManager.class.getResource("/styles/app.css").toExternalForm();
                    if (!root.getStylesheets().contains(css)) root.getStylesheets().add(css);
                    if (!root.getStyleClass().contains("theme-popup-root")) root.getStyleClass().add("theme-popup-root");
                    attach(root);
                    // PopupControl's CSSBridge uses an owner-based styleable parent, bypassing the
                    // popup Scene root. Give that surface an explicit palette as well.
                    for (javafx.scene.Node child : root.getChildrenUnmodifiable()) if (child instanceof Parent surface) {
                        ROOTS.put(surface, Boolean.TRUE); apply(surface);
                    }
                }
            }
        });
    }
    private ThemeManager() { }
    public static boolean isLight() { return LIGHT.get(); }
    public static Color backgroundColor() { return isLight() ? LIGHT_BACKGROUND : DARK_BACKGROUND; }
    /** Scene fill remains visible while a root fades; keep it in the same palette. */
    public static void attachScene(Scene scene) {
        scene.setFill(backgroundColor());
        if (scene.getProperties().containsKey(SCENE_THEME_LISTENER)) return;
        ChangeListener<Boolean> update = (obs, old, value) -> scene.setFill(backgroundColor());
        LIGHT.addListener(new WeakChangeListener<>(update));
        // The scene owns its listener; the global preference must not retain old scenes.
        scene.getProperties().put(SCENE_THEME_LISTENER, update);
    }
    public static void setLight(boolean value) {
        LIGHT.set(value);
        // Snapshot weakly held roots after property listeners run; closing native dialogs can release roots.
        for (Parent root : new java.util.ArrayList<>(ROOTS.keySet())) apply(root);
    }
    public static void attach(Parent root) {
        // Old meeting roots used a forced class; actual windows follow the shared preference.
        if (root.getScene() != null && root.getScene().getRoot() == root) root.getStyleClass().remove("theme-dark");
        ROOTS.put(root, Boolean.FALSE); apply(root);
    }
    private static void apply(Parent root) {
        if (Boolean.TRUE.equals(ROOTS.get(root))) {
            root.getStyleClass().removeAll("light-mode", "theme-dark");
            root.getStyleClass().add(isLight() ? "light-mode" : "theme-dark");
            return;
        }
        root.getStyleClass().removeAll("light-mode");
        if (!root.getStyleClass().contains("theme-dark") && isLight()) root.getStyleClass().add("light-mode");
    }
    public static Button toggleButton() {
        Button button = new Button(); button.getStyleClass().add("theme-toggle");
        button.textProperty().bind(Bindings.when(LIGHT).then("☾  Tối").otherwise("☀  Sáng"));
        button.setAccessibleText("Chuyển giao diện sáng hoặc tối");
        Tooltip tip = new Tooltip();
        tip.textProperty().bind(Bindings.when(LIGHT).then("Chuyển sang chế độ tối").otherwise("Chuyển sang chế độ sáng"));
        button.setTooltip(tip); button.setOnAction(e -> setLight(!isLight()));
        return button;
    }
    /** Lựa chọn có trạng thái rõ ràng trong Cài đặt, thay vì nút đổi theme rải rác. */
    public static HBox selector() {
        Button light = new Button("☀  Sáng"), dark = new Button("☾  Tối");
        light.setId("theme-light"); dark.setId("theme-dark");
        light.getStyleClass().add("theme-choice"); dark.getStyleClass().add("theme-choice");
        light.setOnAction(e -> setLight(true)); dark.setOnAction(e -> setLight(false));
        HBox choices = new HBox(6, light, dark); choices.getStyleClass().add("theme-selector");
        ChangeListener<Boolean> update = (obs, old, value) -> {
            light.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), value);
            dark.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), !value);
        };
        update.changed(LIGHT, isLight(), isLight());
        LIGHT.addListener(new WeakChangeListener<>(update));
        choices.getProperties().put("theme-listener", update);
        return choices;
    }
}
