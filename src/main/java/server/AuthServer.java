package server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.auth.TlsSupport;
import common.chat.*;
import common.meeting.RoomRequest;
import common.meeting.RoomResponse;

import server.config.ServerConfig;
import server.database.DatabaseConnection;
import server.filetransfer.FileMetadataRepository;
import server.filetransfer.FileStorageService;
import server.filetransfer.FileTransferServer;
import server.filetransfer.TransferConfig;
import server.meeting.RoomService;
import server.messaging.ChatService;

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
import java.util.Locale;
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

/** TLS/TCP authentication server. One bounded JSON request per connection. */
public final class AuthServer implements AutoCloseable {
    @FunctionalInterface
    public interface Handler {
        AuthResponse handle(AuthRequest request) throws SQLException;
    }

    private final SSLServerSocket listener;
    private final Handler handler;
    private final RoomService rooms;
    private final ChatService chats;
    private final Set<Thread> chatThreads = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(
                    8,
                    8,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    Thread.ofPlatform().daemon().name("auth-worker-", 0).factory());
    private final Set<SSLSocket> clients = ConcurrentHashMap.newKeySet();
    private final AtomicLong connectionSequence = new AtomicLong();

    private final AtomicBoolean closeStarted = new AtomicBoolean();
    private volatile boolean closed;
    private Thread acceptThread;

    public AuthServer(SSLContext tls, InetSocketAddress address, Handler handler)
            throws IOException {
        this(tls, address, handler, null);
    }

    public AuthServer(SSLContext tls, InetSocketAddress address, Handler handler, RoomService rooms)
            throws IOException {
        this.handler = handler;
        this.rooms = rooms;
        this.chats = rooms == null ? null : new ChatService(rooms);
        listener = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket();
        try {
            listener.setReuseAddress(true);
            listener.setEnabledProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
            listener.bind(address, 32);
        } catch (IOException | RuntimeException error) {
            try {
                listener.close();
            } catch (IOException closeError) {
                error.addSuppressed(closeError);
            }
            workers.shutdownNow();
            throw error;
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    public synchronized void start() {
        if (closed || acceptThread != null)
            throw new IllegalStateException("Server cannot be started twice");
        acceptThread = Thread.ofPlatform().daemon().name("auth-accept").start(this::accept);
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

                long connectionId = connectionSequence.incrementAndGet();
                socket.setSoTimeout(10000);
                socket.setTcpNoDelay(true);
                clients.add(socket);
                if (clients.size() > 128) {
                    closeClient(socket);
                    continue;
                }
                try {
                    workers.execute(() -> handle(socket, connectionId));
                } catch (RejectedExecutionException e) {
                    closeClient(socket);
                }
            } catch (SocketException e) {
                if (!closed) {
                    System.err.println("Auth server: không thể nhận kết nối.");
                }
                break;
            } catch (IOException e) {
                if (!closed) {
                    System.err.println("Auth server: lỗi nhận kết nối.");
                }
            }
        }
    }

