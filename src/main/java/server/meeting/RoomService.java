package server.meeting;

import common.meeting.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import server.auth.PasswordHasher;
import server.auth.SessionPrincipal;

/** Single-server room authority. Listener must enqueue events without doing socket I/O. */
public final class RoomService implements AutoCloseable {
    private static final int MAX_LIVE_CONNECTIONS = 128;
    private static final int MAX_ROOMS = 128;
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private final Object lock = new Object(), dispatchLock = new Object();
    private final RoomRepository repository;
    private final Clock clock;
    private final String instanceId, epoch;
    private final byte[] receiptKey;
    private final int maxParticipants;
    private final Consumer<RoomEvent> listener;
    private final PasswordHasher pins = new PasswordHasher();
    private final SecureRandom random = new SecureRandom();
    private final Map<String, LiveRoom> rooms = new LinkedHashMap<>();
    private final Map<String, Member> connections = new LinkedHashMap<>();
    private final Map<String, PendingHistory> pendingHistory = new LinkedHashMap<>();
    private final ArrayDeque<List<RoomEvent>> outbound = new ArrayDeque<>();
    private LegacyRoomService legacy;
    private final Map<Long, Long> pendingTransfers = new LinkedHashMap<>();
    public RoomService() { this(server.database.DatabaseConnection::getConnection, Clock.systemUTC()); }
    public RoomService(server.auth.ConnectionProvider connections, Clock clock) {
        this(new RoomRepository(connections), clock, "legacy", new byte[32], 16, event -> { });
        legacy = new LegacyRoomService(connections, clock);
    }
    private ScheduledExecutorService cleanup;
    private boolean started, closed;

    public RoomService(RoomRepository repository, Clock clock, String instanceId, byte[] receiptKey,
            int maxParticipants, Consumer<RoomEvent> listener) {
        this.repository = Objects.requireNonNull(repository); this.clock = Objects.requireNonNull(clock);
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid instance identifier");
        if (receiptKey == null || receiptKey.length < 32) throw new IllegalArgumentException("Receipt key must contain at least 32 bytes");
        if (maxParticipants < 2 || maxParticipants > 250) throw new IllegalArgumentException("Invalid participant policy");
        this.instanceId = instanceId; this.receiptKey = receiptKey.clone(); this.maxParticipants = maxParticipants;
        this.listener = Objects.requireNonNull(listener); this.epoch = opaque("e_");
    }

