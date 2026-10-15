package client.UI.dialogs;

import client.UI.components.*;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Window;

/** Sharing credentials is an explicit host action; no invitation is sent automatically. */
public final class InviteDialog extends Dialog<Void> {
    public InviteDialog(Window owner, String name, String code, String pin) {
        initOwner(owner); setTitle("Mời người dùng"); setHeaderText(name); Ui.style(this);
        TextField codeField = new TextField(code), pinField = new TextField(pin.isBlank() ? "Chủ phòng cung cấp riêng" : pin); codeField.setEditable(false); pinField.setEditable(false);
        Label status = Ui.label("", "caption");
        var content = Ui.column(14, Ui.field("Mã phòng", codeField), Ui.field("PIN", pinField), status); content.setPrefWidth(380); getDialogPane().setContent(content);
        ButtonType copy = new ButtonType("Sao chép lời mời", ButtonBar.ButtonData.OTHER);
        getDialogPane().getButtonTypes().addAll(copy, ButtonType.CLOSE);
        Button button = (Button) getDialogPane().lookupButton(copy); button.setGraphic(IconButton.icon("copy"));
        button.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); ClipboardContent text = new ClipboardContent(); text.putString("Phòng: " + name + "\nMã phòng: " + code + "\nPIN: " + pinField.getText());
            try { status.setText(Clipboard.getSystemClipboard().setContent(text) ? "Đã sao chép" : "Chưa thể sao chép."); }
            catch (IllegalStateException error) { status.setText("Chưa thể sao chép."); }
        });
    }
}
