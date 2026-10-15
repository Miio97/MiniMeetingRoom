package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.Ui;
import client.meeting.MeetingSession;
import common.meeting.CreateRoomParams;
import common.meeting.ParticipantView;
import common.meeting.RoomSnapshot;
import common.room.RoomValidation;
import common.signaling.SignalException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Live room and camera controls backed by authoritative membership and native WebRTC. */
public final class SfuRoomScreen extends BorderPane {
    private final SceneManager manager;
    private final MeetingSession session;
    private final VBox members = new VBox(8);
    private final VBox operations = new VBox(16);
    private final VBox videoArea = new VBox();
    private client.UI.components.VideoCallPane videoPane;
    private final Label error = Ui.label("", "error-text");
    private final Label details = Ui.label("Chưa tham gia phòng.", "subtitle");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button lock;
    private final Button leave;
    private final ChangeListener<RoomSnapshot> listener = (observable, before, after) -> render(after);
    private boolean disposed, pending;

    public SfuRoomScreen(SceneManager manager, MeetingSession session) {
        this.manager = manager; this.session = session;
        VBox content = new VBox(20); content.setPadding(new Insets(32));
        Button back = Ui.button("Về sảnh", "secondary", () -> perform(session::leave, manager::lobby));
        Label status = Ui.label("", "muted"); status.textProperty().bind(session.noticeProperty());
        error.setWrapText(true); error.setId("sfu-error"); details.setId("sfu-room-details");
        progress.setPrefSize(24, 24); progress.setMaxSize(24, 24); progress.setId("sfu-progress");
        progress.setVisible(false); progress.setManaged(false);
        content.getChildren().addAll(Ui.row(12, Ui.label("Phòng SFU", "title"), Ui.spacer(), back), status,
                Ui.label("Video qua WebRTC/SFU. Camera chỉ bật khi bạn chọn thiết bị và nhấn Bật camera.", "caption"));
        TextField name = new TextField(); name.setPromptText("Tên phòng"); name.setId("sfu-room-name");
        PasswordField createPin = new PasswordField(); createPin.setPromptText("PIN 4–6 số"); createPin.setId("sfu-create-pin");
        Spinner<Integer> capacity = new Spinner<>(2, manager.maxRoomCapacity(), 3); capacity.setId("sfu-capacity");
        Button create = Ui.button("Tạo và tham gia", "primary", () -> {
            String validation = RoomValidation.nameError(name.getText());
            if (validation == null) validation = RoomValidation.pinError(createPin.getText());
            if (validation != null) { error.setText(validation); return; }
            CreateRoomParams params = new CreateRoomParams(name.getText(), createPin.getText(), capacity.getValue(), false);
            perform(() -> session.create(params).thenCompose(room -> session.join(false, false)), createPin::clear);
        }); create.setId("sfu-create-submit");
        TextField code = new TextField(); code.setPromptText("MMR-…"); code.setId("sfu-join-code");
        PasswordField joinPin = new PasswordField(); joinPin.setPromptText("PIN"); joinPin.setId("sfu-join-pin");
        Button join = Ui.button("Tham gia", "primary", () -> {
            String validation = RoomValidation.codeError(code.getText());
            if (validation == null) validation = RoomValidation.pinError(joinPin.getText());
            if (validation != null) { error.setText(validation); return; }
            String enteredCode = code.getText().strip().toUpperCase(java.util.Locale.ROOT), pin = joinPin.getText();
            perform(() -> session.lookup(enteredCode, pin).thenCompose(room -> session.join(false, false)), joinPin::clear);
        }); join.setId("sfu-join-submit");
        operations.getChildren().addAll(Ui.label("Tạo phòng", "subtitle"), Ui.row(12, name, createPin, capacity, create),
                Ui.label("Tham gia bằng mã SFU", "subtitle"), Ui.row(12, code, joinPin, join));
        lock = Ui.button("Khóa phòng", "secondary", () -> {
            RoomSnapshot room = session.snapshot();
            if (room != null) perform(() -> session.lock(!room.locked()), () -> { });
        }); lock.setId("sfu-lock");
        leave = Ui.button("Rời phòng", "secondary", () -> perform(session::leave, () -> { })); leave.setId("sfu-leave");
        members.setId("sfu-participants");
        content.getChildren().addAll(operations, Ui.row(10, progress, error), details, Ui.row(12, lock, leave), videoArea, members);
        setCenter(Ui.scroll(content));
        session.roomProperty().addListener(listener); render(session.snapshot());
        session.ensureConnected(); // Prepare TLS/auth off the FX thread before the first button click.
    }

