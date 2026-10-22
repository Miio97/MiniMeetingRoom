package server.signalingapi;

import com.google.gson.JsonObject;
import common.diagnostics.Diagnostics;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import common.signaling.SignalWire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import server.auth.SessionPrincipal;
import server.auth.SessionValidator;

/** Independent long-lived TLS server; authentication workers remain short-lived. */
public final class SignalingServer implements AutoCloseable {
    public interface Handler {
        SignalMessage handle(Connection connection, SignalMessage request) throws Exception;
        void disconnected(Connection connection, String reason) throws Exception;
        default void validated(Connection connection) { }
        default void bindServer(SignalingServer server) { }
        default java.util.concurrent.CompletionStage<SignalMessage> handleAsync(Connection connection,SignalMessage request) throws Exception {
            return java.util.concurrent.CompletableFuture.completedFuture(handle(connection,request));
        }
    }

    public record Options(int maxConnections, int maxFrameBytes, int requestsPerSecond, int maxParticipants) {
        public Options(int maxConnections, int maxFrameBytes, int requestsPerSecond) {
            this(maxConnections, maxFrameBytes, requestsPerSecond, 20);
        }
        public Options {
            if (maxConnections < 1 || maxConnections > 512 || maxFrameBytes < 16384
                    || maxFrameBytes > 1048576 || requestsPerSecond < 1 || requestsPerSecond > 100
                    || maxParticipants < 2 || maxParticipants > 50)
                throw new IllegalArgumentException("SIGNALING_LIMITS");
        }
        public static Options defaults() { return new Options(256, SignalWire.DEFAULT_MAX_BYTES, 50, 20); }
    }