    private void handle(SSLSocket socket, long connectionId) {
        String action = "OTHER";

        boolean handedOff = false;
        boolean chatRequest = false;
        try {
            socket.startHandshake();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            AuthResponse response;
            try {
                JsonObject envelope = AuthWire.read(input, JsonObject.class);
                if (envelope.has("action")
                        && (!envelope.get("action").isJsonPrimitive()
                                || !envelope.getAsJsonPrimitive("action").isString()))
                    throw new IllegalArgumentException("Invalid action");
                if (chats != null
                        && envelope.has("action")
                        && envelope.get("action").getAsString().startsWith("CHAT_")) {
                    chatRequest = true;
                    ChatRequest request = new Gson().fromJson(envelope, ChatRequest.class);
                    if ("CHAT_LISTEN".equals(request.action())) {
                        ChatService.Subscription subscription =
                                chats.subscribe(
                                        request,
                                        () -> {
                                            try {
                                                socket.close();
                                            } catch (IOException ignored) {
                                            }
                                        });
                        // Persistent readers never occupy the bounded authentication workers.
                        Thread stream =
                                Thread.ofVirtual()
                                        .name("chat-stream-" + connectionId)
                                        .unstarted(() -> streamChat(socket, output, subscription));
                        chatThreads.add(stream);
                        handedOff = true;
                        try {
                            stream.start();
                        } catch (RuntimeException error) {
                            handedOff = false;
                            chatThreads.remove(stream);
                            subscription.close();
                            throw error;
                        }
                        return;
                    }
                    ChatResponse chatResponse = chats.send(request);
                    AuthWire.write(output, chatResponse);
                    return;
                }
                if (rooms != null
                        && envelope.has("action")
                        && envelope.get("action").getAsString().startsWith("ROOM_")) {
                    RoomResponse roomResponse;
                    roomResponse = rooms.handle(new Gson().fromJson(envelope, RoomRequest.class));

                    AuthWire.write(output, roomResponse);
                    return;
                }
                AuthRequest request = new Gson().fromJson(envelope, AuthRequest.class);
                action = safeAction(request.action());

                response = handler.handle(request);
            } catch (ChatException e) {
                response = AuthResponse.error(e.code(), e.getMessage());
            } catch (SQLException e) {
                // Do not send SQL, connection strings or credentials to the client.
                System.err.println(
                        "Auth database error (SQLState="
                                + e.getSQLState()
                                + ", ErrorCode="
                                + e.getErrorCode()
                                + ").");
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
                response = AuthResponse.error("INVALID_REQUEST", "Yêu cầu không hợp lệ.");
            } catch (RuntimeException e) {
                System.err.println("Auth server error: " + e.getClass().getSimpleName());
                response = AuthResponse.error("SERVER_ERROR", "Máy chủ gặp lỗi. Vui lòng thử lại.");
            }

            AuthWire.write(output, response);
        } catch (IOException e) {
            // Closed, malformed or timed-out connections are contained to one worker.
        } finally {
            if (!handedOff) {
                closeClient(socket);
            }
        }
    }

