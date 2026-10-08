package client.UI.screens;

import client.UI.SceneManager;
import client.UI.components.*;
import client.UI.dialogs.*;
import client.UI.mock.MockData;
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

/** Không gian họp responsive, phân trang phòng đông và quyền host chỉ tắt thiết bị. */
public final class MeetingScreen extends BorderPane {
    private final SceneManager manager;
    private final ObservableList<MockData.Participant> people;
    private final List<VideoTile> tiles = new ArrayList<>();
    private final StackPane canvas = new StackPane();
    private final StackPane videoContent = new StackPane();
    private static final int PAGE_SIZE = 12;
    private int page;
    private Spinner<Integer> count;
    private boolean syncingCount;
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
    public MeetingScreen(SceneManager manager) {
        getStyleClass().add("theme-dark");
        this.manager = manager; people = FXCollections.observableArrayList(MockData.participants(manager.user()).subList(0, Math.min(6, manager.room().capacity()))); peakPeople = people.size();
        Label duration = Ui.label("00:00:00", "meeting-time");
        IconButton copy = new IconButton("copy", "Sao chép mã phòng");
        copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(manager.room().code()); Clipboard.getSystemClipboard().setContent(content); copy.setIcon("check"); PauseTransition reset = new PauseTransition(Duration.seconds(1.5)); reset.setOnFinished(done -> copy.setIcon("copy")); reset.play(); });
        NetworkBadge badge = new NetworkBadge(() -> new NetworkStatsDialog(manager.stage()).showAndWait());
        Label title = Ui.label(manager.room().name(), "meeting-title"); title.setMaxWidth(350);
        HBox top = Ui.row(12, IconButton.icon("camera"), Ui.column(5, title, Ui.row(8, Ui.label(manager.room().code(), "caption"), lockLabel)), copy, Ui.spacer(), participantCount, duration, badge); top.getStyleClass().add("meeting-top"); setTop(top); updateLock();
        Tab participants = new Tab("Thành viên", new ParticipantPanel(people, p -> disableMedia(p, true), p -> disableMedia(p, false), this::removeParticipant, () -> { manager.locked(!manager.locked()); updateLock(); }, this::leave));
        Tab chat = new Tab("Chat", new ChatPanel(manager.user())); Tab files = new Tab("Tệp", new FilePanel(manager.stage(), manager.user()));
        tabs.getTabs().addAll(participants, chat, files); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); VBox.setVgrow(tabs, Priority.ALWAYS);
        IconButton close = new IconButton("close", "Đóng panel"); close.setOnAction(e -> togglePanel(-1));
        HBox panelHeader = Ui.row(8, Ui.label("Trong cuộc họp", "strong"), Ui.spacer(), close); panelHeader.setPadding(new Insets(12, 14, 8, 16)); panel.getChildren().addAll(panelHeader, tabs); panel.getStyleClass().add("meeting-panel");
        panel.setMinWidth(0); panel.setPrefWidth(320); panel.setMaxWidth(320);
        Rectangle clip = new Rectangle(); clip.widthProperty().bind(panel.widthProperty()); clip.heightProperty().bind(panel.heightProperty()); panel.setClip(clip);
        canvas.setMinWidth(0); HBox.setHgrow(canvas, Priority.ALWAYS); canvas.setPadding(new Insets(20));
        HBox body = new HBox(canvas, panel); setCenter(body);
        controls = new ControlBar(manager.mic(), manager.camera(), this::toggleMic, this::toggleCamera, this::toggleShare, () -> togglePanel(1), () -> togglePanel(0), () -> new DeviceSettingsDialog(manager.stage()).showAndWait(), this::leave);
        controls.users.active(true);
        count = new Spinner<>(1, manager.room().capacity(), people.size()); count.setEditable(false); count.setPrefWidth(84); count.setAccessibleText("Số thành viên mô phỏng");
        count.valueProperty().addListener((obs, old, value) -> { if (!syncingCount) setCount(value); });
        HBox demo = Ui.row(10, Ui.label("UI DEMO", "eyebrow"), Ui.label("Số người", "caption"), count, Ui.label("Tối đa " + manager.room().capacity(), "caption")); demo.setAlignment(Pos.CENTER);
        VBox bottom = Ui.column(10, controls, demo); bottom.setAlignment(Pos.CENTER); bottom.setPadding(new Insets(8, 16, 16, 16)); setBottom(bottom);
        people.addListener((ListChangeListener<MockData.Participant>) change -> {
            peakPeople = Math.max(peakPeople, people.size());
            syncingCount = true; try { count.getValueFactory().setValue(people.size()); } finally { syncingCount = false; }
            renderVideos();
        });
        tabs.getSelectionModel().selectedIndexProperty().addListener((obs, old, value) -> updatePanelButtons()); renderVideos();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> duration.setText(MockData.duration(elapsed())))); clock.setCycleCount(Animation.INDEFINITE); clock.play();
    }
    private void updateLock() { lockLabel.setText(manager.locked() ? "● Phòng đã khóa" : "● Phòng đang mở"); }
    private long elapsed() { return (System.nanoTime() - startNanos) / 1_000_000_000; }
    private void toggleMic() { manager.mic(!manager.mic()); controls.mic(manager.mic()); updateSelf(); }
    private void toggleCamera() { manager.camera(!manager.camera()); controls.camera(manager.camera()); updateSelf(); }
    private void updateSelf() {
        if (!people.isEmpty()) { MockData.Participant p = people.getFirst(); people.set(0, new MockData.Participant(p.name(), true, !manager.mic(), manager.camera(), p.color())); }
    }
    private void setCount(int count) {
        count = Math.max(1, Math.min(count, manager.room().capacity()));
        List<MockData.Participant> next = new ArrayList<>(people.subList(0, Math.min(count, people.size())));
        List<MockData.Participant> base = MockData.participants(manager.user());
        while (next.size() < count) { int i = next.size(); next.add(i < base.size() ? base.get(i) : new MockData.Participant("Khách " + (i + 1), false, i % 2 == 0, true, "#394868")); }
        people.setAll(next);
    }
    /** Quyền host chỉ chuyển sang trạng thái tắt, không bao giờ bật thiết bị người khác. */
    private void disableMedia(MockData.Participant participant, boolean microphone) {
        int index = people.indexOf(participant); if (index < 0) return;
        if (index == 0) {
            if (microphone) { manager.mic(false); controls.mic(false); }
            else { manager.camera(false); controls.camera(false); }
            updateSelf(); return;
        }
        people.set(index, new MockData.Participant(participant.name(), participant.host(), microphone || participant.muted(), microphone && participant.camera(), participant.color()));
    }
    private void toggleShare() { sharing = !sharing; controls.share.active(sharing); renderVideos(); }
    private void removeParticipant(MockData.Participant participant) {
        int index = people.indexOf(participant);
        if (index == pinnedIndex) pinnedIndex = -1;
        else if (index >= 0 && index < pinnedIndex) pinnedIndex--;
        people.remove(participant);
    }
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
        MockData.Participant p = people.get(i); if (i == 0) p = new MockData.Participant(p.name(), true, !manager.mic(), manager.camera(), p.color());
        VideoTile tile = new VideoTile(p, i == 0); tile.setSpeaking(i == 1 && !p.muted());
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
    public void dispose() { clock.stop(); if (panelAnimation != null) panelAnimation.stop(); tiles.forEach(VideoTile::dispose); }
}
