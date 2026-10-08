package client.UI.components;

import client.UI.dialogs.HostMenuPopup;
import client.UI.mock.MockData;
import javafx.collections.ObservableList;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.layout.*;
import java.util.function.Consumer;

public final class ParticipantPanel extends BorderPane {
    private final ListView<MockData.Participant> list = new ListView<>();
    private final ObservableList<MockData.Participant> people;
    private final Consumer<MockData.Participant> remove;
    private final Consumer<MockData.Participant> mute, stopCamera;
    private final Runnable lock, close;
    public ParticipantPanel(ObservableList<MockData.Participant> people, Consumer<MockData.Participant> mute, Consumer<MockData.Participant> stopCamera, Consumer<MockData.Participant> remove, Runnable lock, Runnable close) {
        this.mute = mute; this.stopCamera = stopCamera;
        this.people = people; this.remove = remove; this.lock = lock; this.close = close;
        list.setItems(people); list.getStyleClass().add("participant-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(MockData.Participant p, boolean empty) {
                super.updateItem(p, empty); setText(null); setGraphic(empty || p == null ? null : row(p));
            }
        }); setCenter(list);
        setBottom(Ui.label("Host • Chỉ được tắt mic/camera của thành viên", "panel-note"));
    }
    private HBox row(MockData.Participant p) {
            VBox details = Ui.column(4, Ui.label(p.name(), "strong"), Ui.row(5, IconButton.icon(p.muted() ? "mic-off" : "mic"), IconButton.icon(p.camera() ? "camera" : "camera-off")));
            HBox.setHgrow(details, Priority.ALWAYS);
            Button menu = Ui.button("⋮", "small-button", () -> { });
            menu.setAccessibleText("Quản lý " + p.name());
            menu.setTooltip(new javafx.scene.control.Tooltip(p.host() ? "Quản lý phòng (Host)" : "Quản lý thành viên"));
            menu.setOnAction(e -> new HostMenuPopup(p.host(), p.muted(), p.camera(), () -> mute.accept(p), () -> stopCamera.accept(p), () -> remove.accept(p), lock, close).show(menu, javafx.geometry.Side.BOTTOM, 0, 0));
            HBox row = Ui.row(8, Ui.avatar(p.name(), 34), details);
            if (p.host()) row.getChildren().add(Ui.label("Host", "host-badge")); row.getChildren().add(menu); row.getStyleClass().add("participant-row");
            return row;
    }
}
