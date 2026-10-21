package client.UI.screens;

import client.UI.SceneManager;
import client.UI.VietnamTime;
import client.UI.components.*;
import client.UI.dialogs.CreateRoomDialog;
import common.auth.AuthResponse;
import common.room.RoomInfo;
import common.room.RoomValidation;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class LobbyScreen extends BorderPane {
    private final FadeTransition pulse;
    private final javafx.animation.Timeline roomUpdates;
    private List<RoomInfo> renderedRooms;
    private boolean catalogError;
    private boolean hasMoreRooms;
    private boolean expandedRooms;
    private final Button moreRooms = Ui.button("Xem thêm", "secondary", this::loadMoreRooms);
    private final SceneManager manager;
    private final TextField code = new TextField();
    private final PasswordField pin = new PasswordField();
    private final VBox rooms = new VBox(12);
    private final Label roomStatus = Ui.label("", "caption");
    private final Button refresh;
    private boolean disposed, loading, joining, reopening;
    private Runnable stopWaiting = () -> { };

    public LobbyScreen(SceneManager manager) { this(manager, ""); }

    public LobbyScreen(SceneManager manager, String initialCode) {
        this.manager = manager;
        setLeft(new Sidebar(manager, "lobby"));
        VBox main = new VBox(24); main.setPadding(new Insets(32));

        LocalDate today = VietnamTime.today();
        String[] parts = manager.user().strip().split("\\s+");
        Circle dot = new Circle(4, javafx.scene.paint.Color.web("#1E9E5A"));
        HBox status = Ui.row(8, dot, Ui.label("Sẵn sàng kết nối", "ready-text"));
        status.getStyleClass().add("ready-chip"); status.setMaxHeight(34);

        pulse = new FadeTransition(Duration.seconds(1.2), dot);
        pulse.setFromValue(1); pulse.setToValue(.35); pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE); pulse.play();

        main.getChildren().add(Ui.row(12,
                Ui.column(8,
                        Ui.label(VietnamTime.formatLobbyDate(today), "date-label"),
                        Ui.label("Xin chào, " + parts[parts.length - 1], "title"),
                        Ui.label("Hôm nay bạn muốn kết nối với ai?", "muted")),
                Ui.spacer(),
                status));

        // --- THẺ TẠO PHÒNG & THAM GIA PHÒNG ---
        Runnable createRoom = () -> { if (joining) return; new CreateRoomDialog(manager).showAndWait().ifPresent(result -> { if (!disposed) manager.preJoin(result); }); };
        StackPane art = new StackPane(); art.getStyleClass().add("create-art"); art.setMouseTransparent(true); art.setMinSize(92, 82); art.setMaxSize(92, 82);
        StackPane video = Icons.icon("video", 64); video.getStyleClass().add("create-art-camera"); video.setTranslateY(-12);
        HBox avatars = Ui.row(-12, Ui.avatar("Hoàng Nam", 36), Ui.avatar("Thảo Linh", 36), Ui.avatar("Ngọc Mai", 36));
        avatars.setTranslateY(24); avatars.setAlignment(Pos.CENTER); art.getChildren().addAll(video, avatars);
        VBox heading = Ui.column(14, Icons.icon("video", 28), Ui.label("Tạo phòng mới", "subtitle"));
        heading.setMinWidth(0); HBox.setHgrow(heading, Priority.ALWAYS);
        Label description = Ui.label("Một không gian riêng cho nhóm của bạn. Mời mọi người và bắt đầu trò chuyện.", "muted");
        description.setMinWidth(0); description.setMaxWidth(320);
        VBox create = Ui.column(14, Ui.row(12, heading, art), description, Ui.button("Tạo phòng", "primary", createRoom));
        create.getStyleClass().addAll("card", "create-card");

        VBox join = new VBox(8); join.getStyleClass().add("card");
        code.setText(initialCode); code.setPromptText("Ví dụ: MMR-XXXXXXXX"); code.setId("join-room-identifier");
        pin.setPromptText("PIN 4–6 số"); pin.setId("join-room-pin");
        VBox codeField = Ui.field("Mã phòng", code);
        VBox pinField = Ui.field("PIN", pin); codeField.setMinWidth(0); pinField.setMinWidth(0);
        HBox.setHgrow(codeField, Priority.ALWAYS); HBox.setHgrow(pinField, Priority.ALWAYS);
        HBox inputs = Ui.row(12, codeField, pinField); inputs.setAlignment(Pos.TOP_LEFT);
        Label error = Ui.label("", "error-text"); error.setWrapText(true); error.setId("join-room-error"); error.setManaged(false); error.setVisible(false);
        Button cancelAdmission = Ui.button("Hủy yêu cầu", "secondary", () -> stopWaiting.run());
        cancelAdmission.setId("cancel-room-admission"); cancelAdmission.setVisible(false); cancelAdmission.setManaged(false);
        Button enter = Ui.button("Tham gia", "primary", () -> {
            error.setManaged(true); error.setVisible(true);
            String validation = RoomValidation.codeError(code.getText());
            if (validation == null) validation = RoomValidation.pinError(pin.getText());
            if (validation != null) { error.setText(validation); return; }
            String identifier = code.getText(), roomPin = pin.getText();
            if (!manager.usesSignalingRooms()) {
                joining = true; error.setText("Đang kiểm tra phòng…"); setDisable(true);
                manager.requestJoinRoom(identifier, roomPin, room -> {
                    if (disposed || !joining) return;
                    joining = false; setDisable(false); pin.clear(); manager.preJoin(room);
                }, cause -> { if (!disposed) { joining = false; setDisable(false); error.setText(SceneManager.connectionError(cause)); } });
                return;
            }
            error.setText("Đang kiểm tra phòng…"); joining = true; if (!manager.usesSignalingRooms()) setDisable(true);
            code.setDisable(true); pin.setDisable(true); actionsPending(true);
            cancelAdmission.setVisible(true); cancelAdmission.setManaged(true);
            var session = manager.sfuSession();
            javafx.beans.value.ChangeListener<String> notice = (observable, before, after) -> { if (joining && !disposed) error.setText(after); };
            session.noticeProperty().addListener(notice);
            stopWaiting = () -> { joining = false; setDisable(false); manager.cancelAdmission(); session.noticeProperty().removeListener(notice); code.setDisable(false); pin.setDisable(false); actionsPending(false); cancelAdmission.setVisible(false); cancelAdmission.setManaged(false); error.setText(""); };
            manager.requestJoinRoom(identifier, roomPin, room -> {
                if (disposed || !joining) return; joining = false; setDisable(false); session.noticeProperty().removeListener(notice);
                pin.clear(); manager.preJoin(room);
            }, cause -> { if (!disposed && joining) { stopWaiting.run(); error.setText(SceneManager.connectionError(cause)); } });
        }); enter.setId("join-room-submit"); enter.setMaxWidth(Double.MAX_VALUE);
        join.getChildren().addAll(Ui.label("Tham gia phòng", "subtitle"), inputs, error, enter, cancelAdmission);

        HBox actions = Ui.row(20, create, join); actions.setAlignment(Pos.TOP_LEFT);
        create.setMinWidth(0); join.setMinWidth(0); create.setPrefWidth(350); join.setPrefWidth(420);
        HBox.setHgrow(create, Priority.ALWAYS); HBox.setHgrow(join, Priority.ALWAYS);

        refresh = Ui.button("Làm mới", "secondary", this::loadRooms); refresh.setId("refresh-rooms");
        roomStatus.setWrapText(true); roomStatus.setId("rooms-status"); rooms.setId("recent-rooms");

        main.getChildren().addAll(actions,
                Ui.row(12, Ui.label("Phòng gần đây của bạn", "subtitle"), Ui.spacer(), refresh), roomStatus, rooms, moreRooms);
        ScrollPane scroll = Ui.scroll(main);
        scroll.vvalueProperty().addListener((observable, before, after) -> {
            if (expandedRooms && hasMoreRooms && after.doubleValue() >= 0.98
                    && before.doubleValue() < 0.98) loadMoreRooms();
        });
        setCenter(scroll);

        moreRooms.setId("more-recent-rooms");
        moreRooms.setVisible(false); moreRooms.setManaged(false);
        List<RoomInfo> cached = manager.recentRooms();
        if (cached == null) {
            loadRooms();
        } else {
            hasMoreRooms = cached.size() >= 3;
            renderRooms(cached.subList(0, Math.min(3, cached.size())));
            moreRooms.setVisible(hasMoreRooms); moreRooms.setManaged(hasMoreRooms);
        }
        roomUpdates = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(2), event -> loadRooms(true)));
        roomUpdates.setCycleCount(Animation.INDEFINITE); roomUpdates.play();
    }

    private void actionsPending(boolean value) {
        javafx.scene.Node submit = lookup("#join-room-submit"); if (submit != null) submit.setDisable(value);
    }

    private void loadRooms() {
        loadRooms(false, false);
    }

    private void loadRooms(boolean background) { loadRooms(background, false); }

    private void loadMoreRooms() {
        if (loading || !hasMoreRooms) return;
        expandedRooms = true;
        loadRooms(false, true);
    }

    private void loadRooms(boolean background, boolean append) {
        if (disposed || loading || reopening) return;
        if (!background && !append) expandedRooms = false;
        loading = true; refresh.setDisable(true); moreRooms.setDisable(true); if (!background) roomStatus.setText("Đang tải danh sách phòng…");
        String token = manager.sessionToken();
        int offset = append && renderedRooms != null ? renderedRooms.size() : 0;
        int desired = append ? 10 : background && renderedRooms != null ? Math.max(3, renderedRooms.size()) : 3;
        manager.runBackgroundRoomRequest(() -> {
            List<RoomInfo> fetched = new ArrayList<>();
            boolean hasMore = true;
            while (fetched.size() < desired && hasMore) {
                int size = Math.min(10, desired - fetched.size());
                AuthResponse page = manager.authClient().listRooms(token, offset + fetched.size(), size);
                if (!page.success()) return page;
                fetched.addAll(page.rooms());
                hasMore = page.hasMoreRooms() && !page.rooms().isEmpty();
            }
            return AuthResponse.roomsPage(fetched, hasMore);
        }, response -> {
            if (disposed) return; loading = false; refresh.setDisable(false); moreRooms.setDisable(false);
            if (manager.sessionExpired(response)) return;
            if (!response.success()) { catalogError = true; roomStatus.setText(response.message()); return; }
            List<RoomInfo> recent = new ArrayList<>();
            if (append && renderedRooms != null) recent.addAll(renderedRooms);
            for (RoomInfo room : response.rooms()) {
                recent.removeIf(existing -> existing.id() == room.id());
                recent.add(room);
            }
            hasMoreRooms = response.hasMoreRooms();
            moreRooms.setVisible(hasMoreRooms); moreRooms.setManaged(hasMoreRooms);
            manager.cacheRecentRooms(recent);
            if (!background || catalogError || !recent.equals(renderedRooms)) renderRooms(recent);
            catalogError = false;
        }, cause -> { if (!disposed) { loading = false; refresh.setDisable(false); moreRooms.setDisable(false); catalogError = true; roomStatus.setText(SceneManager.connectionError(cause)); } });
    }

    private void renderRooms(List<RoomInfo> recent) {
        renderedRooms = List.copyOf(recent);
        rooms.getChildren().clear();
        roomStatus.setText(recent.isEmpty() ? "Bạn chưa tạo hoặc tham gia phòng nào." : "Đang hiển thị " + recent.size() + " phòng • Chọn phòng và nhập lại PIN để tham gia.");
        for (RoomInfo room : recent) {
            boolean live = "DANG_HOAT_DONG".equals(room.status()) || "CHO".equals(room.status());
            boolean canReopen = "DA_DONG".equals(room.status()) && manager.authenticatedUser() != null
                    && room.hostId() == manager.authenticatedUser().id();
            Label badge = Ui.label(live ? "● Đang diễn ra" : "Đã kết thúc", live ? "room-live" : "room-ended");
            Label title = Ui.label(room.name(), "room-title");
            title.setMinWidth(0); title.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(title, Priority.ALWAYS);
            badge.setMinWidth(Region.USE_PREF_SIZE); badge.setWrapText(false);
            VBox text = Ui.column(6, Ui.row(10, title, badge), Ui.label(room.code() + "  ·  " + room.people() + "/" + room.capacity() + " người  ·  " + VietnamTime.formatDateTime(room.createdAt()), "room-meta"));
            text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS);
            Button action = Ui.button(canReopen ? "Mở lại phòng" : live ? "Nhập PIN" : "Đã đóng", live || canReopen ? "primary" : "secondary", () -> {
                if (canReopen) { reopen(room); return; }
                code.setText(room.code()); pin.clear(); pin.requestFocus();
            }); action.setId("room-action-" + room.id()); action.setDisable(!live && !canReopen); action.setMinWidth(90);
            HBox item = Ui.row(14, Ui.avatar(room.name(), 40), text, action); item.getStyleClass().add("room-row"); rooms.getChildren().add(item);
        }
    }

    private void reopen(RoomInfo room) {
        if (disposed || reopening) return;
        reopening = true; setDisable(true); roomStatus.setText("Đang mở lại phòng…");
        manager.requestReopenRoom(room.id(), reopened -> {
            if (disposed) return;
            reopening = false; setDisable(false); pin.clear(); manager.preJoin(reopened);
        }, cause -> {
            if (disposed) return;
            reopening = false; setDisable(false); roomStatus.setText(SceneManager.connectionError(cause));
        });
    }

    public void dispose() {
        if (joining) stopWaiting.run();
        disposed = true;
        pin.clear();
        pulse.stop();
        roomUpdates.stop();
    }
}