    private final SSLServerSocket listener;
    private final SessionValidator validator;
    private final Handler handler;
    private final Options options;
    private final String epoch;
    private final Semaphore admission;
    private final ConcurrentHashMap<String, Connection> clients = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final Object cleanupLifecycle = new Object();
    private static final long DATABASE_GRACE_NS=TimeUnit.MINUTES.toNanos(2);
    private static final java.util.Set<String> RAM_CHAT_ACTIONS=java.util.Set.of("CHAT_SEND","CHAT_HISTORY","CHAT_STATUS");
    private static final java.util.Set<String> DATABASE_GRACE_ACTIONS=java.util.Set.of("VIDEO_STATE","AUDIO_STATE","SCREEN_STATE","VIDEO_UNSUBSCRIBE","VIDEO_STOP","AUDIO_STOP","SCREEN_STOP","GET_ROOM","ROOM_CATALOG_UNWATCH","LEAVE_ROOM","CHAT_SEND","CHAT_HISTORY","CHAT_STATUS");
    private final ConcurrentHashMap<Runnable, Runnable> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PinWindow> pinWindows = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor work = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), Thread.ofPlatform().daemon().name("meeting-work-", 0).factory());
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("meeting-validation-", 0).factory());
    private final ThreadPoolExecutor cleanup = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), Thread.ofPlatform().daemon().name("meeting-cleanup-", 0).factory());
    private Thread acceptThread;
    private static final class PinWindow { long start = System.nanoTime(); int attempts; }

    private boolean allowPin(String hash) {
        if (pinWindows.size() >= 2048 && !pinWindows.containsKey(hash)) return false;
        PinWindow window = pinWindows.computeIfAbsent(hash, ignored -> new PinWindow());
        synchronized (window) {
            if (System.nanoTime() - window.start >= TimeUnit.MINUTES.toNanos(1)) { window.start = System.nanoTime(); window.attempts = 0; }
            return ++window.attempts <= 5;
        }
    }

    public SignalingServer(SSLContext tls, InetSocketAddress address, SessionValidator validator,
            Handler handler, String epoch, Options options) throws IOException {
        this.validator = validator; this.handler = handler; this.epoch = epoch; this.options = options;
        if (handler != null) handler.bindServer(this);
        admission = new Semaphore(options.maxConnections());
        listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        try {
            listener.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"}); listener.setReuseAddress(true);
            listener.bind(address, 32);
        } catch (IOException | RuntimeException failure) {
            listener.close(); work.shutdownNow(); timer.shutdownNow(); cleanup.shutdownNow(); throw failure;
        }
    }

    public int port() { return listener.getLocalPort(); }

    public synchronized void start() {
        if (closed.get() || acceptThread != null) throw new IllegalStateException("SERVER_STATE");
        acceptThread = Thread.ofPlatform().daemon().name("meeting-accept").start(this::accept);
        timer.scheduleAtFixedRate(this::validateSessions, 1, 1, TimeUnit.SECONDS);
        Diagnostics.current().event("signaling.server.started", Map.of("port", port(), "transport", "tcp_tls"));
    }

    public void awaitTermination() throws InterruptedException {
        if (acceptThread == null) throw new IllegalStateException("SERVER_STATE");
        acceptThread.join();
    }

    public void revokeSession(String hash) {
        long started = System.nanoTime();
        clients.values().forEach(connection -> {
            SessionPrincipal principal = connection.principal;
            if (principal != null && principal.tokenHash().equals(hash)) connection.terminate("SESSION_REVOKED");
        });
        Diagnostics.current().event("signaling.session.revoked", Map.of("total_ms", (System.nanoTime() - started) / 1_000_000.0));
    }

    public void event(String connectionId, String type, String roomId, Long revision, JsonObject body) {
        Connection connection = clients.get(connectionId);
        if (connection != null && connection.principal != null) connection.send(connection.message(type, null, roomId, revision, body));
    }

    private void accept() {
        while (!closed.get()) {
            try {
                SSLSocket socket = (SSLSocket) listener.accept();
                Connection connection;
                synchronized (lifecycle) {
                    if (closed.get() || !admission.tryAcquire()) { socket.close(); continue; }
                    connection = new Connection(socket, sequence.incrementAndGet());
                    clients.put(connection.id, connection);
                }
                Thread.ofVirtual().name("meeting-reader").start(connection::readLoop);
            } catch (IOException failure) {
                if (!closed.get()) Diagnostics.current().failure("signaling.server.failed", failure, Map.of("phase", "accept"));
                break;
            }
        }
    }

    private void validateSessions() {
        long now = System.nanoTime();
        pinWindows.entrySet().removeIf(entry -> now - entry.getValue().start > TimeUnit.MINUTES.toNanos(2));
        for (Connection connection : clients.values()) {
            if (connection.closed.get()) continue;
            SessionPrincipal principal = connection.principal;
            if (principal == null) {
                if (now - connection.created > TimeUnit.SECONDS.toNanos(10)) connection.terminate("AUTH_TIMEOUT");
                continue;
            }
            if (!principal.expiresAt().isAfter(java.time.Instant.now())) {
                connection.terminate("SESSION_EXPIRED"); continue;
            }
            if (connection.validating.get()) {
                if(now-connection.validationStartedAt>TimeUnit.SECONDS.toNanos(3)) connection.databaseGrace(now);
                continue;
            }
            if (now - connection.validatedAt < TimeUnit.SECONDS.toNanos(3) || !connection.validating.compareAndSet(false, true)) continue;
            connection.validationStartedAt = now;
            try {
                work.execute(() -> {
                    try {
                        Optional<SessionPrincipal> result = validator.validateHash(principal.tokenHash());
                        if (result.isEmpty()) connection.terminate("SESSION_INVALID");
                        else if (!connection.closed.get()) { connection.principal = result.get(); connection.validatedAt = System.nanoTime();connection.databaseStatus(true); handler.validated(connection); }
                    } catch (java.sql.SQLException failure) {connection.databaseGrace(System.nanoTime());}
                    catch (Exception failure) { connection.terminate("SESSION_CHECK_FAILED"); }
                    finally { connection.validating.set(false); }
                });
            } catch (RejectedExecutionException failure) { connection.validating.set(false);connection.databaseGrace(now); }
        }
    }

    public final class Connection {
        private final SSLSocket socket;
        private final String id = UUID.randomUUID().toString();
        private final long generation;
        private final long created = System.nanoTime();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean validating = new AtomicBoolean();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicBoolean cleanupStarted = new AtomicBoolean();
        private final Object outputAdmission = new Object();
        private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(64);
        private int queuedBytes;
        private volatile long validatedAt = created;
        private volatile long validationStartedAt = created;
        private long rateWindow = created;
        private int windowRequests;
        private volatile SessionPrincipal principal;
        private volatile boolean databaseDegraded;
        private final java.util.concurrent.atomic.AtomicLong lastGraceRenewal=new java.util.concurrent.atomic.AtomicLong();
        private void databaseStatus(boolean available) {
            if(databaseDegraded==!available) return;databaseDegraded=!available;
            var body=new JsonObject();body.addProperty("available",available);send(message("SERVER_DATABASE_STATUS",null,null,null,body));
        }
        private void databaseGrace(long now) {
            if(principal==null || !principal.expiresAt().isAfter(java.time.Instant.now()) || now-validatedAt>DATABASE_GRACE_NS) {terminate("DATABASE_GRACE_EXPIRED");return;}
            databaseStatus(false);
            long previous=lastGraceRenewal.get();
            if(now-previous>=TimeUnit.SECONDS.toNanos(3) && lastGraceRenewal.compareAndSet(previous,now)) handler.validated(this);
        }
        private volatile int outputMaximum = options.maxFrameBytes();
        private volatile String disconnectReason = "MAT_KET_NOI";

        Connection(SSLSocket socket, long generation) { this.socket = socket; this.generation = generation; }
        public String id() { return id; }
        public long generation() { return generation; }
        public SessionPrincipal principal() throws SignalException {
            if (closed.get() || principal == null) throw new SignalException("SESSION_INVALID");
            return principal;
        }
        public boolean isClosed() { return closed.get(); }

        public SignalMessage message(String type, String requestId, String roomId, Long revision, JsonObject body) {
            return new SignalMessage(1, type, requestId, epoch, id, generation, roomId, null, revision, body);
        }

        private void readLoop() {
            try {
                socket.setSoTimeout(10000); socket.startHandshake(); socket.setSoTimeout(20000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                Thread.ofVirtual().name("meeting-writer").start(() -> writeLoop(output));
                JsonObject body = new JsonObject(); body.addProperty("maxFrameBytes", options.maxFrameBytes());
                body.addProperty("protocolVersion", 1);
                body.addProperty("maxParticipants", options.maxParticipants());
                send(message("SERVER_WELCOME", null, null, null, body));
                while (!closed.get()) {
                    SignalMessage request = SignalWire.read(input, options.maxFrameBytes());
                    if (request.requestId() == null || !epoch.equals(request.serverEpoch()) || !id.equals(request.connectionId())
                            || generation != request.generation()) { error(request.requestId(), "STALE_CONNECTION"); continue; }
                    long now = System.nanoTime();
                    if (now - rateWindow >= TimeUnit.SECONDS.toNanos(1)) { rateWindow = now; windowRequests = 0; }
                    if (++windowRequests > options.requestsPerSecond()) { error(request.requestId(), "RATE_LIMIT"); continue; }
                    if ("HEARTBEAT".equals(request.type())) { send(message("HEARTBEAT_ACK", request.requestId(), null, null, new JsonObject())); continue; }
                    if (principal == null && !"AUTHENTICATE".equals(request.type())) { error(request.requestId(), "AUTH_REQUIRED"); continue; }
                    if(inFlight.incrementAndGet()>16) {inFlight.decrementAndGet();error(request.requestId(),"SERVER_BUSY");continue;}
                    AtomicBoolean operationFinished = new AtomicBoolean();
                    Runnable finish = () -> {
                        if (operationFinished.compareAndSet(false, true) && inFlight.decrementAndGet() == 0 && closed.get()) disconnect();
                    };
                    long queuedAt = System.nanoTime();
                    var completion = new java.util.concurrent.CompletableFuture<SignalMessage>();
                    try {
                        Runnable operation = new Runnable() {
                          @Override public void run() {
                            boolean deferred=false;
                            cancellations.remove(this);
                            long handlerAt = System.nanoTime();
                            try {
                                if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                                SignalMessage response;
                                if ("AUTHENTICATE".equals(request.type())) {
                                    if (principal != null) throw new SignalException("ALREADY_AUTHENTICATED");
                                    var tokenValue = request.body().get("token");
                                    if (tokenValue == null || !tokenValue.isJsonPrimitive() || !tokenValue.getAsJsonPrimitive().isString())
                                        throw new SignalException("INVALID_REQUEST");
                                    String token = tokenValue.getAsString();
                                    int negotiated = options.maxFrameBytes();
                                    if (request.body().has("maxFrameBytes")) {
                                        var value = request.body().get("maxFrameBytes");
                                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new SignalException("FRAME_LIMIT");
                                        int limit;
                                        try { limit = value.getAsBigDecimal().intValueExact(); }
                                        catch (ArithmeticException invalid) { throw new SignalException("FRAME_LIMIT"); }
                                        if (limit < 16384 || limit > 1048576) throw new SignalException("FRAME_LIMIT");
                                        negotiated = Math.min(limit, options.maxFrameBytes());
                                    }
                                    Optional<SessionPrincipal> found = validator.validate(token);
                                    if (found.isEmpty()) throw new SignalException("SESSION_INVALID");
                                    if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                                    principal = found.get(); validatedAt = System.nanoTime();
                                    outputMaximum = negotiated;
                                    JsonObject authenticated = new JsonObject();
                                    authenticated.addProperty("expiresAt", principal.expiresAt().toString());
                                    authenticated.addProperty("selfDisplayName", principal.displayName());
                                    authenticated.addProperty("maxParticipants", options.maxParticipants());
                                    response = message("AUTHENTICATED", request.requestId(), null, null, authenticated);
                                } else if ("NETWORK_STATS".equals(request.type())) {
                                    if (principal == null || closed.get()) {
                                        terminate("SESSION_INVALID"); throw new SignalException("SESSION_INVALID");
                                    }
                                    response = handler.handle(Connection.this, request);
                                } else if(RAM_CHAT_ACTIONS.contains(request.type())) {
                                    // The background validator/revocation owns DB checks, not individual chat packets.
                                    if(principal==null || closed.get() || !principal.expiresAt().isAfter(java.time.Instant.now())) {
                                        terminate("SESSION_INVALID");throw new SignalException("SESSION_INVALID");
                                    }
                                    if(System.nanoTime()-validatedAt>DATABASE_GRACE_NS) {
                                        terminate("DATABASE_GRACE_EXPIRED");throw new SignalException("SESSION_INVALID");
                                    }
                                    var stage=handler.handleAsync(Connection.this,request);deferred=true;
                                    stage.whenComplete((reply,failure) -> {
                                        if(failure==null) completion.complete(reply);else completion.completeExceptionally(failure);
                                        Diagnostics.current().event("signaling.request.completed",Map.of("action",safeAction(request.type()),"queue_ms",(handlerAt-queuedAt)/1_000_000.0,"handler_ms",(System.nanoTime()-handlerAt)/1_000_000.0));finish.run();
                                    });return;
                                } else {
                                    Optional<SessionPrincipal> live;
                                    try {live=validator.validateHash(principal.tokenHash());databaseStatus(true);validatedAt=System.nanoTime();}
                                    catch(java.sql.SQLException unavailable) {
                                        databaseGrace(System.nanoTime());
                                        if(closed.get() || !DATABASE_GRACE_ACTIONS.contains(request.type())) throw new SignalException("DATABASE_UNAVAILABLE");
                                        live=Optional.of(principal);
                                    }
                                    if (live.isEmpty() || closed.get()) {
                                        terminate("SESSION_INVALID"); throw new SignalException("SESSION_INVALID");
                                    }
                                    principal = live.get();
                                    if (java.util.Set.of("CREATE_ROOM", "LOOKUP_ROOM", "JOIN_ROOM").contains(request.type())
                                            && !allowPin(principal.tokenHash())) throw new SignalException("PIN_RATE_LIMIT");
                                    response = handler.handle(Connection.this, request);
                                }
                                completion.complete(response);
                            } catch (Exception failure) { completion.completeExceptionally(failure); }
                            finally {
                                if(!deferred) Diagnostics.current().event("signaling.request.completed", Map.of("action", safeAction(request.type()),
                                        "queue_ms", (handlerAt - queuedAt) / 1_000_000.0,
                                        "handler_ms", (System.nanoTime() - handlerAt) / 1_000_000.0));
                                if(!deferred) finish.run();
                            }
                          }
                        };
                        cancellations.put(operation, () -> {
                            completion.completeExceptionally(new SignalException("CONNECTION_CLOSED")); finish.run();
                        });
                        try { work.execute(operation); }
                        catch (RejectedExecutionException rejected) {
                            cancellations.remove(operation); throw rejected;
                        }
                        if(RAM_CHAT_ACTIONS.contains(request.type())) {
                            completion.whenComplete((reply,failure) -> {
                                if(failure==null) send(reply);
                                else {Throwable cause=failure;while(cause instanceof java.util.concurrent.CompletionException && cause.getCause()!=null) cause=cause.getCause();error(request.requestId(),cause instanceof SignalException e?e.code():cause instanceof server.meeting.RoomException e?e.code():"CHAT_OUTCOME_UNKNOWN");}
                            });
                        } else send(completion.get(15, TimeUnit.SECONDS));
                    } catch (RejectedExecutionException failure) {
                        finish.run();
                        if (!closed.get()) error(request.requestId(), "SERVER_BUSY");
                    }
                    catch (java.util.concurrent.TimeoutException failure) { terminate("REQUEST_TIMEOUT"); }
                    catch (java.util.concurrent.ExecutionException failure) {
                        Throwable cause = failure.getCause();
                        if (cause instanceof SignalException signal) error(request.requestId(), signal.code());
                        else if (cause instanceof server.meeting.RoomException room) error(request.requestId(), room.code());
                        else { Diagnostics.current().failure("signaling.request.failed", cause, Map.of("phase", "dispatch")); error(request.requestId(), "SERVER_ERROR"); }
                    }
                }
            } catch (Exception failure) {
                if (!closed.get()) Diagnostics.current().failure("signaling.connection.failed", failure, Map.of("phase", "control"));
            } finally { terminate("MAT_KET_NOI"); }
        }

        private void writeLoop(DataOutputStream output) {
            try {
                while (!closed.get()) {
                    byte[] bytes = outbound.poll(1, TimeUnit.SECONDS);
                    if (bytes != null) {
                        synchronized (outputAdmission) { if (closed.get()) return; queuedBytes -= bytes.length; }
                        SignalWire.writeEncoded(output, bytes);
                    }
                }
            } catch (Exception failure) { terminate("MAT_KET_NOI"); }
        }

        private void error(String requestId, String code) {
            JsonObject body = new JsonObject(); body.addProperty("code", code);
            send(message("ERROR", requestId, null, null, body));
        }

        private void send(SignalMessage message) {
            if (message == null || closed.get()) return;
            try {
                byte[] bytes = SignalWire.encode(message, outputMaximum);
                synchronized (outputAdmission) {
                    if (closed.get()) return;
                    if (queuedBytes + bytes.length > 1024 * 1024 || !outbound.offer(bytes)) { terminate("OUTPUT_BACKPRESSURE"); return; }
                    queuedBytes += bytes.length;
                }
            } catch (IOException failure) { terminate("FRAME_LIMIT"); }
        }

        public void terminate(String reason) {
            if (!closed.compareAndSet(false, true)) return;
            disconnectReason = reason;
            Diagnostics.current().event("signaling.connection.closed", Map.of("reason", safeCloseReason(reason)));
            synchronized (outputAdmission) { outbound.clear(); queuedBytes = 0; }
            try { socket.close(); } catch (IOException ignored) { }
            if (inFlight.get() == 0) disconnect();
        }

        private void disconnect() {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            Runnable task = () -> {
                try { handler.disconnected(this, disconnectReason); }
                catch (Exception failure) { Diagnostics.current().failure("signaling.cleanup.failed", failure, Map.of("phase", "close")); }
                finally { clients.remove(id, this); admission.release(); }
            };
            synchronized (cleanupLifecycle) {
                try {
                    cleanup.execute(task);
                } catch (RejectedExecutionException failure) {
                    // A retained connection permit bounds these final cleanup tasks by maxConnections.
                    Thread.ofVirtual().name("meeting-final-cleanup").start(task);
                }
            }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (lifecycle) {
            try { listener.close(); } catch (IOException ignored) { }
            java.util.List.copyOf(clients.values()).forEach(connection -> connection.terminate("SERVER_SHUTDOWN"));
        }
        timer.shutdownNow(); work.shutdown();
        try { if (!work.awaitTermination(3, TimeUnit.SECONDS)) cancelQueued(work.shutdownNow()); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); cancelQueued(work.shutdownNow()); }
        synchronized (cleanupLifecycle) { cleanup.shutdown(); }
        try { cleanup.awaitTermination(3, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private void cancelQueued(java.util.List<Runnable> queued) {
        for (Runnable task : queued) {
            Runnable cancellation = cancellations.remove(task);
            if (cancellation != null) cancellation.run();
        }
    }
    String lifecycleState() {
        return "registered=" + clients.size() + " work=" + work.getActiveCount() + " queued=" + work.getQueue().size()
                + " cleanup=" + cleanup.getActiveCount() + "/" + cleanup.getQueue().size() + "/" + cleanup.isTerminated()
                + " cancellations=" + cancellations.size() + " remaining="
                + clients.values().stream().map(connection -> connection.inFlight.get() + ":" + connection.cleanupStarted.get()).toList();
    }

    private static String safeAction(String type) {
        return switch (type) {
            case "AUTHENTICATE", "CREATE_ROOM", "REOPEN_ROOM", "LOOKUP_ROOM", "JOIN_ROOM", "RESUME_ROOM", "ADMISSION_STATUS", "GET_ROOM", "LEAVE_ROOM",
                    "SET_MEDIA_STATE", "SET_LOCKED", "STOP_PUBLISH", "KICK", "VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "VIDEO_STATE", "VIDEO_STOP",
                    "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "AUDIO_STATE", "AUDIO_STOP", "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE",
                    "SCREEN_STATE", "SCREEN_STOP", "PUBLISH", "UNPUBLISH",
                    "SUBSCRIBE", "UNSUBSCRIBE", "SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE",
                    "CHAT_SEND", "CHAT_HISTORY", "CHAT_STATUS", "NETWORK_STATS" -> type;
            default -> "OTHER";
        };
    }
    private static String safeCloseReason(String reason) {
        return switch (reason) {
            case "SESSION_EXPIRED", "SESSION_INVALID", "SESSION_REVOKED", "SESSION_CHECK_TIMEOUT", "SESSION_CHECK_FAILED",
                    "AUTH_TIMEOUT", "SERVER_BUSY", "REQUEST_TIMEOUT", "OUTPUT_BACKPRESSURE", "FRAME_LIMIT",
                    "SERVER_SHUTDOWN", "MAT_KET_NOI" -> reason;
            default -> "OTHER";
        };
    }
}
