package server.media;

import com.google.gson.JsonObject;
import common.diagnostics.Diagnostics;
import common.meeting.*;
import common.signaling.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import server.meeting.RoomService;
import server.signalingapi.SignalingServer;

/** Grants are connection-owned, expire in Go, and are renewed only after authoritative validation. */
public final class VideoCoordinator implements AutoCloseable {
    private record Owner(SignalingServer.Connection connection, MemberAuthorization member) { }
    private final RoomService rooms;
    private final VideoEngineClient engine;
    private final ConcurrentHashMap<String, Owner> owners = new ConcurrentHashMap<>();
    private final java.util.Set<String> renewing = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor controls;
    private final AtomicBoolean closed = new AtomicBoolean();
    public VideoCoordinator(RoomService rooms, VideoEngineClient engine) {
        this(rooms, engine, new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128),
                Thread.ofPlatform().daemon().name("video-permission-", 0).factory()));
    }
    VideoCoordinator(RoomService rooms, VideoEngineClient engine, ThreadPoolExecutor controls) {
        this.rooms = rooms; this.engine = engine; this.controls = controls;
    }
    private JsonObject envelope(String operation, MemberAuthorization member) {
        JsonObject body = new JsonObject(); body.addProperty("operation", operation); body.addProperty("room", member.roomId());
        body.addProperty("participant", member.participantId()); body.addProperty("generation", member.generation()); return body;
    }
    private void grant(Owner owner) throws Exception {
        if (closed.get() || owner.connection().isClosed()) throw new SignalException("CONNECTION_CLOSED");
        MemberAuthorization live = rooms.assertMember(owner.connection().id(), owner.member().roomId());
        long ttl = Math.min(15000, java.time.Duration.between(java.time.Instant.now(), owner.connection().principal().expiresAt()).toMillis());
        if (ttl < 1 || live.generation() != owner.member().generation()) throw new SignalException("SESSION_INVALID");
        JsonObject body = envelope("renew", live); body.addProperty("ttl_ms", ttl); engine.control(body);
        try {
            if (owner.connection().isClosed()) throw new SignalException("CONNECTION_CLOSED");
            rooms.assertMember(owner.connection().id(), live.roomId());
        } catch (Exception ended) { engine.control(envelope("revoke", live)); throw ended; }
    }
    public JsonObject handle(SignalingServer.Connection connection, SignalMessage request) throws Exception {
        MemberAuthorization member = rooms.assertMember(connection.id(), request.roomId());
        Owner owner = new Owner(connection, member); owners.put(connection.id(), owner); grant(owner);
        switch (request.type()) {
            case "VIDEO_STATE" -> {
                JsonObject state = engine.control(envelope("state", member));
                for (Owner registered : owners.values()) if (registered.member().roomId().equals(member.roomId())) {
                    try { rooms.confirmVideo(registered.connection().id(), state.getAsJsonObject("sources").has(registered.member().participantId())); }
                    catch (RuntimeException ended) { /* Terminal room events revoke those owners. */ }
                }
                JsonObject sources = new JsonObject(); var participants = rooms.getRoom(connection.id(), member.roomId()).participants();
                for (var entry : state.getAsJsonObject("sources").entrySet()) {
                    if (!entry.getKey().equals(member.participantId()) && participants.stream().anyMatch(p -> p.participantId().equals(entry.getKey()))) sources.add(entry.getKey(), entry.getValue());
                }
                JsonObject result = new JsonObject(); result.add("sources", sources); return result;
            }
            case "VIDEO_STOP" -> {
                engine.control(envelope("stop", member)); rooms.setMediaState(connection.id(), false, false, false);
                JsonObject result = new JsonObject(); result.addProperty("ok", true); return result;
            }
            case "VIDEO_PUBLISH", "VIDEO_SUBSCRIBE" -> {
                JsonObject body = envelope(request.type().equals("VIDEO_PUBLISH") ? "publish" : "subscribe", member);
                body.addProperty("transport", request.requestId()); body.addProperty("sdp", text(request.body(), "sdp", 110000));
                if (request.type().equals("VIDEO_SUBSCRIBE")) {
                    String target = text(request.body(), "source", 96);
                    if (target.equals(member.participantId()) || rooms.getRoom(connection.id(), member.roomId()).participants().stream().noneMatch(p -> p.participantId().equals(target)))
                        throw new SignalException("CROSS_ROOM_FORBIDDEN");
                    body.addProperty("source", target); body.addProperty("publication", text(request.body(), "publication", 96));
                } else rooms.setMediaState(connection.id(), false, true, false);
                JsonObject reply;
                try { reply = engine.control(body); }
                catch (Exception failure) { if (request.type().equals("VIDEO_PUBLISH")) rooms.setMediaState(connection.id(), false, false, false); throw failure; }
                if (connection.isClosed()) { revoke(connection.id()); throw new SignalException("CONNECTION_CLOSED"); }
                rooms.assertMember(connection.id(), member.roomId()); return reply;
            }
            default -> throw new SignalException("UNKNOWN_OPERATION");
        }
    }
    public void validated(SignalingServer.Connection connection) {
        Owner owner = owners.get(connection.id());
        if (owner == null || closed.get() || !renewing.add(connection.id())) return;
        try { controls.execute(() -> { try { grant(owner); } catch (Exception invalid) { revoke(owner); }
            finally { renewing.remove(connection.id()); } }); }
        catch (RejectedExecutionException busy) { renewing.remove(connection.id()); revoke(owner); }
    }
    public void roomEvent(RoomEvent event) {
        if (!java.util.Set.of("ROOM_LEFT", "ROOM_CLOSED", "PUBLISH_STOPPED").contains(event.type())) return;
        Owner ended = owners.get(event.recipientConnectionId());
        if (!event.type().equals("PUBLISH_STOPPED") && (ended == null || !ended.member().roomId().equals(event.roomId()))) return;
        try { controls.execute(() -> {
            if (event.type().equals("PUBLISH_STOPPED")) {
                if (event.body() instanceof Map<?, ?> body && "VIDEO".equals(body.get("kind")) && body.get("participantId") instanceof String participant)
                    stopParticipant(event.roomId(), participant);
            } else revoke(ended);
        }); } catch (RejectedExecutionException busy) { /* Lease expiry is the bounded fail-closed fallback. */ }
    }
    public void stopParticipant(String roomId, String participant) {
        for (Owner owner : owners.values()) if (owner.member().roomId().equals(roomId) && owner.member().participantId().equals(participant)) {
            try { engine.control(envelope("stop", owner.member())); }
            catch (Exception failure) { Diagnostics.current().failure("video.stop.failed", failure, Map.of("phase", "close")); }
        }
    }
    public void revoke(String connectionId) {
        revoke(owners.get(connectionId));
    }
    private void revoke(Owner owner) {
        if (owner == null) return;
        // Delayed cleanup belongs to this membership, even when the connection has joined another room run.
        owners.remove(owner.connection().id(), owner);
        long start = System.nanoTime();
        try { engine.control(envelope("revoke", owner.member())); }
        catch (Exception failure) { Diagnostics.current().failure("video.revoke.failed", failure, Map.of("phase", "close")); }
        finally { Diagnostics.current().event("video.revoked", Map.of("total_ms", (System.nanoTime() - start) / 1_000_000.0)); }
    }
    private static String text(JsonObject body, String field, int maximum) throws SignalException {
        var value = body.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty() || value.getAsString().length() > maximum) throw new SignalException("INVALID_REQUEST");
        return value.getAsString();
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        controls.shutdownNow(); owners.keySet().forEach(this::revoke);
    }
}
