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
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One owner across lobby/prejoin/meeting. Views borrow properties; only this owner closes transport. */
public final class MeetingSession implements AutoCloseable {
    public enum State { READY, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, CLOSED }
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
    private final AtomicBoolean applicationLeaving = new AtomicBoolean(), roomTerminated = new AtomicBoolean();
    private CompletableFuture<Void> applicationLeave;
    private volatile boolean admissionPending;
    private final ScheduledExecutorService recoveryTimer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("meeting-recovery").factory());
    private final Object recoveryLock = new Object();
    private final java.time.Duration recoveryGrace;
    private volatile Recovery recovery;
    private record Recovery(String roomId, String participantId, long deadline) { }
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
        if (!java.util.Set.of("VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "VIDEO_UNSUBSCRIBE", "VIDEO_STATE", "VIDEO_STOP",
                "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "AUDIO_UNSUBSCRIBE", "AUDIO_STATE", "AUDIO_STOP",
                "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE", "SCREEN_UNSUBSCRIBE", "SCREEN_AUDIO_PUBLISH", "SCREEN_AUDIO_SUBSCRIBE", "SCREEN_AUDIO_UNSUBSCRIBE", "SCREEN_AUDIO_STATE", "SCREEN_AUDIO_STOP", "SCREEN_STATE", "SCREEN_STOP").contains(type))
            return CompletableFuture.failedFuture(new SignalException("INVALID_REQUEST"));
        return submit(() -> command(type, requireRoom().roomId(), body, requestId).body());
    }
    public synchronized client.media.VideoCallController video() {
        if (video == null) video = mediaFactory.apply(this);
        return video;
    }
    public int maxRoomCapacity() {
        SignalingClient c = client;
        return c != null ? c.serverMaxParticipants() : 20;
    }
    private synchronized void closeVideo() { if (video != null) { video.close(); video = null; } }
    private volatile RoomPreview preview;
    private volatile String pin = "";
    private volatile long joinedAt;
    private State currentState = State.READY;
    private Intent createIntent, reopenIntent, joinIntent, exitIntent;

    public MeetingSession(String token, Instant expiresAt) {
        this(token, expiresAt, SignalingClient::new);
    }
    public MeetingSession(String token, Instant expiresAt, TransportFactory transports) {
        this(token, expiresAt, transports, session -> new client.media.VideoCallController(session::videoRequest, session::snapshot, session::selfParticipantId));
    }
    public interface ChatListener {
        void onChatMessage(common.chat.ChatMessage message);
        default void onChatMessage(common.chat.ChatMessage message,String clientMessageId) {onChatMessage(message);}
        default void onChatCleared() { }
        default void onChatResync() { }
    }
    private final List<ChatListener> chatListeners = new CopyOnWriteArrayList<>();
    public record CatalogUpdate(boolean snapshot,long revision,java.util.List<common.room.RoomInfo> rooms,java.util.Set<Long> removed,java.util.Set<Long> cleaning,java.util.Set<Long> evicted) { }
    public final class CatalogWatch implements AutoCloseable {
        private final java.util.function.Consumer<CatalogUpdate> listener;
        private volatile java.util.Set<Long> focus;
        private CatalogWatch(java.util.function.Consumer<CatalogUpdate> listener,java.util.Collection<Long> ids) {this.listener=listener;focus=boundedFocus(ids);}
        public void rooms(java.util.Collection<Long> ids) {var next=boundedFocus(ids);if(!next.equals(focus)) {focus=next;refreshCatalogFocus();}}
        @Override public void close() {catalogListeners.remove(this);if(catalogListeners.isEmpty()) {var transport=client;if(transport!=null) transport.request("ROOM_CATALOG_UNWATCH",null,new JsonObject());}else refreshCatalogFocus();}
    }
    private static java.util.Set<Long> boundedFocus(java.util.Collection<Long> ids) {return ids.stream().filter(id -> id!=null && id>0).limit(100).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private final List<CatalogWatch> catalogListeners=new CopyOnWriteArrayList<>();
    private final AtomicBoolean catalogFocusQueued=new AtomicBoolean();
    private java.util.Set<Long> catalogFocus() {return catalogListeners.stream().flatMap(watch -> watch.focus.stream()).distinct().limit(100).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private void refreshCatalogFocus() {
        if(closed.get() || catalogListeners.isEmpty() || !catalogFocusQueued.compareAndSet(false,true)) return;
        var before=catalogFocus();refreshCatalog().whenComplete((v,e) -> {catalogFocusQueued.set(false);if(!before.equals(catalogFocus())) refreshCatalogFocus();});
    }
    private volatile long catalogRevision;
    private volatile String catalogWatchId="";
    private final AtomicBoolean catalogRetry=new AtomicBoolean();
    public CatalogWatch watchCatalog(java.util.function.Consumer<CatalogUpdate> listener) {return watchCatalog(listener,List.of());}
    public CatalogWatch watchCatalog(java.util.function.Consumer<CatalogUpdate> listener,java.util.Collection<Long> focus) {
        var watch=new CatalogWatch(listener,focus);catalogListeners.add(watch);
        submit(() -> {if(client!=null && client.state()==SignalingClient.State.CONNECTED) subscribeCatalog();else connect();return null;}).whenComplete((v,e) -> {if(e!=null) retryCatalog();});
        return watch;
    }
    private void subscribeCatalog() throws Exception {
        if(catalogListeners.isEmpty()) return;
        catalogWatchId=UUID.randomUUID().toString();catalogRevision=0;
        var body=new JsonObject();body.addProperty("limit",200);body.addProperty("watchId",catalogWatchId);
        body.add("roomIds",JSON.toJsonTree(catalogFocus()));
        applyCatalog(client.request("ROOM_CATALOG_WATCH",null,body).get(16,TimeUnit.SECONDS).body());
    }
    private synchronized void applyCatalog(JsonObject body) {
        if(!body.has("watchId") || !catalogWatchId.equals(body.get("watchId").getAsString())) return;
        long revision=body.get("revision").getAsLong();if(revision<=catalogRevision) return;
        boolean snapshot=body.get("snapshot").getAsBoolean();
        if(!snapshot && revision!=catalogRevision+1) {retryCatalog();return;}
        catalogRevision=revision;
        var removed=new java.util.HashSet<Long>();for(var id:body.getAsJsonArray("removed")) removed.add(id.getAsLong());
        var cleaning=new java.util.HashSet<Long>();for(var id:body.getAsJsonArray("cleaning")) cleaning.add(id.getAsLong());
        var evicted=new java.util.HashSet<Long>();if(body.has("evicted")) for(var id:body.getAsJsonArray("evicted")) evicted.add(id.getAsLong());
        var update=new CatalogUpdate(snapshot,revision,List.of(JSON.fromJson(body.get("rooms"),common.room.RoomInfo[].class)),java.util.Set.copyOf(removed),java.util.Set.copyOf(cleaning),java.util.Set.copyOf(evicted));
        for(var watch:catalogListeners) watch.listener.accept(update);
    }
    private void retryCatalog() {
        if(closed.get() || applicationLeaving.get() || catalogListeners.isEmpty() || !catalogRetry.compareAndSet(false,true)) return;
        try {recoveryTimer.schedule(() -> {catalogRetry.set(false);if(!closed.get() && recovery==null && !catalogListeners.isEmpty()) refreshCatalog();},2,TimeUnit.SECONDS);}
        catch(RejectedExecutionException stopped) {catalogRetry.set(false);}
    }
    public CompletableFuture<Void> refreshCatalog() {
        return this.<Void>submit(() -> {if(client!=null && client.state()==SignalingClient.State.CONNECTED) subscribeCatalog();else connect();return null;})
                .whenComplete((v,e) -> {if(e!=null) retryCatalog();});
    }
    public void addChatListener(ChatListener listener) { chatListeners.add(listener); }
    public void removeChatListener(ChatListener listener) { chatListeners.remove(listener); }
    private void clearChat() {
        chatDraft="";
        for (ChatListener listener : chatListeners) {
            listener.onChatCleared();
        }
    }
    public MeetingSession(String token, Instant expiresAt, TransportFactory transports,
                          java.util.function.Function<MeetingSession, client.media.VideoCallController> mediaFactory) {
        this(token, expiresAt, transports, mediaFactory, java.time.Duration.ofMinutes(2));
    }
    MeetingSession(String token, Instant expiresAt, TransportFactory transports,
                   java.util.function.Function<MeetingSession, client.media.VideoCallController> mediaFactory,
                   java.time.Duration recoveryGrace) {
        this.token = Objects.requireNonNull(token); this.expiresAt = Objects.requireNonNull(expiresAt);
        this.transports = Objects.requireNonNull(transports);
        this.mediaFactory = Objects.requireNonNull(mediaFactory);
        this.recoveryGrace = Objects.requireNonNull(recoveryGrace);
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
            return acceptJoin(joined);
        });
        return awaitAdmission(attempt);
    }
    private RoomSnapshot acceptJoin(RoomJoinReply joined) {
        self = joined.selfParticipantId(); snapshot = joined.room(); joinedAt = System.nanoTime(); joinIntent = null; reopenIntent = null; pin = "";
        admissionPending = false; roomTerminated.set(false);
        publish(State.CONNECTED, "Thành viên đã đồng bộ; âm thanh và hình ảnh chưa kết nối."); return snapshot;
    }
    private CompletableFuture<RoomSnapshot> awaitAdmission(CompletableFuture<RoomSnapshot> attempt) {
        return attempt.handle((value, failure) -> {
            if (failure == null) return CompletableFuture.completedFuture(value);
            Throwable cause = failure;
            while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) cause = cause.getCause();
            if (cause instanceof SignalException signal && "APPROVAL_PENDING".equals(signal.code())) admissionPending = true;
            if (cause instanceof SignalException signal && "APPROVAL_PENDING".equals(signal.code()) && !closed.get() && !applicationLeaving.get()) {
                publish(State.CONNECTED, "Đang chờ chủ phòng duyệt. Bạn có thể hủy yêu cầu.");
                return CompletableFuture.runAsync(() -> { }, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS))
                        .thenCompose(ignored -> awaitAdmission(submit(() -> acceptJoin(decode(command("ADMISSION_STATUS",
                                Objects.requireNonNull(preview, "ROOM_REQUIRED").roomId(), new JsonObject(), UUID.randomUUID().toString()), "join", RoomJoinReply.class)))));
            }
            return CompletableFuture.<RoomSnapshot>failedFuture(cause);
        }).thenCompose(value -> value);
    }
    public CompletableFuture<RoomSnapshot> refresh() { return submit(this::refreshNow); }
    private RoomSnapshot refreshNow() throws Exception {
        RoomSnapshot before = snapshot;
        if (before == null) return null;
        RoomSnapshot value = decode(command("GET_ROOM", before.roomId(), new JsonObject(), UUID.randomUUID().toString()), "room", RoomSnapshot.class);
        if (recovery == null && snapshot != null && value.roomId().equals(snapshot.roomId()) && value.revision() >= snapshot.revision()) {
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
        return sendChat(content,UUID.randomUUID().toString()).thenApply(ack -> null);
    }
    public record ChatAcknowledgement(long id,String clientMessageId,String sentAt) { }
    private final java.util.concurrent.atomic.AtomicInteger pendingChats=new java.util.concurrent.atomic.AtomicInteger();
    private record ChatAttempt(String context,long started) { }
    private final java.util.Map<String,ChatAttempt> chatAttempts=new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String chatDraft="";
    public String chatDraft() {return chatDraft;}
    public void chatDraft(String value) {chatDraft=value==null?"":value.substring(0,Math.min(2000,value.length()));}
    public CompletableFuture<ChatAcknowledgement> sendChat(String content,String nonce) {
        var transport=client;var expected=snapshot;
        if(closed.get() || recovery!=null || transport==null || expected==null) return CompletableFuture.failedFuture(new SignalException("CONNECTION_STATE"));
        String context=transport.serverEpoch()+":"+expected.roomId();long now=System.nanoTime();
        var prior=chatAttempts.get(nonce);
        if(prior!=null && (!context.equals(prior.context()) || now-prior.started()>TimeUnit.MINUTES.toNanos(9))) return CompletableFuture.failedFuture(new SignalException("CHAT_RETRY_UNSAFE"));
        if(prior==null) {
            if(chatAttempts.size()>=1024) return CompletableFuture.failedFuture(new SignalException("CHAT_BUSY"));
            chatAttempts.put(nonce,new ChatAttempt(context,now));
        }
        if(pendingChats.incrementAndGet()>8) {pendingChats.decrementAndGet();return CompletableFuture.failedFuture(new SignalException("CHAT_BUSY"));}
        var body=new JsonObject();body.addProperty("content",content);body.addProperty("clientMessageId",nonce);
        return transport.request("CHAT_SEND",expected.roomId(),body,nonce).thenApply(reply -> {
            if(snapshot==null || !expected.roomId().equals(snapshot.roomId())) throw new CompletionException(new SignalException("STALE_ROOM"));
            return new ChatAcknowledgement(reply.body().get("id").getAsLong(),nonce,reply.body().get("sentAt").getAsString());
        }).whenComplete((value,failure) -> {pendingChats.decrementAndGet();if(failure==null) chatAttempts.remove(nonce);});
    }
    public CompletableFuture<java.util.List<common.chat.ChatMessage>> chatHistory(Long beforeId, Integer limit) {
        JsonObject body = new JsonObject();
        if (beforeId != null) body.addProperty("beforeId", beforeId);
        if (limit != null) body.addProperty("limit", limit);
        var expected=snapshot;var transport=client;
        if(expected==null || transport==null || recovery!=null) return CompletableFuture.failedFuture(new SignalException("CONNECTION_STATE"));
        return transport.request("CHAT_HISTORY",expected.roomId(),body).thenApply(reply -> {
            if(snapshot==null || !snapshot.roomId().equals(expected.roomId())) throw new CompletionException(new SignalException("STALE_ROOM"));
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
    public CompletableFuture<List<ChatAcknowledgement>> acknowledgedChats(java.util.Collection<String> ids) {
        var expected=snapshot;var transport=client;
        if(expected==null || transport==null || ids.size()>8) return CompletableFuture.failedFuture(new SignalException("CONNECTION_STATE"));
        var body=new JsonObject();body.add("clientMessageIds",JSON.toJsonTree(ids));
        return transport.request("CHAT_STATUS",expected.roomId(),body).thenApply(reply -> {
            if(snapshot==null || !snapshot.roomId().equals(expected.roomId())) throw new CompletionException(new SignalException("STALE_ROOM"));
            var result=new ArrayList<ChatAcknowledgement>();for(var item:reply.body().getAsJsonArray("messages")) {
                var saved=item.getAsJsonObject();result.add(new ChatAcknowledgement(saved.get("id").getAsLong(),saved.get("clientMessageId").getAsString(),saved.get("sentAt").getAsString()));
            }return List.copyOf(result);
        });
    }
    public CompletableFuture<Void> leave() { return leaveBody(new JsonObject()); }
    public CompletableFuture<Void> leave(String successorParticipantId, boolean closeRoom) {
        JsonObject body=new JsonObject();body.addProperty("closeRoom",closeRoom);
        if(successorParticipantId!=null) body.addProperty("successorParticipantId",successorParticipantId);
        return leaveBody(body);
    }
    private CompletableFuture<Void> leaveBody(JsonObject body) {
        return submit(() -> {
            if(snapshot==null) return null;
            String roomId=requireRoom().roomId();
            JsonObject intent=body.deepCopy();intent.addProperty("roomId",roomId);
            if(exitIntent==null || !exitIntent.body().equals(intent)) exitIntent=new Intent(UUID.randomUUID().toString(),intent);
            command("LEAVE_ROOM",roomId,body,exitIntent.id());
            exitIntent=null;
            closeVideo();recovery=null;snapshot=null;self=null;pin="";joinIntent=null;reopenIntent=null;joinedAt=0;
            closeTransport();publish(State.READY,"Đã rời phòng.");clearChat();return null;
        });
    }
    /** Wait for an intentional leave before releasing signaling; the server chooses a host atomically. */
    public synchronized CompletableFuture<Void> leaveForApplicationClose() {
        if (applicationLeave != null) return applicationLeave;
        if (closed.get()) return CompletableFuture.completedFuture(null);
        applicationLeaving.set(true);
        final Recovery interruptedRecovery;
        synchronized (recoveryLock) { interruptedRecovery = recovery; recovery = null; }
        applicationLeave = this.<Void>submit(() -> {
            try {
                RoomSnapshot active = snapshot;
                if (active != null) {
                    SignalingClient transport = client;
                    if (interruptedRecovery != null || transport == null || transport.state() != SignalingClient.State.CONNECTED) {
                        closeTransport(); connectTransport(false);
                        JsonObject resume = new JsonObject(); resume.addProperty("participantId", self);
                        client.request("RESUME_ROOM", active.roomId(), resume).get(10, TimeUnit.SECONDS);
                    }
                    // Empty body requests the authority's automatic succession, including when only the host remains.
                    client.request("LEAVE_ROOM", active.roomId(), new JsonObject()).get(10, TimeUnit.SECONDS);
                } else if (admissionPending && preview != null && client != null && client.state() == SignalingClient.State.CONNECTED) {
                    client.request("LEAVE_ROOM", preview.roomId(), new JsonObject()).get(10, TimeUnit.SECONDS);
                }
                return null;
            } finally {
                recovery = null; snapshot = null; self = null; pin = ""; createIntent = null;
                joinIntent = null; reopenIntent = null; joinedAt = 0; admissionPending = false;
                closeVideo(); closeTransport(); publish(State.READY, "Đã rời phòng."); clearChat();
            }
        }, true).orTimeout(10, TimeUnit.SECONDS);
        applicationLeave.whenComplete((ignored, failure) -> { if (failure != null) closeTransport(); });
        return applicationLeave;
    }
    private RoomSnapshot requireRoom() throws SignalException { if (snapshot == null) throw new SignalException("ROOM_REQUIRED"); return snapshot; }
    private void connect() throws Exception {
        if (recovery != null) throw new SignalException("CONNECTION_RESTORING");
        if (snapshot != null && (client == null || client.state() != SignalingClient.State.CONNECTED)) {
            beginRecovery(); throw new SignalException("CONNECTION_RESTORING");
        }
        connectTransport(false);
    }
    private void connectTransport(boolean restoring) throws Exception {
        Recovery expectedRecovery = recovery;
        if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
        if (!expiresAt.isAfter(Instant.now())) throw new SignalException("SESSION_INVALID");
        if (client != null && client.state() == SignalingClient.State.CONNECTED) return;
        if (!restoring) publish(State.CONNECTING, "Đang kết nối phòng họp…");
        SignalingClient connection = transports.create(this::event);
        synchronized (recoveryLock) {
            if (restoring && (expectedRecovery == null || recovery != expectedRecovery)) {
                connection.close(); throw new SignalException("RECONNECT_EXPIRED");
            }
            synchronized (transportLock) {
                if (closed.get()) { connection.close(); throw new SignalException("CONNECTION_CLOSED"); }
                client = connection;
            }
        }
        try { connection.connect(token);subscribeCatalog(); if (!restoring) publish(State.CONNECTED, "Đã kết nối máy chủ phòng họp."); }
        catch (Exception failure) { connection.close(); if (!restoring) publish(State.DISCONNECTED, "Không thể kết nối máy chủ phòng họp. Có thể thử lại."); throw failure; }
    }
    private void beginRecovery() {
        Recovery pending;
        synchronized (recoveryLock) {
            if (closed.get() || applicationLeaving.get() || roomTerminated.get() || recovery != null || snapshot == null || self == null) return;
            pending = new Recovery(snapshot.roomId(), self, System.nanoTime() + recoveryGrace.toNanos());
            recovery = pending;
            if (video != null) video.controlConnected(false);
            publish(State.RECONNECTING, "Mất kết nối. Đang kết nối lại (tối đa 2 phút)…");
            recoveryTimer.schedule(() -> expireRecovery(pending, "Không thể kết nối lại trong 2 phút. Bạn đã rời phòng."),
                    recoveryGrace.toNanos(), TimeUnit.NANOSECONDS);
            recoveryTimer.schedule(() -> retryRecovery(pending), 1, TimeUnit.SECONDS);
        }
    }
    private void retryRecovery(Recovery pending) {
        if (recovery != pending || closed.get() || applicationLeaving.get() || roomTerminated.get()) return;
        submit(() -> {
            if (recovery != pending) return null;
            if (System.nanoTime() - pending.deadline() >= 0) {
                expireRecovery(pending, "Không thể kết nối lại trong 2 phút. Bạn đã rời phòng."); return null;
            }
            try {
                connectTransport(true);
                JsonObject body = new JsonObject(); body.addProperty("participantId", pending.participantId());
                RoomJoinReply resumed = decode(client.request("RESUME_ROOM", pending.roomId(), body).get(16, TimeUnit.SECONDS), "join", RoomJoinReply.class);
                synchronized (recoveryLock) {
                    if (recovery != pending || closed.get()) return null;
                    if (System.nanoTime() - pending.deadline() >= 0) {
                        expireRecovery(pending, "Không thể kết nối lại trong 2 phút. Bạn đã rời phòng."); return null;
                    }
                    if (!pending.participantId().equals(resumed.selfParticipantId()) || !pending.roomId().equals(resumed.room().roomId()))
                        throw new SignalException("INVALID_RESPONSE");
                    snapshot = resumed.room(); recovery = null;
                    if (video != null) video.controlConnected(true);
                    publish(State.CONNECTED, "Đã kết nối lại. Bạn có thể bật lại camera và mic.");
                    for(var listener:chatListeners) listener.onChatResync();
                }
            } catch (Exception failure) {
                Throwable cause = failure;
                while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof SignalException signal && java.util.Set.of("SESSION_INVALID", "ROOM_NOT_ACTIVE", "ROOM_NOT_FOUND",
                        "NOT_A_MEMBER", "RECONNECT_EXPIRED", "STALE_CONNECTION", "UNKNOWN_OPERATION").contains(signal.code())) {
                    expireRecovery(pending, "Không thể khôi phục phiên phòng. Bạn đã rời phòng.");
                } else {
                    closeTransport();
                }
            }
            return null;
        }).whenComplete((ignored, failure) -> {
            synchronized (recoveryLock) {
                if (recovery == pending && !closed.get() && !applicationLeaving.get() && !roomTerminated.get()) recoveryTimer.schedule(() -> retryRecovery(pending), 2, TimeUnit.SECONDS);
            }
        });
    }
    private void expireRecovery(Recovery pending, String reason) {
        synchronized (recoveryLock) {
            if (recovery != pending || closed.get()) return;
            recovery = null; snapshot = null; self = null; pin = ""; joinIntent = null; reopenIntent = null;
            closeTransport(); closeVideo(); publish(State.DISCONNECTED, reason); clearChat();
        }
    }
    private SignalMessage command(String type, String roomId, JsonObject body, String requestId) throws Exception {
        connect();
        long started = System.nanoTime();
        try { return client.request(type, roomId, body, requestId).get(16, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof SignalException signal && "UNKNOWN_OPERATION".equals(signal.code()) && "ADMISSION_STATUS".equals(type))
                throw new SignalException("SERVER_UPDATE_REQUIRED");
            if (failure.getCause() instanceof Exception cause) throw cause; throw failure;
        }
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
        if("ROOM_CATALOG_CHANGED".equals(message.type())) {applyCatalog(message.body());return;}
        if("SERVER_DATABASE_STATUS".equals(message.type())) {
            boolean available=message.body().get("available").getAsBoolean();
            publish(State.CONNECTED,available?"Kết nối dữ liệu đã phục hồi.":"Database tạm gián đoạn. Dữ liệu đang hiển thị có thể chưa được cập nhật.");return;
        }
        if ("CONNECTION_CLOSED".equals(message.type()) && snapshot != null) { beginRecovery(); return; }
        if ("CONNECTION_CLOSED".equals(message.type()) || "SESSION_ENDED".equals(message.type())) {
            retryCatalog();
            submit(() -> { if (currentConnection(message)) { recovery = null; closeVideo(); snapshot = null; self = null; publish(State.DISCONNECTED, "Kết nối phòng họp đã kết thúc. Không tự bật lại media."); clearChat(); } return null; }); return;
        }
        RoomSnapshot active = snapshot;
        String expectedRoom = active != null ? active.roomId() : preview == null ? null : preview.roomId();
        if (!Objects.equals(expectedRoom, message.roomId())) return;
        if ("ROOM_LEFT".equals(message.type()) && message.body().has("reason")
                && "DEVICE_REPLACED".equals(message.body().get("reason").getAsString())) {
            roomTerminated.set(true);
            synchronized (recoveryLock) { recovery = null; }
            closeTransport();
            submit(() -> {
                closeVideo(); snapshot = null; self = null; pin = ""; joinIntent = null; reopenIntent = null;
                admissionPending = false;
                publish(State.READY, "Tài khoản đã vào phòng trên thiết bị khác. Thiết bị này đã rời phòng.");
                clearChat(); return null;
            });
            return;
        }
        if ("ROOM_CLOSED".equals(message.type()) || "ROOM_LEFT".equals(message.type())) {
            submit(() -> {
                RoomSnapshot current = snapshot;
                String currentRoom = current != null ? current.roomId() : preview == null ? null : preview.roomId();
                if (currentConnection(message) && Objects.equals(currentRoom, message.roomId())) {
                    recovery = null; closeVideo(); snapshot = null; self = null; publish(State.CONNECTED, "Bạn đã rời phòng hoặc phòng đã đóng."); clearChat();
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
                listener.onChatMessage(chatMsg,b.has("clientMessageId")?b.get("clientMessageId").getAsString():null);
            }
            return;
        }
        if (recovery != null) return;
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
            if (recovery == null && update != null && active != null && update.roomId().equals(active.roomId()) && update.revision() > active.revision()) {
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
    private <T> CompletableFuture<T> submit(Callable<T> task) { return submit(task, false); }
    private <T> CompletableFuture<T> submit(Callable<T> task, boolean departure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (closed.get() || applicationLeaving.get() && !departure) { future.completeExceptionally(new SignalException("CONNECTION_CLOSED")); return future; }
        jobs.add(future);
        try { worker.execute(() -> {
            try {
                if (closed.get() || applicationLeaving.get() && !departure) throw new SignalException("CONNECTION_CLOSED");
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
        currentState = closed.get() ? State.CLOSED : recovery != null && value == State.CONNECTED ? State.RECONNECTING : value;
        latest.set(new Update(currentState, snapshot, text));
        scheduleFx();
    }
    private void scheduleFx() {
        if (!fxScheduled.compareAndSet(false, true)) return;
        Platform.runLater(() -> {
            Update value = latest.getAndSet(null);
            if (value != null) { notice.set(value.notice()); state.set(value.state()); room.set(value.room()); participants.setAll(value.room() == null ? java.util.List.of() : value.room().participants()); }
            fxScheduled.set(false);
            if (latest.get() != null) scheduleFx();
        });
    }
    private void closeTransport() {
        incomingRoom.set(null);
        synchronized (transportLock) { SignalingClient current = client; client = null; if (current != null) current.close(); }
    }
    @Override public void close() {
        synchronized (recoveryLock) {
            if (!closed.compareAndSet(false, true)) return;
            recovery = null; recoveryTimer.shutdownNow();
        }
        closeVideo(); closeTransport(); worker.shutdownNow(); snapshot = null; self = null; pin = ""; createIntent = null; reopenIntent = null; joinIntent = null;
        jobs.forEach(future -> future.completeExceptionally(new SignalException("CONNECTION_CLOSED"))); jobs.clear();
        catalogListeners.clear();chatListeners.clear();chatDraft="";
        publish(State.CLOSED, "Session đã đóng.");
    }
    private record Intent(String id, JsonObject body) { @Override public String toString() { return "Intent[redacted]"; } }
    private record Update(State state, RoomSnapshot room, String notice) { }
}
