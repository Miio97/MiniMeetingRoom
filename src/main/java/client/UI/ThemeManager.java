package client.UI;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.css.PseudoClass;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import java.util.WeakHashMap;

/** Theme dùng chung trong phiên chạy; đổi màu tại chỗ, giữ nguyên dữ liệu và control. */
public final class ThemeManager {
    private static final BooleanProperty LIGHT = new SimpleBooleanProperty(true);
    private static final WeakHashMap<Parent, Boolean> ROOTS = new WeakHashMap<>();
    static { LIGHT.addListener((obs, old, value) -> ROOTS.keySet().forEach(ThemeManager::apply)); }
    private ThemeManager() { }
    public static boolean isLight() { return LIGHT.get(); }
    public static void setLight(boolean value) { LIGHT.set(value); }
    public static void attach(Parent root) { ROOTS.put(root, Boolean.TRUE); apply(root); }
    private static void apply(Parent root) {
        root.getStyleClass().remove("light-mode");
        boolean auth = root.getStyleClass().contains("auth-screen");
        if (!root.getStyleClass().contains("theme-dark") && (!auth || isLight())) root.getStyleClass().add("light-mode");
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
