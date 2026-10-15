package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.dialogs.*;
import client.UI.mock.MockData;
import common.room.RoomInfo;
import common.room.RoomParticipant;
import javafx.animation.*;
import javafx.collections.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import java.util.ArrayList;
import java.util.List;

/** Main meeting layout; video rooms borrow the session media owner and keep the file panel. */
public final class MeetingScreen extends BorderPane {
    private final client.network.NetworkMonitor network;
    private final NetworkBadge networkBadge;
    private NetworkStatsDialog networkDialog;
    private final ChatPanel chatPanel;
    private final ObservableList<common.meeting.Participant> managedPeople = FXCollections.observableArrayList();
    private ParticipantPanel participantPanel;
    private common.meeting.RoomResponse managedState;
    public client.network.NetworkMonitor networkMonitor() { return network; }
    public void refreshNetwork() { if (!disposed) networkBadge.quality(network.snapshot().quality()); }
    private void networkStats() {
        if (disposed || networkDialog != null) return;
        networkDialog = new NetworkStatsDialog(manager.stage(), network);
        try { networkDialog.showAndWait(); } finally { networkDialog.dispose(); networkDialog = null; }
    }
    private final SceneManager manager;
    private final ObservableList<MockData.Participant> people;
    private final List<VideoTile> tiles = new ArrayList<>();
    private final StackPane canvas = new StackPane();
    private final StackPane videoContent = new StackPane();
    private static final int PAGE_SIZE = 12;
    private int page;
    private final VBox panel = new VBox();
    private final TabPane tabs = new TabPane();
    private final Timeline clock;
    private final Timeline roomPoll;
    private Timeline panelAnimation;
    private final ControlBar controls;
    private FilePanel filePanel;
    private final Label lockLabel = Ui.label("", "caption");
    private final Label participantCount = Ui.label("", "caption");
    private final Label roomError = Ui.label("", "error-text");
    private List<RoomParticipant> participantSnapshot = List.of();
    private boolean disposed, polling, leaving;
    private boolean sharing, panelOpen = true;
    private long startNanos = System.nanoTime();
    private int peakPeople;
    private int pinnedIndex = -1;
    private final boolean videoRoom;
    private final client.meeting.MeetingSession mediaSession;
    private final client.media.VideoCallController mediaController;
    private Label nativeStatus;
    private CameraSettingsDialog cameraSettings;
    private DeviceSettingsDialog managementSettings;
    private javafx.beans.value.ChangeListener<common.meeting.RoomSnapshot> managementListener;
    private java.util.concurrent.CompletableFuture<Void> cameraOperation;
    private final javafx.beans.value.ChangeListener<common.meeting.RoomSnapshot> videoRoomListener = (observable, before, after) -> {
        if (disposed || after == null) return;
        applyParticipants(after.collaborationRoom()); updateLock(); renderNativeParticipants(); renderVideos();
    };
    private final javafx.beans.value.ChangeListener<Boolean> cameraListener = (observable, before, after) -> updateCameraButton(after);
    private void updateCameraButton(boolean active) { controls.camera(active); }
    public MeetingScreen(SceneManager manager) {
        this.manager = manager; videoRoom = manager.hasVideoRoom(); people = FXCollections.observableArrayList();
        mediaSession = videoRoom ? manager.sfuSession() : null; mediaController = videoRoom ? mediaSession.video() : null;
        applyParticipants(manager.roomInfo()); peakPeople = people.size();
        Label duration = Ui.label("00:00:00", "meeting-time");
        IconButton copy = new IconButton("copy", "Sao chép mã phòng");
        copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(manager.room().code()); Clipboard.getSystemClipboard().setContent(content); copy.setIcon("check"); PauseTransition reset = new PauseTransition(Duration.seconds(1.5)); reset.setOnFinished(done -> copy.setIcon("copy")); reset.play(); });
        network = manager.authClient().startNetworkMonitoring();
        networkBadge = new NetworkBadge(this::networkStats);
        Button badge = networkBadge;
        Label title = Ui.label(manager.room().name(), "meeting-title"); title.setMaxWidth(350);
        HBox top = Ui.row(12, IconButton.icon("camera"), Ui.column(5, title, Ui.row(8, Ui.label(manager.room().code(), "caption"), lockLabel)), copy, Ui.spacer(), participantCount, duration, badge); top.getStyleClass().add("meeting-top"); setTop(top); updateLock();
        participantPanel = new ParticipantPanel(managedPeople, manager.authenticatedUser().id(), this::invite, this::hostCommand);
        Tab participants = new Tab("Thành viên", participantPanel);
        filePanel = new FilePanel(manager);
        chatPanel = new ChatPanel(manager);
        Tab chat = new Tab("Chat", chatPanel); Tab files = new Tab("Tệp", filePanel);
        tabs.getTabs().addAll(participants, chat, files); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); VBox.setVgrow(tabs, Priority.ALWAYS);
        IconButton close = new IconButton("close", "Đóng panel"); close.setOnAction(e -> togglePanel(-1));
        HBox panelHeader = Ui.row(8, Ui.label("Trong cuộc họp", "strong"), Ui.spacer(), close); panelHeader.setPadding(new Insets(12, 14, 8, 16)); panel.getChildren().addAll(panelHeader, tabs); panel.getStyleClass().add("meeting-panel");
        panel.setMinWidth(0); panel.setPrefWidth(320); panel.setMaxWidth(320);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(panel.widthProperty()); clip.heightProperty().bind(panel.heightProperty()); panel.setClip(clip);
        canvas.setId("meeting-video-area"); canvas.setMinWidth(0); HBox.setHgrow(canvas, Priority.ALWAYS); canvas.setPadding(new Insets(20));
        HBox body = new HBox(canvas, panel); setCenter(body);
        controls = new ControlBar(videoRoom ? false : manager.mic(), videoRoom ? manager.sfuSession().video().cameraRequestedProperty().get() : manager.camera(),
                this::toggleMic, this::toggleCamera, this::toggleShare, () -> togglePanel(1), () -> togglePanel(0),
                this::deviceSettings, this::leave);
        controls.users.active(true);
        Label demo = Ui.label(videoRoom ? "Video, chat và tệp dùng phòng hiện tại • Âm thanh và chia sẻ màn hình chưa bật."
                : "Thành viên và tệp đồng bộ từ máy chủ • Camera, mic, chia sẻ và chat đang mô phỏng.", "caption"); demo.setWrapText(true);
        roomError.setWrapText(true); roomError.setId("meeting-room-error");
        MeetingToolbar bottom = new MeetingToolbar(controls, demo, roomError); bottom.setPadding(new Insets(8, 16, 8, 16)); setBottom(bottom);
        if (videoRoom) {
            var video = manager.sfuSession().video();
            nativeStatus = Ui.label("", "caption");
            nativeStatus.textProperty().bind(video.statusProperty());
            Button management = Ui.button("Quản lý phòng", "secondary", this::roomSettings); management.setId("room-management");
            management.setVisible(manager.sfuSession().isHost()); management.setManaged(management.isVisible());
            managementListener = (observable, before, after) -> {
                if (!disposed) { management.setVisible(manager.sfuSession().isHost()); management.setManaged(management.isVisible()); }
            };
            manager.sfuSession().roomProperty().addListener(managementListener);
            bottom.content().getChildren().addAll(nativeStatus, management);
            controls.mic.setDisable(true); controls.share.setDisable(true);
            video.cameraRequestedProperty().addListener(cameraListener);
            manager.sfuSession().roomProperty().addListener(videoRoomListener); renderNativeParticipants();
        }
        people.addListener((ListChangeListener<MockData.Participant>) change -> {
            peakPeople = Math.max(peakPeople, people.size());
            renderVideos();
        });
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, old, value) -> updatePanelButtons()); renderVideos();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> { duration.setText(MockData.duration(elapsed())); refreshNetwork(); })); clock.setCycleCount(Animation.INDEFINITE); clock.play();
        roomPoll = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshRoom()));
        roomPoll.setCycleCount(Animation.INDEFINITE); roomPoll.play(); if (videoRoom) refreshRoom();
    }
    private void applyParticipants(RoomInfo room) {
        if (room == null) return;
        long selfId = manager.authenticatedUser().id();
        List<RoomParticipant> next = room.participants().stream()
            .sorted(java.util.Comparator.comparingInt(p -> p.userId() == selfId ? 0 : 1)).toList();
        if (next.equals(participantSnapshot)) return;
        pinnedIndex = -1;
        participantSnapshot = next;
        people.setAll(next.stream().map(p -> new MockData.Participant(p.displayName(), p.userId() == room.hostId(),
            p.userId() != selfId || !manager.mic(), p.userId() == selfId && manager.camera(), MockData.avatarColor(p.displayName()))).toList());
    }
    private void refreshRoom() {
        if (disposed || polling || leaving) return;
        if (videoRoom) {
            polling = true;
            manager.runMeetingRequest(manager.roomCommand("ROOM_POLL", 0), response -> {
                polling = false; if (disposed || leaving) return;
                managedState = response; managedPeople.setAll(response.members());
                participantPanel.waiting(response.waiting(), this::hostCommand); participantPanel.status("");
                updateLock();
            }, error -> {
                polling = false; if (!disposed && !leaving) participantPanel.status(SceneManager.connectionError(error));
            });
            return;
        }
        polling = true; String token = manager.sessionToken(); long id = manager.roomInfo().id();
        manager.runBackgroundRoomRequest(() -> manager.authClient().getRoom(token, id), response -> {
            if (disposed) return; polling = false;
            if (leaving) return;
            if (manager.sessionExpired(response)) return;
            if (response.success() && response.room() != null) {
                manager.updateRoom(response.room()); applyParticipants(response.room()); updateLock();
                participantCount.setText(response.room().people() + "/" + response.room().capacity() + " người"); roomError.setText("");
            } else roomError.setText(response.message());
        }, cause -> { if (!disposed) { polling = false; if (!leaving) roomError.setText(SceneManager.connectionError(cause)); } });
    }
    private void updateLock() { lockLabel.setText(manager.locked() ? "● Phòng đã khóa" : "● Phòng đang mở"); }
    private long elapsed() { return (System.nanoTime() - startNanos) / 1_000_000_000; }
    private void toggleMic() { manager.mic(!manager.mic()); controls.mic(manager.mic()); updateSelf(); }
    private void toggleCamera() {
        if (videoRoom) {
            var operation = mediaController.toggleSelectedCamera();
            if (operation == cameraOperation) return;
            cameraOperation = operation;
            operation.whenComplete((value, failure) -> javafx.application.Platform.runLater(() -> {
                if (disposed || cameraOperation != operation) return;
                if (failure != null) { roomError.setText(client.media.CameraErrors.message(failure)); deviceSettings(); }
                else roomError.setText("");
            }));
            return;
        }
        manager.camera(!manager.camera()); controls.camera(manager.camera()); updateSelf();
    }
    private void deviceSettings() {
        if (disposed) return;
        if (videoRoom) {
            if (cameraSettings != null && cameraSettings.isShowing()) return;
            cameraSettings = new CameraSettingsDialog(manager.stage(), mediaController);
            cameraSettings.show();
        }
        else new DeviceSettingsDialog(manager.stage()).show();
    }
    private void updateSelf() {
        if (!people.isEmpty() && !participantSnapshot.isEmpty() && participantSnapshot.getFirst().userId() == manager.authenticatedUser().id()) {
            MockData.Participant p = people.getFirst(); people.set(0, new MockData.Participant(p.name(), p.host(), !manager.mic(), manager.camera(), p.color()));
        }
    }
    private void toggleShare() { sharing = !sharing; controls.share.active(sharing); renderVideos(); }
    private void pin(int index) { pinnedIndex = pinnedIndex == index ? -1 : index; renderVideos(); }
    private void renderVideos() {
        tiles.forEach(VideoTile::dispose); tiles.clear(); videoContent.getChildren().clear(); participantCount.setText(people.size() + "/" + manager.room().capacity() + " người");
        int pages = Math.max(1, (people.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * PAGE_SIZE, end = Math.min(start + PAGE_SIZE, people.size());
        BorderPane frame = new BorderPane(videoContent);
        if (pages > 1) {
            Button previous = Ui.button("← Trước", "small-button", () -> { page--; renderVideos(); }); previous.setDisable(page == 0);
            Button next = Ui.button("Sau →", "small-button", () -> { page++; renderVideos(); }); next.setDisable(page == pages - 1);
            HBox pager = Ui.row(12, previous, Ui.label("Trang " + (page + 1) + "/" + pages + " · Người " + (start + 1) + "–" + end, "caption"), next); pager.setAlignment(Pos.CENTER); pager.setPadding(new Insets(12, 0, 0, 0)); frame.setBottom(pager);
        }
        canvas.getChildren().setAll(frame);
        if (pinnedIndex >= people.size()) pinnedIndex = -1;
        if (sharing) {
            VBox thumbnails = new VBox(10); thumbnails.setPrefWidth(164); thumbnails.setMinWidth(130);
            for (int i = start; i < end; i++) { VideoTile tile = tile(i); tile.setPrefHeight(108); tile.setMinHeight(108); tile.setMaxHeight(108); thumbnails.getChildren().add(tile); }
            ScrollPane strip = Ui.scroll(thumbnails); strip.setPrefWidth(180); strip.setMinWidth(145);
            VBox slide = Ui.column(20, Ui.label("MINI MEETING ROOM", "eyebrow"), Ui.label("Cùng xây dựng\nnhững ý tưởng tốt hơn.", "share-title"), Ui.label("01  /  Tổng quan sản phẩm", "brand-subtitle"), Ui.row(12, shareCard("Kết nối", "Nhóm nhỏ, tập trung"), shareCard("Cộng tác", "Chia sẻ tức thì")));
            slide.setAlignment(Pos.CENTER_LEFT); slide.getStyleClass().add("shared-slide");
            VBox large = Ui.column(14, Ui.row(8, IconButton.icon("share"), Ui.label("Bạn đang chia sẻ màn hình • mô phỏng", "accent-text")), slide); VBox.setVgrow(slide, Priority.ALWAYS); HBox.setHgrow(large, Priority.ALWAYS); large.setMinWidth(0);
            HBox layout = new HBox(14, large, strip); videoContent.getChildren().add(layout);
        } else if (pinnedIndex >= 0) {
            VideoTile focused = tile(pinnedIndex);
            BalancedVideoGrid focus = new BalancedVideoGrid(List.of(focused)); VBox.setVgrow(focus, Priority.ALWAYS);
            Button back = Ui.button("Trở về lưới", "secondary", () -> { pinnedIndex = -1; renderVideos(); });
            VBox main = Ui.column(12, Ui.row(8, IconButton.icon("pin"), Ui.label("Đã ghim · " + people.get(pinnedIndex).name(), "strong"), Ui.spacer(), back), focus);
            main.setMinWidth(0); HBox.setHgrow(main, Priority.ALWAYS);
            if (people.size() > 1) {
                VBox thumbnails = new VBox(10); thumbnails.setPrefWidth(164);
                for (int i = start; i < end; i++) if (i != pinnedIndex) {
                    VideoTile thumbnail = tile(i); thumbnail.setPrefHeight(108); thumbnail.setMinHeight(108); thumbnail.setMaxHeight(108); thumbnails.getChildren().add(thumbnail);
                }
                ScrollPane strip = Ui.scroll(thumbnails); strip.setPrefWidth(180); strip.setMinWidth(180); strip.setMaxWidth(180);
                videoContent.getChildren().add(new HBox(14, main, strip));
            } else videoContent.getChildren().add(main);
        } else {
            List<VideoTile> gallery = new ArrayList<>();
            for (int i = start; i < end; i++) gallery.add(tile(i));
            videoContent.getChildren().add(new BalancedVideoGrid(gallery));
        }
    }
    private VBox shareCard(String title, String text) { VBox card = Ui.column(8, Ui.label(title, "subtitle"), Ui.label(text, "muted")); card.getStyleClass().add("share-card"); HBox.setHgrow(card, Priority.ALWAYS); return card; }
    private VideoTile tile(int i) {
        if (videoRoom) {
            var session = manager.sfuSession();
            var participant = session.snapshot().participants().stream()
                    .sorted(java.util.Comparator.comparingInt(p -> p.participantId().equals(session.selfParticipantId()) ? 0 : 1)).toList().get(i);
            VideoTile tile = new VideoTile(participant, participant.participantId().equals(session.selfParticipantId()),
                    session.video().frames(participant.participantId()).imageProperty());
            tile.pinAction(participant.displayName(), pinnedIndex == i, () -> pin(i)); tiles.add(tile); return tile;
        }
        MockData.Participant p = people.get(i);
        boolean self = participantSnapshot.get(i).userId() == manager.authenticatedUser().id();
        if (self) p = new MockData.Participant(p.name(), p.host(), !manager.mic(), manager.camera(), p.color());
        VideoTile tile = new VideoTile(p, self);
        tile.pinAction(p.name(), pinnedIndex == i, () -> pin(i)); tiles.add(tile); return tile;
    }
    private void togglePanel(int tab) {
        boolean open = tab >= 0 && (!panelOpen || tabs.getSelectionModel().getSelectedIndex() != tab);
        if (tab >= 0) tabs.getSelectionModel().select(tab);
        panelOpen = open; if (panelAnimation != null) panelAnimation.stop(); panel.setVisible(true);
        panelAnimation = new Timeline(new KeyFrame(Duration.millis(220), new KeyValue(panel.prefWidthProperty(), open ? 320 : 0, Interpolator.EASE_BOTH), new KeyValue(panel.opacityProperty(), open ? 1 : 0, Interpolator.EASE_BOTH)));
        panelAnimation.setOnFinished(e -> panel.setVisible(panelOpen)); panelAnimation.play(); updatePanelButtons();
    }
    private void updatePanelButtons() { controls.chat.active(panelOpen && tabs.getSelectionModel().getSelectedIndex() == 1); controls.users.active(panelOpen && tabs.getSelectionModel().getSelectedIndex() == 0); }
    private void leave() {
        if (disposed || leaving) return;
        leaving = true; setDisable(true); roomError.setText("Đang rời phòng…");
        manager.leaveRoom(() -> { if (!disposed) manager.ended(elapsed(), peakPeople); }, message -> {
            if (!disposed) { leaving = false; setDisable(false); roomError.setText(message); }
        });
    }
    public void pauseFileTransfers() { filePanel.close(); }
    public void restoreFileTransfers() {
        if (disposed) return;
        filePanel = new FilePanel(manager);
        tabs.getTabs().get(2).setContent(filePanel);
    }
    private void renderNativeParticipants() {
        var session = manager.sfuSession(); var room = session.snapshot(); if (room == null) return;
        var ids = room.collaborationRoom().participants();
        java.util.List<common.meeting.Participant> next = new ArrayList<>();
        for (int i = 0; i < room.participants().size(); i++) {
            var person = room.participants().get(i);
            next.add(new common.meeting.Participant(ids.get(i).userId(), person.displayName(), person.role() == common.meeting.RoomRole.HOST,
                    person.mediaState().micRequested(), person.mediaState().cameraRequested()));
        }
        managedPeople.setAll(next);
    }
    private void invite() {
        if (!videoRoom || !manager.sfuSession().isHost()) return;
        new InviteDialog(manager.stage(), manager.room().name(), manager.room().code(), manager.invitationPin()).showAndWait();
    }
    private void hostCommand(String action, Long target) {
        manager.runMeetingRequest(manager.roomCommand(action, target), this::managedReply,
                error -> { if (!disposed) roomError.setText(SceneManager.connectionError(error)); });
    }
    private void managedReply(common.meeting.RoomResponse response) {
        if (disposed) return;
        managedState = response; managedPeople.setAll(response.members()); participantPanel.waiting(response.waiting(), this::hostCommand);
        if (response.room().closed()) manager.ended(elapsed(), peakPeople);
        else roomAction(manager.sfuSession().refresh());
    }
    private void roomSettings() {
        if (disposed || managedState == null || !manager.sfuSession().isHost() || managementSettings != null && managementSettings.isShowing()) return;
        var state = managedState;
        managementSettings = new DeviceSettingsDialog(manager.stage(), state.room(),
                locked -> managedSettings(locked, null), approval -> managedSettings(null, approval), () -> hostCommand("ROOM_CLOSE", 0L));
        managementSettings.show();
    }
    private void managedSettings(Boolean locked, Boolean approval) {
        if (disposed) return;
        manager.runMeetingRequest(common.meeting.RoomRequest.settings(manager.sessionToken(), manager.roomInfo().id(), manager.sfuSession().selfParticipantId(), locked, approval),
                this::managedReply, error -> { if (!disposed) roomError.setText(SceneManager.connectionError(error)); });
    }
    private void roomAction(java.util.concurrent.CompletableFuture<?> action) {
        action.whenComplete((value, error) -> javafx.application.Platform.runLater(() -> {
            if (!disposed) roomError.setText(error == null ? "" : SceneManager.connectionError(error));
        }));
    }
    public void dispose() {
        chatPanel.dispose(); manager.authClient().stopNetworkMonitoring(network);
        if (networkDialog != null) { networkDialog.dispose(); networkDialog.close(); }
        if (cameraSettings != null) { cameraSettings.close(); cameraSettings = null; }
        if (managementSettings != null) { managementSettings.close(); managementSettings = null; }
        disposed = true; filePanel.close(); clock.stop(); roomPoll.stop(); if (panelAnimation != null) panelAnimation.stop(); tiles.forEach(VideoTile::dispose);
        if (nativeStatus != null) nativeStatus.textProperty().unbind();
        if (videoRoom) {
            // Navigation removes bindings only; SceneManager/MeetingSession own the active media.
            mediaSession.roomProperty().removeListener(videoRoomListener);
            if (managementListener != null) mediaSession.roomProperty().removeListener(managementListener);
            mediaController.cameraRequestedProperty().removeListener(cameraListener);
        }
    }
}