    private void streamChat(
            SSLSocket socket, DataOutputStream output, ChatService.Subscription subscription) {
        try (socket;
                subscription) {
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
                    AuthWire.write(
                            output, event == null ? ChatResponse.ok(java.util.List.of()) : event);
                }
            } catch (ChatException error) {
                AuthWire.write(output, ChatResponse.error(error.code(), error.getMessage()));
            } catch (SQLException error) {
                AuthWire.write(
                        output,
                        ChatResponse.error(
                                "DATABASE_UNAVAILABLE",
                                "Không thể tải chat lúc này. Vui lòng thử lại."));
            }
        } catch (IOException error) {
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            closeClient(socket);
            chatThreads.remove(Thread.currentThread());
        }
    }

    private void closeClient(SSLSocket socket) {
        clients.remove(socket);
        try {
            socket.close();
        } catch (IOException error) {
        }
    }

    @Override
    public void close() throws IOException {
        if (!closeStarted.compareAndSet(false, true)) return;
        closed = true;
        try {
            listener.close();
        } finally {
            if (chats != null) chats.close();
            clients.forEach(this::closeClient);
            chatThreads.forEach(Thread::interrupt);
            workers.shutdownNow();
            try {
                workers.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            for (Thread thread : java.util.List.copyOf(chatThreads)) {
                try {
                    thread.join(
                            Math.max(
                                    1,
                                    TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (System.nanoTime() >= deadline) break;
            }
            if (rooms != null)
                try {
                    rooms.close();
                } catch (Exception error) {
                }
        }
    }

    public static void main(String[] args) {
        AtomicReference<AuthServer> runningServer = new AtomicReference<>();
        AtomicReference<FileTransferServer> runningFiles = new AtomicReference<>();
        AtomicReference<server.meeting.ImmediateRoomCleanup> runningCleanup =
                new AtomicReference<>();
        AtomicBoolean cleanupStarted = new AtomicBoolean();
        Runnable cleanup =
                () -> {
                    if (!cleanupStarted.compareAndSet(false, true)) return;
                    try {
                        AuthServer server = runningServer.get();
                        if (server != null) server.close();
                    } catch (IOException error) {
                    } finally {
                        var roomCleanup = runningCleanup.get();
                        if (roomCleanup != null) roomCleanup.close();
                        FileTransferServer files = runningFiles.get();
                        if (files != null)
                            try {
                                files.close();
                            } catch (IOException error) {
                            }
                        DatabaseConnection.close();
                    }
                };
        Thread shutdownHook = new Thread(cleanup, "auth-shutdown");
        boolean hookRegistered = false;

        String bindAddress = "127.0.0.1";
        int port = 8443;
        int exitCode = 0;
        String startupStep = "đọc cấu hình";
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;
            bindAddress = ServerConfig.get("auth.bind", bindAddress);
            port = ServerConfig.getInt("auth.port", port);
            TransferConfig transferConfig = TransferConfig.load();
            startupStep = "kết nối MySQL và kiểm tra bảng";

            // Read-only preflight: never create or alter the user's existing database.
            try (Connection connection = DatabaseConnection.getConnection();
                    Statement statement = connection.createStatement()) {
                statement
                        .executeQuery(
                                "SELECT Ma_Nguoi_Dung, Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi,"
                                    + " Email, Trang_Thai, Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung WHERE"
                                    + " 1=0")
                        .close();
                statement
                        .executeQuery(
                                "SELECT Ma_Phien, Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc,"
                                        + " Thu_Hoi_Luc FROM Phien_Dang_Nhap WHERE 1=0")
                        .close();
                statement
                        .executeQuery(
                                "SELECT Ma_Phong, Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam,"
                                        + " Ma_Chu_Phong, Trang_Thai, Da_Khoa, So_Nguoi_Toi_Da,"
                                        + " Ngay_Tao, Ngay_Dong FROM Phong_Hop WHERE 1=0")
                        .close();
                statement
                        .executeQuery(
                                "SELECT Ma_Thanh_Vien_Phong, Ma_Phong, Ma_Nguoi_Dung, Vai_Tro,"
                                        + " Tham_Gia_Luc, Roi_Phong_Luc, Ly_Do_Roi FROM"
                                        + " Thanh_Vien_Phong WHERE 1=0")
                        .close();
                statement
                        .executeQuery(
                                "SELECT Ma_Lich_Hen, Ma_Nguoi_Tao, Tieu_De, Mo_Ta, Bat_Dau_Luc,"
                                    + " Ket_Thuc_Luc, Lap_Lai, Ma_Phong, Ngay_Tao, Ngay_Xoa FROM"
                                    + " Lich_Hen WHERE 1=0")
                        .close();
                statement
                        .executeQuery(
                                "SELECT Ma_Phong, Room_Opaque_ID, Instance_ID, Server_Epoch FROM"
                                        + " MMR_Phong_Van_Hanh WHERE 1=0")
                        .close();
                if (transferConfig.enabled()) {
                    statement
                            .executeQuery(
                                    "SELECT Transfer_Id, Ma_Tep, Ma_Tin_Nhan, Duong_Dan_Luu_Tru"
                                            + " FROM Tep_Dinh_Kem WHERE 1=0")
                            .close();
                    statement
                            .executeQuery(
                                    "SELECT Ma_Tin_Nhan, Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan,"
                                            + " Ngay_Xoa FROM Tin_Nhan WHERE 1=0")
                            .close();
                }
            }

            startupStep = "nạp chứng chỉ TLS";

            char[] password =
                    ServerConfig.get("auth.keystorePassword", "changeit-local-only").toCharArray();
            SSLContext tls;
            try {
                tls =
                        TlsSupport.server(
                                Path.of(ServerConfig.get("auth.keystore", "config/auth-dev.p12")),
                                password);
            } finally {
                Arrays.fill(password, '\0');
            }

            startupStep = "mở cổng TCP/TLS";

            var authRooms = new server.rooms.RoomService();
            String legacyInstance = server.config.MeetingSecrets.load().instanceId();
            authRooms.legacyOwner(legacyInstance);
            var contentCleanup =
                    new server.meeting.ImmediateRoomCleanup(
                            DatabaseConnection::getConnection,
                            legacyInstance,
                            id -> {
                                var service = runningFiles.get();
                                if (service != null) service.cleanupRoom(id);
                            },
                            id ->
                                    runningFiles.get() != null
                                            && runningFiles.get().hasPendingRoom(id),
                            () -> {});
            runningCleanup.set(contentCleanup);
            authRooms.setReopenGuard(contentCleanup);
            authRooms.onChanged(() -> contentCleanup.request(0));
            var legacyRooms = new RoomService();
            legacyRooms.legacyOwner(legacyInstance);
            AuthServer server =
                    new AuthServer(
                            tls,
                            new InetSocketAddress(bindAddress, port),
                            new ApplicationService(new server.auth.AuthService(), authRooms)
                                    ::handle,
                            legacyRooms);
            runningServer.set(server);

            FileTransferServer files = fileServer(tls, transferConfig, legacyInstance);
            runningFiles.set(files);
            contentCleanup.start();
            server.start();
            if (files != null) {
                files.start();
                System.out.println(
                        "File transfer TCP/TLS đã sẵn sàng tại "
                                + transferConfig.bind()
                                + ":"
                                + files.port());
            }

            System.out.println("Auth server TLS đã sẵn sàng tại " + listenerAddress(server));

            server.awaitTermination();
        } catch (BindException e) {
            System.err.println(
                    "Không thể mở auth server tại "
                            + bindAddress
                            + ":"
                            + port
                            + " (BindException: "
                            + e.getMessage()
                            + ").");
            System.err.println(
                    "Kiểm tra cổng auth/file.transfer.port và địa chỉ bind: cổng có thể đã được"
                        + " server khác sử dụng. Nếu auth server đã chạy, chỉ cần chạy client; để"
                        + " khởi động lại, hãy dừng server cũ trước.");
            exitCode = 1;
        } catch (SQLException e) {
            System.err.println(
                    "Không khởi động được auth server ở bước kết nối MySQL/kiểm tra bảng"
                            + " (SQLState="
                            + e.getSQLState()
                            + "). Kiểm tra db.url, db.user, db.password trong"
                            + " config/auth-server.properties hoặc các biến MMR_DB_*. Nếu thiếu"
                            + " Transfer_Id, áp dụng database/migrations/001_file_transfer.sql"
                            + " trước khi bật file transfer. Nếu thiếu bảng Lich_Hen, áp dụng"
                            + " database/migrations/002_lich_hen.sql cho database hiện có"
                            + " (SCHEMA_NOT_READY).");
            exitCode = 1;
        } catch (Exception e) {
            System.err.println(
                    "Không khởi động được auth server ở bước "
                            + startupStep
                            + " ("
                            + e.getClass().getSimpleName()
                            + "). Kiểm tra cấu hình tương ứng"
                            + " trong config/auth-server.properties.");
            exitCode = 1;
        } finally {
            cleanup.run();
            if (hookRegistered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException | SecurityException ignored) {
                    /* The JVM may already be shutting down. */
                }
            }
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static String safeAction(String action) {
        if (action == null) return "OTHER";
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "REGISTER" -> "REGISTER";
            case "LOGIN" -> "LOGIN";
            case "RESUME_SESSION" -> "RESUME_SESSION";
            case "LOGOUT" -> "LOGOUT";
            case "CREATE_ROOM",
                            "REOPEN_ROOM",
                            "JOIN_ROOM",
                            "LIST_ROOMS",
                            "GET_ROOM",
                            "LEAVE_ROOM",
                            "VERIFY_REGISTRATION",
                            "RESEND_REGISTRATION_OTP",
                            "REQUEST_PASSWORD_RESET",
                            "RESET_PASSWORD",
                            "UPDATE_PROFILE",
                            "CHANGE_PASSWORD",
                            "REQUEST_EMAIL_VERIFICATION",
                            "VERIFY_EMAIL",
                            "REQUEST_ACCOUNT_PASSWORD_RESET",
                            "RESET_ACCOUNT_PASSWORD" ->
                    action.toUpperCase(Locale.ROOT);
            default -> "OTHER";
        };
    }

    private static String listenerAddress(AuthServer server) {
        return server.listener.getInetAddress().getHostAddress() + ":" + server.port();
    }

    static FileTransferServer fileServer(SSLContext tls, TransferConfig config) throws IOException {
        return fileServer(tls, config, null);
    }

    static FileTransferServer fileServer(SSLContext tls, TransferConfig config, String instance)
            throws IOException {
        if (!config.enabled()) return null;
        FileStorageService storage =
                new FileStorageService(
                        config.storagePath(),
                        config.quotaBytes(),
                        config.maxFileBytes(),
                        config.maxPendingPerUser(),
                        config.expiry(),
                        config.resumeEnabled());
        try {
            var repository = new FileMetadataRepository();
            if (instance != null) repository.legacyOwner(instance);
            return new FileTransferServer(tls, config, repository, storage);
        } catch (IOException | RuntimeException e) {
            storage.close();
            throw e;
        }
    }
}
