package client.UI.components;

import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;
import java.util.Map;

/** Các path trong hệ tọa độ 24px, cùng độ dày nét sau khi thu phóng. */
public final class Icons {
    private Icons() { }
    private static final Map<String, String> PATHS = Map.of(
        "home", "M3 10L12 3L21 10V21H15V14H9V21H3Z",
        "history", "M22 12A10 10 0 1 0 2 12A10 10 0 1 0 22 12 M12 6V12L16 14",
        "settings", "M9 3L10 1H14L15 3L18 5L21 5L23 9L21 12L23 15L21 19L18 19L15 21L14 23H10L9 21L6 19H3L1 15L3 12L1 9L3 5H6Z M16 12A4 4 0 1 0 8 12A4 4 0 1 0 16 12",
        "user", "M16 7A4 4 0 1 0 8 7A4 4 0 1 0 16 7 M4 22V20A8 8 0 0 1 20 20V22",
        "video", "M3 6H15V18H3Z M15 10L22 6V18L15 14Z",
        "logout", "M9 3H3V21H9 M8 12H22 M17 7L22 12L17 17",
        "search", "M18 10A8 8 0 1 0 2 10A8 8 0 1 0 18 10 M16 16L22 22"
    );
    public static StackPane icon(String name, double size) {
        SVGPath path = new SVGPath(); path.setContent(PATHS.getOrDefault(name, PATHS.get("video")));
        path.getStyleClass().add("sidebar-icon");
        path.setStrokeWidth(1.8 * 24 / size);
        path.getTransforms().add(new Scale(size / 24, size / 24));
        StackPane box = new StackPane(path); box.setMinSize(size, size); box.setPrefSize(size, size); box.setMaxSize(size, size);
        return box;
    }
}