    /** Preflight never creates tables; reconciliation only touches this instance's marked old rooms. */
    public void start() throws SQLException {
        synchronized (lock) {
            if (started || closed) throw new IllegalStateException("Room service cannot start twice");
            repository.checkSchema(); repository.reconcile(instanceId, epoch, clock.instant()); started = true;
            cleanup = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("room-history-cleanup").factory());
            cleanup.scheduleWithFixedDelay(this::maintenanceTick, 5, 5, TimeUnit.SECONDS);
        }
    }
    public String serverEpoch() { return epoch; }
    public int maxParticipants() { return maxParticipants; }
    public int roomCount() { synchronized (lock) { return rooms.size(); } }
    public int participantCount() { synchronized (lock) { return connections.size(); } }

    public RoomSnapshot create(SessionPrincipal principal, String requestId, CreateRoomParams params) throws SQLException {
        checkPrincipal(principal); requestId(requestId); Objects.requireNonNull(params);
        String name = params.name() == null ? "" : params.name().strip();
        if (name.isEmpty() || name.length() > 100 || params.capacity() < 2 || params.capacity() > maxParticipants) throw new RoomException("INVALID_ROOM");
        validatePin(params.pin());
        byte[] hash = fingerprint("CREATE_ROOM", name, params.pin(), params.capacity(), params.locked());
        String pinHash = emptyPin(params.pin()) ? null : pins.hash(params.pin());
        synchronized (lock) {
            available();
            RoomRepository.CreateResult result = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    result = repository.create(principal, requestId, hash, instanceId, epoch, opaque("r_"), code(),
                            name, pinHash, params.capacity(), params.locked(), rooms.size() < MAX_ROOMS, clock.instant()); break;
                } catch (SQLException failure) {
                    if (!("23505".equals(failure.getSQLState()) || failure.getErrorCode() == 1062) || attempt == 7) throw failure;
                }
            }
            RoomRepository.StoredRoom stored = Objects.requireNonNull(result).room();
            if (stored == null) throw new RoomException("REQUEST_ALREADY_ENDED");
            if (stored.status() == RoomStatus.CLOSED || !epoch.equals(stored.epoch())) return snapshot(new LiveRoom(stored, clock.instant()));
            return snapshot(rooms.computeIfAbsent(stored.roomId(), ignored -> new LiveRoom(stored, clock.instant())));
        }
    }

    public RoomPreview lookup(SessionPrincipal principal, String joinCode, String pin) throws SQLException {
        checkPrincipal(principal);
        RoomRepository.StoredRoom stored = find(joinCode); verifyPin(principal, stored, pin);
        synchronized (lock) {
            available(); LiveRoom room = requireRoom(stored.roomId());
            return new RoomPreview(room.stored.roomId(), room.stored.joinCode(), room.stored.name(), room.stored.status(),
                    room.stored.locked(), room.stored.capacity(), room.members.size());
        }
    }

    public RoomSnapshot reopen(SessionPrincipal principal, String requestId, String joinCode) throws SQLException {
        checkPrincipal(principal); requestId(requestId);
        String identifier = roomIdentifier(joinCode);
        byte[] hash = fingerprint("REOPEN_ROOM", identifier);
        synchronized (lock) {
            available();
            RoomRepository.CreateResult result = repository.reopen(principal, requestId, hash, instanceId,
                    epoch, opaque("r_"), identifier, rooms.size() < MAX_ROOMS,
                    id -> !pendingHistory.containsKey("room:" + id), clock.instant());
            RoomRepository.StoredRoom stored = result.room();
            return snapshot(rooms.computeIfAbsent(stored.roomId(), ignored -> new LiveRoom(stored, clock.instant())));
        }
    }

    public RoomJoinReply join(SessionPrincipal principal, String connectionId, long generation, String requestId,
            String joinCode, String pin, boolean mic, boolean camera) throws SQLException {
        return join(principal, connectionId, generation, requestId, null, joinCode, pin, mic, camera);
    }
    public RoomJoinReply join(SessionPrincipal principal, String connectionId, long generation, String requestId,
            String expectedRoomId, String joinCode, String pin, boolean mic, boolean camera) throws SQLException {
        checkPrincipal(principal); requestId(requestId); connectionId(connectionId);
        if (generation < 1) throw new RoomException("INVALID_GENERATION");
        RoomRepository.StoredRoom stored = find(joinCode); verifyPin(principal, stored, pin);
        byte[] hash = fingerprint("JOIN_ROOM", stored.roomId(), stored.joinCode(), pin, mic, camera);
        RoomJoinReply reply;
        synchronized (lock) {
            available(); eventBudget();
            if (expectedRoomId != null && !expectedRoomId.equals(stored.roomId())) throw new RoomException("STALE_ROOM");
            Member current = connections.get(connectionId);
            if (current != null && (!current.roomId.equals(stored.roomId()) || current.generation != generation
                    || current.principal.sessionId() != principal.sessionId())) throw new RoomException("ALREADY_IN_ROOM");
            if (current == null && connections.size() >= MAX_LIVE_CONNECTIONS) throw new RoomException("SERVER_CAPACITY");
            LiveRoom room = requireRoom(stored.roomId());
            if (current == null) {
                for (Member admitted : connections.values()) if (admitted.principal.userId() == principal.userId())
                    throw new RoomException(admitted.roomId.equals(stored.roomId()) ? "ALREADY_JOINED" : "ALREADY_IN_ROOM");
                for (LiveRoom other : rooms.values()) {
                    Waiting waiting = other.waiting.get(principal.userId());
                    if (waiting != null && (!other.stored.roomId().equals(stored.roomId()) || !waiting.connectionId.equals(connectionId)))
                        throw new RoomException("ALREADY_IN_ROOM");
                }
            }
            if (current == null && room.approval && room.stored.hostId() != principal.userId()) {
                if (room.stored.status() == RoomStatus.READY) throw new RoomException("ROOM_NOT_STARTED");
                if (room.stored.locked()) throw new RoomException("ROOM_LOCKED");
                if (room.members.size() >= room.stored.capacity()) throw new RoomException("ROOM_FULL");
                Waiting waiting = room.waiting.get(principal.userId());
                if (waiting == null || !waiting.principal.tokenHash().equals(principal.tokenHash())) {
                    if (room.waiting.size() >= 20) throw new RoomException("QUEUE_FULL");
                    waiting = new Waiting(principal, connectionId, clock.instant()); room.waiting.put(principal.userId(), waiting);
                }
                waiting.seen = clock.instant();
                if (waiting.rejected) { room.waiting.remove(principal.userId()); throw new RoomException("ADMISSION_REJECTED"); }
                if (!waiting.approved) throw new RoomException("APPROVAL_PENDING");
            }
            RoomRepository.JoinResult result = repository.join(principal, requestId, hash, stored.id(), opaque("p_"), instanceId, epoch, clock.instant());
            if (!result.active() || result.room().status() == RoomStatus.CLOSED) throw new RoomException("REQUEST_ALREADY_ENDED");
            if (result.replay()) {
                if (current == null || !current.participantId.equals(result.participantId())) throw new RoomException("ALREADY_JOINED");
                reply = new RoomJoinReply(current.participantId, snapshot(room));
            } else {
                Member member = new Member(connectionId, generation, principal, stored.roomId(), result.membershipId(),
                        result.participantId(), result.room().hostId() == principal.userId() ? RoomRole.HOST : RoomRole.MEMBER,
                        new MediaState(mic, camera, false, false, false, false));
                List<RoomEvent> events = new ArrayList<>(); room.stored = result.room(); room.revision++;
                room.members.put(member.participantId, member); connections.put(connectionId, member); room.waiting.remove(principal.userId());
                for (Member other : room.members.values()) if (other != member)
                    events.add(event(other, "PARTICIPANT_JOINED", room, Map.of("participant", member.view())));
                enqueue(events);
                reply = new RoomJoinReply(member.participantId, snapshot(room));
            }
        }
        drainEvents(); return reply;
    }

    public RoomSnapshot getRoom(String connectionId, String roomId) {
        synchronized (lock) { available(); Member member = member(connectionId); sameRoom(member, roomId); return snapshot(requireRoom(roomId)); }
    }
    public MemberAuthorization assertMember(String connectionId, String roomId) {
        synchronized (lock) {
            available(); Member member = member(connectionId); sameRoom(member, roomId);
            return new MemberAuthorization(member.roomId, member.participantId, member.generation, member.role, member.media);
        }
    }
    public List<String> roomConnections(String roomId) {
        synchronized (lock) {
            available();
            LiveRoom room = requireRoom(roomId);
            return room.members.values().stream().map(m -> m.connectionId).toList();
        }
    }
    public long storedRoomId(String roomId) {
        synchronized (lock) {
            available();
            return requireRoom(roomId).stored.id();
        }
    }
    public String participantId(String connectionId) {
        synchronized (lock) {
            Member member = connections.get(connectionId);
            return member != null ? member.participantId : null;
        }
    }
    public Long currentStoredRoomIdForConnection(String connectionId) {
        synchronized (lock) {
            Member member = connections.get(connectionId);
            if (member == null) return null;
            LiveRoom room = rooms.get(member.roomId);
            return room != null ? room.stored.id() : null;
        }
    }
    public String currentRoomIdForConnection(String connectionId) {
        synchronized (lock) {
            Member member = connections.get(connectionId);
            return member != null ? member.roomId : null;
        }
    }
    public Clock clock() { return clock; }
    public server.auth.ConnectionProvider connectionProvider() { return repository.connections(); }
    public RoomSnapshot setMediaState(String connectionId, boolean mic, boolean camera, boolean share) {
        RoomSnapshot reply;
        synchronized (lock) {
            available(); eventBudget(); Member member = member(connectionId); LiveRoom room = requireRoom(member.roomId);
            MediaState old = member.media;
            MediaState state = new MediaState(mic, camera, share, mic && old.audioPublishing(), camera && old.videoPublishing(), share && old.sharePublishing());
            if (!state.equals(member.media)) { member.media = state; room.revision++; broadcast(room, "PARTICIPANT_STATE", Map.of("participantId", member.participantId, "mediaState", state)); }
            reply = snapshot(room);
        }
        drainEvents(); return reply;
    }
    /** Only the trusted media adapter confirms RTP publication, never a client body. */
    public void confirmVideo(String connectionId, boolean publishing) {
        synchronized (lock) {
            Member member = member(connectionId); LiveRoom room = requireRoom(member.roomId);
            MediaState old = member.media;
            boolean active = publishing && old.cameraRequested();
            if (old.videoPublishing() == active) return;
            member.media = new MediaState(old.micRequested(), old.cameraRequested(), old.shareRequested(), old.audioPublishing(), active, old.sharePublishing());
            room.revision++; broadcast(room, "PARTICIPANT_STATE", Map.of("participantId", member.participantId, "mediaState", member.media));
        }
        drainEvents();
    }
    public void setMediaRequested(String connectionId, String kind, boolean requested) {
        synchronized (lock) {
            available(); eventBudget(); Member member = member(connectionId); LiveRoom room = requireRoom(member.roomId);
            MediaState o = member.media;
            member.media = switch (kind) {
                case "AUDIO" -> new MediaState(requested, o.cameraRequested(), o.shareRequested(), requested && o.audioPublishing(), o.videoPublishing(), o.sharePublishing());
                case "VIDEO" -> new MediaState(o.micRequested(), requested, o.shareRequested(), o.audioPublishing(), requested && o.videoPublishing(), o.sharePublishing());
                case "SCREEN" -> new MediaState(o.micRequested(), o.cameraRequested(), requested, o.audioPublishing(), o.videoPublishing(), requested && o.sharePublishing());
                default -> throw new RoomException("INVALID_MEDIA_KIND");
            };
            if (!o.equals(member.media)) { room.revision++; broadcast(room,"PARTICIPANT_STATE",Map.of("participantId",member.participantId,"mediaState",member.media)); }
        }
        drainEvents();
    }
    public void confirmAudio(String connectionId, boolean publishing) { confirmKind(connectionId, "AUDIO", publishing); }
    public void confirmShare(String connectionId, boolean publishing) { confirmKind(connectionId, "SCREEN", publishing); }
    private void confirmKind(String connectionId, String kind, boolean publishing) {
        synchronized (lock) {
            available(); Member member = member(connectionId); LiveRoom room = requireRoom(member.roomId); MediaState o = member.media;
            MediaState state = kind.equals("AUDIO")
                ? new MediaState(o.micRequested(),o.cameraRequested(),o.shareRequested(),publishing && o.micRequested(),o.videoPublishing(),o.sharePublishing())
                : new MediaState(o.micRequested(),o.cameraRequested(),o.shareRequested(),o.audioPublishing(),o.videoPublishing(),publishing && o.shareRequested());
            if (state.equals(o)) return;
            member.media=state; room.revision++; broadcast(room,"PARTICIPANT_STATE",Map.of("participantId",member.participantId,"mediaState",state));
        }
        drainEvents();
    }
    public RoomSnapshot setLocked(String connectionId, boolean locked) throws SQLException {
        RoomSnapshot reply;
        synchronized (lock) {
            available(); eventBudget(); Member host = host(connectionId); LiveRoom room = requireRoom(host.roomId);
            if (room.stored.locked() != locked) {
                repository.setLocked(room.stored.id(), locked); room.stored = room.stored.withLock(locked); room.revision++;
                broadcast(room, "ROOM_STATE", Map.of("locked", locked, "status", room.stored.status()));
            }
            reply = snapshot(room);
        }
        drainEvents(); return reply;
    }
    public RoomSnapshot stopPublish(String connectionId, String targetParticipantId, String kind) {
        RoomSnapshot reply;
        synchronized (lock) {
            available(); eventBudget(); Member actor = host(connectionId); LiveRoom room = requireRoom(actor.roomId);
            Member target = room.members.get(targetParticipantId); if (target == null) throw new RoomException("PARTICIPANT_NOT_FOUND");
            MediaState old = target.media;
            target.media = switch (kind == null ? "" : kind) {
                case "AUDIO" -> new MediaState(false, old.cameraRequested(), old.shareRequested(), false, old.videoPublishing(), old.sharePublishing());
                case "VIDEO" -> new MediaState(old.micRequested(), false, old.shareRequested(), old.audioPublishing(), false, old.sharePublishing());
                case "SCREEN" -> new MediaState(old.micRequested(), old.cameraRequested(), false, old.audioPublishing(), old.videoPublishing(), false);
                default -> throw new RoomException("INVALID_MEDIA_KIND");
            };
            room.revision++;
            broadcast(room, "PUBLISH_STOPPED", Map.of("participantId", target.participantId, "kind", kind, "reason", "HOST_STOP"));
            broadcast(room, "PARTICIPANT_STATE", Map.of("participantId", target.participantId, "mediaState", target.media));
            reply = snapshot(room);
        }
        drainEvents(); return reply;
    }
    public void kick(String connectionId, String targetParticipantId) {
        synchronized (lock) {
            available(); eventBudget(); Member actor = host(connectionId); LiveRoom room = requireRoom(actor.roomId);
            Member target = room.members.get(targetParticipantId); if (target == null) throw new RoomException("PARTICIPANT_NOT_FOUND");
            remove(target, "BI_MOI_RA");
        }
        drainEvents();
    }
    public void leave(String connectionId, String reason) {
        synchronized (lock) {
            for (LiveRoom room : rooms.values()) room.waiting.values().removeIf(waiting -> waiting.connectionId.equals(connectionId));
            Member member = connections.get(connectionId); if (member != null) remove(member, normalizeReason(reason));
        }
        drainEvents();
    }
    public void revokeSession(String tokenHash) {
        synchronized (lock) {
            for (LiveRoom room : rooms.values()) room.waiting.values().removeIf(waiting -> waiting.principal.tokenHash().equals(tokenHash));
            List<Member> affected = connections.values().stream().filter(member -> member.principal.tokenHash().equals(tokenHash)).toList();
            for (Member member : affected) if (connections.containsKey(member.connectionId)) remove(member, "MAT_KET_NOI");
        }
        drainEvents();
    }

    private void remove(Member member, String reason) {
        LiveRoom room = rooms.get(member.roomId); if (room == null) { connections.remove(member.connectionId); return; }
        Instant now = clock.instant(); room.revision++;
        boolean closeRoom = room.members.size() == 1 || "PHONG_DONG".equals(reason);
        if (closeRoom) {
            broadcast(room, "ROOM_CLOSED", Map.of("reason", reason));
            for (Member other : room.members.values()) connections.remove(other.connectionId);
            room.members.clear(); room.waiting.clear(); rooms.remove(member.roomId);
            pendingTransfers.remove(room.stored.id());
            persist(new PendingHistory(true, room.stored.id(), room.stored.roomId(), now, "PHONG_DONG"));
        } else {
            room.members.remove(member.participantId); connections.remove(member.connectionId);
            persist(new PendingHistory(false, member.membershipId, null, now, reason));
            if (member.role == RoomRole.HOST) {
                Member next = room.members.values().iterator().next();
                try { repository.transferHost(room.stored.id(), next.principal.userId()); }
                catch (SQLException failure) { pendingTransfers.put(room.stored.id(), next.principal.userId()); }
                room.stored = room.stored.withHost(next.principal.userId()); next.role = RoomRole.HOST;
            }
            List<RoomEvent> events = new ArrayList<>();
            events.add(event(member, "ROOM_LEFT", room, Map.of("reason", reason)));
            for (Member other : room.members.values()) events.add(event(other, "PARTICIPANT_LEFT", room,
                    Map.of("participantId", member.participantId, "reason", reason)));
            enqueue(events);
        }
    }

    private void persist(PendingHistory task) {
        String key = (task.room ? "room:" : "member:") + task.id;
        try { task.run(repository); pendingHistory.remove(key); }
        catch (SQLException failure) { pendingHistory.putIfAbsent(key, task); }
    }
    /** Also exposed for deterministic lifecycle tests without sleeping. */
    public void maintenanceTick() {
        synchronized (lock) {
            if (closed) return;
            var pending = pendingHistory.entrySet().iterator();
            while (pending.hasNext()) {
                try { pending.next().getValue().run(repository); pending.remove(); }
                catch (SQLException failure) { /* Retry remains bounded by admitted room/member counts. */ }
            }
            var transfers = pendingTransfers.entrySet().iterator();
            while (transfers.hasNext()) {
                var transfer = transfers.next();
                try { repository.transferHost(transfer.getKey(), transfer.getValue()); transfers.remove(); }
                catch (SQLException failure) { }
            }
            Instant now = clock.instant();
            for (LiveRoom room : rooms.values()) room.waiting.values().removeIf(waiting -> waiting.seen.isBefore(now.minusSeconds(10)) || !waiting.principal.expiresAt().isAfter(now));
            for (Member member : new ArrayList<>(connections.values())) {
                if (!member.principal.expiresAt().isAfter(now) && connections.containsKey(member.connectionId)) remove(member, "MAT_KET_NOI");
            }
            for (LiveRoom room : new ArrayList<>(rooms.values())) {
                if (room.stored.status() == RoomStatus.READY && !room.readyDeadline.isAfter(now)) {
                    rooms.remove(room.stored.roomId()); persist(new PendingHistory(true, room.stored.id(), room.stored.roomId(), now, "PHONG_DONG"));
                }
            }
        }
        drainEvents();
    }
    @Override public void close() {
        ScheduledExecutorService worker;
        synchronized (lock) {
            if (closed) return; closed = true; worker = cleanup;
            if (legacy != null) { try { legacy.close(); } catch (SQLException failure) { throw new IllegalStateException("Legacy room cleanup failed", failure); } }
            for (LiveRoom room : new ArrayList<>(rooms.values())) {
                room.revision++; broadcast(room, "ROOM_CLOSED", Map.of("reason", "SERVER_SHUTDOWN"));
                persist(new PendingHistory(true, room.stored.id(), room.stored.roomId(), clock.instant(), "PHONG_DONG"));
            }
            connections.clear(); rooms.clear();
        }
        if (worker != null) { worker.shutdownNow(); try { worker.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
        drainEvents(); java.util.Arrays.fill(receiptKey, (byte) 0);
    }

    private RoomRepository.StoredRoom find(String joinCode) throws SQLException {
        if (joinCode == null) throw new RoomException("ROOM_NOT_FOUND");
        String identifier = joinCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (!identifier.matches("MMR-[A-HJ-NP-Z2-9]{16}")) throw new RoomException("ROOM_NOT_FOUND");
        // This process owns live rooms; historical rows never authorize a live join.
        // The committing repository still checks membership/capacity/current DB state.
        synchronized (lock) {
            for (LiveRoom room : rooms.values()) {
                RoomRepository.StoredRoom stored = room.stored;
                if (identifier.equals(stored.joinCode())
                        && stored.status() != RoomStatus.CLOSED && epoch.equals(stored.epoch())) return stored;
            }
        }
        throw new RoomException("ROOM_NOT_FOUND");
    }
    private static String roomIdentifier(String joinCode) {
        if (joinCode == null) throw new RoomException("ROOM_NOT_FOUND");
        String identifier = joinCode.strip().toUpperCase(java.util.Locale.ROOT);
        if (!identifier.matches("MMR-[A-Z2-9]{16}|[0-9]{1,19}")) throw new RoomException("ROOM_NOT_FOUND");
        return identifier;
    }
    private void verifyPin(SessionPrincipal principal, RoomRepository.StoredRoom room, String pin) {
        if (room.hostId() != principal.userId() && room.pinHash() != null && !pins.verify(pin, room.pinHash())) throw new RoomException("WRONG_PIN");
    }
    private void checkPrincipal(SessionPrincipal principal) {
        if (principal == null || !principal.expiresAt().isAfter(clock.instant())) throw new RoomException("SESSION_INVALID");
    }
    private void available() {
        if (!started || closed) throw new RoomException("SERVICE_UNAVAILABLE");
        if (!pendingTransfers.isEmpty()) throw new RoomException("HISTORY_UNAVAILABLE");
        if (pendingHistory.size() >= 256) throw new RoomException("HISTORY_UNAVAILABLE");
    }
    private void eventBudget() { if (outbound.size() >= 383) throw new RoomException("BACKPRESSURE"); }
    private Member member(String connectionId) {
        Member member = connections.get(connectionId); if (member == null) throw new RoomException("NOT_A_MEMBER");
        checkPrincipal(member.principal); return member;
    }
    private Member host(String connectionId) {
        Member member = member(connectionId); if (member.role != RoomRole.HOST) throw new RoomException("HOST_REQUIRED"); return member;
    }
    private static void sameRoom(Member member, String roomId) { if (!member.roomId.equals(roomId)) throw new RoomException("CROSS_ROOM_FORBIDDEN"); }
    private LiveRoom requireRoom(String roomId) {
        LiveRoom room = rooms.get(roomId); if (room == null) throw new RoomException("ROOM_NOT_ACTIVE"); return room;
    }
    private RoomSnapshot snapshot(LiveRoom room) {
        String host = room.members.values().stream().filter(member -> member.role == RoomRole.HOST).map(member -> member.participantId).findFirst().orElse(null);
        RoomRepository.StoredRoom stored = room.stored;
        var collaboration = new common.room.RoomInfo(stored.id(), stored.name(), stored.joinCode(), stored.hostId(), stored.createdAt(),
                switch (stored.status()) { case READY -> "CHO"; case ACTIVE -> "DANG_HOAT_DONG"; case CLOSED -> "DA_DONG"; },
                stored.locked(), stored.capacity(), room.members.size(), room.members.values().stream()
                        .map(member -> new common.room.RoomParticipant(member.principal.userId(), member.principal.displayName(),
                                member.role == RoomRole.HOST ? "CHU_PHONG" : "THANH_VIEN")).toList());
        return new RoomSnapshot(stored.roomId(), stored.joinCode(), stored.name(), stored.status(), stored.locked(),
                stored.capacity(), host, room.revision, room.members.values().stream().map(Member::view).toList(), collaboration);
    }
    private void broadcast(LiveRoom room, String type, Object body) {
        enqueue(room.members.values().stream().map(member -> event(member, type, room, body)).toList());
    }
    private RoomEvent event(Member recipient, String type, LiveRoom room, Object body) {
        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        if (body instanceof Map<?, ?> values) values.forEach((key, value) -> {
            if (key instanceof String name) fields.put(name, value);
        });
        fields.put("room", snapshot(room));
        return new RoomEvent(recipient.connectionId, type, recipient.roomId, room.revision, Map.copyOf(fields));
    }
    private void enqueue(List<RoomEvent> events) {
        if (!events.isEmpty()) {
            // Normal mutations reserve 128 batches for terminal events from all admitted connections.
            if (outbound.size() >= 512) throw new IllegalStateException("Terminal event budget exceeded");
            outbound.addLast(List.copyOf(events));
        }
    }
    private void drainEvents() {
        synchronized (dispatchLock) {
            while (true) {
                List<RoomEvent> events;
                synchronized (lock) { events = outbound.pollFirst(); }
                if (events == null) return;
                for (RoomEvent event : events) {
                    try { listener.accept(event); }
                    catch (RuntimeException deliveryFailure) { /* Listener owns connection backpressure/closure. */ }
                }
            }
        }
    }
    private byte[] fingerprint(Object... fields) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(receiptKey, "HmacSHA256"));
            for (Object field : fields) {
                byte[] value = String.valueOf(field).getBytes(StandardCharsets.UTF_8);
                mac.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array()); mac.update(value);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException failure) { throw new IllegalStateException("Receipt hashing unavailable"); }
    }
    private String opaque(String prefix) {
        byte[] bytes = new byte[16]; random.nextBytes(bytes); return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private String code() {
        StringBuilder code = new StringBuilder("MMR-"); for (int i = 0; i < 16; i++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length()))); return code.toString();
    }
    private static void requestId(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{16,64}")) throw new RoomException("INVALID_REQUEST_ID"); }
    private static void connectionId(String value) { if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}")) throw new RoomException("INVALID_CONNECTION_ID"); }
    private static boolean emptyPin(String pin) { return pin == null || pin.isBlank(); }
    private static void validatePin(String pin) { if (!emptyPin(pin) && !pin.matches("[0-9]{4,6}")) throw new RoomException("INVALID_PIN"); }
    private static String normalizeReason(String reason) {
        return switch (reason == null ? "" : reason) {
            case "TU_ROI", "SELF", "USER_LEFT" -> "TU_ROI";
            case "BI_MOI_RA" -> "BI_MOI_RA";
            case "PHONG_DONG" -> "PHONG_DONG";
            default -> "MAT_KET_NOI";
        };
    }
    private static final class LiveRoom {
        RoomRepository.StoredRoom stored; final Map<String, Member> members = new LinkedHashMap<>();
        final Map<Long, Waiting> waiting = new LinkedHashMap<>(); boolean approval;
        final Instant readyDeadline; long revision;
        LiveRoom(RoomRepository.StoredRoom stored, Instant now) { this.stored = stored; readyDeadline = now.plus(Duration.ofMinutes(10)); }
    }
    private static final class Member {
        final String connectionId, roomId, participantId; final long generation, membershipId;
        final SessionPrincipal principal; RoomRole role; MediaState media;
        Member(String connectionId, long generation, SessionPrincipal principal, String roomId, long membershipId,
                String participantId, RoomRole role, MediaState media) {
            this.connectionId = connectionId; this.generation = generation; this.principal = principal; this.roomId = roomId;
            this.membershipId = membershipId; this.participantId = participantId; this.role = role; this.media = media;
        }
        ParticipantView view() { return new ParticipantView(participantId, principal.displayName(), role, media); }
    }
    private static final class Waiting {
        final SessionPrincipal principal; final String connectionId; Instant seen; boolean approved, rejected;
        Waiting(SessionPrincipal principal, String connectionId, Instant now) { this.principal = principal; this.connectionId = connectionId; seen = now; }
    }
    @FunctionalInterface public interface ChatAction<T> {
        T handle(java.sql.Connection connection, Participant participant) throws SQLException;
    }
    public <T> T chat(String token, long roomId, String ticket, ChatAction<T> action) throws SQLException, common.chat.ChatException {
        if (legacy != null) return legacy.chat(token, roomId, ticket, action::handle);
        SessionPrincipal principal = new server.auth.SessionValidator(repository.connections(), clock).validate(token)
                .orElseThrow(() -> new common.chat.ChatException("SESSION_INVALID", "Phiên đăng nhập không hợp lệ."));
        synchronized (lock) {
            Member member = authorized(principal, roomId, ticket);
            if (member == null) throw new common.chat.ChatException("NOT_IN_ROOM", "Bạn không còn trong phòng hoặc chưa được duyệt.");
            try (var connection = repository.connections().getConnection()) { return action.handle(connection, participant(member)); }
        }
    }
    private Member authorized(SessionPrincipal principal, long roomId, String ticket) {
        if (ticket == null) return null;
        for (Member member : connections.values()) {
            LiveRoom room = rooms.get(member.roomId);
            if (room != null && room.stored.id() == roomId && member.participantId.equals(ticket)
                    && member.principal.sessionId() == principal.sessionId() && member.principal.tokenHash().equals(principal.tokenHash())
                    && member.principal.expiresAt().isAfter(clock.instant())) return member;
        }
        return null;
    }
    private Participant participant(Member member) {
        return new Participant(member.principal.userId(), member.principal.displayName(), member.role == RoomRole.HOST,
                member.media.micRequested(), member.media.cameraRequested());
    }
    private RoomResponse managedView(Member self) {
        LiveRoom room = requireRoom(self.roomId);
        var stored = room.stored;
        return new RoomResponse(true, "OK", "", new RoomView(stored.id(), stored.name(), stored.joinCode(), stored.capacity(), stored.locked(), room.approval, false),
                self.participantId, room.members.values().stream().map(this::participant).toList(),
                self.role == RoomRole.HOST ? room.waiting.values().stream().filter(waiting -> !waiting.rejected && !waiting.approved)
                        .map(waiting -> new Participant(waiting.principal.userId(), waiting.principal.displayName(), false, false, false)).toList() : List.of(), false);
    }
    public RoomResponse handle(RoomRequest request) throws SQLException {
        if (legacy != null) return legacy.handle(request);
        if (request == null || request.action() == null) return RoomResponse.error("INVALID_REQUEST", "Yêu cầu không hợp lệ.");
        SessionPrincipal principal = new server.auth.SessionValidator(repository.connections(), clock).validate(request.token()).orElse(null);
        if (principal == null) return RoomResponse.error("SESSION_INVALID", "Phiên đăng nhập không hợp lệ.");
        RoomResponse response;
        try {
            synchronized (lock) {
                available(); eventBudget(); Member self = authorized(principal, request.roomId(), request.ticket());
                if (self == null) return RoomResponse.error("NOT_IN_ROOM", "Bạn không còn trong phòng hoặc chưa được duyệt.");
                LiveRoom room = requireRoom(self.roomId);
                Member target = room.members.values().stream().filter(member -> member.principal.userId() == request.target()).findFirst().orElse(null);
                switch (request.action()) {
                    case "ROOM_POLL" -> { }
                    case "ROOM_MEDIA" -> {
                        MediaState old = self.media;
                        self.media = new MediaState(request.microphone() == null ? old.micRequested() : request.microphone(),
                                request.camera() == null ? old.cameraRequested() : request.camera(), old.shareRequested(), old.audioPublishing(), old.videoPublishing(), old.sharePublishing());
                        room.revision++; broadcast(room, "PARTICIPANT_STATE", Map.of());
                    }
                    default -> {
                        if (self.role != RoomRole.HOST) throw new RoomException("HOST_REQUIRED");
                        switch (request.action()) {
                            case "ROOM_SETTINGS" -> {
                                if (request.locked() != null) { repository.setLocked(room.stored.id(), request.locked()); room.stored = room.stored.withLock(request.locked()); }
                                if (request.approval() != null) room.approval = request.approval();
                                room.revision++; broadcast(room, "ROOM_STATE", Map.of());
                            }
                            case "ROOM_PENDING_APPROVE", "ROOM_PENDING_REJECT" -> {
                                Waiting waiting = room.waiting.get(request.target());
                                if (waiting == null) throw new RoomException("PARTICIPANT_NOT_FOUND");
                                if (room.stored.locked() && request.action().equals("ROOM_PENDING_APPROVE")) throw new RoomException("ROOM_LOCKED");
                                if (room.members.size() >= room.stored.capacity() && request.action().equals("ROOM_PENDING_APPROVE")) throw new RoomException("ROOM_FULL");
                                if (request.action().equals("ROOM_PENDING_APPROVE") && new server.auth.SessionValidator(repository.connections(), clock).validateHash(waiting.principal.tokenHash()).isEmpty()) {
                                    room.waiting.remove(request.target()); throw new RoomException("SESSION_INVALID");
                                }
                                waiting.approved = request.action().equals("ROOM_PENDING_APPROVE"); waiting.rejected = !waiting.approved;
                            }
                            case "ROOM_CLOSE" -> {
                                remove(self, "PHONG_DONG");
                                response = new RoomResponse(true, "OK", "", new RoomView(request.roomId(), room.stored.name(), room.stored.joinCode(), room.stored.capacity(), room.stored.locked(), room.approval, true), self.participantId, List.of(), List.of(), false);
                                return response;
                            }
                            case "ROOM_TRANSFER", "ROOM_KICK", "ROOM_MIC_OFF", "ROOM_CAMERA_OFF" -> {
                                if (target == null || target == self) throw new RoomException("PARTICIPANT_NOT_FOUND");
                                if (request.action().equals("ROOM_TRANSFER")) {
                                    repository.transferHost(room.stored.id(), target.principal.userId());
                                    self.role = RoomRole.MEMBER; target.role = RoomRole.HOST; room.stored = room.stored.withHost(target.principal.userId());
                                    room.revision++; broadcast(room, "ROOM_STATE", Map.of());
                                } else if (request.action().equals("ROOM_KICK")) remove(target, "BI_MOI_RA");
                                else {
                                    String kind = request.action().equals("ROOM_MIC_OFF") ? "AUDIO" : "VIDEO";
                                    MediaState old = target.media;
                                    target.media = kind.equals("AUDIO") ? new MediaState(false, old.cameraRequested(), old.shareRequested(), false, old.videoPublishing(), old.sharePublishing())
                                            : new MediaState(old.micRequested(), false, old.shareRequested(), old.audioPublishing(), false, old.sharePublishing());
                                    room.revision++; broadcast(room, "PUBLISH_STOPPED", Map.of("participantId", target.participantId, "kind", kind, "reason", "HOST_STOP"));
                                }
                            }
                            default -> throw new RoomException("INVALID_REQUEST");
                        }
                    }
                }
                response = managedView(self);
            }
            return response;
        } catch (RoomException error) { return RoomResponse.error(error.code(), controlError(error.code())); }
        finally { drainEvents(); }
    }
    private static String controlError(String code) {
        return switch (code) {
            case "HOST_REQUIRED" -> "Chỉ chủ phòng được thực hiện thao tác này.";
            case "ROOM_LOCKED" -> "Mở khóa phòng trước khi duyệt.";
            case "ROOM_FULL" -> "Phòng đã đủ người.";
            case "PARTICIPANT_NOT_FOUND" -> "Thành viên hoặc yêu cầu chờ không còn tồn tại.";
            case "SESSION_INVALID" -> "Phiên đăng nhập không còn hợp lệ.";
            case "HISTORY_UNAVAILABLE" -> "Đang đồng bộ trạng thái phòng. Vui lòng thử lại.";
            default -> "Thao tác chưa hoàn tất. Vui lòng thử lại.";
        };
    }
    private record PendingHistory(boolean room, long id, String roomOpaqueId, Instant at, String reason) {
        void run(RoomRepository repository) throws SQLException {
            if (room) repository.closeRoom(id, roomOpaqueId, at, reason); else repository.leave(id, at, reason);
        }
    }
}
