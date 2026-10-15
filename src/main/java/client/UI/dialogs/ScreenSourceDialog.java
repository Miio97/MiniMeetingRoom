package client.UI.dialogs;

import client.UI.components.Ui;
import client.media.*;
import java.util.concurrent.atomic.AtomicLong;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.stage.Window;

/** Listing is metadata only. Selecting a source explicitly starts a local preview; Cancel publishes nothing. */
public final class ScreenSourceDialog extends Dialog<ScreenVideoInput.Choice> {
    public ScreenSourceDialog(Window owner,ScreenCallController screen,String self) {
        if(owner!=null) initOwner(owner);setTitle("Chia sẻ màn hình");setHeaderText("Chọn màn hình hoặc cửa sổ để chia sẻ");Ui.style(this);
        ComboBox<ScreenVideoInput.Choice> choices=new ComboBox<>();choices.setId("screen-source-choice");choices.setPrefWidth(520);
        choices.setPromptText("Chọn nguồn — chưa bắt đầu chia sẻ");
        choices.setConverter(new javafx.util.StringConverter<>() {
            public String toString(ScreenVideoInput.Choice choice) {return choice==null ? "" : (choice.window() ? "Cửa sổ: " : "Màn hình: ")+choice.title();}
            public ScreenVideoInput.Choice fromString(String value) {return null;}
        });
        ImageView preview=new ImageView();preview.setPreserveRatio(true);preview.setFitWidth(520);preview.setFitHeight(280);
        preview.imageProperty().bind(screen.frames(self).imageProperty());
        Label error=Ui.label("", "error-text");error.setWrapText(true);
        ButtonType share=new ButtonType("Chia sẻ",ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(share,ButtonType.CANCEL);
        ((Button)getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Hủy");
        Button start=(Button)getDialogPane().lookupButton(share);start.setDisable(true);start.setId("screen-source-confirm");
        getDialogPane().setContent(Ui.column(12,choices,preview,error,Ui.label("Chỉ chia sẻ khi bạn nhấn Chia sẻ. Đóng hoặc thu nhỏ cửa sổ có thể dừng nguồn; chọn lại nguồn sau khi khôi phục.","caption")));
        AtomicLong selection=new AtomicLong();
        choices.valueProperty().addListener((observable,before,after) -> {
            long epoch=selection.incrementAndGet();start.setDisable(true);error.setText("");if(after==null) return;
            screen.preview(after).whenComplete((value,failure) -> Platform.runLater(() -> {
                if(selection.get()!=epoch || !isShowing()) return;
                if(failure!=null) error.setText(CameraErrors.screenMessage(failure));else start.setDisable(false);
            }));
        });
        screen.choices().whenComplete((sources,failure) -> Platform.runLater(() -> {
            if(failure!=null) error.setText(CameraErrors.screenMessage(failure));
            else {choices.getItems().setAll(sources);if(sources.isEmpty()) error.setText("Không tìm thấy nguồn chia sẻ. Kiểm tra quyền chụp màn hình của Windows.");}
        }));
        setResultConverter(button -> button==share ? choices.getValue() : null);
        setOnHidden(event -> {selection.incrementAndGet();preview.imageProperty().unbind();preview.setImage(null);screen.endPreview();});
    }
}
