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
    private final server.meeting.RoomCatalog catalog;
    public void catalogChanged() {catalog.invalidate();}
    public void cleanupStatus(java.util.function.LongPredicate status) {catalog.cleanupStatus(status);}
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
    public MeetingHandler(RoomService rooms) {this(rooms,null,null,null);}
    public MeetingHandler(RoomService rooms,server.media.VideoCoordinator video) {this(rooms,video,null,null);}
    public MeetingHandler(RoomService rooms,server.media.VideoCoordinator video,ConnectionProvider connections,ChatDispatcher dispatcher) {
        this.rooms=rooms;this.video=video;this.connections=connections!=null?connections:rooms.connectionProvider();this.dispatcher=dispatcher;this.catalog=new server.meeting.RoomCatalog(this.connections);
    }

    public void setDispatcher(ChatDispatcher dispatcher) { this.dispatcher = dispatcher; }
    public void bindSignaling(SignalingServer signaling) {
        catalog.bind(signaling);
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
                case "ROOM_CATALOG_WATCH" -> {
                    var focus=new java.util.HashSet<Long>();
                    if(body.has("roomIds")) {
                        var ids=body.getAsJsonArray("roomIds");if(ids.size()>100) throw new SignalException("INVALID_REQUEST");
                        for(var id:ids) {if(!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isNumber()) throw new SignalException("INVALID_REQUEST");try {focus.add(id.getAsBigDecimal().longValueExact());} catch(ArithmeticException invalid) {throw new SignalException("INVALID_REQUEST");}}
                    }
                    response=catalog.subscribe(connection,body.has("limit")?integer(body,"limit"):20,body.has("watchId")?text(body,"watchId",64):request.requestId(),focus);type="ROOM_CATALOG_SNAPSHOT";
                }
                case "ROOM_CATALOG_UNWATCH" -> {catalog.unsubscribe(connection.id());response.addProperty("ok",true);type="ROOM_CATALOG_UNWATCHED";}
                case "VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "VIDEO_UNSUBSCRIBE", "VIDEO_STOP", "VIDEO_STATE", "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "AUDIO_STOP", "AUDIO_STATE", "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE", "SCREEN_STOP", "SCREEN_STATE" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    if (video == null) throw new SignalException("MEDIA_NOT_READY");
                    response = video.handle(connection, request); type = request.type().substring(0, request.type().indexOf('_')) + "_REPLY";
                }
                case "CHAT_SEND", "CHAT_HISTORY" -> {return handleAsync(connection,request).toCompletableFuture().get(15,TimeUnit.SECONDS);}
                case "CHAT_STATUS" -> {
                    rooms.assertMember(connection.id(),request.roomId());
                    var ids=request.body().getAsJsonArray("clientMessageIds");
                    if(ids==null || ids.size()>8) throw new SignalException("INVALID_REQUEST");
                    var saved=new JsonArray();
                    for(var id:ids) {
                        if(!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().matches("[A-Za-z0-9_-]{1,64}")) throw new SignalException("INVALID_REQUEST");
                        var receipt=receipts.get(connection.principal().sessionId()+":"+request.roomId()+":"+id.getAsString());
                        if(receipt!=null && receipt.result().isDone() && !receipt.result().isCompletedExceptionally()) saved.add(receipt.result().getNow(null).body().deepCopy());
                    }
                    response.add("messages",saved);type="CHAT_STATUS_RESULT";
                }
                case "NETWORK_STATS" -> {
                    // Transitional ACK for older clients. No queue, sample parsing, persistence or retention task.
                    response.addProperty("ok",true);response.addProperty("dropped",true);response.addProperty("retired",true);
                    type="NETWORK_STATS_ACK";
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
                case "ADMISSION_STATUS" -> {
                    if (request.roomId() == null) throw new SignalException("INVALID_REQUEST");
                    var joined = rooms.admissionStatus(connection.principal(), connection.id(), connection.generation(), request.roomId());
                    response.add("join", JSON.toJsonTree(joined)); type = "ROOM_JOINED";
                }
                case "GET_ROOM" -> {
                    response.add("room", JSON.toJsonTree(rooms.getRoom(connection.id(), request.roomId()))); type = "ROOM_SNAPSHOT";
                }
                case "RESUME_ROOM" -> {
                    var resumed = rooms.resume(connection.principal(), connection.id(), connection.generation(),
                            request.roomId(), text(body, "participantId", 96));
                    if (video != null && !connection.id().equals(resumed.previousConnectionId())) video.revoke(resumed.previousConnectionId());
                    response.add("join", JSON.toJsonTree(resumed.join())); type = "ROOM_RESUMED";
                }
                case "LEAVE_ROOM" -> {
                    rooms.assertMember(connection.id(), request.roomId());
                    if (body.has("closeRoom")) rooms.leaveByChoice(connection.id(), request.roomId(),
                            body.has("successorParticipantId") ? text(body, "successorParticipantId", 96) : null, bool(body, "closeRoom", false));
                    else rooms.leave(connection.id(), "TU_ROI");
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
        if(java.util.Set.of("CREATE_ROOM","REOPEN_ROOM","JOIN_ROOM","ADMISSION_STATUS","LEAVE_ROOM","SET_LOCKED","KICK").contains(request.type())) catalog.invalidate();
        return connection.message(type, request.requestId(), request.roomId(), null, response);
    }

    private final server.meeting.RoomWorkQueue chatWrites=new server.meeting.RoomWorkQueue("chat-room",2,128);
    private record ChatReceipt(String content,CompletableFuture<SignalMessage> result,long at) { }
    private final ConcurrentHashMap<String,ChatReceipt> receipts=new ConcurrentHashMap<>();
    private final java.util.Set<CompletableFuture<SignalMessage>> historyJobs=ConcurrentHashMap.newKeySet();
    @Override public java.util.concurrent.CompletionStage<SignalMessage> handleAsync(SignalingServer.Connection connection,SignalMessage request) throws Exception {
        if(!request.type().equals("CHAT_SEND") && !request.type().equals("CHAT_HISTORY")) return SignalingServer.Handler.super.handleAsync(connection,request);
        rooms.assertMember(connection.id(),request.roomId());
        long storedRoom=rooms.storedRoomId(request.roomId());var principal=connection.principal();
        if(request.type().equals("CHAT_HISTORY")) {
            var result=new CompletableFuture<SignalMessage>();
            historyJobs.add(result);result.whenComplete((v,e) -> historyJobs.remove(result));
            try {chatExecutor.execute(() -> {try {
                rooms.assertMember(connection.id(),request.roomId());
                Long before=request.body().has("beforeId") && !request.body().get("beforeId").isJsonNull()?request.body().get("beforeId").getAsLong():null;
                int limit=request.body().has("limit")?Math.min(100,Math.max(1,request.body().get("limit").getAsInt())):50;
                var history=queryChatHistory(storedRoom,before,limit);
                rooms.assertMember(connection.id(),request.roomId());
                JsonArray array=new JsonArray();for(var item:history) {var value=new JsonObject();value.addProperty("id",item.id());value.addProperty("userId",item.userId());value.addProperty("displayName",item.displayName());value.addProperty("content",item.content());value.addProperty("sentAt",item.sentAt().toString());array.add(value);}
                var body=new JsonObject();body.add("messages",array);result.complete(connection.message("CHAT_HISTORY_RESULT",request.requestId(),request.roomId(),null,body));
            } catch(Exception failure) {result.completeExceptionally(failure);}});}
            catch(RejectedExecutionException full) {result.completeExceptionally(new SignalException("CHAT_BUSY"));}return result;
        }
        String content=text(request.body(),"content",4000).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","").strip();
        if(content.isEmpty() || content.length()>2000) throw new SignalException("INVALID_REQUEST");
        String nonce=request.body().has("clientMessageId")?text(request.body(),"clientMessageId",64):request.requestId();
        if(nonce==null || !nonce.matches("[A-Za-z0-9_-]{1,64}")) throw new SignalException("INVALID_REQUEST");
        long now=System.nanoTime();receipts.entrySet().removeIf(e -> e.getValue().result().isDone() && now-e.getValue().at()>TimeUnit.MINUTES.toNanos(10));
        String key=principal.sessionId()+":"+request.roomId()+":"+nonce;
        var receipt=new ChatReceipt(content,new CompletableFuture<>(),now);var existing=receipts.putIfAbsent(key,receipt);
        if(existing!=null) {if(!existing.content().equals(content)) throw new SignalException("REQUEST_ID_REUSED");return existing.result().thenApply(reply -> connection.message(reply.type(),request.requestId(),request.roomId(),null,reply.body()));}
        if(receipts.size()>1024) {receipts.remove(key,receipt);throw new SignalException("CHAT_BUSY");}
        if(!chatLimits.computeIfAbsent(connection.id(),id -> new ChatRateLimiter()).tryConsume()) {receipts.remove(key,receipt);throw new SignalException("RATE_LIMIT");}
        try {chatWrites.execute(request.roomId(),() -> {
            try {
                rooms.assertMember(connection.id(),request.roomId());
                var saved=insertChatMessage(storedRoom,principal,request.roomId(),content);
                var body=new JsonObject();body.addProperty("id",saved.id());body.addProperty("clientMessageId",nonce);body.addProperty("userId",principal.userId());body.addProperty("displayName",principal.displayName());body.addProperty("content",content);body.addProperty("sentAt",saved.sentAt().toString());
                // Closing may win just after commit; never deliver old content into a new generation.
                try {rooms.assertMember(connection.id(),request.roomId());if(dispatcher!=null) for(String target:rooms.roomConnections(request.roomId())) dispatcher.sendChat(target,request.roomId(),body);} catch(server.meeting.RoomException ended) { }
                var ack=new JsonObject();ack.addProperty("ok",true);ack.addProperty("id",saved.id());ack.addProperty("clientMessageId",nonce);ack.addProperty("sentAt",saved.sentAt().toString());
                receipt.result().complete(connection.message("CHAT_SENT",request.requestId(),request.roomId(),null,ack));
            } catch(Exception failure) {
                if(failure instanceof SignalException signal && !signal.code().equals("CHAT_OUTCOME_UNKNOWN")) receipts.remove(key,receipt);
                receipt.result().completeExceptionally(failure);
            }
        });} catch(RejectedExecutionException full) {receipts.remove(key,receipt);throw new SignalException("CHAT_BUSY");}
        return receipt.result();
    }
    private SavedChatMessage insertChatMessage(long roomId,server.auth.SessionPrincipal principal,String opaque,String content) throws Exception {
        Instant now=rooms.clock().instant();boolean committing=false;
        try(var conn=connections.getConnection()) {
            conn.setTransactionIsolation(java.sql.Connection.TRANSACTION_READ_COMMITTED);conn.setAutoCommit(false);
            try {
                try(var query=conn.prepareStatement("SELECT p.Token_Bam FROM Phien_Dang_Nhap p JOIN Nguoi_Dung n ON n.Ma_Nguoi_Dung=p.Ma_Nguoi_Dung WHERE p.Ma_Phien=? AND p.Thu_Hoi_Luc IS NULL AND p.Het_Han_Luc>? AND n.Trang_Thai='HOAT_DONG' FOR UPDATE")) {
                    query.setLong(1,principal.sessionId());query.setTimestamp(2,Timestamp.from(now));try(var rows=query.executeQuery()) {if(!rows.next() || !principal.tokenHash().equals(rows.getString(1))) throw new SignalException("SESSION_INVALID");}
                }
                try(var query=conn.prepareStatement("SELECT x.Room_Opaque_ID,p.Trang_Thai FROM Phong_Hop p JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong WHERE p.Ma_Phong=? FOR UPDATE")) {
                    query.setLong(1,roomId);try(var rows=query.executeQuery()) {if(!rows.next() || !opaque.equals(rows.getString(1)) || !java.util.Set.of("CHO","DANG_HOAT_DONG").contains(rows.getString(2))) throw new SignalException("STALE_ROOM");}
                }
                try(var query=conn.prepareStatement("SELECT Ma_Thanh_Vien_Phong FROM Thanh_Vien_Phong WHERE Ma_Phong=? AND Ma_Nguoi_Dung=? AND Roi_Phong_Luc IS NULL FOR UPDATE")) {
                    query.setLong(1,roomId);query.setLong(2,principal.userId());try(var rows=query.executeQuery()) {if(!rows.next()) throw new SignalException("NOT_A_MEMBER");}
                }
                long id;
                try(var query=conn.prepareStatement("INSERT INTO Tin_Nhan (Ma_Phong,Ma_Nguoi_Gui,Loai_Tin_Nhan,Noi_Dung,Ngay_Gui) VALUES (?,?,'VAN_BAN',?,?)",Statement.RETURN_GENERATED_KEYS)) {
                    query.setLong(1,roomId);query.setLong(2,principal.userId());query.setString(3,content);query.setTimestamp(4,Timestamp.from(now));query.executeUpdate();
                    try(var keys=query.getGeneratedKeys()) {if(!keys.next()) throw new SQLException("CHAT_KEY_MISSING");id=keys.getLong(1);}
                }
                committing=true;conn.commit();return new SavedChatMessage(id,now);
            } catch(Exception failure) {try {conn.rollback();} catch(SQLException ignored) {}if(committing) throw new SignalException("CHAT_OUTCOME_UNKNOWN");throw failure;}
        } catch(SQLException failure) {throw new SignalException(committing?"CHAT_OUTCOME_UNKNOWN":"DATABASE_UNAVAILABLE");}
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
        catalog.unsubscribe(connection.id());catalog.invalidate();
        chatLimits.remove(connection.id());
        if (video != null) video.revoke(connection.id());
        if (java.util.Set.of("MAT_KET_NOI", "REQUEST_TIMEOUT").contains(reason)) rooms.suspend(connection.id());
        else rooms.leave(connection.id(), "SERVER_SHUTDOWN".equals(reason) ? "PHONG_DONG" : "MAT_KET_NOI");
        common.diagnostics.Diagnostics.current().event("meeting.state", java.util.Map.of(
                "room_count", rooms.roomCount(), "participant_count", rooms.participantCount()));
    }
    @Override public void validated(SignalingServer.Connection connection) { if (video != null) video.validated(connection); }

    @Override public void close() {
        catalog.close();
        receipts.values().forEach(receipt -> receipt.result().completeExceptionally(new SignalException("CONNECTION_CLOSED")));
        historyJobs.forEach(job -> job.completeExceptionally(new SignalException("CONNECTION_CLOSED")));historyJobs.clear();
        chatWrites.close();receipts.clear();chatExecutor.shutdownNow();
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
