package client.UI.components;

import javafx.animation.ScaleTransition;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import java.util.Map;

/** Icon vector tự chứa; không cần font icon hay ảnh bên ngoài. */
public class IconButton extends Button {
    private static final Map<String, String> PATHS = Map.ofEntries(
        Map.entry("pin", "M8 3h8 M9 3v6l-3 4v2h12v-2l-3-4V3 M12 15v7"),
        Map.entry("mic", "M9 5a3 3 0 0 1 6 0v7a3 3 0 0 1-6 0z M5 10v2a7 7 0 0 0 14 0v-2 M12 19v3 M8 22h8"),
        Map.entry("mic-off", "M9 5a3 3 0 0 1 6 0v7 M5 10v2a7 7 0 0 0 12 5 M12 19v3 M8 22h8 M2 2l20 20"),
        Map.entry("camera", "M3 6h12v12H3z M15 10l6-4v12l-6-4z"),
        Map.entry("camera-off", "M3 6h12v12H3z M15 10l6-4v12l-6-4 M2 2l20 20"),
        Map.entry("share", "M8 17H3V3h18v14h-5 M12 21V9 M8 13l4-4 4 4"),
        Map.entry("chat", "M3 3h18v14H9l-6 4z M7 8h10 M7 12h7"),
        Map.entry("users", "M9 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M2 21v-2a7 7 0 0 1 14 0v2 M17 5a4 4 0 0 1 0 7 M19 15a5 5 0 0 1 3 5"),
        // Bánh răng 8 răng đối xứng quanh (12,12), vòng tròn giữa không nối với viền.
        Map.entry("settings", "M9.528 4.392 L10.264 2.152 L13.736 2.152 L14.472 4.392 L15.632 4.872 L17.736 3.808 L20.192 6.264 L19.128 8.368 L19.608 9.528 L21.848 10.264 L21.848 13.736 L19.608 14.472 L19.128 15.632 L20.192 17.736 L17.736 20.192 L15.632 19.128 L14.472 19.608 L13.736 21.848 L10.264 21.848 L9.528 19.608 L8.368 19.128 L6.264 20.192 L3.808 17.736 L4.872 15.632 L4.392 14.472 L2.152 13.736 L2.152 10.264 L4.392 9.528 L4.872 8.368 L3.808 6.264 L6.264 3.808 L8.368 4.872 Z M15.3 12 A3.3 3.3 0 1 0 8.7 12 A3.3 3.3 0 1 0 15.3 12 Z"),
        Map.entry("phone", "M3 15v-4q9-8 18 0v4h-5v-4q-4-2-8 0v4z"),
        Map.entry("copy", "M8 8h13v13H8z M16 4V2H2v14h2"),
        Map.entry("send", "M2 3l20 9-20 9 4-9z M6 12h16"),
        Map.entry("file", "M5 2h9l5 5v15H5z M14 2v6h5 M8 13h8 M8 17h8"),
        Map.entry("close", "M5 5l14 14 M19 5L5 19"),
        Map.entry("plus", "M12 3v18 M3 12h18"),
        Map.entry("arrow", "M5 12h14 M13 6l6 6-6 6"),
        Map.entry("check", "M4 12l5 5L20 6")
    );
    public IconButton(String icon, String tooltip) {
        getStyleClass().add("icon-button"); setIcon(icon); setTooltip(new Tooltip(tooltip)); setAccessibleText(tooltip);
        ScaleTransition scale = new ScaleTransition(Duration.millis(120), this);
        setOnMouseEntered(e -> { scale.stop(); scale.setToX(1.05); scale.setToY(1.05); scale.play(); });
        setOnMouseExited(e -> { scale.stop(); scale.setToX(1); scale.setToY(1); scale.play(); });
    }
    public void setIcon(String name) { setGraphic(icon(name)); }
    public static StackPane icon(String name) {
        SVGPath path = new SVGPath(); path.setContent(PATHS.getOrDefault(name, PATHS.get("file"))); path.getStyleClass().add("vector-icon");
        StackPane wrapper = new StackPane(path); wrapper.setMinSize(24, 24); wrapper.setPrefSize(24, 24); wrapper.setMaxSize(24, 24); return wrapper;
    }
    public void active(boolean active) { pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), active); }
}
