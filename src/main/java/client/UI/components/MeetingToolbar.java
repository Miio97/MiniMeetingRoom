package client.UI.components;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

/** Collapse removes the controls from layout without stopping the call. Reveal remains reachable. */
public final class MeetingToolbar extends VBox {
    private final VBox content;
    private final Button toggle;
    public MeetingToolbar(Node... children) {
        super(6); setId("meeting-toolbar"); setAlignment(Pos.CENTER);
        content = Ui.column(10, children); content.setId("meeting-toolbar-content"); content.setAlignment(Pos.CENTER);
        toggle = Ui.button("Ẩn thanh công cụ", "text-button", () -> expanded(!content.isManaged()));
        toggle.setId("toggle-meeting-toolbar"); getChildren().addAll(content, toggle); expanded(true);
    }
    private void expanded(boolean expanded) {
        content.setVisible(expanded); content.setManaged(expanded);
        toggle.setText(expanded ? "Ẩn thanh công cụ" : "Hiện thanh công cụ");
        toggle.setAccessibleText(toggle.getText());
    }
    public VBox content() { return content; }
}
