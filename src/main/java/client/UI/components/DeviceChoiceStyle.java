package client.UI.components;

import java.util.function.Function;
import javafx.scene.control.*;

/** Bounded device names with full names available to keyboard and pointer users. */
final class DeviceChoiceStyle {
    private DeviceChoiceStyle() { }
    static <T> void apply(ComboBox<T> box, Function<T, String> name) {
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        box.setCellFactory(list -> cell(name));
        box.setButtonCell(cell(name));
    }
    private static <T> ListCell<T> cell(Function<T, String> name) {
        return new ListCell<>() {
            { setPrefWidth(0); }
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                String text = empty || item == null ? null : name.apply(item);
                setText(text);
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setTooltip(text == null ? null : new Tooltip(text));
            }
        };
    }
}
