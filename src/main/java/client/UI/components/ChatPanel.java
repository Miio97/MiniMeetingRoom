package client.UI.components;

import client.UI.SceneManager;
import client.UI.VietnamTime;
import client.auth.ChatConnection;
import client.meeting.MeetingSession;

import common.chat.*;
import common.signaling.SignalException;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Chat UI supporting both real-time signaling chat in video rooms and preview demo for legacy
 * rooms.
 */
public final class ChatPanel extends BorderPane {
    private static final int MAX_MESSAGES = 500;
    private final VBox messages = new VBox(18);
    private final ScrollPane scroll = Ui.scroll(messages);

    private record ChatRow(String nonce, ChatMessage message, String state) {}

    private final javafx.collections.ObservableList<ChatRow> rows =
            javafx.collections.FXCollections.observableArrayList();
    private final ListView<ChatRow> virtualMessages = new ListView<>(rows);
    private final java.util.Map<String, ChatRow> pending = new java.util.LinkedHashMap<>();
    private final TextField input = new TextField();
    private final IconButton send = new IconButton("send", "Gửi tin nhắn");
    private final Label status = Ui.label("", "error-text");
    private final TreeMap<Long, ChatMessage> received = new TreeMap<>();
    private final PauseTransition reconnect = new PauseTransition(Duration.seconds(2));
    private final SceneManager manager;
    private final MeetingSession session;
    private final MeetingSession.ChatListener chatListener;
    private final String token, ticket;
    private final long roomId, userId;
    private ChatConnection connection;
    private boolean connected, sending, disposed;

    public ChatPanel(SceneManager manager) {
        this.manager = Objects.requireNonNull(manager);
        boolean isVideo = manager.hasVideoRoom();
        this.session = isVideo ? manager.sfuSession() : null;
        token = manager.sessionToken();
        ticket =
                isVideo
                        ? manager.sfuSession().selfParticipantId()
                        : manager.meetingState() == null ? "" : manager.meetingState().ticket();
        roomId =
                isVideo || manager.meetingState() == null
                        ? manager.roomInfo().id()
                        : manager.meetingState().room().id();
        userId = manager.authenticatedUser() == null ? 0 : manager.authenticatedUser().id();

        messages.setId("chat-messages");
        messages.setPadding(new Insets(16));
        if (isVideo) {
            virtualMessages.setId("chat-messages");
            virtualMessages.setCellFactory(
                    list ->
                            new ListCell<>() {
                                @Override
                                protected void updateItem(ChatRow row, boolean empty) {
                                    super.updateItem(row, empty);
                                    setText(null);
                                    setGraphic(null);
                                    if (!empty && row != null) {
                                        VBox content = entry(row.message());
                                        if (!row.state().isEmpty()) {
                                            content.getChildren()
                                                    .add(Ui.label(row.state(), "caption"));
                                            if (!row.state().equals("Đang gửi…")
                                                    && !row.state().startsWith("Không thể thử lại"))
                                                content.getChildren()
                                                        .add(
                                                                Ui.button(
                                                                        "Thử lại",
                                                                        "small-button",
                                                                        () -> sendPending(row)));
                                        }
                                        setGraphic(content);
                                    }
                                }
                            });
            setCenter(virtualMessages);
        } else setCenter(scroll);

        input.setId("chat-input");
        input.setPromptText("Viết tin nhắn...");
        HBox.setHgrow(input, Priority.ALWAYS);
        send.setId("chat-send");
        send.getStyleClass().add("primary");
        send.setOnAction(event -> submit());
        input.setOnAction(event -> submit());

        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());

        Label notice = Ui.label(isVideo ? "" : "Bản xem trước (demo)", "caption");
        notice.setManaged(!isVideo);
        notice.setVisible(!isVideo);
        notice.setStyle("-fx-font-style: italic; -fx-text-fill: -text-muted;");
        notice.setWrapText(true);

        HBox controls = Ui.row(8, input, send);
        VBox bottom = Ui.column(6, notice, status, controls);
        bottom.setPadding(new Insets(12));
        setBottom(bottom);

