package client.UI.components;

import client.UI.SceneManager;
import client.auth.ChatConnection;
import common.chat.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeMap;

/** Duc: existing chat UI, backed by server-confirmed messages and persistent history. */
public final class ChatPanel extends BorderPane {
    private final VBox messages = new VBox(18);
    private final ScrollPane scroll = Ui.scroll(messages);
    private final TextField input = new TextField();
    private final IconButton send = new IconButton("send", "Gửi tin nhắn");
    private final Label status = Ui.label("", "error-text");
    private final TreeMap<Long, ChatMessage> received = new TreeMap<>();
    private final PauseTransition reconnect = new PauseTransition(Duration.seconds(2));
    private final SceneManager manager;
    private final String token, ticket;
    private final long roomId, userId;
    private ChatConnection connection;
    private boolean connected, sending, disposed;

    public ChatPanel(SceneManager manager) {
        this.manager = manager;
        token = manager.sessionToken(); ticket = manager.meetingState().ticket();
        roomId = manager.meetingState().room().id(); userId = manager.authenticatedUser().id();
        messages.setId("chat-messages"); messages.setPadding(new Insets(16)); setCenter(scroll);
        input.setId("chat-input"); input.setPromptText("Viết tin nhắn..."); HBox.setHgrow(input, Priority.ALWAYS);
        send.setId("chat-send"); send.getStyleClass().add("primary");
        send.setOnAction(event -> submit()); input.setOnAction(event -> submit());
        status.managedProperty().bind(status.textProperty().isNotEmpty()); status.visibleProperty().bind(status.managedProperty());
        HBox controls = Ui.row(8, input, send);
        VBox bottom = Ui.column(6, status, controls); bottom.setPadding(new Insets(12)); setBottom(bottom);
        reconnect.setOnFinished(event -> connect()); connect();
    }
    private void connect() {
        if (disposed) return;
        if (connection != null) connection.close();
        connected = false; updateSend();
        connection = manager.authClient().listenChat(ChatRequest.listen(token, roomId, ticket),
                response -> Platform.runLater(() -> {
                    if (disposed) return;
                    if (!connected) status.setText("");
                    connected = true; updateSend(); append(response.messages(), false);
                }), error -> Platform.runLater(() -> {
                    if (disposed) return;
                    connected = false; updateSend(); status.setText(SceneManager.connectionError(error));
                    if (!(error instanceof ChatException rejection && rejection.admissionEnded())) reconnect.playFromStart();
                }));
    }
    private void submit() {
        if (disposed || sending || !connected || input.getText().isBlank()) return;
        String draft = input.getText();
        if (draft.strip().length() > 2000) { status.setText("Tin nhắn tối đa 2000 ký tự."); return; }
        sending = true; updateSend();
        manager.runChatRequest(ChatRequest.send(token, roomId, ticket, draft), response -> {
            if (disposed) return;
            sending = false; updateSend(); status.setText(""); append(response.messages(), true);
            if (draft.equals(input.getText())) input.clear();
        }, error -> {
            if (disposed) return;
            sending = false; updateSend(); status.setText(SceneManager.connectionError(error));
        });
    }
    private void updateSend() { send.setDisable(sending || !connected); }
    private void append(List<ChatMessage> batch, boolean ownSend) {
        if (batch.isEmpty()) return;
        boolean bottom = ownSend || received.isEmpty() || scroll.getVvalue() >= .98;
        boolean changed = false;
        for (ChatMessage message : batch) changed |= received.putIfAbsent(message.id(), message) == null;
        if (!changed) return;
        while (received.size() > 200) received.pollFirstEntry();
        messages.getChildren().setAll(received.values().stream().map(this::entry).toList());
        if (bottom) Platform.runLater(() -> { if (!disposed) { scroll.applyCss(); scroll.layout(); scroll.setVvalue(1); } });
    }
    private VBox entry(ChatMessage message) {
        boolean mine = message.userId() == userId;
        String time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(message.sentAt()));
        Label bubble = Ui.label(message.text(), "bubble"); bubble.getStyleClass().add(mine ? "bubble-mine" : "bubble-other"); bubble.setMaxWidth(248);
        bubble.setUserData(message.id());
        VBox entry = Ui.column(6, Ui.label((mine ? "Bạn" : message.name()) + "  ·  " + time, "caption"), bubble);
        entry.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT); return entry;
    }
    public void dispose() {
        disposed = true; reconnect.stop();
        if (connection != null) connection.close();
    }
}