    private void render(RoomSnapshot room) {
        if (disposed) return;
        boolean joined = room != null;
        operations.setVisible(!joined); operations.setManaged(!joined);
        videoArea.setVisible(joined); videoArea.setManaged(joined);
        if (joined && videoPane == null) { videoPane = new client.UI.components.VideoCallPane(session); videoArea.getChildren().setAll(videoPane); }
        else if (!joined && videoPane != null) { videoPane.dispose(); videoPane = null; videoArea.getChildren().clear(); }
        progress.setVisible(pending); progress.setManaged(pending);
        operations.setDisable(pending || joined); leave.setDisable(pending || !joined);
        lock.setDisable(pending || !joined || !session.isHost());
        details.setText(joined ? room.name() + " • " + room.joinCode() + " • " + room.participants().size() + "/" + room.capacity()
                : "Chưa tham gia phòng.");
        lock.setText(joined && room.locked() ? "Mở khóa phòng" : "Khóa phòng");
        members.getChildren().clear();
        if (!joined) return;
        for (ParticipantView participant : room.participants()) {
            Label label = Ui.label(participant.displayName() + " • " + participant.role()
                    + " • " + (participant.mediaState().videoPublishing() ? "Đang phát video" : "Camera đang tắt hoặc kết nối") + " • Âm thanh chưa bật", "muted");
            HBox row = Ui.row(12, label, Ui.spacer());
            if (session.isHost() && !participant.participantId().equals(session.selfParticipantId())) {
                Button stopVideo = Ui.button("Dừng video", "secondary", () -> perform(() -> session.stopPublish(participant.participantId(), "VIDEO"), () -> { }));
                stopVideo.setDisable(pending || !participant.mediaState().cameraRequested()); row.getChildren().add(stopVideo);
                Button kick = Ui.button("Mời ra", "secondary", () -> perform(() -> session.kick(participant.participantId()), () -> { }));
                kick.setDisable(pending); row.getChildren().add(kick);
            }
            members.getChildren().add(row);
        }
    }

    private void perform(Supplier<? extends CompletableFuture<?>> action, Runnable success) {
        if (pending || disposed) return;
        pending = true; error.setText("Đang xử lý…"); render(session.snapshot());
        try {
            action.get().whenComplete((value, failure) -> Platform.runLater(() -> {
                if (disposed) return;
                pending = false; render(session.snapshot());
                if (failure == null) { error.setText(""); success.run(); }
                else {
                    Throwable cause = failure;
                    while (cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) cause = cause.getCause();
                    error.setText(cause instanceof SignalException signal ? message(signal.code()) : "Không thể kết nối máy chủ phòng SFU. Vui lòng thử lại.");
                }
            }));
        } catch (RuntimeException failure) { pending = false; render(session.snapshot()); error.setText("Yêu cầu chưa thể thực hiện."); }
    }
    private static String message(String code) {
        return switch (code) {
            case "WRONG_PIN" -> "PIN không đúng.";
            case "ROOM_FULL" -> "Phòng đã đủ người.";
            case "ROOM_LOCKED" -> "Phòng đang khóa.";
            case "ROOM_NOT_FOUND" -> "Không tìm thấy phòng SFU đang hoạt động.";
            case "SESSION_INVALID" -> "Phiên đăng nhập không còn hợp lệ. Hãy đăng nhập lại.";
            case "PIN_RATE_LIMIT" -> "Bạn thử quá nhiều lần. Hãy chờ một phút.";
            case "ALREADY_JOINED" -> "Tài khoản đã tham gia phòng trên kết nối khác.";
            case "HOST_REQUIRED" -> "Chỉ chủ phòng được thực hiện thao tác này.";
            case "CONNECTION_CLOSED", "CONNECTION_STATE", "CONNECTION_FAILED" -> "Kết nối phòng đã gián đoạn. Vui lòng thử lại; nếu còn lỗi, kiểm tra Output của server.";
            default -> "Yêu cầu phòng chưa hoàn tất. Vui lòng thử lại.";
        };
    }
    public void dispose() { disposed = true; session.roomProperty().removeListener(listener); if (videoPane != null) videoPane.dispose(); }
}
