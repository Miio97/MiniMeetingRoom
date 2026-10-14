package client.meeting;

import client.signalingapi.SignalingClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import common.meeting.*;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One owner across lobby/prejoin/meeting. Views borrow properties; only this owner closes transport. */
public final class MeetingSession implements AutoCloseable {
    public enum State { READY, CONNECTING, CONNECTED, DISCONNECTED, CLOSED }
    private static final Gson JSON = new Gson();
    private final String token;
    private final Instant expiresAt;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("meeting-client-owner").factory());
    private final AtomicBoolean closed = new AtomicBoolean(), refreshing = new AtomicBoolean(), fxScheduled = new AtomicBoolean();
    private final AtomicReference<Update> latest = new AtomicReference<>();
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.READY);
    private final ReadOnlyObjectWrapper<RoomSnapshot> room = new ReadOnlyObjectWrapper<>();
    private final ObservableList<ParticipantView> participants = FXCollections.observableArrayList();
    private final ReadOnlyStringWrapper notice = new ReadOnlyStringWrapper("Phòng và thành viên kết nối máy chủ; media chưa sẵn sàng.");
    private volatile SignalingClient client;
    private volatile RoomSnapshot snapshot;
    private volatile String self;
    private volatile RoomPreview preview;
    private volatile String pin = "";
    private volatile long joinedAt;
    private State currentState = State.READY;
    private Intent createIntent, joinIntent;

    public MeetingSession(String token, Instant expiresAt) {
        this.token = Objects.requireNonNull(token); this.expiresAt = Objects.requireNonNull(expiresAt);
    }
    public ReadOnlyObjectProperty<State> stateProperty() { return state.getReadOnlyProperty(); }
    public ReadOnlyObjectProperty<RoomSnapshot> roomProperty() { return room.getReadOnlyProperty(); }
    public ReadOnlyStringProperty noticeProperty() { return notice.getReadOnlyProperty(); }
    public ObservableList<ParticipantView> participants() { return FXCollections.unmodifiableObservableList(participants); }
    public String selfParticipantId() { return self; }
    public RoomPreview preview() { return preview; }
    public RoomSnapshot snapshot() { return snapshot; }
    public long elapsedSeconds() { return joinedAt == 0 ? 0 : (System.nanoTime() - joinedAt) / 1_000_000_000; }
    public boolean isHost() { RoomSnapshot value = snapshot; return value != null && Objects.equals(self, value.hostParticipantId()); }

    public void prepare(RoomPreview value, String pin) { this.preview = Objects.requireNonNull(value); this.pin = pin == null ? "" : pin; }

    public CompletableFuture<Void> ensureConnected() { return submit(() -> { connect(); return null; }); }
    public CompletableFuture<RoomPreview> create(CreateRoomParams params) {
        return submit(() -> {
            JsonObject body = JSON.toJsonTree(params).getAsJsonObject();
            if (createIntent == null || !createIntent.body().equals(body)) createIntent = new Intent(UUID.randomUUID().toString(), body.deepCopy());
            RoomSnapshot created = decode(command("CREATE_ROOM", null, body, createIntent.id()), "room", RoomSnapshot.class);
            createIntent = null;
            preview = new RoomPreview(created.roomId(), created.joinCode(), created.name(), created.status(), created.locked(), created.capacity(), created.participants().size());
            pin = ""; return preview;
        });
    }
    public CompletableFuture<RoomPreview> lookup(String code, String suppliedPin) {
        return submit(() -> {
            JsonObject body = new JsonObject(); body.addProperty("joinCode", code); body.addProperty("pin", suppliedPin);
            RoomPreview found = decode(command("LOOKUP_ROOM", null, body, UUID.randomUUID().toString()), "preview", RoomPreview.class);
            prepare(found, suppliedPin); return found;
        });
    }
    public CompletableFuture<RoomSnapshot> join(boolean mic, boolean camera) {
        return submit(() -> {
            RoomPreview target = Objects.requireNonNull(preview, "ROOM_REQUIRED");
            JsonObject body = new JsonObject(); body.addProperty("joinCode", target.joinCode()); body.addProperty("pin", pin);
            body.addProperty("mic", mic); body.addProperty("camera", camera);
            if (joinIntent == null || !joinIntent.body().equals(body)) joinIntent = new Intent(UUID.randomUUID().toString(), body.deepCopy());
            RoomJoinReply joined;
            try { joined = decode(command("JOIN_ROOM", target.roomId(), body, joinIntent.id()), "join", RoomJoinReply.class); }
            catch (Exception failure) {
                if (failure instanceof SignalException signal && "REQUEST_ALREADY_ENDED".equals(signal.code())) joinIntent = null;
                throw failure;
            }
            self = joined.selfParticipantId(); snapshot = joined.room(); joinedAt = System.nanoTime(); joinIntent = null;
            publish(State.CONNECTED, "Thành viên đã đồng bộ; âm thanh và hình ảnh chưa kết nối."); return snapshot;
        });
    }
    public CompletableFuture<RoomSnapshot> refresh() { return submit(this::refreshNow); }
    private RoomSnapshot refreshNow() throws Exception {
        RoomSnapshot before = snapshot;
        if (before == null) return null;
        RoomSnapshot value = decode(command("GET_ROOM", before.roomId(), new JsonObject(), UUID.randomUUID().toString()), "room", RoomSnapshot.class);
        if (snapshot != null && value.roomId().equals(snapshot.roomId()) && value.revision() >= snapshot.revision()) {
            snapshot = value; publish(State.CONNECTED, "Thành viên đã đồng bộ; âm thanh và hình ảnh chưa kết nối.");
        }
        return snapshot;
    }
    public CompletableFuture<RoomSnapshot> mediaState(boolean mic, boolean camera) {
        JsonObject body = new JsonObject(); body.addProperty("mic", mic); body.addProperty("camera", camera); body.addProperty("share", false);
        return mutate("SET_MEDIA_STATE", body);
    }
    public CompletableFuture<RoomSnapshot> lock(boolean value) { JsonObject body = new JsonObject(); body.addProperty("locked", value); return mutate("SET_LOCKED", body); }
    public CompletableFuture<RoomSnapshot> stopPublish(String participant, String kind) {
        JsonObject body = new JsonObject(); body.addProperty("targetParticipantId", participant); body.addProperty("kind", kind); return mutate("STOP_PUBLISH", body);
    }
    public CompletableFuture<Void> kick(String participant) {
        return submit(() -> { JsonObject body = new JsonObject(); body.addProperty("targetParticipantId", participant);
            command("KICK", requireRoom().roomId(), body, UUID.randomUUID().toString()); refreshNow(); return null; });
    }
    private CompletableFuture<RoomSnapshot> mutate(String type, JsonObject body) {
        return submit(() -> { RoomSnapshot value = decode(command(type, requireRoom().roomId(), body, UUID.randomUUID().toString()), "room", RoomSnapshot.class);
            snapshot = value; publish(State.CONNECTED, "Trạng thái được máy chủ xác nhận; chưa phát media."); return value; });
    }
    public CompletableFuture<Void> leave() {
        return submit(() -> {
            try { if (snapshot != null) command("LEAVE_ROOM", snapshot.roomId(), new JsonObject(), UUID.randomUUID().toString()); }
            finally { snapshot = null; self = null; joinIntent = null; joinedAt = 0; closeTransport(); publish(State.READY, "Đã rời phòng."); }
            return null;
        });
    }
    private RoomSnapshot requireRoom() throws SignalException { if (snapshot == null) throw new SignalException("ROOM_REQUIRED"); return snapshot; }
    private void connect() throws Exception {
        if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
        if (!expiresAt.isAfter(Instant.now())) throw new SignalException("SESSION_INVALID");
        if (client != null && client.state() == SignalingClient.State.CONNECTED) return;
        publish(State.CONNECTING, "Đang kết nối phòng họp…");
        SignalingClient connection = new SignalingClient(this::event); client = connection;
        try { connection.connect(token); publish(State.CONNECTED, "Đã kết nối máy chủ phòng họp."); }
        catch (Exception failure) { connection.close(); publish(State.DISCONNECTED, "Không thể kết nối máy chủ phòng họp. Có thể thử lại."); throw failure; }
    }
    private SignalMessage command(String type, String roomId, JsonObject body, String requestId) throws Exception {
        connect();
        try { return client.request(type, roomId, body, requestId).get(16, TimeUnit.SECONDS); }
        catch (ExecutionException failure) { if (failure.getCause() instanceof Exception cause) throw cause; throw failure; }
    }
    private static <T> T decode(SignalMessage response, String field, Class<T> type) throws SignalException {
        if (!response.body().has(field)) throw new SignalException("INVALID_RESPONSE");
        try { return Objects.requireNonNull(JSON.fromJson(response.body().get(field), type)); }
        catch (RuntimeException failure) { throw new SignalException("INVALID_RESPONSE"); }
    }
    private void event(SignalMessage message) {
        SignalingClient connection = client;
        if (closed.get() || connection == null || !Objects.equals(message.connectionId(), connection.connectionId())
                || message.generation() != connection.generation()) return;
        if ("CONNECTION_CLOSED".equals(message.type()) || "SESSION_ENDED".equals(message.type())) {
            snapshot = null; self = null; publish(State.DISCONNECTED, "Kết nối phòng họp đã kết thúc. Không tự bật lại media."); return;
        }
        RoomSnapshot active = snapshot;
        if (active == null || !Objects.equals(active.roomId(), message.roomId())) return;
        if ("ROOM_CLOSED".equals(message.type()) || "ROOM_LEFT".equals(message.type())) {
            snapshot = null; self = null; publish(State.CONNECTED, "Bạn đã rời phòng hoặc phòng đã đóng."); return;
        }
        if (message.revision() != null && message.revision() <= active.revision()) return;
        if (refreshing.compareAndSet(false, true)) refresh().whenComplete((value, failure) -> refreshing.set(false));
    }
    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed.get()) { future.completeExceptionally(new SignalException("CONNECTION_CLOSED")); return future; }
        try { worker.execute(() -> { try { future.complete(task.call()); } catch (Exception failure) { future.completeExceptionally(failure); } }); }
        catch (RejectedExecutionException failure) { future.completeExceptionally(new SignalException("CLIENT_BUSY")); }
        return future;
    }
    private synchronized void publish(State value, String text) {
        currentState = closed.get() ? State.CLOSED : value;
        latest.set(new Update(currentState, snapshot, text));
        scheduleFx();
    }
    private void scheduleFx() {
        if (!fxScheduled.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            Update value = latest.getAndSet(null);
            if (value != null) { state.set(value.state()); room.set(value.room()); participants.setAll(value.room() == null ? java.util.List.of() : value.room().participants()); notice.set(value.notice()); }
            fxScheduled.set(false);
            if (latest.get() != null) scheduleFx();
        });
    }
    private void closeTransport() { SignalingClient current = client; client = null; if (current != null) current.close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeTransport(); worker.shutdownNow(); snapshot = null; self = null; pin = ""; createIntent = null; joinIntent = null;
        publish(State.CLOSED, "Session đã đóng.");
    }
    private record Intent(String id, JsonObject body) { @Override public String toString() { return "Intent[redacted]"; } }
    private record Update(State state, RoomSnapshot room, String notice) { }
}
