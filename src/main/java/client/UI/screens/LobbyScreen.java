package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.dialogs.CreateRoomDialog;
import common.room.RoomInfo;
import common.room.RoomValidation;
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
    private final SceneManager manager;
    private final TextField code = new TextField();
    private final PasswordField pin = new PasswordField();
    private final VBox rooms = new VBox(12);
    private final Label roomStatus = Ui.label("", "caption");
    private final Button refresh;
    private boolean disposed, loading;
    public LobbyScreen(SceneManager manager) { this(manager, ""); }
    public LobbyScreen(SceneManager manager, String initialCode) {
        this.manager = manager;
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
        Runnable createRoom = () -> new CreateRoomDialog(manager).showAndWait().ifPresent(result -> { if (!disposed) manager.preJoin(result); });
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
        code.setText(initialCode); code.setPromptText("Ví dụ: 42 hoặc MMR-…"); code.setId("join-room-identifier");
        pin.setPromptText("PIN 4–6 số"); pin.setId("join-room-pin");
        VBox codeField = Ui.field("ID / Mã phòng", code); codeField.getChildren().add(Ui.label("Nhập ID hoặc mã được chủ phòng chia sẻ", "room-hint"));
        VBox pinField = Ui.field("PIN", pin); codeField.setMinWidth(0); pinField.setMinWidth(0);
        HBox.setHgrow(codeField, Priority.ALWAYS); HBox.setHgrow(pinField, Priority.ALWAYS);
        HBox inputs = Ui.row(12, codeField, pinField); inputs.setAlignment(Pos.TOP_LEFT);
        Label error = Ui.label("", "error-text"); error.setWrapText(true); error.setId("join-room-error"); error.setManaged(false); error.setVisible(false);
        Button enter = Ui.button("Tham gia", "primary", () -> {
            error.setManaged(true); error.setVisible(true);
            String validation = RoomValidation.identifierError(code.getText());
            if (validation == null) validation = RoomValidation.pinError(pin.getText());
            if (validation != null) { error.setText(validation); return; }
            String identifier = code.getText(), roomPin = pin.getText();
            error.setText("Đang kiểm tra phòng…"); setDisable(true);
            manager.requestJoinRoom(identifier, roomPin, room -> {
                if (disposed) return; setDisable(false);
                pin.clear(); manager.preJoin(room);
            }, cause -> { if (!disposed) { setDisable(false); error.setText(SceneManager.connectionError(cause)); } });
        }); enter.setId("join-room-submit"); enter.setMaxWidth(Double.MAX_VALUE);
        join.getChildren().addAll(Ui.label("Tham gia phòng", "subtitle"), Ui.label("Nhập ID / mã phòng và PIN được chia sẻ với bạn.", "muted"), inputs, error, enter);
        HBox actions = Ui.row(20, create, join); actions.setAlignment(Pos.TOP_LEFT);
        create.setMinWidth(0); join.setMinWidth(0); create.setPrefWidth(350); join.setPrefWidth(420);
        HBox.setHgrow(create, Priority.ALWAYS); HBox.setHgrow(join, Priority.ALWAYS);
        refresh = Ui.button("Làm mới", "secondary", this::loadRooms); refresh.setId("refresh-rooms");
        roomStatus.setWrapText(true); roomStatus.setId("rooms-status"); rooms.setId("recent-rooms");
        main.getChildren().addAll(actions,
                Ui.row(12, Ui.label("Phòng gần đây của bạn", "subtitle"), Ui.spacer(), refresh), roomStatus, rooms);
        setCenter(Ui.scroll(main));
        java.util.List<RoomInfo> cached = manager.recentRooms();
        if (cached == null) loadRooms(); else renderRooms(cached);
    }
    private void loadRooms() {
        if (disposed || loading) return;
        loading = true; refresh.setDisable(true); roomStatus.setText("Đang tải danh sách phòng…");
        String token = manager.sessionToken();
        manager.runBackgroundRoomRequest(() -> manager.authClient().listRooms(token), response -> {
            if (disposed) return; loading = false; refresh.setDisable(false);
            if (manager.sessionExpired(response)) return;
            if (!response.success()) { roomStatus.setText(response.message()); return; }
            java.util.List<RoomInfo> recent = response.rooms() == null ? java.util.List.of() : response.rooms();
            manager.cacheRecentRooms(recent); renderRooms(recent);
        }, cause -> { if (!disposed) { loading = false; refresh.setDisable(false); roomStatus.setText(SceneManager.connectionError(cause)); } });
    }
    private void renderRooms(java.util.List<RoomInfo> recent) {
            rooms.getChildren().clear();
            roomStatus.setText(recent.isEmpty() ? "Bạn chưa tạo hoặc tham gia phòng nào." : recent.size() + " phòng • Chọn phòng và nhập lại PIN để tham gia.");
            for (RoomInfo room : recent) {
            boolean live = "DANG_HOAT_DONG".equals(room.status()) || "CHO".equals(room.status());
            Label badge = Ui.label(live ? "● Đang diễn ra" : "Đã kết thúc", live ? "room-live" : "room-ended");
            Label title = Ui.label(room.name(), "room-title");
            VBox text = Ui.column(6, Ui.row(10, title, badge), Ui.label("ID " + room.id() + "  ·  " + room.code() + "  ·  " + room.people() + "/" + room.capacity() + " người  ·  " + room.createdAt(), "room-meta"));
            text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS);
            Button action = Ui.button(live ? "Nhập PIN" : "Đã đóng", live ? "primary" : "secondary", () -> {
                code.setText(room.code()); pin.clear(); pin.requestFocus();
            }); action.setDisable(!live); action.setMinWidth(90);
            HBox item = Ui.row(14, Ui.avatar(room.name(), 40), text, action); item.getStyleClass().add("room-row"); rooms.getChildren().add(item);
            }
    }
    public void dispose() { disposed = true; pin.clear(); pulse.stop(); }
}
