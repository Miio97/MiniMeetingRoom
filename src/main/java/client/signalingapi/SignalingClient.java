package client.signalingapi;

import com.google.gson.JsonObject;
import common.auth.TlsSupport;
import common.diagnostics.Diagnostics;
import common.signaling.SignalException;
import common.signaling.SignalMessage;
import common.signaling.SignalWire;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Long-lived TLS control connection. connect() belongs on a background owner thread. */
public final class SignalingClient implements AutoCloseable {
    public enum State { NEW, CONNECTING, AUTHENTICATING, CONNECTED, CLOSED }
    private final ArrayBlockingQueue<byte[]> outbound = new ArrayBlockingQueue<>(64);
    private final ArrayBlockingQueue<SignalMessage> events = new ArrayBlockingQueue<>(32);
    private final AtomicInteger queuedBytes = new AtomicInteger();
    private final ConcurrentHashMap<String, CompletableFuture<SignalMessage>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("signal-client-timer-", 0).factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object admission = new Object();
    private final Settings settings;
    private final SSLContext tls;
    private final Consumer<SignalMessage> listener;
    private volatile State state = State.NEW;
    private volatile SSLSocket socket;
    private SignalMessage welcome;
    private volatile int negotiatedMaxBytes;
    private volatile int serverMaxParticipants = 20;

    public SignalingClient(Consumer<SignalMessage> listener) throws IOException {
        this(Settings.load(), null, listener);
    }

    public SignalingClient(Settings settings, SSLContext tls, Consumer<SignalMessage> listener) {
        this.settings = settings; this.tls = tls; this.listener = listener == null ? ignored -> { } : listener;
        negotiatedMaxBytes = settings.maxBytes();
    }

    public State state() { return state; }
    public long generation() { return welcome == null ? 0 : welcome.generation(); }
    public String connectionId() { return welcome == null ? null : welcome.connectionId(); }
    public int serverMaxParticipants() { return serverMaxParticipants; }

