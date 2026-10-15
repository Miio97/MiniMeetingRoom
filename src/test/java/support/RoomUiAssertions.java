package support;

import java.util.regex.Pattern;
import java.util.stream.Stream;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks displayed and accessibility copy; JavaFX node IDs remain internal selectors. */
public final class RoomUiAssertions {
    private RoomUiAssertions() { }

    public static void noRoomIds(Parent root, long roomId) {
        Pattern idLabel = Pattern.compile("(?i)\\b(?:ID|roomId|room_id|Ma_Phong)\\b");
        Pattern unlabelled = Pattern.compile("(?:^" + roomId + "\\s+[·|]|[·|]\\s*" + roomId + "(?:\\s*[·|]|$))");
        nodes(root).forEach(node -> {
            check(node.getAccessibleText(), idLabel, unlabelled);
            check(node.getAccessibleHelp(), idLabel, unlabelled);
            if (node instanceof Labeled label) check(label.getText(), idLabel, unlabelled);
            if (node instanceof TextInputControl input) check(input.getPromptText(), idLabel, unlabelled);
            if (node instanceof ComboBox<?> combo) check(combo.getPromptText(), idLabel, unlabelled);
            if (node instanceof Control control && control.getTooltip() != null)
                check(control.getTooltip().getText(), idLabel, unlabelled);
            if (node instanceof TabPane tabs) tabs.getTabs().forEach(tab -> {
                check(tab.getText(), idLabel, unlabelled);
                if (tab.getTooltip() != null) check(tab.getTooltip().getText(), idLabel, unlabelled);
            });
        });
    }

    private static void check(String text, Pattern idLabel, Pattern unlabelled) {
        if (text == null) return;
        assertFalse(idLabel.matcher(text).find(), () -> "Room ID wording leaked to the UI: " + text);
        assertFalse(unlabelled.matcher(text).find(), () -> "Internal room number leaked to metadata: " + text);
    }

    private static Stream<Node> nodes(Node root) {
        if (root instanceof ScrollPane scroll && scroll.getContent() != null)
            return Stream.concat(Stream.of(root), nodes(scroll.getContent()));
        if (root instanceof TabPane tabs)
            return Stream.concat(Stream.of(root), tabs.getTabs().stream().map(Tab::getContent).filter(java.util.Objects::nonNull).flatMap(RoomUiAssertions::nodes));
        return root instanceof Parent parent ? Stream.concat(Stream.of(root),
                parent.getChildrenUnmodifiable().stream().flatMap(RoomUiAssertions::nodes)) : Stream.of(root);
    }
}
