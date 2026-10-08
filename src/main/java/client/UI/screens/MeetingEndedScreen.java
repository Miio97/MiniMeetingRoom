package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.mock.MockData;
import javafx.geometry.Pos;
import javafx.scene.layout.*;

public final class MeetingEndedScreen extends StackPane {
    public MeetingEndedScreen(SceneManager manager, long seconds, int count) {
        StackPane check = IconButton.icon("check"); check.getStyleClass().add("ended-icon"); check.setMinSize(80, 80); check.setMaxSize(80, 80);
        VBox duration = Ui.column(8, Ui.label(MockData.duration(seconds), "title"), Ui.label("Thời lượng", "muted")); duration.setAlignment(Pos.CENTER);
        VBox participants = Ui.column(8, Ui.label(String.valueOf(count), "title"), Ui.label("Người tham gia", "muted")); participants.setAlignment(Pos.CENTER);
        HBox stats = Ui.row(64, duration, participants); stats.setAlignment(Pos.CENTER); stats.getStyleClass().add("ended-stats");
        HBox actions = Ui.row(12, Ui.button("Quay lại sảnh", "primary", manager::lobby), Ui.button("Vào lại phòng", "secondary", () -> manager.preJoin(manager.room()))); actions.setAlignment(Pos.CENTER);
        VBox card = Ui.column(24, check, Ui.label("Cuộc họp đã kết thúc", "title"), Ui.label("Cảm ơn bạn đã dành thời gian kết nối.", "muted"), Ui.label(manager.room().name(), "strong"), stats, actions, Ui.label("MINI MEETING ROOM  ·  HẸN GẶP LẠI BẠN", "eyebrow"));
        card.setAlignment(Pos.CENTER); card.setMaxWidth(580); card.setMaxHeight(USE_PREF_SIZE); card.getStyleClass().add("ended-card"); getChildren().add(card);
    }
}
