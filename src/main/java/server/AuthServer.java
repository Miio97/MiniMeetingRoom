package server;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.auth.TlsSupport;
import common.diagnostics.Diagnostics;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import server.config.ServerConfig;
import server.database.DatabaseConnection;

/** TLS/TCP authentication server. One bounded JSON request per connection. */
public final class AuthServer implements AutoCloseable {
    @FunctionalInterface public interface Handler {
        AuthResponse handle(AuthRequest request) throws SQLException;
    }

    private final SSLServerSocket listener;
    private final Handler handler;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon().name("auth-worker-", 0).factory());
    private final Set<SSLSocket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong acceptedConnections = new AtomicLong();
    private final AtomicLong rejectedConnections = new AtomicLong();
    private final AtomicLong completedConnections = new AtomicLong();
    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private volatile boolean closed;
    private Thread acceptThread;

    public AuthServer(SSLContext tls, InetSocketAddress address, Handler handler) throws IOException {
        this.handler = handler;
        listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            listener.bind(address, 32);
        } catch (IOException | RuntimeException error) {
            try { listener.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            workers.shutdownNow();
            throw error;
        }
    }

    public int port() { return listener.getLocalPort(); }

    public synchronized void start() {
        if (closed || acceptThread != null) throw new IllegalStateException("Server cannot be started twice");
        acceptThread = Thread.ofPlatform().daemon().name("auth-accept").start(this::accept);
        Map<String, Object> fields = workload();
        fields.put("port", port());
        fields.put("bind_loopback", listener.getInetAddress().isLoopbackAddress());
        fields.put("bind_any", listener.getInetAddress().isAnyLocalAddress());
        fields.put("transport", "tcp_tls");
        fields.put("media_status", "not_implemented");
        Diagnostics.current().event("server.started", fields);
    }

    public void awaitTermination() throws InterruptedException {
        Thread thread = acceptThread;
        if (thread == null) throw new IllegalStateException("Server has not started");
        thread.join();
    }

    private void accept() {
        while (!closed) {
            try {
                SSLSocket socket = (SSLSocket) listener.accept();
                long acceptedAt = System.nanoTime();
                long connectionId = acceptedConnections.incrementAndGet();
                socket.setSoTimeout(10000);
                clients.add(socket);
                try {
                    workers.execute(() -> handle(socket, connectionId, acceptedAt));
                    Map<String, Object> fields = workload();
                    fields.put("connection_id", connectionId);
                    Diagnostics.current().event("server.connection.accepted", fields);
                } catch (RejectedExecutionException e) {
                    rejectedConnections.incrementAndGet();
                    closeClient(socket);
                    Map<String, Object> fields = workload();
                    fields.put("connection_id", connectionId);
                    Diagnostics.current().failure("server.connection.rejected", e, fields);
                }
            } catch (SocketException e) {
                if (!closed) {
                    Diagnostics.current().failure("server.accept.failed", e, workload());
                    System.err.println("Auth server: không thể nhận kết nối.");
                }
                break;
            } catch (IOException e) {
                if (!closed) {
                    Diagnostics.current().failure("server.accept.failed", e, workload());
                    System.err.println("Auth server: lỗi nhận kết nối.");
                }
            }
        }
    }

    private void handle(SSLSocket socket, long connectionId, long acceptedAt) {
        double queueMillis = elapsedMillis(acceptedAt);
        long handshakeAt = System.nanoTime();
        double handshakeMillis = -1;
        double handlerMillis = -1;
        String action = "OTHER";
        String responseCode = "NONE";
        String phase = "tls_handshake";
        String result = "transport_error";
        try (socket) {
            try { socket.startHandshake(); }
            finally { handshakeMillis = elapsedMillis(handshakeAt); }
            phase = "request_read";
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            AuthResponse response;
            try {
                AuthRequest request = AuthWire.read(input, AuthRequest.class);
                action = safeAction(request.action());
                phase = "request_handler";
                long handlerAt = System.nanoTime();
                try { response = handler.handle(request); }
                finally { handlerMillis = elapsedMillis(handlerAt); }
            } catch (SQLException e) {
                Diagnostics.current().failure("server.request.failed", e,
                        Map.of("connection_id", connectionId, "phase", phase, "action", action));
                // Do not send SQL, connection strings or credentials to the client.
                System.err.println("Auth database error (SQLState=" + e.getSQLState() + ").");
                response = AuthResponse.error("DATABASE_UNAVAILABLE", "Không thể xử lý tài khoản lúc này. Vui lòng thử lại.");
            } catch (IOException | IllegalArgumentException e) {
                Diagnostics.current().failure("server.request.failed", e,
                        Map.of("connection_id", connectionId, "phase", phase, "action", action));
                response = AuthResponse.error("INVALID_REQUEST", "Yêu cầu không hợp lệ.");
            } catch (RuntimeException e) {
                Diagnostics.current().failure("server.request.failed", e,
                        Map.of("connection_id", connectionId, "phase", phase, "action", action));
                System.err.println("Auth server error: " + e.getClass().getSimpleName());
                response = AuthResponse.error("SERVER_ERROR", "Máy chủ gặp lỗi. Vui lòng thử lại.");
            }
            responseCode = safeResponseCode(response == null ? null : response.code());
            phase = "response_write";
            AuthWire.write(output, response);
            result = response != null && response.success() ? "success" : "rejected";
            phase = "connection_close";
        } catch (IOException e) {
            // Closed, malformed or timed-out connections are contained to one worker.
            Diagnostics.current().failure("server.connection.failed", e,
                    Map.of("connection_id", connectionId, "phase", phase, "action", action));
        } finally {
            clients.remove(socket);
            completedConnections.incrementAndGet();
            Map<String, Object> fields = workload();
            fields.put("connection_id", connectionId);
            fields.put("action", action);
            fields.put("response_code", responseCode);
            fields.put("outcome", result);
            fields.put("queue_ms", queueMillis);
            fields.put("tls_handshake_ms", handshakeMillis);
            if (handlerMillis >= 0) fields.put("handler_ms", handlerMillis);
            fields.put("total_ms", elapsedMillis(acceptedAt));
            Diagnostics.current().event("server.connection.completed", fields);
        }
    }

    private void closeClient(SSLSocket socket) {
        clients.remove(socket);
        try { socket.close(); }
        catch (IOException error) { Diagnostics.current().failure("server.connection.close_failed", error, workload()); }
    }

    @Override public void close() throws IOException {
        if (!closeStarted.compareAndSet(false, true)) return;
        closed = true;
        try {
            listener.close();
        } finally {
            clients.forEach(this::closeClient);
            workers.shutdownNow();
            try { workers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            Map<String, Object> fields = workload();
            fields.put("workers_terminated", workers.isTerminated());
            Diagnostics.current().event("server.stopped", fields);
        }
    }

    public static void main(String[] args) {
        Diagnostics diagnostics = Diagnostics.start("server");
        AtomicReference<AuthServer> runningServer = new AtomicReference<>();
        AtomicBoolean cleanupStarted = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            try {
                AuthServer server = runningServer.get();
                if (server != null) server.close();
            } catch (IOException error) {
                diagnostics.failure("server.shutdown.failed", error, Map.of());
            } finally {
                diagnostics.close();
            }
        };
        Thread shutdownHook = new Thread(cleanup, "auth-shutdown");
        boolean hookRegistered = false;
        long startupAt = System.nanoTime();
        String bindAddress = "127.0.0.1";
        int port = 8443;
        int exitCode = 0;
        String startupStep = "đọc cấu hình";
        String startupPhase = "configuration";
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;
            bindAddress = ServerConfig.get("auth.bind", bindAddress);
            port = ServerConfig.getInt("auth.port", port);
            startupStep = "kết nối MySQL và kiểm tra bảng";
            startupPhase = "database_preflight";
            long databaseAt = System.nanoTime();
            // Read-only preflight: never create or alter the user's existing database.
            try (Connection connection = DatabaseConnection.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT Ma_Nguoi_Dung, Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi, Email, Trang_Thai, Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Phien, Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc, Thu_Hoi_Luc FROM Phien_Dang_Nhap WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Phong, Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam, Ma_Chu_Phong, Trang_Thai, Da_Khoa, So_Nguoi_Toi_Da, Ngay_Tao, Ngay_Dong FROM Phong_Hop WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Thanh_Vien_Phong, Ma_Phong, Ma_Nguoi_Dung, Vai_Tro, Tham_Gia_Luc, Roi_Phong_Luc, Ly_Do_Roi FROM Thanh_Vien_Phong WHERE 1=0").close();
            }
            diagnostics.event("server.startup.database_checked", Map.of("database_ms", elapsedMillis(databaseAt)));
            startupStep = "nạp chứng chỉ TLS";
            startupPhase = "tls_context";
            long tlsAt = System.nanoTime();
            char[] password = ServerConfig.get("auth.keystorePassword", "changeit-local-only").toCharArray();
            SSLContext tls;
            try { tls = TlsSupport.server(Path.of(ServerConfig.get("auth.keystore", "config/auth-dev.p12")), password); }
            finally { Arrays.fill(password, '\0'); }
            diagnostics.event("server.startup.tls_loaded", Map.of("tls_context_ms", elapsedMillis(tlsAt)));
            startupStep = "mở cổng TCP/TLS";
            startupPhase = "listener_bind";
            AuthServer server = new AuthServer(tls, new InetSocketAddress(bindAddress, port),
                    new ApplicationService()::handle);
            runningServer.set(server);
            server.start();
            diagnostics.event("server.startup.completed", Map.of("port", server.port(), "startup_ms", elapsedMillis(startupAt)));
            System.out.println("Auth server TLS đã sẵn sàng tại " + listenerAddress(server));
            startupPhase = "listener_run";
            server.awaitTermination();
        } catch (BindException e) {
            diagnostics.failure("server.startup.failed", e,
                    Map.of("phase", startupPhase, "port", port, "total_ms", elapsedMillis(startupAt)));
            System.err.println("Không thể mở auth server tại " + bindAddress + ":" + port
                    + " (BindException: " + e.getMessage() + ").");
            System.err.println("Cổng có thể đã được một server khác sử dụng hoặc auth.bind không phải địa chỉ của máy này."
                    + " Nếu auth server đã chạy, chỉ cần chạy client; để khởi động lại, hãy dừng server cũ trước.");
            exitCode = 1;
        } catch (SQLException e) {
            diagnostics.failure("server.startup.failed", e,
                    Map.of("phase", startupPhase, "total_ms", elapsedMillis(startupAt)));
            System.err.println("Không khởi động được auth server ở bước kết nối MySQL/kiểm tra bảng"
                    + " (SQLState=" + e.getSQLState() + "). Kiểm tra db.url, db.user, db.password"
                    + " trong config/auth-server.properties hoặc các biến MMR_DB_*.");
            exitCode = 1;
        } catch (Exception e) {
            diagnostics.failure("server.startup.failed", e,
                    Map.of("phase", startupPhase, "total_ms", elapsedMillis(startupAt)));
            System.err.println("Không khởi động được auth server ở bước " + startupStep
                    + " (" + e.getClass().getSimpleName() + "). Kiểm tra cấu hình tương ứng"
                    + " trong config/auth-server.properties.");
            exitCode = 1;
        } finally {
            cleanup.run();
            if (hookRegistered) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException | SecurityException ignored) { /* The JVM may already be shutting down. */ }
            }
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private Map<String, Object> workload() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("accepted_total", acceptedConnections.get());
        fields.put("rejected_total", rejectedConnections.get());
        fields.put("completed_total", completedConnections.get());
        fields.put("active_workers", workers.getActiveCount());
        fields.put("queued_connections", workers.getQueue().size());
        fields.put("connected_clients", clients.size());
        return fields;
    }

    private static double elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000.0;
    }

    private static String safeAction(String action) {
        if (action == null) return "OTHER";
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "REGISTER" -> "REGISTER";
            case "LOGIN" -> "LOGIN";
            case "LOGOUT" -> "LOGOUT";
            case "CREATE_ROOM", "JOIN_ROOM", "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM" -> action.toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }

    private static String safeResponseCode(String code) {
        if (code == null) return "OTHER";
        return switch (code) {
            case "OK", "INVALID_REQUEST", "INVALID_CREDENTIALS", "VALIDATION_ERROR", "USERNAME_TAKEN",
                    "EMAIL_TAKEN", "DATABASE_UNAVAILABLE", "SERVER_ERROR", "SESSION_EXPIRED", "ROOM_NOT_FOUND",
                    "ROOM_CLOSED", "ROOM_LOCKED", "INVALID_ROOM_PIN", "ROOM_FULL", "ROOM_ACCESS_DENIED" -> code;
            default -> "OTHER";
        };
    }

    private static String listenerAddress(AuthServer server) {
        return server.listener.getInetAddress().getHostAddress() + ":" + server.port();
    }
}
