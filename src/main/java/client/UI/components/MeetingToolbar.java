package client.UI.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

/**
 * Collapse removes the controls from layout without stopping the call. Reveal remains reachable.
 */
public final class MeetingToolbar extends VBox {
    private final VBox content;
    private final IconButton hide, reveal;

    public MeetingToolbar(Node... children) {
        super(6);
        setId("meeting-toolbar");
        setAlignment(Pos.CENTER);
        content = Ui.column(10, children);
        content.setId("meeting-toolbar-content");
        content.setAlignment(Pos.CENTER);
        hide = new IconButton("eye-off", "Ẩn thanh công cụ");
        hide.setId("toggle-meeting-toolbar");
        hide.setOnAction(event -> expanded(false));
        reveal = new IconButton("eye", "Hiện thanh công cụ");
        reveal.setId("show-meeting-toolbar");
        reveal.setOnAction(event -> expanded(true));
        reveal.setMaxSize(48, 48);
        if (children.length > 0 && children[0] instanceof ControlBar bar) {
            VBox action = Ui.column(6, hide, Ui.label("Ẩn", "control-label"));
            action.setAlignment(Pos.CENTER);
            bar.getChildren().add(Math.max(0, bar.getChildren().size() - 1), action);
        } else content.getChildren().add(hide);
        getChildren().add(content);
        expanded(true);
    }

    private void expanded(boolean expanded) {
        content.setVisible(expanded);
        content.setManaged(expanded);
        setVisible(expanded);
        setManaged(expanded);
        reveal.setVisible(!expanded);
        reveal.setManaged(!expanded);
    }

    public VBox content() {
        return content;
    }

    public Button revealControl() {
        return reveal;
    }
}
