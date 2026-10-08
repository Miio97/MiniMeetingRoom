package client.UI.components;

import client.UI.components.Ui;
import client.UI.mock.MockData;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;

public final class FilePanel extends BorderPane {
    private final VBox list = new VBox(12);
    private int count;
    public FilePanel(Window owner, String user) {
        list.setPadding(new Insets(16)); MockData.files().forEach(f -> add(f, owner)); setCenter(Ui.scroll(list));
        Button send = Ui.button("+  Gửi tệp", "primary", () -> add(new MockData.SharedFile("Tai-lieu-demo-" + (++count) + ".pdf", "256 KB", user), owner)); send.setMaxWidth(Double.MAX_VALUE);
        VBox bottom = Ui.column(8, send, Ui.label("Gửi/tải chỉ mô phỏng, không đọc hoặc ghi tệp.", "caption")); bottom.setPadding(new Insets(12)); setBottom(bottom);
    }
    private void add(MockData.SharedFile file, Window owner) {
        IconButton download = new IconButton("arrow", "Tải " + file.name()); download.setOnAction(e -> Ui.info(owner, "Tải tệp demo", file.name() + "\nĐã mô phỏng tải xuống thành công. Không có tệp được ghi."));
        Label title = Ui.label(file.name(), "strong"); title.setMaxWidth(190);
        VBox row = Ui.column(10, Ui.row(8, IconButton.icon("file"), title, Ui.spacer(), download), Ui.label(file.size() + "  ·  " + file.sender(), "caption")); row.getStyleClass().add("file-row"); list.getChildren().add(row);
    }
}
