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

    public RoomJoinReply join(SessionPrincipal principal, String connectionId, long generation, String requestId,
            String joinCode, String pin, boolean mic, boolean camera) throws SQLException {
        checkPrincipal(principal); requestId(requestId); connectionId(connectionId);
        if (generation < 1) throw new RoomException("INVALID_GENERATION");
        RoomRepository.StoredRoom stored = find(joinCode); verifyPin(principal, stored, pin);
        byte[] hash = fingerprint("JOIN_ROOM", stored.joinCode(), pin, mic, camera);
        RoomJoinReply reply;
        synchronized (lock) {
            available(); eventBudget();
            Member current = connections.get(connectionId);
            if (current != null && (!current.roomId.equals(stored.roomId()) || current.generation != generation
                    || current.principal.sessionId() != principal.sessionId())) throw new RoomException("ALREADY_IN_ROOM");
            if (current == null && connections.size() >= MAX_LIVE_CONNECTIONS) throw new RoomException("SERVER_CAPACITY");
            LiveRoom room = requireRoom(stored.roomId());
            RoomRepository.JoinResult result = repository.join(principal, requestId, hash, stored.id(), opaque("p_"), instanceId, epoch, clock.instant());
            if (!result.active() || result.room().status() == RoomStatus.CLOSED) throw new RoomException("REQUEST_ALREADY_ENDED");
            if (result.replay()) {
                if (current == null || !current.participantId.equals(result.participantId())) throw new RoomException("ALREADY_JOINED");
                reply = new RoomJoinReply(current.participantId, snapshot(room));
            } else {
                Member member = new Member(connectionId, generation, principal, stored.roomId(), result.membershipId(),
                        result.participantId(), stored.hostId() == principal.userId() ? RoomRole.HOST : RoomRole.MEMBER,
                        new MediaState(mic, camera, false, false, false, false));
                List<RoomEvent> events = new ArrayList<>(); room.stored = result.room(); room.revision++;
                for (Member other : room.members.values()) events.add(event(other, "PARTICIPANT_JOINED", room, Map.of("participant", member.view())));
                room.members.put(member.participantId, member); connections.put(connectionId, member); enqueue(events);
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
    public RoomSnapshot setMediaState(String connectionId, boolean mic, boolean camera, boolean share) {
        RoomSnapshot reply;
        synchronized (lock) {
            available(); eventBudget(); Member member = member(connectionId); LiveRoom room = requireRoom(member.roomId);
            MediaState state = new MediaState(mic, camera, share, false, false, false);
            if (!state.equals(member.media)) { member.media = state; room.revision++; broadcast(room, "PARTICIPANT_STATE", Map.of("participantId", member.participantId, "mediaState", state)); }
            reply = snapshot(room);
        }
        drainEvents(); return reply;
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
            Member member = connections.get(connectionId); if (member != null) remove(member, normalizeReason(reason));
        }
        drainEvents();
    }
    public void revokeSession(String tokenHash) {
        synchronized (lock) {
            List<Member> affected = connections.values().stream().filter(member -> member.principal.tokenHash().equals(tokenHash)).toList();
            for (Member member : affected) if (connections.containsKey(member.connectionId)) remove(member, "MAT_KET_NOI");
        }
        drainEvents();
    }

    private void remove(Member member, String reason) {
        LiveRoom room = rooms.get(member.roomId); if (room == null) { connections.remove(member.connectionId); return; }
        Instant now = clock.instant(); room.revision++;
        if (member.role == RoomRole.HOST) {
            broadcast(room, "ROOM_CLOSED", Map.of("reason", reason));
            for (Member other : room.members.values()) connections.remove(other.connectionId);
            room.members.clear(); rooms.remove(member.roomId);
            persist(new PendingHistory(true, room.stored.id(), now, "PHONG_DONG"));
        } else {
            room.members.remove(member.participantId); connections.remove(member.connectionId);
            List<RoomEvent> events = new ArrayList<>();
            events.add(event(member, "ROOM_LEFT", room, Map.of("reason", reason)));
            for (Member other : room.members.values()) events.add(event(other, "PARTICIPANT_LEFT", room,
                    Map.of("participantId", member.participantId, "reason", reason)));
            enqueue(events); persist(new PendingHistory(false, member.membershipId, now, reason));
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
            Instant now = clock.instant();
            for (Member member : new ArrayList<>(connections.values())) {
                if (!member.principal.expiresAt().isAfter(now) && connections.containsKey(member.connectionId)) remove(member, "MAT_KET_NOI");
            }
            for (LiveRoom room : new ArrayList<>(rooms.values())) {
                if (room.stored.status() == RoomStatus.READY && !room.readyDeadline.isAfter(now)) {
                    rooms.remove(room.stored.roomId()); persist(new PendingHistory(true, room.stored.id(), now, "PHONG_DONG"));
                }
            }
        }
        drainEvents();
    }
    @Override public void close() {
        ScheduledExecutorService worker;
        synchronized (lock) {
            if (closed) return; closed = true; worker = cleanup;
            for (LiveRoom room : new ArrayList<>(rooms.values())) {
                room.revision++; broadcast(room, "ROOM_CLOSED", Map.of("reason", "SERVER_SHUTDOWN"));
                persist(new PendingHistory(true, room.stored.id(), clock.instant(), "PHONG_DONG"));
            }
            connections.clear(); rooms.clear();
        }
        if (worker != null) { worker.shutdownNow(); try { worker.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
        drainEvents(); java.util.Arrays.fill(receiptKey, (byte) 0);
    }

    private RoomRepository.StoredRoom find(String joinCode) throws SQLException {
        if (joinCode == null || !joinCode.matches("MMR-[A-Z2-9]{16}")) throw new RoomException("ROOM_NOT_FOUND");
        RoomRepository.StoredRoom stored = repository.findByCode(instanceId, joinCode);
        if (stored == null || stored.status() == RoomStatus.CLOSED || !epoch.equals(stored.epoch())) throw new RoomException("ROOM_NOT_FOUND");
        return stored;
    }
    private void verifyPin(SessionPrincipal principal, RoomRepository.StoredRoom room, String pin) {
        if (room.hostId() != principal.userId() && room.pinHash() != null && !pins.verify(pin, room.pinHash())) throw new RoomException("WRONG_PIN");
    }
    private void checkPrincipal(SessionPrincipal principal) {
        if (principal == null || !principal.expiresAt().isAfter(clock.instant())) throw new RoomException("SESSION_INVALID");
    }
    private void available() {
        if (!started || closed) throw new RoomException("SERVICE_UNAVAILABLE");
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
        return new RoomSnapshot(stored.roomId(), stored.joinCode(), stored.name(), stored.status(), stored.locked(),
                stored.capacity(), host, room.revision, room.members.values().stream().map(Member::view).toList());
    }
    private void broadcast(LiveRoom room, String type, Object body) {
        enqueue(room.members.values().stream().map(member -> event(member, type, room, body)).toList());
    }
    private static RoomEvent event(Member recipient, String type, LiveRoom room, Object body) {
        return new RoomEvent(recipient.connectionId, type, recipient.roomId, room.revision, body);
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
        final Instant readyDeadline; long revision;
        LiveRoom(RoomRepository.StoredRoom stored, Instant now) { this.stored = stored; readyDeadline = now.plus(Duration.ofMinutes(10)); }
    }
    private static final class Member {
        final String connectionId, roomId, participantId; final long generation, membershipId;
        final SessionPrincipal principal; final RoomRole role; MediaState media;
        Member(String connectionId, long generation, SessionPrincipal principal, String roomId, long membershipId,
                String participantId, RoomRole role, MediaState media) {
            this.connectionId = connectionId; this.generation = generation; this.principal = principal; this.roomId = roomId;
            this.membershipId = membershipId; this.participantId = participantId; this.role = role; this.media = media;
        }
        ParticipantView view() { return new ParticipantView(participantId, principal.displayName(), role, media); }
    }
    private record PendingHistory(boolean room, long id, Instant at, String reason) {
        void run(RoomRepository repository) throws SQLException {
            if (room) repository.closeRoom(id, at, reason); else repository.leave(id, at, reason);
        }
    }
}
