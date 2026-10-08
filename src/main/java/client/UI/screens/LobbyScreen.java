package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.dialogs.CreateRoomDialog;
import client.UI.mock.MockData;
import javafx.animation.FadeTransition;
import javafx.animation.Animation;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class LobbyScreen extends BorderPane {
    private final FadeTransition pulse;
    public LobbyScreen(SceneManager manager) {
        setLeft(new Sidebar(manager, "lobby"));
        VBox main = new VBox(24); main.setPadding(new Insets(32));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String[] days = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"};
        String[] parts = manager.user().strip().split("\\s+");
        Circle dot = new Circle(4, javafx.scene.paint.Color.web("#1E9E5A"));
        HBox status = Ui.row(8, dot, Ui.label("Sẵn sàng kết nối", "ready-text")); status.getStyleClass().add("ready-chip"); status.setMaxHeight(34);
        pulse = new FadeTransition(Duration.seconds(1.2), dot); pulse.setFromValue(1); pulse.setToValue(.35); pulse.setAutoReverse(true); pulse.setCycleCount(Animation.INDEFINITE); pulse.play();
        main.getChildren().add(Ui.row(12, Ui.column(8, Ui.label(days[today.getDayOfWeek().getValue() - 1] + ", " + today.format(DateTimeFormatter.ofPattern("dd/MM")), "date-label"),
            Ui.label("Xin chào, " + parts[parts.length - 1], "title"), Ui.label("Hôm nay bạn muốn kết nối với ai?", "muted")), Ui.spacer(), status));
        Runnable createRoom = () -> new CreateRoomDialog(manager.stage()).showAndWait().ifPresent(result -> { manager.locked(result.locked()); manager.preJoin(result.room()); });
        VBox createText = Ui.column(14, Icons.icon("video", 28), Ui.label("Tạo phòng mới", "subtitle"),
            Ui.label("Một không gian riêng cho nhóm của bạn.\nMời mọi người và bắt đầu trò chuyện.", "muted"), Ui.button("Tạo phòng", "primary", createRoom));
        createText.setMaxWidth(Double.MAX_VALUE);
        StackPane art = new StackPane(); art.setMouseTransparent(true); art.setMinSize(92, 120); art.setMaxSize(92, 120);
        StackPane video = Icons.icon("video", 82); video.setOpacity(.12); video.setTranslateY(-20);
        HBox avatars = Ui.row(-12, Ui.avatar("Hoàng Nam", 36), Ui.avatar("Thảo Linh", 36), Ui.avatar("Ngọc Mai", 36));
        avatars.setTranslateY(36); avatars.setAlignment(Pos.CENTER); art.getChildren().addAll(video, avatars);
        StackPane create = new StackPane(createText, art); create.getStyleClass().addAll("card", "create-card");
        StackPane.setAlignment(art, Pos.CENTER_RIGHT); art.setTranslateY(20);
        ((Label) createText.getChildren().get(2)).maxWidthProperty().bind(create.widthProperty().subtract(148));
        VBox join = new VBox(8); join.getStyleClass().add("card");
        TextField code = new TextField(); code.setPromptText("Ví dụ: MMR-2048"); PasswordField pin = new PasswordField(); pin.setPromptText("PIN 4–6 số");
        VBox codeField = Ui.field("Mã phòng", code); codeField.getChildren().add(Ui.label("Mã phòng có dạng MMR-XXXX", "room-hint"));
        VBox pinField = Ui.field("PIN", pin); codeField.setMinWidth(0); pinField.setMinWidth(0);
        HBox.setHgrow(codeField, Priority.ALWAYS); HBox.setHgrow(pinField, Priority.ALWAYS);
        HBox inputs = Ui.row(12, codeField, pinField); inputs.setAlignment(Pos.TOP_LEFT);
        Label error = Ui.label("", "error-text"); error.setManaged(false); error.setVisible(false);
        Button enter = Ui.button("Tham gia", "primary", () -> {
            error.setManaged(true); error.setVisible(true);
            if (code.getText().isBlank() || !pin.getText().matches("\\d{4,6}")) { error.setText("Nhập mã phòng và PIN gồm 4–6 số."); return; }
            MockData.Room room = MockData.rooms().stream().filter(r -> r.code().equalsIgnoreCase(code.getText().strip())).findFirst().orElse(new MockData.Room("Phòng họp của bạn", code.getText().strip().toUpperCase(), 6, "Vừa tham gia", pin.getText(), 10));
            if (!room.pin().equals(pin.getText())) { error.setText("PIN phòng demo chưa đúng. Thử 1234."); return; }
            if (room.people() >= room.capacity()) { error.setText("Phòng đã đủ người."); return; }
            manager.locked(false); manager.preJoin(room);
        }); enter.setMaxWidth(Double.MAX_VALUE);
        join.getChildren().addAll(Ui.label("Tham gia phòng", "subtitle"), Ui.label("Nhập mã và PIN được chia sẻ với bạn.", "muted"), inputs, error, enter);
        HBox actions = Ui.row(20, create, join); actions.setAlignment(Pos.TOP_LEFT);
        create.setMinWidth(0); join.setMinWidth(0); create.setPrefWidth(350); join.setPrefWidth(420);
        HBox.setHgrow(create, Priority.ALWAYS); HBox.setHgrow(join, Priority.ALWAYS);
        main.getChildren().addAll(actions, Ui.row(12, Ui.label("Phòng gần đây", "subtitle"), Ui.spacer(), Ui.label("4 cuộc trò chuyện", "caption")));
        VBox rooms = new VBox(12);
        int index = 0;
        for (MockData.Room room : MockData.rooms()) {
            boolean live = index++ % 2 == 0;
            Label badge = Ui.label(live ? "● Đang diễn ra" : "Đã kết thúc", live ? "room-live" : "room-ended");
            Label title = Ui.label(room.name(), "room-title");
            VBox text = Ui.column(6, Ui.row(10, title, badge), Ui.label(room.code() + "  ·  " + room.people() + "/" + room.capacity() + " người  ·  " + room.time(), "room-meta"));
            text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS);
            Button action = Ui.button(live ? "Vào lại" : "Xem lại", live ? "primary" : "secondary", () -> {
                if (live) { manager.locked(false); manager.preJoin(room); }
                else HistoryScreen.showDetails(manager, room.name(), room.code(), room.people(), 45);
            }); action.setMinWidth(90);
            HBox item = Ui.row(14, Ui.avatar(room.name(), 40), text, action); item.getStyleClass().add("room-row"); rooms.getChildren().add(item);
        }
        main.getChildren().add(rooms); setCenter(Ui.scroll(main));
    }
    public void dispose() { pulse.stop(); }
}