        if (isVideo) {
            chatListener =
                    new MeetingSession.ChatListener() {
                        @Override
                        public void onChatMessage(ChatMessage message) {
                            Platform.runLater(
                                    () -> append(List.of(message), message.userId() == userId));
                        }

                        @Override
                        public void onChatMessage(ChatMessage message, String nonce) {
                            Platform.runLater(
                                    () -> appendVideo(message, nonce, message.userId() == userId));
                        }

                        @Override
                        public void onChatCleared() {
                            Platform.runLater(ChatPanel.this::clear);
                        }

                        @Override
                        public void onChatResync() {
                            Platform.runLater(ChatPanel.this::resyncChat);
                        }
                    };
            session.addChatListener(chatListener);
            input.setText(session.chatDraft());
            input.textProperty()
                    .addListener((observable, before, after) -> session.chatDraft(after));
            loadRealHistory();
        } else {
            chatListener = null;
            reconnect.setOnFinished(event -> connect());
            connect();
        }
    }

    private void loadRealHistory() {
        if (session == null) return;
        session.chatHistory(null, 50)
                .thenAccept(
                        batch ->
                                Platform.runLater(
                                        () -> {
                                            if (disposed) return;
                                            append(batch, false);
                                        }))
                .exceptionally(
                        error -> {
                            Platform.runLater(
                                    () -> {
                                        if (!disposed)
                                            status.setText(SceneManager.connectionError(error));
                                    });
                            return null;
                        });
    }

    private void resyncChat() {
        if (disposed || session == null) return;
        loadRealHistory();
        if (pending.isEmpty()) return;
        session.acknowledgedChats(List.copyOf(pending.keySet()))
                .thenAccept(
                        saved ->
                                Platform.runLater(
                                        () -> {
                                            if (disposed) return;
                                            for (var ack : saved) {
                                                var row = pending.get(ack.clientMessageId());
                                                if (row != null)
                                                    appendVideo(
                                                            new ChatMessage(
                                                                    ack.id(),
                                                                    roomId,
                                                                    userId,
                                                                    "Bạn",
                                                                    row.message().text(),
                                                                    ack.sentAt()),
                                                            ack.clientMessageId(),
                                                            false);
                                            }
                                        }));
    }

    private void connect() {
        if (disposed || manager.hasVideoRoom()) return;
        if (connection != null) connection.close();
        connected = false;
        updateControls();
        connection =
                manager.authClient()
                        .listenChat(
                                ChatRequest.listen(token, roomId, ticket),
                                response ->
                                        Platform.runLater(
                                                () -> {
                                                    if (disposed) return;
                                                    if (!connected) status.setText("");
                                                    connected = true;
                                                    updateControls();
                                                    append(response.messages(), false);
                                                }),
                                error ->
                                        Platform.runLater(
                                                () -> {
                                                    if (disposed) return;
                                                    connected = false;
                                                    updateControls();
                                                    status.setText(
                                                            SceneManager.connectionError(error));
                                                    if (!(error instanceof ChatException rejection
                                                            && rejection.admissionEnded()))
                                                        reconnect.playFromStart();
                                                }));
    }

    private void submit() {
        if (session != null) {
            if (disposed || input.getText().isBlank() || pending.size() >= 8) return;
            String content = input.getText().strip();
            if (content.length() > 2000) {
                status.setText("Tin nhắn tối đa 2000 ký tự.");
                return;
            }
            var row =
                    new ChatRow(
                            java.util.UUID.randomUUID().toString(),
                            new ChatMessage(
                                    0, roomId, userId, "Bạn", content, Instant.now().toString()),
                            "Đang gửi…");
            rows.add(row);
            pending.put(row.nonce(), row);
            input.clear();
            trimRows();
            virtualMessages.scrollTo(rows.size() - 1);
            sendPending(row);
            return;
        }
        if (disposed || sending || input.getText().isBlank()) return;
        if (!manager.hasVideoRoom() && !connected) return;
        String draft = input.getText();
        if (draft.strip().length() > 2000) {
            status.setText("Tin nhắn tối đa 2000 ký tự.");
            return;
        }

        sending = true;
        updateControls();

        if (manager.hasVideoRoom() && session != null) {
            session.sendChat(draft.strip())
                    .thenRun(
                            () ->
                                    Platform.runLater(
                                            () -> {
                                                if (disposed) return;
                                                sending = false;
                                                updateControls();
                                                status.setText("");
                                                if (draft.equals(input.getText())) input.clear();
                                            }))
                    .exceptionally(
                            error -> {
                                Platform.runLater(
                                        () -> {
                                            if (disposed) return;
                                            sending = false;
                                            updateControls();
                                            Throwable cause = error;
                                            while ((cause instanceof CompletionException
                                                            || cause instanceof ExecutionException)
                                                    && cause.getCause() != null) {
                                                cause = cause.getCause();
                                            }
                                            if (cause instanceof SignalException signal) {
                                                if ("CHAT_BUSY".equals(signal.code())) {
                                                    status.setText(
                                                            "Máy chủ đang bận, vui lòng thử lại"
                                                                    + " sau.");
                                                } else if ("RATE_LIMIT".equals(signal.code())) {
                                                    status.setText(
                                                            "Bạn đang gửi tin nhắn quá nhanh. Vui"
                                                                    + " lòng chờ giây lát.");
                                                } else {
                                                    status.setText(
                                                            SceneManager.connectionError(cause));
                                                }
                                            } else {
                                                status.setText(SceneManager.connectionError(cause));
                                            }
                                        });
                                return null;
                            });
        } else {
            manager.runChatRequest(
                    ChatRequest.send(token, roomId, ticket, draft),
                    response -> {
                        if (disposed) return;
                        sending = false;
                        updateControls();
                        status.setText("");
                        append(response.messages(), true);
                        if (draft.equals(input.getText())) input.clear();
                    },
                    error -> {
                        if (disposed) return;
                        sending = false;
                        updateControls();
                        status.setText(SceneManager.connectionError(error));
                    });
        }
    }

    private void updateControls() {
        input.setDisable(sending);
        messages.setDisable(sending);
        send.setDisable(sending || (!manager.hasVideoRoom() && !connected));
    }

    private void sendPending(ChatRow row) {
        if (disposed || session == null || !pending.containsKey(row.nonce())) return;
        var sendingRow = new ChatRow(row.nonce(), row.message(), "Đang gửi…");
        replaceRow(row.nonce(), sendingRow);
        pending.put(row.nonce(), sendingRow);
        session.sendChat(row.message().text(), row.nonce())
                .whenComplete(
                        (ack, failure) ->
                                Platform.runLater(
                                        () -> {
                                            if (disposed) return;
                                            if (failure == null)
                                                appendVideo(
                                                        new ChatMessage(
                                                                ack.id(),
                                                                roomId,
                                                                userId,
                                                                "Bạn",
                                                                row.message().text(),
                                                                ack.sentAt()),
                                                        row.nonce(),
                                                        true);
                                            else {
                                                if (!pending.containsKey(row.nonce()))
                                                    return; // A push may already have confirmed
                                                // this write.
                                                var cause = failure;
                                                while ((cause instanceof CompletionException
                                                                || cause
                                                                        instanceof
                                                                        ExecutionException)
                                                        && cause.getCause() != null)
                                                    cause = cause.getCause();
                                                boolean unsafe =
                                                        cause instanceof SignalException error
                                                                && error.code()
                                                                        .equals(
                                                                                "CHAT_RETRY_UNSAFE");
                                                String text =
                                                        unsafe
                                                                ? "Không thể thử lại an toàn; hãy"
                                                                        + " kiểm tra lịch sử."
                                                                : cause
                                                                                        instanceof
                                                                                        SignalException
                                                                                                        error
                                                                                && java.util.Set.of(
                                                                                                "CHAT_BUSY",
                                                                                                "RATE_LIMIT",
                                                                                                "INVALID_REQUEST",
                                                                                                "DATABASE_UNAVAILABLE")
                                                                                        .contains(
                                                                                                error
                                                                                                        .code())
                                                                        ? "Chưa gửi được"
                                                                        : "Chưa xác nhận gửi thành"
                                                                                + " công";
                                                var failed =
                                                        new ChatRow(
                                                                row.nonce(), row.message(), text);
                                                replaceRow(row.nonce(), failed);
                                                pending.put(row.nonce(), failed);
                                                status.setText(SceneManager.connectionError(cause));
                                            }
                                        }));
    }

    private void replaceRow(String nonce, ChatRow replacement) {
        for (int i = 0; i < rows.size(); i++)
            if (java.util.Objects.equals(nonce, rows.get(i).nonce())) {
                rows.set(i, replacement);
                return;
            }
    }

    private boolean atBottom() {
        return virtualMessages.lookupAll(".scroll-bar").stream()
                .filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast)
                .filter(bar -> bar.getOrientation() == javafx.geometry.Orientation.VERTICAL)
                .allMatch(bar -> bar.getValue() >= bar.getMax() - .01);
    }

    private void trimRows() {
        while (rows.size() > MAX_MESSAGES) {
            var removed = rows.remove(0);
            received.remove(removed.message().id());
            if (removed.nonce() != null) pending.remove(removed.nonce());
        }
    }

    private void appendVideo(ChatMessage message, String nonce, boolean ownSend) {
        if (disposed) return;
        boolean bottom = ownSend || atBottom();
        if (message.userId() == userId && nonce != null && pending.remove(nonce) != null) {
            rows.removeIf(row -> row.message().id() == message.id());
            replaceRow(nonce, new ChatRow(nonce, message, ""));
            received.put(message.id(), message);
        } else if (received.putIfAbsent(message.id(), message) == null) {
            int index = 0;
            while (index < rows.size()
                    && rows.get(index).message().id() > 0
                    && rows.get(index).message().id() < message.id()) index++;
            rows.add(index, new ChatRow(nonce, message, ""));
        }
        trimRows();
        if (bottom) virtualMessages.scrollTo(rows.size() - 1);
    }

    private void clear() {
        received.clear();
        rows.clear();
        pending.clear();
        if (session != null) session.chatDraft("");
        messages.getChildren().clear();
    }

    private void append(List<ChatMessage> batch, boolean ownSend) {
        if (session != null) {
            for (var message : batch) appendVideo(message, null, ownSend);
            return;
        }
        if (batch.isEmpty()) return;
        boolean bottom = ownSend || received.isEmpty() || scroll.getVvalue() >= .98;
        boolean changed = false;
        for (ChatMessage message : batch)
            changed |= received.putIfAbsent(message.id(), message) == null;
        if (!changed) return;
        while (received.size() > MAX_MESSAGES) received.pollFirstEntry();
        messages.getChildren().setAll(received.values().stream().map(this::entry).toList());
        if (bottom)
            Platform.runLater(
                    () -> {
                        if (!disposed) {
                            scroll.applyCss();
                            scroll.layout();
                            scroll.setVvalue(1);
                        }
                    });
    }

    private VBox entry(ChatMessage message) {
        boolean mine = message.userId() == userId;
        String time = VietnamTime.formatTime(Instant.parse(message.sentAt()));
        Label bubble = Ui.label(message.text(), "bubble");
        bubble.getStyleClass().add(mine ? "bubble-mine" : "bubble-other");
        bubble.setMaxWidth(248);
        bubble.setUserData(message.id());
        VBox entry =
                Ui.column(
                        6,
                        Ui.label((mine ? "Bạn" : message.name()) + "  ·  " + time, "caption"),
                        bubble);
        entry.setAlignment(mine ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        return entry;
    }

    public void dispose() {
        disposed = true;
        reconnect.stop();
        if (chatListener != null && session != null) session.removeChatListener(chatListener);
        if (connection != null) connection.close();
    }
}
