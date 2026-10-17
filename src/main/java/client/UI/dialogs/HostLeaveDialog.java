package client.UI.dialogs;

import client.UI.components.Ui;
import common.meeting.*;
import javafx.scene.control.*;
import javafx.stage.Window;

/** A voluntary host departure always makes the successor/close choice visible to the host. */
public final class HostLeaveDialog extends Dialog<HostLeaveDialog.Choice> {
    public record Choice(String successorParticipantId, boolean closeRoom) { }
    public HostLeaveDialog(Window owner, RoomSnapshot room, String self) {
        initOwner(owner); setTitle("Rời phòng họp"); setHeaderText("Chọn cách rời phòng của chủ phòng"); Ui.style(this);
        ComboBox<ParticipantView> people = new ComboBox<>(); people.setId("host-successor"); people.setMaxWidth(Double.MAX_VALUE);
        people.getItems().setAll(room.participants().stream().filter(person -> !person.participantId().equals(self)).toList());
        people.setPromptText(people.getItems().isEmpty() ? "Không có thành viên khác" : "Chọn chủ phòng tiếp theo");
        people.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ParticipantView person) { return person == null ? "" : person.displayName(); }
            public ParticipantView fromString(String text) { return null; }
        });
        ButtonType transfer = new ButtonType("Chuyển quyền và rời", ButtonBar.ButtonData.OK_DONE);
        ButtonType end = new ButtonType("Đóng cả phòng", ButtonBar.ButtonData.OTHER);
        getDialogPane().getButtonTypes().addAll(transfer, end, ButtonType.CANCEL);
        var transferButton = (Button) getDialogPane().lookupButton(transfer); transferButton.setId("host-leave-transfer");
        transferButton.getStyleClass().add("primary");
        transferButton.disableProperty().bind(people.valueProperty().isNull());
        getDialogPane().lookupButton(end).setId("host-leave-close");
        getDialogPane().lookupButton(end).getStyleClass().add("danger");
        ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        getDialogPane().setContent(Ui.column(12, people, Ui.label("Chuyển quyền để cuộc họp tiếp tục, hoặc đóng phòng để kết thúc cho tất cả thành viên.", "caption")));
        getDialogPane().setPrefWidth(650);
        setResultConverter(button -> button == end ? new Choice(null, true)
                : button == transfer && people.getValue() != null ? new Choice(people.getValue().participantId(), false) : null);
    }
}
