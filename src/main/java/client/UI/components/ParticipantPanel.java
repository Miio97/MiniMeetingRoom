package client.UI.components;

import client.UI.dialogs.HostMenuPopup;
import common.meeting.Participant;
import javafx.collections.*;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import java.util.List;
import java.util.function.BiConsumer;

/** Duc: host-only controls for actual account identities. */
public final class ParticipantPanel extends BorderPane {
    private final VBox queue = new VBox(8);
    private final Label status = Ui.label("", "caption");
    private boolean host;
    private final ObservableList<Participant> people;
    private final java.util.function.Predicate<Participant> sharing;
    private HostMenuPopup activeMenu;
    private long menuTarget;
    private final javafx.beans.property.BooleanProperty pending = new javafx.beans.property.SimpleBooleanProperty();
    public void pending(boolean value) { pending.set(value); }
    public ParticipantPanel(ObservableList<Participant> people, long selfId, BiConsumer<String, Long> command) {
        this(people, selfId, command, person -> false, userId -> { });
    }
    public ParticipantPanel(ObservableList<Participant> people, long selfId, BiConsumer<String, Long> command,
                            java.util.function.Predicate<Participant> sharing, java.util.function.LongConsumer stopShare) {
        this.people = people; this.sharing = sharing;
        ListView<Participant> list = new ListView<>(people); list.setId("room-members"); list.getStyleClass().add("participant-list");
        Runnable permissions = () -> {
            boolean wasHost = host;
            host = people.stream().anyMatch(person -> person.userId() == selfId && person.host());
            if (wasHost != host) list.refresh();
            refreshMenu();
        };
        pending.addListener((obs, before, after) -> permissions.run());
        people.addListener((ListChangeListener<Participant>) change -> permissions.run()); permissions.run();
        sceneProperty().addListener((obs, before, after) -> { if (after == null && activeMenu != null) activeMenu.hide(); });
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Participant person, boolean empty) {
                super.updateItem(person, empty); setText(null); setGraphic(null); if (empty || person == null) return;
                Label name = Ui.label(person.name() + (person.userId() == selfId ? " (Bạn)" : ""), "strong"); name.setMinWidth(0); HBox.setHgrow(name, Priority.ALWAYS);
                HBox row = Ui.row(8, Ui.avatar(person.name(), 34), name, IconButton.icon(person.microphone() ? "mic" : "mic-off"), IconButton.icon(person.camera() ? "camera" : "camera-off"));
                if (person.host()) row.getChildren().add(Ui.label("Host", "host-badge"));
                if (host && person.userId() != selfId) {
                    IconButton menu = new IconButton("settings", "Quản lý " + person.name());
                    menu.setId("participant-menu-" + person.userId()); menu.disableProperty().bind(pending);
                    menu.setOnAction(event -> {
                        if (activeMenu != null) {
                            boolean same = activeMenu.isShowing() && menuTarget == person.userId();
                            activeMenu.hide(); if (same) return;
                        }
                        var bounds = menu.localToScreen(menu.getBoundsInLocal()); if (bounds == null) return;
                        HostMenuPopup popup = new HostMenuPopup(person.microphone(), person.camera(), sharing.test(person),
                            () -> command.accept("ROOM_MIC_OFF", person.userId()), () -> command.accept("ROOM_CAMERA_OFF", person.userId()),
                            () -> stopShare.accept(person.userId()),
                            () -> command.accept("ROOM_KICK", person.userId()), () -> command.accept("ROOM_TRANSFER", person.userId()));
                        activeMenu = popup; menuTarget = person.userId(); popup.bindPending(pending);
                        popup.setOnHidden(e -> { if (activeMenu == popup) activeMenu = null; });
                        // List cells are recycled by polling. Anchor to the stable panel instead of the cell button.
                        popup.show(ParticipantPanel.this, bounds.getMinX(), bounds.getMaxY());
                    });
                    row.getChildren().add(menu);
                }
                row.getStyleClass().add("participant-row"); setGraphic(row);
            }
        });
        queue.setPadding(new Insets(10, 12, 10, 12));
        queue.visibleProperty().bind(Bindings.isNotEmpty(queue.getChildren()));
        queue.managedProperty().bind(queue.visibleProperty());
        setTop(queue); setCenter(list); setBottom(status);
    }
    public void refreshMenu() {
        if (activeMenu == null) return;
        Participant target = people.stream().filter(person -> person.userId() == menuTarget).findFirst().orElse(null);
        if (!host || target == null) { activeMenu.hide(); return; }
        activeMenu.mediaState(target.microphone(), target.camera(), sharing.test(target));
    }
    public void status(String message) { status.setText(message); }
    public void waiting(List<Participant> waiting, BiConsumer<String, Long> command) {
        queue.getChildren().clear(); if (!host || waiting.isEmpty()) return;
        queue.getChildren().add(Ui.label("Chờ duyệt (" + waiting.size() + ")", "strong"));
        for (Participant person : waiting) {
            Label name = Ui.label(person.name(), "caption"); name.setMinWidth(0); HBox.setHgrow(name, Priority.ALWAYS);
            IconButton yes = new IconButton("check", "Chấp nhận " + person.name()); yes.setOnAction(event -> command.accept("ROOM_PENDING_APPROVE", person.userId()));
            IconButton no = new IconButton("close", "Từ chối " + person.name()); no.setOnAction(event -> command.accept("ROOM_PENDING_REJECT", person.userId()));
            queue.getChildren().add(Ui.row(6, name, yes, no));
        }
    }
}
