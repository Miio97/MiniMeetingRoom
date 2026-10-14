package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.dialogs.CreateRoomDialog;
import client.auth.RoomException;
import common.meeting.*;
import javafx.animation.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Duc: actual room admission; no locally invented rooms or users. */
public final class LobbyScreen extends BorderPane {
    private final SceneManager manager;
    private final Label status = Ui.label("", "error-text");
    private final TextField code = new TextField();
    private final PasswordField pin = new PasswordField();
    private final Button enter;
    private final FadeTransition pulse;
    private RoomResponse pending;
    private Timeline poll;
    private boolean busy, disposed;
    public LobbyScreen(SceneManager manager) {
        this.manager = manager; setLeft(new Sidebar(manager, "lobby"));
        VBox main = new VBox(24); main.setPadding(new Insets(32));
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        String[] days = {"Thứ Hai", "Thứ Ba", "Thứ Tư", "Thứ Năm", "Thứ Sáu", "Thứ Bảy", "Chủ Nhật"};
        String[] parts = manager.user().strip().split("\\s+");
        Circle dot = new Circle(4, javafx.scene.paint.Color.web("#1E9E5A"));
        HBox ready = Ui.row(8, dot, Ui.label("Sẵn sàng kết nối", "ready-text"));
        ready.getStyleClass().add("ready-chip"); ready.setMinWidth(Region.USE_PREF_SIZE); ready.setMaxHeight(34);
        pulse = new FadeTransition(Duration.seconds(1.2), dot); pulse.setFromValue(1); pulse.setToValue(.35);
        pulse.setAutoReverse(true); pulse.setCycleCount(Animation.INDEFINITE); pulse.play();
        VBox welcome = Ui.column(8, Ui.label(days[today.getDayOfWeek().getValue() - 1] + ", " + today.format(DateTimeFormatter.ofPattern("dd/MM")), "date-label"),
                Ui.label("Xin chào, " + parts[parts.length - 1], "title"), Ui.label("Hôm nay bạn muốn kết nối với ai?", "muted"));
        welcome.setMinWidth(0); HBox.setHgrow(welcome, Priority.ALWAYS);
        main.getChildren().add(Ui.row(12, welcome, ready));

        Button createButton = Ui.button("Tạo phòng", "primary", () -> {
            if (busy || pending != null) return;
            new CreateRoomDialog(manager.stage()).showAndWait().ifPresent(result -> {
                busy = true; status.setText("Đang tạo phòng…");
                String secret = result.room().pin();
                String token = manager.sessionToken();
                manager.runMeetingRequest(RoomRequest.create(token, result.room().name(), secret, result.room().capacity()),
                        response -> { busy = false; if (disposed) manager.releaseRoom(response, token); else manager.enterRoom(response, secret); }, this::failure);
            });
        });
        Label description = Ui.label("Một không gian riêng cho nhóm của bạn.\nMời mọi người và bắt đầu trò chuyện.", "muted");
        VBox createText = Ui.column(14, Icons.icon("video", 28), Ui.label("Tạo phòng mới", "subtitle"), description, createButton);
        createText.setMaxWidth(Double.MAX_VALUE);
        StackPane art = new StackPane(); art.setMouseTransparent(true); art.setMinSize(92, 120); art.setMaxSize(92, 120);
        StackPane video = Icons.icon("video", 82); video.setOpacity(.12); video.setTranslateY(-20);
        StackPane avatar = Ui.avatar(manager.user(), 36); avatar.setTranslateY(36);
        art.getChildren().addAll(video, avatar);
        StackPane create = new StackPane(createText, art); create.setId("lobby-create-card"); create.getStyleClass().addAll("card", "create-card");
        StackPane.setAlignment(art, Pos.CENTER_RIGHT); art.setTranslateY(20);
        description.maxWidthProperty().bind(create.widthProperty().subtract(148));

        VBox join = new VBox(8); join.setId("lobby-join-card"); join.getStyleClass().add("card");
        code.setPromptText("MMR-XXXXXXXXXX"); code.setId("join-room-code"); pin.setPromptText("PIN 4–6 số"); pin.setId("join-room-pin");
        enter = Ui.button("Tham gia", "primary", this::join); pin.setOnAction(event -> join());
        VBox codeField = Ui.field("Mã phòng", code), pinField = Ui.field("PIN", pin);
        codeField.getChildren().add(Ui.label("MMR-XXXXXXXXXX", "room-hint"));
        codeField.setMinWidth(0); pinField.setMinWidth(0);
        HBox.setHgrow(codeField, Priority.ALWAYS); HBox.setHgrow(pinField, Priority.ALWAYS);
        HBox inputs = Ui.row(12, codeField, pinField); inputs.setAlignment(Pos.TOP_LEFT);
        status.managedProperty().bind(status.textProperty().isNotEmpty()); status.visibleProperty().bind(status.managedProperty());
        enter.setMaxWidth(Double.MAX_VALUE);
        join.getChildren().addAll(Ui.label("Tham gia phòng", "subtitle"), Ui.label("Nhập mã và PIN được chia sẻ với bạn.", "muted"), inputs, status, enter);
        HBox actions = Ui.row(20, create, join); actions.setAlignment(Pos.TOP_LEFT);
        create.setMinWidth(0); join.setMinWidth(0); create.setPrefWidth(350); join.setPrefWidth(420);
        HBox.setHgrow(create, Priority.ALWAYS); HBox.setHgrow(join, Priority.ALWAYS);
        VBox recent = Ui.column(12, Ui.label("Chưa có dữ liệu phòng gần đây.", "caption")); recent.setId("lobby-recent-rooms");
        main.getChildren().addAll(actions, Ui.row(12, Ui.label("Phòng gần đây", "subtitle"), Ui.spacer()), recent);
        setCenter(Ui.scroll(main));
    }
    private void join() {
        if (busy) return;
        if (pending != null) { cancel(); return; }
        if (code.getText().isBlank()) { status.setText("Nhập mã phòng."); return; }
        busy = true; enter.setDisable(true); status.setText("Đang tham gia…");
        String token = manager.sessionToken(), secret = pin.getText();
        manager.runMeetingRequest(RoomRequest.join(token, code.getText().strip(), secret), response -> {
            if (disposed) { manager.releaseRoom(response, token); return; }
            busy = false; enter.setDisable(false);
            if (response.pending()) {
                pending = response; manager.updateMeeting(response); status.setText("Đang chờ chủ phòng duyệt."); enter.setText("Hủy yêu cầu");
                poll = new Timeline(new KeyFrame(Duration.seconds(1), event -> poll())); poll.setCycleCount(Animation.INDEFINITE); poll.play();
            } else manager.enterRoom(response, secret);
        }, this::failure);
    }
    private void poll() {
        if (pending == null || busy || disposed) return;
        busy = true;
        manager.runMeetingRequest(RoomRequest.command("ROOM_POLL", manager.sessionToken(), pending.room().id(), pending.ticket(), 0), response -> {
            busy = false; if (disposed) return;
            if (!response.pending()) { pending = null; poll.stop(); manager.enterRoom(response, pin.getText()); }
        }, error -> {
            if (disposed) return;
            if (error instanceof RoomException rejection && rejection.admissionEnded()) stopWait();
            else busy = false;
            failure(error);
        });
    }
    private void cancel() {
        busy = true;
        manager.runMeetingRequest(RoomRequest.command("ROOM_LEAVE", manager.sessionToken(), pending.room().id(), pending.ticket(), 0),
                response -> { if (!disposed) { stopWait(); status.setText("Đã hủy yêu cầu."); } }, this::failure);
    }
    private void stopWait() { pending = null; manager.leaveRoomQuietly(); busy = false; if (poll != null) poll.stop(); enter.setText("Tham gia"); enter.setDisable(false); }
    private void failure(Throwable error) { if (!disposed) { busy = false; enter.setDisable(false); status.setText(SceneManager.connectionError(error)); } }
    public void dispose() {
        disposed = true;
        pulse.stop();
        if (poll != null) poll.stop();
        if (pending != null) manager.leaveRoomQuietly();
        pending = null;
    }
}
