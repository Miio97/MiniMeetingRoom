package client.UI.dialogs;

import client.UI.SceneManager;
import client.UI.components.Ui;
import common.room.RoomInfo;
import common.room.RoomValidation;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

/** Chỉ đóng thành công sau khi server tạo phòng và thêm chủ phòng. */
public final class CreateRoomDialog extends Dialog<RoomInfo> {
    private boolean pending;
    public CreateRoomDialog(SceneManager manager) {
        initOwner(manager.stage()); setTitle("Tạo phòng mới");
        setHeaderText("Một không gian cho những ý tưởng mới"); Ui.style(this);
        TextField name = new TextField(); name.setPromptText("Ví dụ: Họp nhóm sản phẩm"); name.setId("room-name");
        PasswordField pin = new PasswordField(); pin.setPromptText("PIN gồm 4–6 chữ số"); pin.setId("room-pin");
        Spinner<Integer> capacity = new Spinner<>(RoomValidation.MIN_CAPACITY, RoomValidation.MAX_CAPACITY, RoomValidation.DEFAULT_CAPACITY);
        capacity.setId("room-capacity"); capacity.setEditable(false); capacity.setMaxWidth(Double.MAX_VALUE);
        capacity.setAccessibleText("Số người tối đa trong phòng");
        Label error = Ui.label("", "error-text"); error.setWrapText(true); error.setId("create-room-error");
        VBox content = Ui.column(16, Ui.field("Tên phòng", name), Ui.field("PIN", pin),
            Ui.field("Số người tối đa (bao gồm chủ phòng)", capacity),
            Ui.label("Mã phòng và ID được máy chủ tạo tự động.", "caption"), error);
        content.setPrefWidth(400); getDialogPane().setContent(content);
        ButtonType create = new ButtonType("Tạo phòng", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, create);
        Button cancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL); cancel.setText("Hủy");
        Button submit = (Button) getDialogPane().lookupButton(create); submit.getStyleClass().add("primary"); submit.setId("create-room-submit");
        setResultConverter(type -> null);
        setOnCloseRequest(event -> { if (pending) event.consume(); });
        submit.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); if (pending) return;
            String validation = RoomValidation.nameError(name.getText());
            if (validation == null) validation = RoomValidation.pinError(pin.getText());
            if (validation == null) validation = RoomValidation.capacityError(capacity.getValue());
            if (validation != null) { error.setText(validation); return; }
            String roomName = name.getText(); String roomPin = pin.getText(); int limit = capacity.getValue();
            pending = true; content.setDisable(true); submit.setDisable(true); cancel.setDisable(true); submit.setText("Đang tạo…"); error.setText("");
            manager.runAuthRequest(() -> manager.authClient().createRoom(manager.sessionToken(), roomName, roomPin, limit), response -> {
                pending = false; content.setDisable(false); submit.setDisable(false); cancel.setDisable(false); submit.setText("Tạo phòng");
                if (manager.sessionExpired(response)) { close(); return; }
                if (response.success() && response.room() != null) { pin.clear(); setResult(response.room()); close(); }
                else error.setText(response.message() == null ? "Máy chủ chưa tạo được phòng. Vui lòng thử lại." : response.message());
            }, cause -> {
                pending = false; content.setDisable(false); submit.setDisable(false); cancel.setDisable(false); submit.setText("Tạo phòng");
                error.setText(SceneManager.connectionError(cause));
            });
        });
        setOnHidden(event -> pin.clear());
    }
}
