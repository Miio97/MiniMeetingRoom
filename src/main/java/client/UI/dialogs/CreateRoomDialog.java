package client.UI.dialogs;

import client.UI.components.Ui;
import client.UI.mock.MockData;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

public final class CreateRoomDialog extends Dialog<CreateRoomDialog.Result> {
    public record Result(MockData.Room room, boolean locked) { }
    public CreateRoomDialog(Window owner) {
        initOwner(owner); setTitle("Tạo phòng mới"); setHeaderText("Một không gian cho những ý tưởng mới"); Ui.style(this);
        TextField name = new TextField(); name.setPromptText("Ví dụ: Họp nhóm sản phẩm");
        PasswordField pin = new PasswordField(); pin.setPromptText("PIN gồm 4–6 chữ số");
        ToggleButton lock = new ToggleButton("Khóa phòng sau khi bắt đầu"); lock.setMaxWidth(Double.MAX_VALUE);
        Label error = Ui.label("", "error-text");
        Spinner<Integer> capacity = new Spinner<>(2, MockData.MAX_ROOM_CAPACITY, 50); capacity.setEditable(false); capacity.setMaxWidth(Double.MAX_VALUE); capacity.setAccessibleText("Số người tối đa trong phòng");
        VBox content = Ui.column(16, Ui.field("Tên phòng", name), Ui.field("PIN", pin), Ui.field("Số người tối đa (bao gồm chủ phòng)", capacity), Ui.label("2–250 người • Giới hạn mô phỏng, chưa phản ánh năng lực backend.", "caption"), lock, error); content.setPrefWidth(400);
        getDialogPane().setContent(content);
        ButtonType create = new ButtonType("Tạo phòng", ButtonBar.ButtonData.OK_DONE); getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, create);
        ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        Button submit = (Button) getDialogPane().lookupButton(create); submit.getStyleClass().add("primary");
        submit.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (name.getText().isBlank() || !pin.getText().matches("\\d{4,6}")) { error.setText("Điền tên phòng và PIN gồm 4–6 số."); e.consume(); }
        });
        setResultConverter(type -> type == create ? new Result(new MockData.Room(name.getText().strip(), "MMR-" + (1000 + new java.util.Random().nextInt(9000)), Math.min(6, capacity.getValue()), "Vừa tạo", pin.getText(), capacity.getValue()), lock.isSelected()) : null);
    }
}
