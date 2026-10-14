package server.signalingapi;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import common.meeting.CreateRoomParams;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import server.meeting.RoomService;

/** Typed room control dispatch. Client identity and host role always come from server state. */
public final class MeetingHandler implements SignalingServer.Handler {
    private static final Gson JSON = new Gson();
    private final RoomService rooms;
    public MeetingHandler(RoomService rooms) { this.rooms = rooms; }

    @Override public SignalMessage handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
        JsonObject body = request.body();
        JsonObject response = new JsonObject();
        String type;
        try {
            switch (request.type()) {
                case "CREATE_ROOM" -> {
                    var params = new CreateRoomParams(text(body, "name", 100), text(body, "pin", 6),
                            integer(body, "capacity"), bool(body, "locked", false));
                    var room = rooms.create(connection.principal(), request.requestId(), params);
                    response.add("room", JSON.toJsonTree(room)); type = "ROOM_CREATED";
                }
                case "LOOKUP_ROOM" -> {
                    var preview = rooms.lookup(connection.principal(), text(body, "joinCode", 20), text(body, "pin", 6));
                    response.add("preview", JSON.toJsonTree(preview)); type = "ROOM_PREVIEW";
                }
                case "JOIN_ROOM" -> {
                    var joined = rooms.join(connection.principal(), connection.id(), connection.generation(), request.requestId(),
                            text(body, "joinCode", 20), text(body, "pin", 6), bool(body, "mic", false), bool(body, "camera", false));
                    response.add("join", JSON.toJsonTree(joined)); type = "ROOM_JOINED";
                }
                case "GET_ROOM" -> {
                    response.add("room", JSON.toJsonTree(rooms.getRoom(connection.id(), request.roomId()))); type = "ROOM_SNAPSHOT";
                }
                case "LEAVE_ROOM" -> {
                    rooms.assertMember(connection.id(), request.roomId()); rooms.leave(connection.id(), "TU_ROI");
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
                    type = "ROOM_SNAPSHOT";
                }
                case "KICK" -> {
                    rooms.assertMember(connection.id(), request.roomId()); rooms.kick(connection.id(), text(body, "targetParticipantId", 96));
                    response.addProperty("ok", true); type = "PARTICIPANT_KICKED";
                }
                case "PUBLISH", "UNPUBLISH", "SUBSCRIBE", "UNSUBSCRIBE", "SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE" -> {
                    rooms.assertMember(connection.id(), request.roomId()); throw new SignalException("MEDIA_NOT_READY");
                }
                default -> throw new SignalException("UNKNOWN_OPERATION");
            }
        } catch (IllegalArgumentException | IllegalStateException invalid) { throw new SignalException("INVALID_REQUEST"); }
        return connection.message(type, request.requestId(), request.roomId(), null, response);
    }

    @Override public void disconnected(SignalingServer.Connection connection, String reason) {
        rooms.leave(connection.id(), "SERVER_SHUTDOWN".equals(reason) ? "PHONG_DONG" : "MAT_KET_NOI");
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
