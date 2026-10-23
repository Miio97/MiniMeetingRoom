    package server;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.auth.TlsSupport;
import common.diagnostics.Diagnostics;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import common.meeting.RoomRequest;
import common.meeting.RoomResponse;
import common.chat.*;
import server.messaging.ChatService;
import server.meeting.RoomService;
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
import server.filetransfer.FileMetadataRepository;
import server.filetransfer.FileStorageService;
import server.filetransfer.FileTransferServer;
import server.filetransfer.TransferConfig;

/** TLS/TCP authentication server. One bounded JSON request per connection. */
public final class AuthServer implements AutoCloseable {
    @FunctionalInterface public interface Handler {
        AuthResponse handle(AuthRequest request) throws SQLException;
    }

    private final SSLServerSocket listener;
    private final Handler handler;
    private final RoomService rooms;
    private final ChatService chats;
    private final Set<Thread> chatThreads = ConcurrentHashMap.newKeySet();
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
        this(tls, address, handler, null);
    }
    public AuthServer(SSLContext tls, InetSocketAddress address, Handler handler, RoomService rooms) throws IOException {
        this.handler = handler;
        this.rooms = rooms;
        this.chats = rooms == null ? null : new ChatService(rooms);
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
                socket.setTcpNoDelay(true);
                clients.add(socket);
                if (clients.size() > 128) { rejectedConnections.incrementAndGet(); closeClient(socket); continue; }
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
        boolean handedOff = false;
        boolean chatRequest = false;
        try {
            try { socket.startHandshake(); }
            finally { handshakeMillis = elapsedMillis(handshakeAt); }
            phase = "request_read";
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            AuthResponse response;
            try {
                JsonObject envelope = AuthWire.read(input, JsonObject.class);
                if (envelope.has("action") && (!envelope.get("action").isJsonPrimitive() || !envelope.getAsJsonPrimitive("action").isString())) throw new IllegalArgumentException("Invalid action");
                if (chats != null && envelope.has("action") && envelope.get("action").getAsString().startsWith("CHAT_")) {
                    chatRequest = true;
                    ChatRequest request = new Gson().fromJson(envelope, ChatRequest.class);
                    phase = "request_handler";
                    if ("CHAT_LISTEN".equals(request.action())) {
                        ChatService.Subscription subscription = chats.subscribe(request, () -> {
                            try { socket.close(); } catch (IOException ignored) { }
                        });
                        // Persistent readers never occupy the bounded authentication workers.
                        Thread stream = Thread.ofVirtual().name("chat-stream-" + connectionId).unstarted(
                                () -> streamChat(socket, output, subscription, connectionId, acceptedAt));
                        chatThreads.add(stream); handedOff = true;
                        try { stream.start(); }
                        catch (RuntimeException error) { handedOff = false; chatThreads.remove(stream); subscription.close(); throw error; }
                        result = "success"; responseCode = "OK"; phase = "chat_stream"; return;
                    }
                    ChatResponse chatResponse = chats.send(request);
                    AuthWire.write(output, chatResponse);
                    result = chatResponse.success() ? "success" : "rejected"; responseCode = safeResponseCode(chatResponse.code()); return;
                }
                if (rooms != null && envelope.has("action") && envelope.get("action").getAsString().startsWith("ROOM_")) {
                    phase = "request_handler"; long handlerAt = System.nanoTime(); RoomResponse roomResponse;
                    try { roomResponse = rooms.handle(new Gson().fromJson(envelope, RoomRequest.class)); }
                    finally { handlerMillis = elapsedMillis(handlerAt); }
                    responseCode = safeResponseCode(roomResponse.code()); phase = "response_write";
                    AuthWire.write(output, roomResponse); result = roomResponse.success() ? "success" : "rejected"; phase = "connection_close"; return;
                }
                AuthRequest request = new Gson().fromJson(envelope, AuthRequest.class);
                action = safeAction(request.action());
                phase = "request_handler";
                long handlerAt = System.nanoTime();
                try { response = handler.handle(request); }
                finally { handlerMillis = elapsedMillis(handlerAt); }
            } catch (ChatException e) {
                response = AuthResponse.error(e.code(), e.getMessage());
            } catch (SQLException e) {
                Diagnostics.current().failure("server.request.failed", e,
                        Map.of("connection_id", connectionId, "phase", phase, "action", action));
                // Do not send SQL, connection strings or credentials to the client.
                System.err.println("Auth database error (SQLState=" + e.getSQLState() + ", ErrorCode=" + e.getErrorCode() + ").");
                String dbErrorMessage;
                if (chatRequest) {
                    dbErrorMessage = "Không thể lưu tin nhắn lúc này. Vui lòng thử lại.";
                } else if (action != null && action.contains("ROOM")) {
                    dbErrorMessage = "Không thể xử lý phòng họp lúc này. Vui lòng thử lại.";
                } else {
                    dbErrorMessage = "Không thể xử lý tài khoản lúc này. Vui lòng thử lại.";
                }
                response = AuthResponse.error("DATABASE_UNAVAILABLE", dbErrorMessage);
            } catch (IOException | IllegalArgumentException | JsonParseException e) {
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
            if (!handedOff) { closeClient(socket); completedConnections.incrementAndGet(); }
            Map<String, Object> fields = workload();
            fields.put("connection_id", connectionId);
            fields.put("action", action);
            fields.put("response_code", responseCode);
            fields.put("outcome", result);
            fields.put("queue_ms", queueMillis);
            fields.put("tls_handshake_ms", handshakeMillis);
            if (handlerMillis >= 0) fields.put("handler_ms", handlerMillis);
            fields.put("total_ms", elapsedMillis(acceptedAt));
            Diagnostics.current().event(handedOff ? "server.chat.connected" : "server.connection.completed", fields);
        }
    }

    private void streamChat(SSLSocket socket, DataOutputStream output, ChatService.Subscription subscription,
            long connectionId, long acceptedAt) {
        try (socket; subscription) {
            try {
                AuthWire.write(output, ChatResponse.ok(java.util.List.of()));
                // History uses one message per bounded UTF-8 frame, including large escaped texts.
                for (ChatMessage message : subscription.history()) {
                    if (closed || subscription.ended()) return;
                    chats.validate(subscription);
                    AuthWire.write(output, ChatResponse.ok(java.util.List.of(message)));
                }
                while (!closed && !subscription.ended()) {
                    ChatResponse event = subscription.next();
                    if (subscription.ended()) break;
                    chats.validate(subscription);
                    AuthWire.write(output, event == null ? ChatResponse.ok(java.util.List.of()) : event);
                }
            } catch (ChatException error) {
                AuthWire.write(output, ChatResponse.error(error.code(), error.getMessage()));
            } catch (SQLException error) {
                Diagnostics.current().failure("server.chat.database_failed", error, Map.of("connection_id", connectionId));
                AuthWire.write(output, ChatResponse.error("DATABASE_UNAVAILABLE", "Không thể tải chat lúc này. Vui lòng thử lại."));
            }
        } catch (IOException error) {
            if (!closed) Diagnostics.current().failure("server.chat.connection_closed", error, Map.of("connection_id", connectionId));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            closeClient(socket); chatThreads.remove(Thread.currentThread()); completedConnections.incrementAndGet();
            Diagnostics.current().event("server.connection.completed", Map.of("connection_id", connectionId,
                    "action", "OTHER", "total_ms", elapsedMillis(acceptedAt), "outcome", "chat_closed"));
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
            if (chats != null) chats.close();
            clients.forEach(this::closeClient);
            chatThreads.forEach(Thread::interrupt);
            workers.shutdownNow();
            try { workers.awaitTermination(1, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            for (Thread thread : java.util.List.copyOf(chatThreads)) {
                try { thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                if (System.nanoTime() >= deadline) break;
            }
            if (rooms != null) try { rooms.close(); }
            catch (Exception error) { Diagnostics.current().failure("server.rooms.close_failed", error, Map.of()); }
            Map<String, Object> fields = workload();
            fields.put("workers_terminated", workers.isTerminated());
            Diagnostics.current().event("server.stopped", fields);
        }
    }

    public static void main(String[] args) {
        Diagnostics diagnostics = Diagnostics.start("server");
        AtomicReference<AuthServer> runningServer = new AtomicReference<>();
        AtomicReference<FileTransferServer> runningFiles = new AtomicReference<>();
        AtomicReference<server.meeting.ImmediateRoomCleanup> runningCleanup=new AtomicReference<>();
        AtomicBoolean cleanupStarted = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleanupStarted.compareAndSet(false, true)) return;
            try {
                AuthServer server = runningServer.get();
                if (server != null) server.close();
            } catch (IOException error) {
                diagnostics.failure("server.shutdown.failed", error, Map.of());
            } finally {
                var roomCleanup=runningCleanup.get();if(roomCleanup!=null) roomCleanup.close();
                FileTransferServer files = runningFiles.get();
                if (files != null) try { files.close(); }
                catch (IOException error) { diagnostics.failure("server.file_shutdown.failed", error, Map.of()); }
                DatabaseConnection.close(); diagnostics.close();
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
            TransferConfig transferConfig = TransferConfig.load();
            startupStep = "kết nối MySQL và kiểm tra bảng";
            startupPhase = "database_preflight";
            long databaseAt = System.nanoTime();
            // Read-only preflight: never create or alter the user's existing database.
            try (Connection connection = DatabaseConnection.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT Ma_Nguoi_Dung, Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi, Email, Trang_Thai, Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Phien, Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc, Thu_Hoi_Luc FROM Phien_Dang_Nhap WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Phong, Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam, Ma_Chu_Phong, Trang_Thai, Da_Khoa, So_Nguoi_Toi_Da, Ngay_Tao, Ngay_Dong FROM Phong_Hop WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Thanh_Vien_Phong, Ma_Phong, Ma_Nguoi_Dung, Vai_Tro, Tham_Gia_Luc, Roi_Phong_Luc, Ly_Do_Roi FROM Thanh_Vien_Phong WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Lich_Hen, Ma_Nguoi_Tao, Tieu_De, Mo_Ta, Bat_Dau_Luc, Ket_Thuc_Luc, Lap_Lai, Ma_Phong, Ngay_Tao, Ngay_Xoa FROM Lich_Hen WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Phong, Room_Opaque_ID, Instance_ID, Server_Epoch FROM MMR_Phong_Van_Hanh WHERE 1=0").close();
                if (transferConfig.enabled()) {
                    statement.executeQuery("SELECT Transfer_Id, Ma_Tep, Ma_Tin_Nhan, Duong_Dan_Luu_Tru FROM Tep_Dinh_Kem WHERE 1=0").close();
                    statement.executeQuery("SELECT Ma_Tin_Nhan, Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Ngay_Xoa FROM Tin_Nhan WHERE 1=0").close();
                }
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
            var authRooms=new server.rooms.RoomService();
            String legacyInstance=server.config.MeetingSecrets.load().instanceId();authRooms.legacyOwner(legacyInstance);
            var contentCleanup=new server.meeting.ImmediateRoomCleanup(DatabaseConnection::getConnection,
                    legacyInstance,true,Math.max(1,Math.min(2,(ServerConfig.getInt("db.pool.maxConnections",8)-2)/2)),
                    (f,b) -> {var service=runningFiles.get();if(service==null) throw new IOException("FILE_STORAGE_UNAVAILABLE");return service.cleanupBatch(f,b);},
                    id -> runningFiles.get()==null || runningFiles.get().hasPendingRoom(id),
                    f -> {var service=runningFiles.get();if(service==null) throw new IOException("FILE_STORAGE_UNAVAILABLE");return service.cancelAndDrained(f);},() -> { });
            runningCleanup.set(contentCleanup);authRooms.setReopenGuard(contentCleanup);authRooms.onChanged(() -> contentCleanup.request(0));
            authRooms.onRoomClosed(contentCleanup::request);
            var legacyRooms=new RoomService();legacyRooms.legacyOwner(legacyInstance);
            legacyRooms.onRoomClosed(contentCleanup::request);
            AuthServer server = new AuthServer(tls, new InetSocketAddress(bindAddress, port),
                    new ApplicationService(new server.auth.AuthService(),authRooms)::handle, legacyRooms);
            runningServer.set(server);
            startupPhase = "file_listener_bind";
            FileTransferServer files = fileServer(tls, transferConfig,legacyInstance);
            runningFiles.set(files);
            if(files!=null) files.cleanupTermination(contentCleanup::isTerminated);
            contentCleanup.start();
            server.start();
            if (files != null) {
                files.start();
                System.out.println("File transfer TCP/TLS đã sẵn sàng tại " + transferConfig.bind() + ":" + files.port());
            }
            diagnostics.event("server.startup.completed", Map.of("port", server.port(), "startup_ms", elapsedMillis(startupAt)));
            System.out.println("Auth server TLS đã sẵn sàng tại " + listenerAddress(server));
            startupPhase = "listener_run";
            server.awaitTermination();
        } catch (BindException e) {
            diagnostics.failure("server.startup.failed", e,
                    Map.of("phase", startupPhase, "port", port, "total_ms", elapsedMillis(startupAt)));
            System.err.println("Không thể mở auth server tại " + bindAddress + ":" + port
                    + " (BindException: " + e.getMessage() + ").");
            System.err.println("Kiểm tra cổng auth/file.transfer.port và địa chỉ bind: cổng có thể đã được server khác sử dụng."
                    + " Nếu auth server đã chạy, chỉ cần chạy client; để khởi động lại, hãy dừng server cũ trước.");
            exitCode = 1;
        } catch (SQLException e) {
            diagnostics.failure("server.startup.failed", e,
                    Map.of("phase", startupPhase, "total_ms", elapsedMillis(startupAt)));
            System.err.println("Không khởi động được auth server ở bước kết nối MySQL/kiểm tra bảng"
                    + " (SQLState=" + e.getSQLState() + "). Kiểm tra db.url, db.user, db.password"
                    + " trong config/auth-server.properties hoặc các biến MMR_DB_*."
                    + " Nếu thiếu Transfer_Id, áp dụng database/migrations/001_file_transfer.sql trước khi bật file transfer."
                    + " Nếu thiếu bảng Lich_Hen, áp dụng database/migrations/002_lich_hen.sql cho database hiện có (SCHEMA_NOT_READY).");
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
            case "RESUME_SESSION" -> "RESUME_SESSION";
            case "LOGOUT" -> "LOGOUT";
            case "CREATE_ROOM", "REOPEN_ROOM", "JOIN_ROOM", "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM", "VERIFY_REGISTRATION", "RESEND_REGISTRATION_OTP", "REQUEST_PASSWORD_RESET", "RESET_PASSWORD", "UPDATE_PROFILE", "CHANGE_PASSWORD", "REQUEST_EMAIL_VERIFICATION", "VERIFY_EMAIL", "REQUEST_ACCOUNT_PASSWORD_RESET", "RESET_ACCOUNT_PASSWORD" -> action.toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }

    private static String safeResponseCode(String code) {
        if (code == null) return "OTHER";
        return switch (code) {
            case "OK", "INVALID_REQUEST", "INVALID_CREDENTIALS", "VALIDATION_ERROR", "USERNAME_TAKEN",
                    "EMAIL_TAKEN", "DATABASE_UNAVAILABLE", "SERVER_ERROR", "SESSION_EXPIRED", "ROOM_NOT_FOUND", "ROOM_CLOSED", "ROOM_LOCKED", "INVALID_ROOM_PIN", "ROOM_FULL", "ROOM_ACCESS_DENIED", "ROOM_ALREADY_ACTIVE", "PERMISSION_DENIED", "WRONG_PASSWORD", "OTP_REQUIRED", "OTP_INVALID", "OTP_EXPIRED", "OTP_LOCKED", "OTP_COOLDOWN", "OTP_RATE_LIMIT", "OTP_UNAVAILABLE", "MAIL_UNAVAILABLE", "DB_MIGRATION_REQUIRED" -> code;
            default -> "OTHER";
        };
    }

    private static String listenerAddress(AuthServer server) {
        return server.listener.getInetAddress().getHostAddress() + ":" + server.port();
    }

    static FileTransferServer fileServer(SSLContext tls, TransferConfig config) throws IOException {
        return fileServer(tls,config,null);
    }
    static FileTransferServer fileServer(SSLContext tls, TransferConfig config,String instance) throws IOException {
        if (!config.enabled()) return null;
        FileStorageService storage = new FileStorageService(config.storagePath(), config.quotaBytes(),
                config.maxFileBytes(), config.maxPendingPerUser(), config.expiry(), config.resumeEnabled());
        try {var repository=new FileMetadataRepository();if(instance!=null) repository.legacyOwner(instance);return new FileTransferServer(tls, config,repository, storage); }
        catch (IOException | RuntimeException e) { storage.close(); throw e; }
    }
}
