package server.signalingapi;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.meeting.CreateRoomParams;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import server.auth.ConnectionProvider;
import server.meeting.RoomService;

/** Typed room control dispatch. Client identity and host role always come from server state. */
public final class MeetingHandler implements SignalingServer.Handler, AutoCloseable {
    private static final Gson JSON = new Gson();
    private final RoomService rooms;
    private final server.media.VideoCoordinator video;
    private final ConnectionProvider connections;
    private volatile ChatDispatcher dispatcher;
    private final ConcurrentHashMap<String, ChatRateLimiter> chatLimits = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor chatExecutor = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256),
            Thread.ofPlatform().daemon().name("meeting-chat-", 0).factory());

    @FunctionalInterface
    public interface ChatDispatcher {
        void sendChat(String connectionId, String roomId, JsonObject body);
    }

    private static final class ChatRateLimiter {
        private final double capacity = 10.0;
        private final double refillRate = 5.0; // 5 per second
        private double tokens = 10.0;
        private long lastNanos = System.nanoTime();

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            double elapsed = (now - lastNanos) / 1_000_000_000.0;
            lastNanos = now;
            tokens = Math.min(capacity, tokens + elapsed * refillRate);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private record SavedChatMessage(long id, Instant sentAt) { }
    private record HistoryChatMessage(long id, long userId, String displayName, String content, Instant sentAt) { }

    public MeetingHandler(RoomService rooms) { this(rooms, null, null, null); }
    public MeetingHandler(RoomService rooms, server.media.VideoCoordinator video) { this(rooms, video, null, null); }
    public MeetingHandler(RoomService rooms, server.media.VideoCoordinator video, ConnectionProvider connections, ChatDispatcher dispatcher) {
        this.rooms = rooms;
        this.video = video;
        this.connections = connections != null ? connections : rooms.connectionProvider();
        this.dispatcher = dispatcher;
    }

    public void setDispatcher(ChatDispatcher dispatcher) { this.dispatcher = dispatcher; }
    public void bindSignaling(SignalingServer signaling) {
        this.dispatcher = (connId, rId, body) -> signaling.event(connId, "CHAT_MESSAGE", rId, null, body);
    }
    @Override public void bindServer(SignalingServer server) {
        bindSignaling(server);
    }

    @Override public SignalMessage handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
        JsonObject body = request.body();
        JsonObject response = new JsonObject();
        String type;
        try {
            switch (request.type()) {
                case "VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "VIDEO_STOP", "VIDEO_STATE", "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "AUDIO_STOP", "AUDIO_STATE", "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE", "SCREEN_STOP", "SCREEN_STATE" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    if (video == null) throw new SignalException("MEDIA_NOT_READY");
                    response = video.handle(connection, request); type = request.type().substring(0, request.type().indexOf('_')) + "_REPLY";
                }
                case "CHAT_SEND" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    ChatRateLimiter limiter = chatLimits.computeIfAbsent(connection.id(), ignored -> new ChatRateLimiter());
                    if (!limiter.tryConsume()) throw new SignalException("RATE_LIMIT");
                    String raw = text(body, "content", 4000);
                    String content = raw.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "").strip();
                    if (content.isEmpty() || content.length() > 2000) throw new SignalException("INVALID_REQUEST");
                    long storedRoomId = rooms.storedRoomId(request.roomId());
                    var principal = connection.principal();
                    long userId = principal.userId();
                    String displayName = principal.displayName();
                    String participantId = rooms.participantId(connection.id());
                    CompletableFuture<SavedChatMessage> future = new CompletableFuture<>();
                    try {
                        chatExecutor.execute(() -> {
                            try {
                                future.complete(insertChatMessage(storedRoomId, userId, content));
                            } catch (Exception failure) { future.completeExceptionally(failure); }
                        });
                    } catch (RejectedExecutionException full) {
                        throw new SignalException("CHAT_BUSY");
                    }
                    SavedChatMessage saved;
                    try { saved = future.get(5, TimeUnit.SECONDS); }
                    catch (TimeoutException timeout) { throw new SignalException("CHAT_BUSY"); }
                    catch (ExecutionException failure) { throw new SignalException("CHAT_FAILED"); }
                    JsonObject eventBody = new JsonObject();
                    eventBody.addProperty("id", saved.id());
                    eventBody.addProperty("participantId", participantId);
                    eventBody.addProperty("userId", userId);
                    eventBody.addProperty("displayName", displayName);
                    eventBody.addProperty("content", content);
                    eventBody.addProperty("sentAt", saved.sentAt().toString());
                    if (dispatcher != null) {
                        for (String connId : rooms.roomConnections(request.roomId())) {
                            dispatcher.sendChat(connId, request.roomId(), eventBody);
                        }
                    }
                    response.addProperty("ok", true);
                    response.addProperty("id", saved.id());
                    response.addProperty("sentAt", saved.sentAt().toString());
                    type = "CHAT_SENT";
                }
                case "CHAT_HISTORY" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    Long beforeId = body.has("beforeId") && !body.get("beforeId").isJsonNull() ? body.get("beforeId").getAsLong() : null;
                    int limit = body.has("limit") && !body.get("limit").isJsonNull() ? Math.min(100, Math.max(1, body.get("limit").getAsInt())) : 50;
                    long storedRoomId = rooms.storedRoomId(request.roomId());
                    CompletableFuture<List<HistoryChatMessage>> future = new CompletableFuture<>();
                    try {
                        chatExecutor.execute(() -> {
                            try {
                                future.complete(queryChatHistory(storedRoomId, beforeId, limit));
                            } catch (Exception failure) { future.completeExceptionally(failure); }
                        });
                    } catch (RejectedExecutionException full) {
                        throw new SignalException("CHAT_BUSY");
                    }
                    List<HistoryChatMessage> history;
                    try { history = future.get(5, TimeUnit.SECONDS); }
                    catch (TimeoutException timeout) { throw new SignalException("CHAT_BUSY"); }
                    catch (ExecutionException failure) { throw new SignalException("CHAT_FAILED"); }
                    JsonArray array = new JsonArray();
                    for (HistoryChatMessage item : history) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("id", item.id());
                        msg.addProperty("userId", item.userId());
                        msg.addProperty("displayName", item.displayName());
                        msg.addProperty("content", item.content());
                        msg.addProperty("sentAt", item.sentAt().toString());
                        array.add(msg);
                    }
                    response.add("messages", array);
                    type = "CHAT_HISTORY_RESULT";
                }
                case "CREATE_ROOM" -> {
                    var params = new CreateRoomParams(text(body, "name", 100), text(body, "pin", 6),
                            integer(body, "capacity"), bool(body, "locked", false));
                    var room = rooms.create(connection.principal(), request.requestId(), params);
                    response.add("room", JSON.toJsonTree(room)); type = "ROOM_CREATED";
                }
                case "REOPEN_ROOM" -> {
                    var room = rooms.reopen(connection.principal(), request.requestId(), text(body, "joinCode", 20));
                    response.add("room", JSON.toJsonTree(room)); type = "ROOM_REOPENED";
                }
                case "LOOKUP_ROOM" -> {
                    var preview = rooms.lookup(connection.principal(), text(body, "joinCode", 64), text(body, "pin", 6));
                    response.add("preview", JSON.toJsonTree(preview)); type = "ROOM_PREVIEW";
                }
                case "JOIN_ROOM" -> {
                    if (request.roomId() == null) throw new SignalException("INVALID_REQUEST");
                    var joined = rooms.join(connection.principal(), connection.id(), connection.generation(), request.requestId(),
                            request.roomId(), text(body, "joinCode", 64), text(body, "pin", 6), bool(body, "mic", false), bool(body, "camera", false));
                    response.add("join", JSON.toJsonTree(joined)); type = "ROOM_JOINED";
                }
                case "GET_ROOM" -> {
                    response.add("room", JSON.toJsonTree(rooms.getRoom(connection.id(), request.roomId()))); type = "ROOM_SNAPSHOT";
                }
                case "LEAVE_ROOM" -> {
                    rooms.assertMember(connection.id(), request.roomId()); rooms.leave(connection.id(), "TU_ROI");
                    if (video != null) video.revoke(connection.id());
                    response.addProperty("ok", true); type = "ROOM_LEFT";
                }
                case "SET_MEDIA_STATE" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    response.add("room", JSON.toJsonTree(rooms.setMediaState(connection.id(),
                            bool(body, "mic", false), bool(body, "camera", false), bool(body, "share", false))));
                    type = "ROOM_SNAPSHOT";
                }
                case "SET_LOCKED" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    response.add("room", JSON.toJsonTree(rooms.setLocked(connection.id(), bool(body, "locked", false))));
                    type = "ROOM_SNAPSHOT";
                }
                case "STOP_PUBLISH" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    response.add("room", JSON.toJsonTree(rooms.stopPublish(connection.id(), text(body, "targetParticipantId", 96), text(body, "kind", 12))));
                    if (video != null) video.stopParticipant(request.roomId(), text(body, "targetParticipantId", 96), text(body, "kind", 12));
                    type = "ROOM_SNAPSHOT";
                }
                case "KICK" -> {
                    rooms.assertMember(connection.id(), request.roomId()); rooms.kick(connection.id(), text(body, "targetParticipantId", 96));
                    response.add("room", JSON.toJsonTree(rooms.getRoom(connection.id(), request.roomId())));
                    response.addProperty("ok", true); type = "PARTICIPANT_KICKED";
                }
                case "PUBLISH", "UNPUBLISH", "SUBSCRIBE", "UNSUBSCRIBE", "SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE" -> {
                    rooms.assertMember(connection.id(), request.roomId()); throw new SignalException("MEDIA_NOT_READY");
                }
                default -> throw new SignalException("UNKNOWN_OPERATION");
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) { throw new SignalException("INVALID_REQUEST"); }
        common.diagnostics.Diagnostics.current().event("meeting.state", java.util.Map.of(
                "room_count", rooms.roomCount(), "participant_count", rooms.participantCount()));
        return connection.message(type, request.requestId(), request.roomId(), null, response);
    }

    private SavedChatMessage insertChatMessage(long roomId, long userId, String content) throws SQLException {
        Instant now = rooms.clock().instant();
        String sql = "INSERT INTO Tin_Nhan (Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Noi_Dung, Ngay_Gui) VALUES (?, ?, 'VAN_BAN', ?, ?)";
        try (var conn = connections.getConnection();
             var ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, roomId);
            ps.setLong(2, userId);
            ps.setString(3, content);
            ps.setTimestamp(4, Timestamp.from(now));
            ps.executeUpdate();
            try (var rs = ps.getGeneratedKeys()) {
                if (rs.next()) return new SavedChatMessage(rs.getLong(1), now);
            }
        }
        throw new SQLException("Failed to retrieve generated message key");
    }

    private List<HistoryChatMessage> queryChatHistory(long roomId, Long beforeId, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT t.Ma_Tin_Nhan, t.Ma_Nguoi_Gui, u.Ten_Hien_Thi, t.Noi_Dung, t.Ngay_Gui " +
                "FROM Tin_Nhan t " +
                "JOIN Nguoi_Dung u ON t.Ma_Nguoi_Gui = u.Ma_Nguoi_Dung " +
                "WHERE t.Ma_Phong = ? AND t.Ngay_Xoa IS NULL AND t.Loai_Tin_Nhan != 'TEP' "
        );
        if (beforeId != null) sql.append("AND t.Ma_Tin_Nhan < ? ");
        sql.append("ORDER BY t.Ma_Tin_Nhan DESC LIMIT ?");
        List<HistoryChatMessage> list = new ArrayList<>();
        try (var conn = connections.getConnection();
             var ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            ps.setLong(idx++, roomId);
            if (beforeId != null) ps.setLong(idx++, beforeId);
            ps.setInt(idx, limit);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    long userId = rs.getLong(2);
                    String displayName = rs.getString(3);
                    String content = rs.getString(4);
                    Instant sentAt = rs.getTimestamp(5).toInstant();
                    list.add(new HistoryChatMessage(id, userId, displayName, content, sentAt));
                }
            }
        }
        Collections.reverse(list);
        return list;
    }

    @Override public void disconnected(SignalingServer.Connection connection, String reason) {
        chatLimits.remove(connection.id());
        if (video != null) video.revoke(connection.id());
        rooms.leave(connection.id(), "SERVER_SHUTDOWN".equals(reason) ? "PHONG_DONG" : "MAT_KET_NOI");
        common.diagnostics.Diagnostics.current().event("meeting.state", java.util.Map.of(
                "room_count", rooms.roomCount(), "participant_count", rooms.participantCount()));
    }
    @Override public void validated(SignalingServer.Connection connection) { if (video != null) video.validated(connection); }

    @Override public void close() {
        chatExecutor.shutdownNow();
        try { chatExecutor.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static String text(JsonObject body, String field, int maximum) throws SignalException {
        var value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().length() > maximum) throw new SignalException("INVALID_REQUEST");
        return value.getAsString();
    }

    private static int integer(JsonObject body, String field) throws SignalException {
        var value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new SignalException("INVALID_REQUEST");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException bad) { throw new SignalException("INVALID_REQUEST"); }
    }

    private static boolean bool(JsonObject body, String field, boolean fallback) throws SignalException {
        var value = body.get(field);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new SignalException("INVALID_REQUEST");
        return value.getAsBoolean();
    }
}
