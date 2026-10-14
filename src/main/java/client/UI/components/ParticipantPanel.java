package client.UI.components;

import client.UI.dialogs.HostMenuPopup;
import common.meeting.Participant;
import javafx.collections.*;
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
    public ParticipantPanel(ObservableList<Participant> people, long selfId, Runnable invite, BiConsumer<String, Long> command) {
        ListView<Participant> list = new ListView<>(people); list.setId("room-members"); list.getStyleClass().add("participant-list");
        Button invitation = Ui.button("Mời người dùng", "secondary", invite); invitation.setGraphic(IconButton.icon("users")); invitation.setMaxWidth(Double.MAX_VALUE);
        invitation.setId("room-invite");
        Runnable permissions = () -> {
            host = people.stream().anyMatch(person -> person.userId() == selfId && person.host());
            invitation.setDisable(!host); invitation.setVisible(host); invitation.setManaged(host); list.refresh();
        };
        people.addListener((ListChangeListener<Participant>) change -> permissions.run()); permissions.run();
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(Participant person, boolean empty) {
                super.updateItem(person, empty); setText(null); setGraphic(null); if (empty || person == null) return;
                Label name = Ui.label(person.name() + (person.userId() == selfId ? " (Bạn)" : ""), "strong"); name.setMinWidth(0); HBox.setHgrow(name, Priority.ALWAYS);
                HBox row = Ui.row(8, Ui.avatar(person.name(), 34), name, IconButton.icon(person.microphone() ? "mic" : "mic-off"), IconButton.icon(person.camera() ? "camera" : "camera-off"));
                if (person.host()) row.getChildren().add(Ui.label("Host", "host-badge"));
                if (host && person.userId() != selfId) {
                    IconButton menu = new IconButton("settings", "Quản lý " + person.name());
                    menu.setOnAction(event -> new HostMenuPopup(person.microphone(), person.camera(),
                            () -> command.accept("ROOM_MIC_OFF", person.userId()), () -> command.accept("ROOM_CAMERA_OFF", person.userId()),
                            () -> command.accept("ROOM_KICK", person.userId()), () -> command.accept("ROOM_TRANSFER", person.userId())).show(menu, javafx.geometry.Side.BOTTOM, 0, 0));
                    row.getChildren().add(menu);
                }
                row.getStyleClass().add("participant-row"); setGraphic(row);
            }
        });
        VBox top = Ui.column(10, invitation, queue); top.setPadding(new Insets(10, 12, 10, 12)); setTop(top); setCenter(list); setBottom(status);
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
