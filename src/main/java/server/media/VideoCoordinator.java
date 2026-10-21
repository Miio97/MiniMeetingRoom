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
        String kind = request.type().startsWith("AUDIO_") ? "AUDIO" : request.type().startsWith("SCREEN_") ? "SCREEN" : "VIDEO";
        switch (request.type()) {
            case "VIDEO_STATE", "AUDIO_STATE", "SCREEN_STATE" -> {
                JsonObject stateBody = envelope("state", member); stateBody.addProperty("kind", kind);
                JsonObject state = engine.control(stateBody);
                for (Owner registered : owners.values()) if (registered.member().roomId().equals(member.roomId())) {
                    try {
                        boolean active = state.getAsJsonObject("sources").has(registered.member().participantId());
                        switch(kind) { case "AUDIO" -> rooms.confirmAudio(registered.connection().id(), active); case "SCREEN" -> rooms.confirmShare(registered.connection().id(), active); default -> rooms.confirmVideo(registered.connection().id(), active); }
                    }
                    catch (RuntimeException ended) { /* Terminal room events revoke those owners. */ }
                }
                JsonObject sources = new JsonObject(); var participants = rooms.getRoom(connection.id(), member.roomId()).participants();
                for (var entry : state.getAsJsonObject("sources").entrySet()) {
                    if (!entry.getKey().equals(member.participantId()) && participants.stream().anyMatch(p -> p.participantId().equals(entry.getKey()))) sources.add(entry.getKey(), entry.getValue());
                }
                JsonObject result = new JsonObject(); result.add("sources", sources); return result;
            }
            case "VIDEO_UNSUBSCRIBE" -> {
                var ids=request.body().getAsJsonArray("transports");
                if(ids==null || ids.size()>6) throw new SignalException("INVALID_REQUEST");
                for(var id:ids) if(!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString() || !id.getAsString().matches("[A-Za-z0-9_-]{1,96}")) throw new SignalException("INVALID_REQUEST");
                var body=envelope("unsubscribe",member);body.addProperty("kind","VIDEO");body.add("transports",ids.deepCopy());return engine.control(body);
            }
            case "VIDEO_STOP", "AUDIO_STOP", "SCREEN_STOP" -> {
                JsonObject stop = envelope("stop", member); stop.addProperty("kind", kind); engine.control(stop); rooms.setMediaRequested(connection.id(), kind, false);
                JsonObject result = new JsonObject(); result.addProperty("ok", true); return result;
            }
            case "VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE" -> {
                JsonObject body = envelope(request.type().endsWith("_PUBLISH") ? "publish" : "subscribe", member);
                body.addProperty("kind", kind); body.addProperty("transport", request.requestId()); body.addProperty("sdp", text(request.body(), "sdp", 110000));
                if (request.type().endsWith("_SUBSCRIBE")) {
                    String target = text(request.body(), "source", 96);
                    if (target.equals(member.participantId()) || rooms.getRoom(connection.id(), member.roomId()).participants().stream().noneMatch(p -> p.participantId().equals(target)))
                        throw new SignalException("CROSS_ROOM_FORBIDDEN");
                    body.addProperty("source", target); body.addProperty("publication", text(request.body(), "publication", 96));
                } else rooms.setMediaRequested(connection.id(), kind, true);
                JsonObject reply;
                try { reply = engine.control(body); }
                catch (Exception failure) { if (request.type().endsWith("_PUBLISH")) rooms.setMediaRequested(connection.id(), kind, false); throw failure; }
                if (connection.isClosed()) { revoke(connection.id()); throw new SignalException("CONNECTION_CLOSED"); }
                MemberAuthorization after = rooms.assertMember(connection.id(), member.roomId());
                if(request.type().endsWith("_PUBLISH") && !requested(after.mediaState(),kind)) {
                    JsonObject stop=envelope("stop",member);stop.addProperty("kind",kind);engine.control(stop);throw new SignalException("MEDIA_CLOSED");
                }
                return reply;
            }
            default -> throw new SignalException("UNKNOWN_OPERATION");
        }
    }
    public void validated(SignalingServer.Connection connection) {
        Owner owner = owners.get(connection.id());
        if (owner == null || closed.get() || !renewing.add(connection.id())) return;
        try { controls.execute(() -> { try { grant(owner); } catch (Exception invalid) { revoke(owner); }
            finally { renewing.remove(connection.id()); } }); }
        catch (RejectedExecutionException busy) { renewing.remove(connection.id()); /* Existing lease retries on the next validation; no new grant is claimed. */ }
    }
    public void roomEvent(RoomEvent event) {
        if (!java.util.Set.of("ROOM_LEFT", "ROOM_CLOSED", "PUBLISH_STOPPED").contains(event.type())) return;
        Owner ended = owners.get(event.recipientConnectionId());
        if (!event.type().equals("PUBLISH_STOPPED") && (ended == null || !ended.member().roomId().equals(event.roomId()))) return;
        try { controls.execute(() -> {
            if (event.type().equals("PUBLISH_STOPPED")) {
                if (event.body() instanceof Map<?, ?> body && body.get("kind") instanceof String kind && java.util.Set.of("VIDEO", "AUDIO", "SCREEN").contains(kind) && body.get("participantId") instanceof String participant)
                    for(Owner owner:owners.values()) if(owner.connection().id().equals(event.recipientConnectionId()) && owner.member().roomId().equals(event.roomId()) && owner.member().participantId().equals(participant)) {
                        try { if(!requested(rooms.assertMember(owner.connection().id(),event.roomId()).mediaState(),kind)) stopParticipant(event.roomId(),participant,kind); }
                        catch(RuntimeException invalid) { revoke(owner); }
                    }
            } else revoke(ended);
        }); } catch (RejectedExecutionException busy) { /* Lease expiry is the bounded fail-closed fallback. */ }
    }
    public void stopParticipant(String roomId, String participant) { stopParticipant(roomId, participant, "VIDEO"); }
    public void stopParticipant(String roomId, String participant, String kind) {
        for (Owner owner : owners.values()) if (owner.member().roomId().equals(roomId) && owner.member().participantId().equals(participant)) {
            try { JsonObject stop = envelope("stop", owner.member()); stop.addProperty("kind", kind); engine.control(stop); }
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
    private static boolean requested(MediaState state,String kind) {
        return switch(kind) { case "AUDIO" -> state.micRequested(); case "SCREEN" -> state.shareRequested(); default -> state.cameraRequested(); };
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
