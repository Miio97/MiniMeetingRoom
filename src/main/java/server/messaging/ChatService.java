package server.messaging;

import common.chat.*;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import server.meeting.RoomService;

/** Duc: committed database messages are broadcast through bounded TLS subscriptions. */
public final class ChatService implements AutoCloseable {
    public static final int MAX_TEXT_LENGTH = 2000;
    public static final int HISTORY_LIMIT = 100;
    private static final int MAX_SUBSCRIPTIONS = 64;
    private final RoomService rooms;
    private final Clock clock;
    private final Set<Subscription> subscribers = new LinkedHashSet<>();
    private boolean closed;

    public ChatService(RoomService rooms) { this(rooms, Clock.systemUTC()); }
    public ChatService(RoomService rooms, Clock clock) { this.rooms = rooms; this.clock = clock; }

    public synchronized ChatResponse send(ChatRequest request) throws SQLException, ChatException {
        require(request, "CHAT_SEND");
        String text = request.text() == null ? "" : request.text().strip();
        if (text.isBlank() || text.length() > MAX_TEXT_LENGTH || !validText(text)) {
            throw new ChatException("VALIDATION_ERROR", "Tin nhắn cần 1–2000 ký tự hợp lệ.");
        }
        ChatMessage message = rooms.chat(request.token(), request.roomId(), request.ticket(), (connection, sender) -> {
            connection.setAutoCommit(false);
            try {
                long id;
                var time = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
                try (PreparedStatement sql = connection.prepareStatement("INSERT INTO Tin_Nhan (Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Noi_Dung, Ngay_Gui) VALUES (?, ?, 'VAN_BAN', ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    sql.setLong(1, request.roomId()); sql.setLong(2, sender.userId()); sql.setString(3, text);
                    sql.setTimestamp(4, Timestamp.from(time), utc()); sql.executeUpdate();
                    try (ResultSet keys = sql.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("Missing message ID");
                        id = keys.getLong(1);
                    }
                }
                connection.commit();
                return new ChatMessage(id, request.roomId(), sender.userId(), sender.name(), text, time.toString());
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        });
        ChatResponse event = ChatResponse.ok(List.of(message));
        for (Subscription subscriber : List.copyOf(subscribers)) {
            if (subscriber.request.roomId() == request.roomId() && !subscriber.events.offer(event)) subscriber.close();
        }
        return event;
    }

    public synchronized Subscription subscribe(ChatRequest request, Runnable disconnect) throws SQLException, ChatException {
        require(request, "CHAT_LISTEN");
        if (subscribers.size() >= MAX_SUBSCRIPTIONS) throw new ChatException("SERVER_BUSY", "Chat đang bận. Vui lòng thử lại.");
        // Registration and history lookup share the send lock, so no message falls between them.
        List<ChatMessage> history = rooms.chat(request.token(), request.roomId(), request.ticket(), (connection, sender) -> {
            List<ChatMessage> messages = new ArrayList<>();
            try (PreparedStatement sql = connection.prepareStatement("SELECT t.Ma_Tin_Nhan, t.Ma_Nguoi_Gui, u.Ten_Hien_Thi, t.Noi_Dung, t.Ngay_Gui FROM Tin_Nhan t JOIN Nguoi_Dung u ON u.Ma_Nguoi_Dung = t.Ma_Nguoi_Gui WHERE t.Ma_Phong = ? AND t.Loai_Tin_Nhan = 'VAN_BAN' AND t.Ngay_Xoa IS NULL ORDER BY t.Ma_Tin_Nhan DESC LIMIT " + HISTORY_LIMIT)) {
                sql.setLong(1, request.roomId());
                try (ResultSet result = sql.executeQuery()) {
                    while (result.next()) messages.add(new ChatMessage(result.getLong(1), request.roomId(), result.getLong(2), result.getString(3), result.getString(4), result.getTimestamp(5, utc()).toInstant().toString()));
                }
            }
            Collections.reverse(messages); return List.copyOf(messages);
        });
        Subscription subscription = new Subscription(request, history, disconnect);
        subscribers.add(subscription); return subscription;
    }

    public void validate(Subscription subscription) throws SQLException, ChatException {
        ChatRequest request = subscription.request;
        rooms.chat(request.token(), request.roomId(), request.ticket(), (connection, sender) -> null);
    }
    private void require(ChatRequest request, String action) throws ChatException {
        if (closed) throw new ChatException("SERVER_CLOSED", "Chat server đã dừng.");
        if (request == null || !action.equals(request.action())) throw new ChatException("INVALID_REQUEST", "Yêu cầu chat không hợp lệ.");
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static boolean validText(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isISOControl(value) && value != '\n' && value != '\t') return false;
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) return false;
            } else if (Character.isLowSurrogate(value)) return false;
        }
        return true;
    }
    public synchronized int subscriberCount() { return subscribers.size(); }
    @Override public synchronized void close() {
        closed = true;
        for (Subscription subscriber : List.copyOf(subscribers)) subscriber.close();
    }
    public final class Subscription implements AutoCloseable {
        private final ChatRequest request;
        private final List<ChatMessage> history;
        private final Runnable disconnect;
        private final ArrayBlockingQueue<ChatResponse> events = new ArrayBlockingQueue<>(64);
        private final AtomicBoolean ended = new AtomicBoolean();
        private Subscription(ChatRequest request, List<ChatMessage> history, Runnable disconnect) {
            this.request = request; this.history = history; this.disconnect = disconnect;
        }
        public List<ChatMessage> history() { return history; }
        public boolean ended() { return ended.get(); }
        public ChatResponse next() throws InterruptedException { return events.poll(2, TimeUnit.SECONDS); }
        @Override public void close() {
            if (!ended.compareAndSet(false, true)) return;
            synchronized (ChatService.this) { subscribers.remove(this); }
            events.clear(); events.offer(ChatResponse.error("CONNECTION_CLOSED", "Kết nối chat đã đóng."));
            // A slow receiver must not block database writes or other chat recipients.
            Thread.ofVirtual().name("chat-disconnect").start(disconnect);
        }
    }
}
