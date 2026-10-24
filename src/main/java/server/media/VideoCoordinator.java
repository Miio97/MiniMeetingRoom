package server.media;

import com.google.gson.JsonObject;

import common.meeting.*;
import common.signaling.*;

import server.meeting.RoomService;
import server.signalingapi.SignalingServer;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Grants are connection-owned, expire in Go, and are renewed only after authoritative validation.
 */
public final class VideoCoordinator implements AutoCloseable {
    private record Owner(SignalingServer.Connection connection, MemberAuthorization member) {}

    private final RoomService rooms;
    private final VideoEngineClient engine;
    private final ConcurrentHashMap<String, Owner> owners = new ConcurrentHashMap<>();
    private final Object[] permissionLocks =
            java.util.stream.IntStream.range(0, 64).mapToObj(ignored -> new Object()).toArray();
    private final java.util.Set<String> renewing = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> watching = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, JsonObject> lastPushed = new ConcurrentHashMap<>();
    private final ExecutorService sourceWatcher =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("media-source-watch").factory());
    private volatile JsonObject sourceSnapshot;
    private final ThreadPoolExecutor controls;
    private final AtomicBoolean closed = new AtomicBoolean();

    public VideoCoordinator(RoomService rooms, VideoEngineClient engine) {
        this(
                rooms,
                engine,
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(128),
                        Thread.ofPlatform().daemon().name("video-permission-", 0).factory()));
    }

    VideoCoordinator(RoomService rooms, VideoEngineClient engine, ThreadPoolExecutor controls) {
        this.rooms = rooms;
        this.engine = engine;
        this.controls = controls;
        sourceWatcher.execute(this::watchSources);
    }

    private JsonObject envelope(String operation, MemberAuthorization member) {
        JsonObject body = new JsonObject();
        body.addProperty("operation", operation);
        body.addProperty("room", member.roomId());
        body.addProperty("participant", member.participantId());
        body.addProperty("generation", member.generation());
        return body;
    }

    private void grant(Owner owner) throws Exception {
        synchronized (permissionLock(owner.member())) {
            grantLocked(owner);
        }
    }

    private Object permissionLock(MemberAuthorization member) {
        return permissionLocks[
                Math.floorMod(member.participantId().hashCode(), permissionLocks.length)];
    }

    private void grantLocked(Owner owner) throws Exception {
        if (closed.get() || owner.connection().isClosed())
            throw new SignalException("CONNECTION_CLOSED");
        MemberAuthorization live =
                rooms.assertMember(owner.connection().id(), owner.member().roomId());
        long ttl =
                Math.min(
                        15000,
                        java.time.Duration.between(
                                        java.time.Instant.now(),
                                        owner.connection().principal().expiresAt())
                                .toMillis());
        if (ttl < 1 || live.generation() != owner.member().generation())
            throw new SignalException("SESSION_INVALID");
        JsonObject body = envelope("renew", live);
        body.addProperty("ttl_ms", ttl);
        engine.control(body);
        try {
            if (owner.connection().isClosed()) throw new SignalException("CONNECTION_CLOSED");
            rooms.assertMember(owner.connection().id(), live.roomId());
        } catch (Exception ended) {
            engine.control(envelope("revoke", live));
            throw ended;
        }
    }

    public JsonObject handle(SignalingServer.Connection connection, SignalMessage request)
            throws Exception {
        MemberAuthorization member = rooms.assertMember(connection.id(), request.roomId());
        Owner owner = new Owner(connection, member);
        owners.put(connection.id(), owner);
        grant(owner);
        String kind =
                request.type().startsWith("SCREEN_AUDIO_")
                        ? "SCREEN_AUDIO"
                        : request.type().startsWith("AUDIO_")
                                ? "AUDIO"
                                : request.type().startsWith("SCREEN_") ? "SCREEN" : "VIDEO";
        switch (request.type()) {
            case "MEDIA_WATCH" -> {
                watching.add(connection.id());
                JsonObject snapshot = sourceSnapshot;
                if (snapshot == null) snapshot = engine.sources(0);
                JsonObject reply = recipientSnapshot(owner, snapshot);
                lastPushed.put(connection.id(), snapshotContent(reply));
                return reply;
            }
            case "VIDEO_STATE", "AUDIO_STATE", "SCREEN_STATE", "SCREEN_AUDIO_STATE" -> {
                JsonObject stateBody = envelope("state", member);
                stateBody.addProperty("kind", kind);
                JsonObject state = engine.control(stateBody);
                for (Owner registered : owners.values())
                    if (!kind.equals("SCREEN_AUDIO")
                            && registered.member().roomId().equals(member.roomId())) {
                        try {
                            boolean active =
                                    state.getAsJsonObject("sources")
                                            .has(registered.member().participantId());
                            switch (kind) {
                                case "AUDIO" ->
                                        rooms.confirmAudio(registered.connection().id(), active);
                                case "SCREEN" ->
                                        rooms.confirmShare(registered.connection().id(), active);
                                default -> rooms.confirmVideo(registered.connection().id(), active);
                            }
                        } catch (RuntimeException ended) {
                            /* Terminal room events revoke those owners. */
                        }
                    }
                JsonObject sources = new JsonObject();
                var participants = rooms.getRoom(connection.id(), member.roomId()).participants();
                for (var entry : state.getAsJsonObject("sources").entrySet()) {
                    if (!entry.getKey().equals(member.participantId())
                            && participants.stream()
                                    .anyMatch(p -> p.participantId().equals(entry.getKey())))
                        sources.add(entry.getKey(), entry.getValue());
                }
                JsonObject paused = new JsonObject();
                if (state.has("pausedSources"))
                    for (var entry : state.getAsJsonObject("pausedSources").entrySet()) {
                        if (!entry.getKey().equals(member.participantId())
                                && participants.stream()
                                        .anyMatch(p -> p.participantId().equals(entry.getKey())))
                            paused.add(entry.getKey(), entry.getValue());
                    }
                JsonObject result = new JsonObject();
                result.add("sources", sources);
                result.add("pausedSources", paused);
                if (kind.equals("SCREEN")
                        && state.getAsJsonObject("sources").has(member.participantId()))
                    result.add(
                            "publication",
                            state.getAsJsonObject("sources").get(member.participantId()));
                return result;
            }
            case "VIDEO_UNSUBSCRIBE",
                    "AUDIO_UNSUBSCRIBE",
                    "SCREEN_UNSUBSCRIBE",
                    "SCREEN_AUDIO_UNSUBSCRIBE" -> {
                var ids = request.body().getAsJsonArray("transports");
                if (ids == null || ids.size() > (kind.equals("AUDIO") ? 50 : 6))
                    throw new SignalException("INVALID_REQUEST");
                for (var id : ids)
                    if (!id.isJsonPrimitive()
                            || !id.getAsJsonPrimitive().isString()
                            || !id.getAsString().matches("[A-Za-z0-9_-]{1,96}"))
                        throw new SignalException("INVALID_REQUEST");
                var body = envelope("unsubscribe", member);
                body.addProperty("kind", kind);
                body.add("transports", ids.deepCopy());
                return engine.control(body);
            }
            case "VIDEO_STOP", "AUDIO_STOP", "SCREEN_STOP", "SCREEN_AUDIO_STOP" -> {
                JsonObject stop = envelope("stop", member);
                stop.addProperty("kind", kind);
                engine.control(stop);
                if (!kind.equals("SCREEN_AUDIO"))
                    rooms.setMediaRequested(connection.id(), kind, false);
                JsonObject result = new JsonObject();
                result.addProperty("ok", true);
                return result;
            }
            case "VIDEO_PAUSE", "AUDIO_PAUSE", "VIDEO_RESUME", "AUDIO_RESUME" -> {
                boolean resume = request.type().endsWith("_RESUME");
                JsonObject body = envelope(resume ? "resume" : "pause", member);
                body.addProperty("kind", kind);
                body.addProperty("transport", text(request.body(), "transport", 96));
                // Requested intent precedes asynchronous SFU work so a concurrent host stop wins.
                rooms.setMediaRequested(connection.id(), kind, resume);
                JsonObject reply;
                try {
                    reply = engine.control(body);
                } catch (Exception failure) {
                    if (resume) rooms.setMediaRequested(connection.id(), kind, false);
                    throw failure;
                }
                MemberAuthorization after = rooms.assertMember(connection.id(), member.roomId());
                if (after.generation() != member.generation()
                        || connection.isClosed()
                        || resume && !requested(after.mediaState(), kind)) {
                    JsonObject stop = envelope("stop", member);
                    stop.addProperty("kind", kind);
                    engine.control(stop);
                    throw new SignalException("MEDIA_CLOSED");
                }
                return reply;
            }
            case "VIDEO_PUBLISH",
                    "VIDEO_SUBSCRIBE",
                    "AUDIO_PUBLISH",
                    "AUDIO_SUBSCRIBE",
                    "SCREEN_PUBLISH",
                    "SCREEN_SUBSCRIBE",
                    "SCREEN_AUDIO_PUBLISH",
                    "SCREEN_AUDIO_SUBSCRIBE" -> {
                JsonObject body =
                        envelope(
                                request.type().endsWith("_PUBLISH") ? "publish" : "subscribe",
                                member);
                body.addProperty("kind", kind);
                body.addProperty("transport", request.requestId());
                body.addProperty("sdp", text(request.body(), "sdp", 110000));
                if (request.type().endsWith("_SUBSCRIBE")) {
                    String target = text(request.body(), "source", 96);
                    if (target.equals(member.participantId())
                            || rooms
                                    .getRoom(connection.id(), member.roomId())
                                    .participants()
                                    .stream()
                                    .noneMatch(p -> p.participantId().equals(target)))
                        throw new SignalException("CROSS_ROOM_FORBIDDEN");
                    body.addProperty("source", target);
                    body.addProperty("publication", text(request.body(), "publication", 96));
                } else if (kind.equals("SCREEN_AUDIO")) {
                    if (!member.mediaState().shareRequested())
                        throw new SignalException("MEDIA_CLOSED");
                    body.addProperty("publication", text(request.body(), "publication", 96));
                } else rooms.setMediaRequested(connection.id(), kind, true);
                JsonObject reply;
                try {
                    reply = engine.control(body);
                } catch (Exception failure) {
                    if (request.type().endsWith("_PUBLISH") && !kind.equals("SCREEN_AUDIO"))
                        rooms.setMediaRequested(connection.id(), kind, false);
                    throw failure;
                }
                if (connection.isClosed()) {
                    revoke(connection.id());
                    throw new SignalException("CONNECTION_CLOSED");
                }
                MemberAuthorization after = rooms.assertMember(connection.id(), member.roomId());
                if (after.generation() != member.generation()
                        || request.type().endsWith("_PUBLISH")
                                && !requested(after.mediaState(), kind)) {
                    JsonObject stop = envelope("stop", member);
                    stop.addProperty("kind", kind);
                    engine.control(stop);
                    throw new SignalException("MEDIA_CLOSED");
                }
                return reply;
            }
            default -> throw new SignalException("UNKNOWN_OPERATION");
        }
    }

    private static JsonObject emptyKinds() {
        JsonObject kinds = new JsonObject();
        for (String kind : java.util.List.of("VIDEO", "AUDIO", "SCREEN"))
            kinds.add(kind, new JsonObject());
        return kinds;
    }

    private static JsonObject snapshotContent(JsonObject snapshot) {
        JsonObject content = snapshot.deepCopy();
        content.remove("revision");
        return content;
    }

    /** Each push is a complete filtered snapshot, so skipped global revisions are safe. */
    private JsonObject recipientSnapshot(Owner owner, JsonObject snapshot) throws Exception {
        if (owner.connection().isClosed()
                || !owner.connection().principal().expiresAt().isAfter(java.time.Instant.now()))
            throw new SignalException("SESSION_INVALID");
        MemberAuthorization member =
                rooms.assertMember(owner.connection().id(), owner.member().roomId());
        if (member.generation() != owner.member().generation())
            throw new SignalException("MEDIA_CLOSED");
        JsonObject result = new JsonObject();
        result.addProperty("generation", member.generation());
        result.addProperty("revision", snapshot.get("revision").getAsLong());
        result.addProperty("snapshot", true);
        JsonObject active = emptyKinds(), paused = emptyKinds();
        result.add("sources", active);
        result.add("pausedSources", paused);
        JsonObject room = snapshot.getAsJsonObject("rooms").getAsJsonObject(member.roomId());
        if (room == null) return result;
        var participants = rooms.getRoom(owner.connection().id(), member.roomId()).participants();
        for (Owner source : owners.values()) {
            if (!source.member().roomId().equals(member.roomId())
                    || source.member().participantId().equals(member.participantId())) continue;
            String id = source.member().participantId();
            if (participants.stream().noneMatch(p -> p.participantId().equals(id))
                    || !generationMatches(room, source)) continue;
            MemberAuthorization live;
            try {
                live = rooms.assertMember(source.connection().id(), member.roomId());
            } catch (RuntimeException ended) {
                continue;
            }
            if (live.generation() != source.member().generation()) continue;
            for (String kind : java.util.List.of("VIDEO", "AUDIO", "SCREEN")) {
                JsonObject available = room.getAsJsonObject("sources").getAsJsonObject(kind);
                JsonObject retained = room.getAsJsonObject("pausedSources").getAsJsonObject(kind);
                if (available.has(id) && requested(live.mediaState(), kind))
                    active.getAsJsonObject(kind).add(id, available.get(id));
                if (retained.has(id)) paused.getAsJsonObject(kind).add(id, retained.get(id));
            }
        }
        return result;
    }

    private static boolean generationMatches(JsonObject room, Owner owner) {
        JsonObject generations = room.getAsJsonObject("generations");
        return generations != null
                && generations.has(owner.member().participantId())
                && generations.get(owner.member().participantId()).getAsLong()
                        == owner.member().generation();
    }

    private void watchSources() {
        long revision = 0, backoff = 250;
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
            try {
                JsonObject snapshot = engine.sources(revision);
                long next = snapshot.get("revision").getAsLong();
                if (next != revision || sourceSnapshot == null) {
                    sourceSnapshot = snapshot;
                    revision = next;
                    confirmSources(snapshot);
                    for (String id : watching) {
                        Owner owner = owners.get(id);
                        if (owner == null || owner.connection().isClosed()) {
                            watching.remove(id);
                            lastPushed.remove(id);
                            continue;
                        }
                        try {
                            JsonObject body = recipientSnapshot(owner, snapshot);
                            JsonObject content = snapshotContent(body);
                            if (!content.equals(lastPushed.put(id, content)))
                                owner.connection()
                                        .event(
                                                "MEDIA_SOURCES",
                                                owner.member().roomId(),
                                                revision,
                                                body);
                        } catch (RuntimeException | SignalException ended) {
                            watching.remove(id);
                            lastPushed.remove(id);
                        }
                    }
                }
                backoff = 250;
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                if (closed.get()) return;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ended) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(5000, backoff * 2);
            }
        }
    }

    private void confirmSources(JsonObject snapshot) {
        for (Owner owner : owners.values()) {
            try {
                MemberAuthorization live =
                        rooms.assertMember(owner.connection().id(), owner.member().roomId());
                if (live.generation() != owner.member().generation()) continue;
                JsonObject room = snapshot.getAsJsonObject("rooms").getAsJsonObject(live.roomId());
                for (String kind : java.util.List.of("VIDEO", "AUDIO", "SCREEN")) {
                    boolean active =
                            room != null
                                    && generationMatches(room, owner)
                                    && room.getAsJsonObject("sources")
                                            .getAsJsonObject(kind)
                                            .has(live.participantId());
                    switch (kind) {
                        case "AUDIO" -> rooms.confirmAudio(owner.connection().id(), active);
                        case "SCREEN" -> rooms.confirmShare(owner.connection().id(), active);
                        default -> rooms.confirmVideo(owner.connection().id(), active);
                    }
                }
            } catch (RuntimeException ended) {
                /* Room closure/revocation owns cleanup. */
            }
        }
    }

    public void validated(SignalingServer.Connection connection) {
        Owner owner = owners.get(connection.id());
        if (owner == null || closed.get() || !renewing.add(connection.id())) return;
        try {
            controls.execute(
                    () -> {
                        try {
                            grant(owner);
                        } catch (Exception invalid) {
                            revoke(owner);
                        } finally {
                            renewing.remove(connection.id());
                        }
                    });
        } catch (RejectedExecutionException busy) {
            renewing.remove(
                    connection
                            .id()); /* Existing lease retries on the next validation; no new grant is claimed. */
        }
    }

    public void roomEvent(RoomEvent event) {
        if (!java.util.Set.of("ROOM_LEFT", "ROOM_CLOSED", "PUBLISH_STOPPED").contains(event.type()))
            return;
        Owner ended = owners.get(event.recipientConnectionId());
        if (!event.type().equals("PUBLISH_STOPPED")
                && (ended == null || !ended.member().roomId().equals(event.roomId()))) return;
        if (event.type().equals("ROOM_LEFT")
                && event.body() instanceof Map<?, ?> body
                && "DEVICE_REPLACED".equals(body.get("reason"))) {
            // The replacement keeps the participant ID. Retire the old generation before its JOIN
            // reply.
            revoke(ended);
            return;
        }
        try {
            controls.execute(
                    () -> {
                        if (event.type().equals("PUBLISH_STOPPED")) {
                            if (event.body() instanceof Map<?, ?> body
                                    && body.get("kind") instanceof String kind
                                    && java.util.Set.of("VIDEO", "AUDIO", "SCREEN").contains(kind)
                                    && body.get("participantId") instanceof String participant)
                                for (Owner owner : owners.values())
                                    if (owner.connection()
                                                    .id()
                                                    .equals(event.recipientConnectionId())
                                            && owner.member().roomId().equals(event.roomId())
                                            && owner.member().participantId().equals(participant)) {
                                        try {
                                            if (!requested(
                                                    rooms.assertMember(
                                                                    owner.connection().id(),
                                                                    event.roomId())
                                                            .mediaState(),
                                                    kind))
                                                stopParticipant(event.roomId(), participant, kind);
                                        } catch (RuntimeException invalid) {
                                            revoke(owner);
                                        }
                                    }
                        } else revoke(ended);
                    });
        } catch (RejectedExecutionException busy) {
            /* Lease expiry is the bounded fail-closed fallback. */
        }
    }

    public void stopParticipant(String roomId, String participant) {
        stopParticipant(roomId, participant, "VIDEO");
    }

    public void stopParticipant(String roomId, String participant, String kind) {
        for (Owner owner : owners.values())
            if (owner.member().roomId().equals(roomId)
                    && owner.member().participantId().equals(participant)) {
                try {
                    JsonObject stop = envelope("stop", owner.member());
                    stop.addProperty("kind", kind);
                    engine.control(stop);
                } catch (Exception failure) {
                }
            }
    }

    public void revoke(String connectionId) {
        revoke(owners.get(connectionId));
    }

    private void revoke(Owner owner) {
        if (owner == null) return;
        synchronized (permissionLock(owner.member())) {
            revokeLocked(owner);
        }
    }

    private void revokeLocked(Owner owner) {
        // Delayed cleanup belongs to this membership, even when the connection has joined another
        // room run.
        owners.remove(owner.connection().id(), owner);
        if (!owners.containsKey(owner.connection().id())) {
            watching.remove(owner.connection().id());
            lastPushed.remove(owner.connection().id());
        }
        try {
            engine.control(envelope("revoke", owner.member()));
        } catch (Exception failure) {
            System.err.println("MEDIA_REVOKE_FAILED: " + failure.getClass().getSimpleName());
        }
    }

    private static boolean requested(MediaState state, String kind) {
        return switch (kind) {
            case "AUDIO" -> state.micRequested();
            case "SCREEN", "SCREEN_AUDIO" -> state.shareRequested();
            default -> state.cameraRequested();
        };
    }

    private static String text(JsonObject body, String field, int maximum) throws SignalException {
        var value = body.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty()
                || value.getAsString().length() > maximum)
            throw new SignalException("INVALID_REQUEST");
        return value.getAsString();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        sourceWatcher.shutdownNow();
        controls.shutdownNow();
        owners.keySet().forEach(this::revoke);
        watching.clear();
        lastPushed.clear();
    }
}
