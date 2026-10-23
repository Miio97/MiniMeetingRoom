package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.dialogs.*;
import client.UI.mock.MockData;
import client.auth.RoomException;
import client.network.NetworkMonitor;
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
import common.meeting.*;

/** Không gian họp responsive, phân trang phòng đông và quyền host chỉ tắt thiết bị. */
public final class LegacyMeetingScreen extends BorderPane {
    private final SceneManager manager;
    private final ObservableList<MockData.Participant> people;
    private final List<VideoTile> tiles = new ArrayList<>();
    private final StackPane canvas = new StackPane();
    private final StackPane videoContent = new StackPane();
    private static final int PAGE_SIZE = 12;
    private int page;
    private final ObservableList<Participant> actualPeople = FXCollections.observableArrayList();
    private ParticipantPanel participantPanel;
    private final ChatPanel chatPanel;
    private final NetworkMonitor network;
    private final NetworkBadge networkBadge;
    private NetworkStatsDialog networkDialog;
    private boolean polling;
    private final VBox panel = new VBox();
    private final TabPane tabs = new TabPane();
    private final Timeline clock;
    private Timeline panelAnimation;
    private final ControlBar controls;
    private final Label lockLabel = Ui.label("", "caption");
    private final Label participantCount = Ui.label("", "caption");
    private boolean sharing, panelOpen = true;
    private long startNanos = System.nanoTime();
    private int peakPeople;
    private int pinnedIndex = -1;
    public LegacyMeetingScreen(SceneManager manager) {

        this.manager = manager; people = FXCollections.observableArrayList(); peakPeople = 0;
        Label duration = Ui.label("00:00:00", "meeting-time");
        IconButton copy = new IconButton("copy", "Sao chép mã phòng");
        copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(manager.room().code()); Clipboard.getSystemClipboard().setContent(content); copy.setIcon("check"); PauseTransition reset = new PauseTransition(Duration.seconds(1.5)); reset.setOnFinished(done -> copy.setIcon("copy")); reset.play(); });
        network = manager.authClient().startNetworkMonitoring();
        networkBadge = new NetworkBadge(this::networkStats);
        Label title = Ui.label(manager.room().name(), "meeting-title"); title.setMaxWidth(350);
        HBox top = Ui.row(12, IconButton.icon("camera"), Ui.column(5, title, Ui.row(8, Ui.label(manager.room().code(), "caption"), lockLabel)), copy, Ui.spacer(), participantCount, duration, networkBadge); top.getStyleClass().add("meeting-top"); setTop(top); updateLock();
        participantPanel = new ParticipantPanel(actualPeople, manager.authenticatedUser().id(), this::hostCommand);
        Tab participants = new Tab("Thành viên", participantPanel);
        chatPanel = new ChatPanel(manager);
        Tab chat = new Tab("Chat", chatPanel); Tab files = new Tab("Tệp", new FilePanel(manager));
        tabs.getTabs().addAll(participants, chat, files); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); VBox.setVgrow(tabs, Priority.ALWAYS);
        IconButton close = new IconButton("close", "Đóng panel"); close.setOnAction(e -> togglePanel(-1));
        HBox panelHeader = Ui.row(8, Ui.label("Trong cuộc họp", "strong"), Ui.spacer(), close); panelHeader.setPadding(new Insets(12, 14, 8, 16)); panel.getChildren().addAll(panelHeader, tabs); panel.getStyleClass().add("meeting-panel");
        panel.setMinWidth(0); panel.setPrefWidth(320); panel.setMaxWidth(320);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(panel.widthProperty()); clip.heightProperty().bind(panel.heightProperty()); panel.setClip(clip);
        canvas.setMinWidth(0); HBox.setHgrow(canvas, Priority.ALWAYS); canvas.setPadding(new Insets(20));
        HBox body = new HBox(canvas, panel); setCenter(body);
        controls = new ControlBar(manager.mic(), manager.camera(), this::toggleMic, this::toggleCamera, this::toggleShare, () -> togglePanel(1), () -> togglePanel(0), this::settings, this::leave);
        controls.users.active(true);
        VBox bottom = Ui.column(10, controls); bottom.setAlignment(Pos.CENTER); bottom.setPadding(new Insets(8, 16, 16, 16)); setBottom(bottom);
        people.addListener((ListChangeListener<MockData.Participant>) change -> {
            peakPeople = Math.max(peakPeople, people.size());
            renderVideos();
        });
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, old, value) -> updatePanelButtons()); renderVideos();
        apply(manager.meetingState());
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> { duration.setText(MockData.duration(elapsed())); refreshNetwork(); poll(); })); clock.setCycleCount(Animation.INDEFINITE); clock.play();
    }
    private boolean disposed;
    public void refreshNetwork() { if (!disposed) networkBadge.quality(network.snapshot().quality()); }
    public NetworkMonitor networkMonitor() { return network; }
    private void networkStats() {
        if (disposed || networkDialog != null) return;
        networkDialog = new NetworkStatsDialog(manager.stage(), network);
        try { networkDialog.showAndWait(); } finally { networkDialog.dispose(); networkDialog = null; }
    }
    private void poll() {
        if (disposed || polling || manager.meetingState() == null) return;
        polling = true;
        manager.runMeetingRequest(manager.roomCommand("ROOM_POLL", 0), response -> { polling = false; if (!disposed) apply(response); },
                error -> {
                    polling = false;
                    failure(error);
                });
    }
    private void failure(Throwable error) {
        if (disposed) return;
        if (error instanceof RoomException rejection && rejection.admissionEnded()) {
            manager.leaveRoomQuietly();
            manager.ended(elapsed(), peakPeople, SceneManager.connectionError(error));
        } else participantPanel.status(SceneManager.connectionError(error));
    }
    private void apply(RoomResponse response) {
        if (response.room().closed()) { manager.ended(elapsed(), peakPeople, "Chủ phòng đã đóng phòng."); return; }
        manager.updateMeeting(response); participantPanel.status(""); updateLock(); controls.mic(manager.mic()); controls.camera(manager.camera());
        List<Participant> next = response.members().stream().sorted(java.util.Comparator.comparing((Participant person) -> person.userId() != manager.authenticatedUser().id())).toList();
        if (!actualPeople.equals(next)) {
            actualPeople.setAll(next); pinnedIndex = -1;
            people.setAll(next.stream().map(person -> new MockData.Participant(person.name(), person.host(), !person.microphone(), person.camera(), MockData.avatarColor(person.name()))).toList());
        }
        participantPanel.waiting(response.waiting(), this::hostCommand);
    }
    private void send(RoomRequest request) {
        if (disposed) return;
        manager.runMeetingRequest(request, response -> { if (!disposed) apply(response); },
                this::failure);
    }
    private void hostCommand(String action, Long target) { send(manager.roomCommand(action, target)); }
    private void settings() {
        if (actualPeople.stream().noneMatch(person -> person.userId() == manager.authenticatedUser().id() && person.host())) { new DeviceSettingsDialog(manager.stage()).showAndWait(); return; }
        RoomResponse state = manager.meetingState();
        new DeviceSettingsDialog(manager.stage(), state.room(),
                locked -> send(RoomRequest.settings(manager.sessionToken(), state.room().id(), state.ticket(), locked, null)),
                approval -> send(RoomRequest.settings(manager.sessionToken(), state.room().id(), state.ticket(), null, approval)),
                () -> send(manager.roomCommand("ROOM_CLOSE", 0))).showAndWait();
    }
    private void updateLock() { lockLabel.setText(manager.locked() ? "● Phòng đã khóa" : "● Phòng đang mở"); }
    private long elapsed() { return (System.nanoTime() - startNanos) / 1_000_000_000; }
    private void toggleMic() { RoomResponse state = manager.meetingState(); send(RoomRequest.media(manager.sessionToken(), state.room().id(), state.ticket(), !manager.mic(), null)); }
    private void toggleCamera() { RoomResponse state = manager.meetingState(); send(RoomRequest.media(manager.sessionToken(), state.room().id(), state.ticket(), null, !manager.camera())); }
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
            VBox main = Ui.column(12, Ui.row(8, IconButton.icon("pin"), Ui.label("Đã ghim · " + people.get(pinnedIndex).name(), "strong"), Ui.spacer()), focus);
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
        MockData.Participant p = people.get(i);
        VideoTile tile = new VideoTile(p, i == 0);
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
    private void leave() { manager.ended(elapsed(), peakPeople); }
    public void dispose() {
        disposed = true; clock.stop(); chatPanel.dispose(); manager.authClient().stopNetworkMonitoring(network);
        if (networkDialog != null) { networkDialog.dispose(); networkDialog.close(); }
        manager.leaveRoomQuietly(); if (panelAnimation != null) panelAnimation.stop(); tiles.forEach(VideoTile::dispose);
    }
}
