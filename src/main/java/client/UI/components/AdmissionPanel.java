package client.UI.components;

import common.meeting.Participant;
import java.util.List;
import java.util.function.BiConsumer;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Host-only waiting list inside the meeting's approval tab. */
public final class AdmissionPanel extends VBox {
    private List<Participant> shown; private boolean locked;
    public AdmissionPanel() { super(8); setId("admission-panel"); setPadding(new Insets(12)); setVisible(false); setManaged(false); }
    public void update(List<Participant> people, boolean host, boolean roomLocked, BiConsumer<String, Long> command) {
        List<Participant> next = host ? List.copyOf(people) : List.of();
        boolean visible = host, wasVisible = isVisible(); setVisible(visible); setManaged(visible);
        if (next.equals(shown) && locked == roomLocked && visible == wasVisible) return;
        shown = next; locked = roomLocked; getChildren().clear();
        if (!visible) return;
        if (next.isEmpty()) { getChildren().add(Ui.label("Chưa có yêu cầu chờ.", "caption")); return; }
        if (locked) getChildren().add(Ui.label("Mở khóa phòng để chấp nhận yêu cầu.", "caption"));
        VBox rows = new VBox(8);
        for (Participant person : next) {
            Label name = Ui.label(person.name(), "strong"); name.setMinWidth(0); HBox.setHgrow(name, Priority.ALWAYS);
            IconButton yes = new IconButton("check", "Chấp nhận " + person.name()); yes.setId("admit-" + person.userId()); yes.setDisable(locked);
            IconButton no = new IconButton("close", "Từ chối " + person.name()); no.setId("reject-" + person.userId());
            Runnable pending = () -> { yes.setDisable(true); no.setDisable(true); };
            yes.setOnAction(event -> { pending.run(); command.accept("ROOM_PENDING_APPROVE", person.userId()); });
            no.setOnAction(event -> { pending.run(); command.accept("ROOM_PENDING_REJECT", person.userId()); });
            rows.getChildren().add(Ui.row(8, name, yes, no));
        }
        ScrollPane scroll = Ui.scroll(rows); VBox.setVgrow(scroll, Priority.ALWAYS); getChildren().add(scroll);
    }
    public void retry() { shown = null; }
}
