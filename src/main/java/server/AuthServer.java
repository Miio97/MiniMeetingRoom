package server;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.auth.TlsSupport;
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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import server.auth.AuthService;
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
                socket.setSoTimeout(10000);
                clients.add(socket);
                try { workers.execute(() -> handle(socket)); }
                catch (RejectedExecutionException e) { closeClient(socket); }
            } catch (SocketException e) {
                if (!closed) System.err.println("Auth server: không thể nhận kết nối.");
                break;
            } catch (IOException e) {
                if (!closed) System.err.println("Auth server: lỗi nhận kết nối.");
            }
        }
    }

    private void handle(SSLSocket socket) {
        try (socket) {
            socket.startHandshake();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            AuthResponse response;
            try {
                AuthRequest request = AuthWire.read(input, AuthRequest.class);
                response = handler.handle(request);
            } catch (SQLException e) {
                // Do not send SQL, connection strings or credentials to the client.
                System.err.println("Auth database error (SQLState=" + e.getSQLState() + ").");
                response = AuthResponse.error("DATABASE_UNAVAILABLE", "Không thể xử lý tài khoản lúc này. Vui lòng thử lại.");
            } catch (IOException | IllegalArgumentException e) {
                response = AuthResponse.error("INVALID_REQUEST", "Yêu cầu không hợp lệ.");
            } catch (RuntimeException e) {
                System.err.println("Auth server error: " + e.getClass().getSimpleName());
                response = AuthResponse.error("SERVER_ERROR", "Máy chủ gặp lỗi. Vui lòng thử lại.");
            }
            AuthWire.write(output, response);
        } catch (IOException e) {
            // Closed, malformed or timed-out connections are contained to one worker.
        } finally { clients.remove(socket); }
    }

    private void closeClient(SSLSocket socket) {
        clients.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    @Override public void close() throws IOException {
        closed = true;
        listener.close();
        clients.forEach(this::closeClient);
        workers.shutdownNow();
    }

    public static void main(String[] args) {
        String bindAddress = "127.0.0.1";
        int port = 8443;
        String startupStep = "đọc cấu hình";
        try {
            bindAddress = ServerConfig.get("auth.bind", bindAddress);
            port = ServerConfig.getInt("auth.port", port);
            startupStep = "kết nối MySQL và kiểm tra bảng";
            // Read-only preflight: never create or alter the user's existing database.
            try (Connection connection = DatabaseConnection.getConnection(); Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT Ma_Nguoi_Dung, Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi, Email, Trang_Thai, Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung WHERE 1=0").close();
                statement.executeQuery("SELECT Ma_Phien, Ma_Nguoi_Dung, Token_Bam, Het_Han_Luc, Thu_Hoi_Luc FROM Phien_Dang_Nhap WHERE 1=0").close();
            }
            startupStep = "nạp chứng chỉ TLS";
            char[] password = ServerConfig.get("auth.keystorePassword", "changeit-local-only").toCharArray();
            SSLContext tls;
            try { tls = TlsSupport.server(Path.of(ServerConfig.get("auth.keystore", "config/auth-dev.p12")), password); }
            finally { Arrays.fill(password, '\0'); }
            startupStep = "mở cổng TCP/TLS";
            AuthServer server = new AuthServer(tls, new InetSocketAddress(bindAddress, port),
                    new AuthService()::handle);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { server.close(); } catch (IOException ignored) { }
            }, "auth-shutdown"));
            server.start();
            System.out.println("Auth server TLS đã sẵn sàng tại " + listenerAddress(server));
            server.awaitTermination();
        } catch (BindException e) {
            System.err.println("Không thể mở auth server tại " + bindAddress + ":" + port
                    + " (BindException: " + e.getMessage() + ").");
            System.err.println("Cổng có thể đã được một server khác sử dụng hoặc auth.bind không phải địa chỉ của máy này."
                    + " Nếu auth server đã chạy, chỉ cần chạy client; để khởi động lại, hãy dừng server cũ trước.");
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Không khởi động được auth server ở bước kết nối MySQL/kiểm tra bảng"
                    + " (SQLState=" + e.getSQLState() + "). Kiểm tra db.url, db.user, db.password"
                    + " trong config/auth-server.properties hoặc các biến MMR_DB_*.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Không khởi động được auth server ở bước " + startupStep
                    + " (" + e.getClass().getSimpleName() + "). Kiểm tra cấu hình tương ứng"
                    + " trong config/auth-server.properties.");
            System.exit(1);
        }
    }

    private static String listenerAddress(AuthServer server) {
        return server.listener.getInetAddress().getHostAddress() + ":" + server.port();
    }
}