    public synchronized void connect(String token) throws IOException {
        if (state != State.NEW || closed.get()) throw new SignalException("CONNECTION_STATE");
        state = State.CONNECTING;
        long started = System.nanoTime();
        try {
            SSLContext context = tls == null ? TlsSupport.client(settings.certificate()) : tls;
            Socket transport = new Socket();
            try {
                transport.connect(new InetSocketAddress(settings.host(), settings.port()), 5000);
                SSLSocket prepared = (SSLSocket) context.getSocketFactory().createSocket(transport, settings.host(), settings.port(), true);
                synchronized (admission) {
                    if (closed.get()) { prepared.close(); throw new SignalException("CONNECTION_CLOSED"); }
                    socket = prepared;
                }
            } catch (Exception failure) { transport.close(); throw failure; }
            socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            SSLParameters parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters); socket.setSoTimeout(20000); socket.startHandshake();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            welcome = SignalWire.read(input, settings.maxBytes());
            if (!"SERVER_WELCOME".equals(welcome.type()) || welcome.serverEpoch() == null
                    || welcome.connectionId() == null || welcome.generation() <= 0) throw new SignalException("INVALID_WELCOME");
            int remoteLimit = welcome.body().get("maxFrameBytes").getAsBigDecimal().intValueExact();
            if (remoteLimit < 16384 || remoteLimit > 1048576) throw new SignalException("INVALID_WELCOME");
            if (welcome.body().has("maxParticipants")) {
                int srvMax = welcome.body().get("maxParticipants").getAsInt();
                if (srvMax >= 2 && srvMax <= 50) serverMaxParticipants = srvMax;
            }
            negotiatedMaxBytes = Math.min(remoteLimit, settings.maxBytes());
            state = State.AUTHENTICATING;
            Thread.ofVirtual().name("signal-client-writer").start(() -> writeLoop(output));
            Thread.ofVirtual().name("signal-client-events").start(this::eventLoop);
            Thread.ofVirtual().name("signal-client-reader").start(() -> readLoop(input));
            JsonObject body = new JsonObject(); body.addProperty("token", token); body.addProperty("maxFrameBytes", settings.maxBytes());
            SignalMessage authReply = request("AUTHENTICATE", null, body).get(15, TimeUnit.SECONDS);
            if (authReply.body() != null && authReply.body().has("maxParticipants")) {
                int srvMax = authReply.body().get("maxParticipants").getAsInt();
                if (srvMax >= 2 && srvMax <= 50) serverMaxParticipants = srvMax;
            }
            synchronized (admission) {
                if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                state = State.CONNECTED;
                timer.scheduleAtFixedRate(() -> {
                    if (!closed.get()) request("HEARTBEAT", null, new JsonObject());
                }, 5, 5, TimeUnit.SECONDS);
            }
            Diagnostics.current().event("signaling.client.connected", Map.of("transport", "tcp_tls",
                    "total_ms", (System.nanoTime() - started) / 1_000_000.0));
        } catch (Exception failure) {
            close();
            if (failure instanceof SignalException signal) throw signal;
            if (failure instanceof java.util.concurrent.ExecutionException execution && execution.getCause() instanceof SignalException signal) throw signal;
            Diagnostics.current().failure("signaling.client.failed", failure, Map.of("phase", "connect"));
            throw new SignalException("CONNECTION_FAILED");
        }
    }

    public CompletableFuture<SignalMessage> request(String type, String roomId, JsonObject body) {
        return request(type, roomId, body, UUID.randomUUID().toString());
    }

    /** The same request ID is retained for a retry whose outcome is unknown. */
    public CompletableFuture<SignalMessage> request(String type, String roomId, JsonObject body, String requestId) {
        CompletableFuture<SignalMessage> future = new CompletableFuture<>();
        if (closed.get() || welcome == null || pending.size() >= 64 || state == State.NEW || state == State.CONNECTING) {
            future.completeExceptionally(new SignalException("CONNECTION_STATE")); return future;
        }
        try {
            SignalMessage message = new SignalMessage(1, type, requestId, welcome.serverEpoch(),
                    welcome.connectionId(), welcome.generation(), roomId, null, null, body);
            byte[] bytes = SignalWire.encode(message, negotiatedMaxBytes);
            synchronized (admission) {
                if (closed.get()) throw new SignalException("CONNECTION_CLOSED");
                if (pending.putIfAbsent(requestId, future) != null) throw new SignalException("REQUEST_PENDING");
                if (queuedBytes.addAndGet(bytes.length) > 1024 * 1024 || !outbound.offer(bytes)) {
                    queuedBytes.addAndGet(-bytes.length); pending.remove(requestId, future);
                    throw new SignalException("QUEUE_FULL");
                }
                timer.schedule(() -> {
                    if (pending.remove(requestId, future)) future.completeExceptionally(new SignalException("REQUEST_TIMEOUT"));
                }, 15, TimeUnit.SECONDS);
            }
        } catch (Exception failure) { future.completeExceptionally(failure); }
        return future;
    }

    private void writeLoop(DataOutputStream output) {
        try {
            while (!closed.get()) {
                byte[] bytes = outbound.poll(1, TimeUnit.SECONDS);
                if (bytes != null) {
                    synchronized (admission) { if (closed.get()) return; queuedBytes.addAndGet(-bytes.length); }
                    SignalWire.writeEncoded(output, bytes);
                }
            }
        } catch (Exception failure) { failConnection(failure); }
    }

    private void readLoop(DataInputStream input) {
        try {
            while (!closed.get()) {
                SignalMessage message = SignalWire.read(input, settings.maxBytes());
                if (!welcome.serverEpoch().equals(message.serverEpoch()) || !welcome.connectionId().equals(message.connectionId())
                        || message.generation() != welcome.generation()) continue;
                if ("SESSION_ENDED".equals(message.type())) { close(message); return; }
                CompletableFuture<SignalMessage> future = message.requestId() == null ? null : pending.remove(message.requestId());
                if (future != null) {
                    if ("ERROR".equals(message.type())) future.completeExceptionally(new SignalException(message.body().get("code").getAsString()));
                    else future.complete(message);
                } else if (message.requestId() == null && !events.offer(message)) throw new SignalException("EVENT_QUEUE_FULL");
            }
        } catch (Exception failure) { failConnection(failure); }
    }

    private void eventLoop() {
        try {
            while (!closed.get() || !events.isEmpty()) {
                SignalMessage message = events.poll(1, TimeUnit.SECONDS);
                if (message != null) listener.accept(message);
            }
        } catch (Exception failure) { failConnection(failure); }
    }

    private void failConnection(Exception failure) {
        if (!closed.get()) Diagnostics.current().failure("signaling.client.failed", failure, Map.of("phase", "control"));
        close();
    }

    @Override public void close() {
        SignalMessage terminal = null;
        if (welcome != null) {
            JsonObject body = new JsonObject(); body.addProperty("code", "CONNECTION_CLOSED");
            terminal = new SignalMessage(1, "CONNECTION_CLOSED", null, welcome.serverEpoch(), welcome.connectionId(),
                    welcome.generation(), null, null, null, body);
        }
        close(terminal);
    }

    private void close(SignalMessage terminal) {
        synchronized (admission) {
            if (!closed.compareAndSet(false, true)) return;
            state = State.CLOSED;
            timer.shutdownNow(); outbound.clear(); queuedBytes.set(0); events.clear();
            if (terminal != null) events.offer(terminal);
            pending.forEach((id, future) -> future.completeExceptionally(new SignalException("CONNECTION_CLOSED")));
            pending.clear();
        }
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
    }

    public record Settings(String host, int port, Path certificate, int maxBytes) {
        public Settings {
            if (host == null || host.isBlank() || port < 1 || port > 65535 || maxBytes < 16384 || maxBytes > 1048576)
                throw new IllegalArgumentException("INVALID_SIGNALING_SETTINGS");
        }

        public static Settings load() throws IOException {
            Properties values = new Properties();
            Path file = Path.of(System.getProperty("mmr.client.config", "config/auth-client.properties"));
            if (Files.exists(file)) try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { values.load(reader); }
            String authHost = System.getenv().getOrDefault("MMR_AUTH_HOST", values.getProperty("auth.host", "localhost"));
            return new Settings(System.getenv().getOrDefault("MMR_SIGNALING_HOST", values.getProperty("signaling.host", authHost)),
                    Integer.parseInt(System.getenv().getOrDefault("MMR_SIGNALING_PORT", values.getProperty("signaling.port", "8444"))),
                    Path.of(System.getenv().getOrDefault("MMR_AUTH_CERTIFICATE", values.getProperty("auth.certificate", "config/auth-server.cer"))),
                    Integer.parseInt(values.getProperty("signaling.maxBytes", "131072")));
        }
    }
}
