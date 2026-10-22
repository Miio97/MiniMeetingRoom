package server.signalingapi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import common.meeting.CreateRoomParams;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
    private final RoomChatStore chat=new RoomChatStore();
    private final Runnable removeChatListener;

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

    public MeetingHandler(RoomService rooms) {this(rooms,null,null,null);}
    public MeetingHandler(RoomService rooms,server.media.VideoCoordinator video) {this(rooms,video,null,null);}
    public MeetingHandler(RoomService rooms,server.media.VideoCoordinator video,ConnectionProvider connections,ChatDispatcher dispatcher) {
        this.rooms=rooms;this.video=video;this.connections=connections!=null?connections:rooms.connectionProvider();this.dispatcher=dispatcher;this.catalog=new server.meeting.RoomCatalog(this.connections);
        this.removeChatListener=rooms.onLiveRoomClosed(chat::removeRoom);
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
                case "CHAT_STATUS" -> {return handleAsync(connection,request).toCompletableFuture().getNow(null);}
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

    @Override public java.util.concurrent.CompletionStage<SignalMessage> handleAsync(SignalingServer.Connection connection,SignalMessage request) throws Exception {
        if(!java.util.Set.of("CHAT_SEND","CHAT_HISTORY","CHAT_STATUS").contains(request.type()))
            return SignalingServer.Handler.super.handleAsync(connection,request);
        // Membership, chat mutation and enqueue are fenced against leave/kick/close, entirely in RAM.
        var reply=rooms.withChatMember(connection.id(),request.roomId(),() -> {
            if(connection.isClosed() || connection.principal()==null || !connection.principal().expiresAt().isAfter(rooms.clock().instant()))
                throw new SignalException("SESSION_INVALID");
            var principal=connection.principal();var body=new JsonObject();String type;
            switch(request.type()) {
                case "CHAT_HISTORY" -> {
                    Long before=null;
                    if(request.body().has("beforeId") && !request.body().get("beforeId").isJsonNull()) {
                        var value=request.body().get("beforeId");
                        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new SignalException("INVALID_REQUEST");
                        try {before=value.getAsBigDecimal().longValueExact();} catch(ArithmeticException invalid) {throw new SignalException("INVALID_REQUEST");}
                    }
                    int limit=request.body().has("limit")?Math.min(100,Math.max(1,integer(request.body(),"limit"))):50;
                    body.add("messages",chat.history(request.roomId(),before,limit));type="CHAT_HISTORY_RESULT";
                }
                case "CHAT_STATUS" -> {
                    var value=request.body().get("clientMessageIds");
                    if(value==null || !value.isJsonArray() || value.getAsJsonArray().size()>8) throw new SignalException("INVALID_REQUEST");
                    var ids=value.getAsJsonArray();
                    for(var id:ids) if(!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().matches("[A-Za-z0-9_-]{1,64}")) throw new SignalException("INVALID_REQUEST");
                    body.add("messages",chat.status(request.roomId(),principal.sessionId(),ids));type="CHAT_STATUS_RESULT";
                }
                default -> {
                    String content=text(request.body(),"content",4000).replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","").strip();
                    if(content.isEmpty() || content.length()>2000) throw new SignalException("INVALID_REQUEST");
                    String nonce=request.body().has("clientMessageId")?text(request.body(),"clientMessageId",64):request.requestId();
                    if(nonce==null || !nonce.matches("[A-Za-z0-9_-]{1,64}")) throw new SignalException("INVALID_REQUEST");
                    body=chat.send(request.roomId(),principal.sessionId(),nonce,content,principal.userId(),principal.displayName(),rooms.clock().instant(),
                            () -> chatLimits.computeIfAbsent(connection.id(),id -> new ChatRateLimiter()).tryConsume(),message -> {
                                if(dispatcher!=null) for(String target:rooms.roomConnections(request.roomId())) dispatcher.sendChat(target,request.roomId(),message);
                            });
                    type="CHAT_SENT";
                }
            }
            return connection.message(type,request.requestId(),request.roomId(),null,body);
        });
        return CompletableFuture.completedFuture(reply);
    }
    int chatRoomCount() {return chat.roomCount();}
    int chatReceiptCount() {return chat.receiptCount();}

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
        chat.close();removeChatListener.run();chatLimits.clear();
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
