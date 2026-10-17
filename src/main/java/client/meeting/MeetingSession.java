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
import java.util.List;
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
    @FunctionalInterface public interface TransportFactory {
        SignalingClient create(java.util.function.Consumer<SignalMessage> listener) throws java.io.IOException;
    }
    private final TransportFactory transports;
    private final java.util.function.Function<MeetingSession, client.media.VideoCallController> mediaFactory;
    private final Object transportLock = new Object();
    private final java.util.Set<CompletableFuture<?>> jobs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean refreshAgain = new AtomicBoolean();
    private final AtomicReference<RoomSnapshot> incomingRoom = new AtomicReference<>();
    private final AtomicBoolean applyingRoom = new AtomicBoolean();
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
    private volatile client.media.VideoCallController video;
    public CompletableFuture<JsonObject> videoRequest(String type, JsonObject body, String requestId) {
        if (!java.util.Set.of("VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "VIDEO_STATE", "VIDEO_STOP", "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "AUDIO_STATE", "AUDIO_STOP", "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE", "SCREEN_STATE", "SCREEN_STOP").contains(type))
            return CompletableFuture.failedFuture(new SignalException("INVALID_REQUEST"));
        return submit(() -> command(type, requireRoom().roomId(), body, requestId).body());
    }
    public synchronized client.media.VideoCallController video() {
        if (video == null) video = mediaFactory.apply(this);
        return video;
    }
    private synchronized void closeVideo() { if (video != null) { video.close(); video = null; } }
    private volatile RoomPreview preview;
    private volatile String pin = "";
    private volatile long joinedAt;
    private State currentState = State.READY;
    private Intent createIntent, reopenIntent, joinIntent;

    public MeetingSession(String token, Instant expiresAt) {
        this(token, expiresAt, SignalingClient::new);
    }
    public MeetingSession(String token, Instant expiresAt, TransportFactory transports) {
        this(token, expiresAt, transports, session -> new client.media.VideoCallController(session::videoRequest, session::snapshot, session::selfParticipantId));
    }
    public interface ChatListener {
        void onChatMessage(common.chat.ChatMessage message);
        default void onChatCleared() { }
    }
    private final List<ChatListener> chatListeners = new CopyOnWriteArrayList<>();
    public void addChatListener(ChatListener listener) { chatListeners.add(listener); }
    public void removeChatListener(ChatListener listener) { chatListeners.remove(listener); }
    private void clearChat() {
        for (ChatListener listener : chatListeners) {
            listener.onChatCleared();
        }
    }
    public MeetingSession(String token, Instant expiresAt, TransportFactory transports,
                          java.util.function.Function<MeetingSession, client.media.VideoCallController> mediaFactory) {
        this.token = Objects.requireNonNull(token); this.expiresAt = Objects.requireNonNull(expiresAt);
        this.transports = Objects.requireNonNull(transports);
        this.mediaFactory = Objects.requireNonNull(mediaFactory);
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
    public CompletableFuture<RoomPreview> reopen(String identifier) {
        return submit(() -> {
            JsonObject body = new JsonObject(); body.addProperty("joinCode", identifier);
            if (reopenIntent == null || !reopenIntent.body().equals(body)) reopenIntent = new Intent(UUID.randomUUID().toString(), body.deepCopy());
            RoomSnapshot reopened;
            try { reopened = decode(command("REOPEN_ROOM", null, body, reopenIntent.id()), "room", RoomSnapshot.class); }
            catch (Exception failure) {
                if (failure instanceof SignalException signal && java.util.Set.of("REQUEST_ALREADY_ENDED", "REQUEST_EXPIRED").contains(signal.code())) {
                    reopenIntent = null; joinIntent = null;
                }
                throw failure;
            }
            if (preview == null || !Objects.equals(preview.roomId(), reopened.roomId())) joinIntent = null;
            preview = new RoomPreview(reopened.roomId(), reopened.joinCode(), reopened.name(), reopened.status(), reopened.locked(), reopened.capacity(), reopened.participants().size());
            // Keep the request ID until JOIN succeeds, so a failed connection can retry the same reopening.
            pin = ""; return preview;
        });
    }
    public CompletableFuture<RoomSnapshot> join(boolean mic, boolean camera) {
        CompletableFuture<RoomSnapshot> attempt = submit(() -> {
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
            self = joined.selfParticipantId(); snapshot = joined.room(); joinedAt = System.nanoTime(); joinIntent = null; reopenIntent = null;
            pin = "";
            publish(State.CONNECTED, "Thành viên đã đồng bộ; âm thanh và hình ảnh chưa kết nối."); return snapshot;
        });
        return attempt.handle((value, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(value);
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof SignalException signal && "APPROVAL_PENDING".equals(signal.code()) && !closed.get()) {
                publish(State.CONNECTED, "Đang chờ chủ phòng duyệt. Bạn có thể hủy yêu cầu.");
                return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS))
                        .thenCompose(ignored -> join(mic, camera));
            }
            return CompletableFuture.<RoomSnapshot>failedFuture(cause);
        }).thenCompose(value -> value);
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
            SignalMessage reply = command("KICK", requireRoom().roomId(), body, UUID.randomUUID().toString());
            if (reply.body().has("room")) { snapshot = decode(reply, "room", RoomSnapshot.class); publish(State.CONNECTED, "Thành viên đã đồng bộ."); }
            else refreshNow();
            return null; });
    }
    private CompletableFuture<RoomSnapshot> mutate(String type, JsonObject body) {
        return submit(() -> { RoomSnapshot value = decode(command(type, requireRoom().roomId(), body, UUID.randomUUID().toString()), "room", RoomSnapshot.class);
            snapshot = value; publish(State.CONNECTED, "Trạng thái được máy chủ xác nhận; chưa phát media."); return value; });
    }
    public CompletableFuture<Void> sendChat(String content) {
        JsonObject body = new JsonObject(); body.addProperty("content", content);
        return submit(() -> { command("CHAT_SEND", requireRoom().roomId(), body, UUID.randomUUID().toString()); return null; });
    }
    public CompletableFuture<java.util.List<common.chat.ChatMessage>> chatHistory(Long beforeId, Integer limit) {
        JsonObject body = new JsonObject();
        if (beforeId != null) body.addProperty("beforeId", beforeId);
        if (limit != null) body.addProperty("limit", limit);
        return submit(() -> {
            SignalMessage reply = command("CHAT_HISTORY", requireRoom().roomId(), body, UUID.randomUUID().toString());
            java.util.List<common.chat.ChatMessage> list = new java.util.ArrayList<>();
            if (reply.body().has("messages") && reply.body().get("messages").isJsonArray()) {
                var array = reply.body().getAsJsonArray("messages");
                for (var elem : array) {
                    JsonObject obj = elem.getAsJsonObject();
                    long id = obj.get("id").getAsLong();
                    long uId = obj.has("userId") && !obj.get("userId").isJsonNull() ? obj.get("userId").getAsLong() : 0;
                    String name = obj.has("displayName") ? obj.get("displayName").getAsString() : "";
                    String text = obj.has("content") ? obj.get("content").getAsString() : "";
                    String sentAt = obj.has("sentAt") ? obj.get("sentAt").getAsString() : Instant.now().toString();
                    list.add(new common.chat.ChatMessage(id, 0, uId, name, text, sentAt));
                }
            }
            return list;
        });
    }
    public CompletableFuture<Void> leave() {
        closeVideo();
        return submit(() -> {
            try { if (snapshot != null) command("LEAVE_ROOM", snapshot.roomId(), new JsonObject(), UUID.randomUUID().toString()); }
            finally { snapshot = null; self = null; pin = ""; joinIntent = null; reopenIntent = null; joinedAt = 0; closeTransport(); publish(State.READY, "Đã rời phòng."); clearChat(); }
            return null;
        });
    }
    private RoomSnapshot requireRoom() throws SignalException { if (snapshot == null) throw new SignalException("ROOM_REQUIRED"); return snapshot; }
    private void connect() throws Exception {
        if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
        if (!expiresAt.isAfter(Instant.now())) throw new SignalException("SESSION_INVALID");
        if (client != null && client.state() == SignalingClient.State.CONNECTED) return;
        publish(State.CONNECTING, "Đang kết nối phòng họp…");
        SignalingClient connection = transports.create(this::event);
        synchronized (transportLock) {
            if (closed.get()) { connection.close(); throw new SignalException("CONNECTION_CLOSED"); }
            client = connection;
        }
        try { connection.connect(token); publish(State.CONNECTED, "Đã kết nối máy chủ phòng họp."); }
        catch (Exception failure) { connection.close(); publish(State.DISCONNECTED, "Không thể kết nối máy chủ phòng họp. Có thể thử lại."); throw failure; }
    }
    private SignalMessage command(String type, String roomId, JsonObject body, String requestId) throws Exception {
        connect();
        long started = System.nanoTime();
        try { return client.request(type, roomId, body, requestId).get(16, TimeUnit.SECONDS); }
        catch (ExecutionException failure) { if (failure.getCause() instanceof Exception cause) throw cause; throw failure; }
        finally { common.diagnostics.Diagnostics.current().event("signaling.client.request", java.util.Map.of(
                "action", type, "signaling_round_trip_ms", (System.nanoTime() - started) / 1_000_000.0)); }
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
            submit(() -> { if (currentConnection(message)) { closeVideo(); snapshot = null; self = null; publish(State.DISCONNECTED, "Kết nối phòng họp đã kết thúc. Không tự bật lại media."); clearChat(); } return null; }); return;
        }
        RoomSnapshot active = snapshot;
        String expectedRoom = active != null ? active.roomId() : preview == null ? null : preview.roomId();
        if (!Objects.equals(expectedRoom, message.roomId())) return;
        if ("ROOM_CLOSED".equals(message.type()) || "ROOM_LEFT".equals(message.type())) {
            submit(() -> {
                RoomSnapshot current = snapshot;
                String currentRoom = current != null ? current.roomId() : preview == null ? null : preview.roomId();
                if (currentConnection(message) && Objects.equals(currentRoom, message.roomId())) {
                    closeVideo(); snapshot = null; self = null; publish(State.CONNECTED, "Bạn đã rời phòng hoặc phòng đã đóng."); clearChat();
                }
                return null;
            }); return;
        }
        if ("CHAT_MESSAGE".equals(message.type())) {
            JsonObject b = message.body();
            long id = b.get("id").getAsLong();
            long uId = b.has("userId") && !b.get("userId").isJsonNull() ? b.get("userId").getAsLong() : 0;
            String name = b.has("displayName") ? b.get("displayName").getAsString() : "";
            String text = b.has("content") ? b.get("content").getAsString() : "";
            String sentAt = b.has("sentAt") ? b.get("sentAt").getAsString() : Instant.now().toString();
            common.chat.ChatMessage chatMsg = new common.chat.ChatMessage(id, 0, uId, name, text, sentAt);
            for (ChatListener listener : chatListeners) {
                listener.onChatMessage(chatMsg);
            }
            return;
        }
        if (active != null && message.revision() != null && message.revision() <= active.revision()) return;
        if (message.body().has("room")) {
            try {
                RoomSnapshot update = decode(message, "room", RoomSnapshot.class);
                if (!Objects.equals(update.roomId(), message.roomId()) || message.revision() == null
                        || update.revision() != message.revision()) return;
                incomingRoom.accumulateAndGet(update, (before, after) -> before != null
                        && before.roomId().equals(after.roomId()) && before.revision() > after.revision() ? before : after);
                applyIncomingRoom();
            } catch (SignalException invalid) { refreshAgain.set(true); refreshEvents(); }
            return;
        }
        refreshAgain.set(true); refreshEvents();
    }
    private boolean currentConnection(SignalMessage message) {
        SignalingClient current = client;
        return current != null && Objects.equals(current.connectionId(), message.connectionId()) && current.generation() == message.generation();
    }
    private void applyIncomingRoom() {
        if (!applyingRoom.compareAndSet(false, true)) return;
        submit(() -> {
            RoomSnapshot update = incomingRoom.getAndSet(null), active = snapshot;
            if (update != null && active != null && update.roomId().equals(active.roomId()) && update.revision() > active.revision()) {
                snapshot = update; publish(State.CONNECTED, "Thành viên và trạng thái đã đồng bộ.");
            }
            return null;
        }).whenComplete((value, failure) -> {
            applyingRoom.set(false);
            if (failure == null && incomingRoom.get() != null && !closed.get()) applyIncomingRoom();
        });
    }
    private void refreshEvents() {
        if (!refreshing.compareAndSet(false, true)) return;
        submit(() -> {
            for (int attempt = 0; attempt < 4; attempt++) {
                refreshAgain.set(false); refreshNow();
                if (!refreshAgain.get()) break;
            }
            return null;
        }).whenComplete((value, failure) -> {
            refreshing.set(false);
            if (failure == null && refreshAgain.get() && !closed.get()) refreshEvents();
        });
    }
    private <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed.get()) { future.completeExceptionally(new SignalException("CONNECTION_CLOSED")); return future; }
        jobs.add(future);
        try { worker.execute(() -> {
            try {
                if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                T value = task.call();
                if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                future.complete(value);
            } catch (Exception failure) { future.completeExceptionally(failure); }
            finally { jobs.remove(future); }
        }); }
        catch (RejectedExecutionException failure) { jobs.remove(future); future.completeExceptionally(new SignalException("CLIENT_BUSY")); }
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
    private void closeTransport() {
        incomingRoom.set(null);
        synchronized (transportLock) { SignalingClient current = client; client = null; if (current != null) current.close(); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        closeVideo(); closeTransport(); worker.shutdownNow(); snapshot = null; self = null; pin = ""; createIntent = null; reopenIntent = null; joinIntent = null;
        jobs.forEach(future -> future.completeExceptionally(new SignalException("CONNECTION_CLOSED"))); jobs.clear();
        publish(State.CLOSED, "Session đã đóng.");
    }
    private record Intent(String id, JsonObject body) { @Override public String toString() { return "Intent[redacted]"; } }
    private record Update(State state, RoomSnapshot room, String notice) { }
}
