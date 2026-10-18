package client.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.auth.TlsSupport;
import common.room.RoomInfo;
import common.room.RoomRequest;
import common.room.RoomValidation;
import common.diagnostics.Diagnostics;
import common.meeting.RoomResponse;
import common.chat.*;
import client.network.NetworkMonitor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Properties;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Blocking network API. Call from a background task, never the JavaFX thread. */
public final class AuthClient {
    private static final Set<String> RESPONSE_CODES = Set.of("OK", "INVALID_REQUEST", "INVALID_CREDENTIALS",
            "VALIDATION_ERROR", "USERNAME_TAKEN", "EMAIL_TAKEN", "WRONG_PASSWORD", "DATABASE_UNAVAILABLE", "SERVER_ERROR",
            "SESSION_EXPIRED", "ROOM_NOT_FOUND", "ROOM_CLOSED", "ROOM_LOCKED", "INVALID_ROOM_PIN",
            "ROOM_FULL", "ROOM_ACCESS_DENIED", "ROOM_ALREADY_ACTIVE", "OTP_REQUIRED", "OTP_INVALID",
            "OTP_EXPIRED", "OTP_LOCKED", "OTP_COOLDOWN", "OTP_RATE_LIMIT", "OTP_UNAVAILABLE", "MAIL_UNAVAILABLE", "DB_MIGRATION_REQUIRED");
    private static final Set<String> ACTIONS = Set.of("REGISTER", "LOGIN", "LOGOUT", "CREATE_ROOM", "REOPEN_ROOM", "JOIN_ROOM",
            "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM", "VERIFY_REGISTRATION", "RESEND_REGISTRATION_OTP",
            "REQUEST_PASSWORD_RESET", "RESET_PASSWORD", "UPDATE_PROFILE", "CHANGE_PASSWORD",
            "REQUEST_EMAIL_VERIFICATION", "VERIFY_EMAIL", "REQUEST_ACCOUNT_PASSWORD_RESET", "RESET_ACCOUNT_PASSWORD");
    private final Settings fixedSettings;
    private final SSLContext fixedTls;
    private final AtomicReference<NetworkMonitor> networkMonitor = new AtomicReference<>();

    public NetworkMonitor startNetworkMonitoring() {
        NetworkMonitor next = new NetworkMonitor();
        NetworkMonitor previous = networkMonitor.getAndSet(next);
        if (previous != null) previous.close();
        return next;
    }
    public void stopNetworkMonitoring(NetworkMonitor monitor) {
        networkMonitor.compareAndSet(monitor, null); monitor.close();
    }

    public AuthClient() { fixedSettings = null; fixedTls = null; }

    public AuthClient(String host, int port, SSLContext tls, int timeoutMillis) {
        fixedSettings = new Settings(host, port, null, timeoutMillis);
        fixedTls = tls;
    }

    public AuthResponse login(String username, String password) throws IOException {
        return send(new AuthRequest("LOGIN", username, password, null, null, null));
    }

    public AuthResponse register(String username, String displayName, String email, String password)
            throws IOException {
        return send(new AuthRequest("REGISTER", username, password, displayName, email, null));
    }

    public AuthResponse logout(String token) throws IOException {
        return send(new AuthRequest("LOGOUT", null, null, null, null, token));
    }

    public AuthResponse verifyRegistration(String email, String otp) throws IOException {
        return verifyRegistration(email, otp, null);
    }

    public AuthResponse verifyRegistration(String email, String otp, String registrationChallenge) throws IOException {
        return send(new AuthRequest("VERIFY_REGISTRATION", null, null, null, email, null, null, otp, registrationChallenge));
    }

    public AuthResponse resendRegistrationOtp(String email) throws IOException {
        return resendRegistrationOtp(email, null);
    }

    public AuthResponse resendRegistrationOtp(String email, String registrationChallenge) throws IOException {
        return send(new AuthRequest("RESEND_REGISTRATION_OTP", null, null, null, email, null, null, null, registrationChallenge));
    }

    public AuthResponse requestPasswordReset(String email) throws IOException {
        return send(new AuthRequest("REQUEST_PASSWORD_RESET", null, null, null, email, null));
    }

    public AuthResponse resetPassword(String email, String otp, String newPassword) throws IOException {
        return send(new AuthRequest("RESET_PASSWORD", null, newPassword, null, email, null, null, otp));
    }

    public AuthResponse requestAccountPasswordReset(String token, String email) throws IOException {
        return send(new AuthRequest("REQUEST_ACCOUNT_PASSWORD_RESET", null, null, null, email, token));
    }

    public AuthResponse resetAccountPassword(String token, String email, String otp, String newPassword) throws IOException {
        return send(new AuthRequest("RESET_ACCOUNT_PASSWORD", null, newPassword, null, email, token, null, otp));
    }

    public AuthResponse updateProfile(String token, String username, String displayName, String email) throws IOException {
        return send(new AuthRequest("UPDATE_PROFILE", username, null, displayName, email, token));
    }

    public AuthResponse changePassword(String token, String currentPassword, String newPassword) throws IOException {
        return send(new AuthRequest("CHANGE_PASSWORD", null, currentPassword, null, null, token, null, null, null, newPassword));
    }

    public AuthResponse requestEmailVerification(String token, String email) throws IOException {
        return send(new AuthRequest("REQUEST_EMAIL_VERIFICATION", null, null, null, email, token));
    }

    public AuthResponse verifyEmail(String token, String email, String otp) throws IOException {
        return send(new AuthRequest("VERIFY_EMAIL", null, null, null, email, token, null, otp));
    }

    public AuthResponse createRoom(String token, String name, String pin, int capacity) throws IOException {
        return roomCommand("CREATE_ROOM", token, new RoomRequest(name, pin, null, capacity));
    }

    public AuthResponse joinRoom(String token, String identifier, String pin) throws IOException {
        return roomCommand("JOIN_ROOM", token, new RoomRequest(null, pin, identifier, 0));
    }

    public AuthResponse reopenRoom(String token, long roomId) throws IOException {
        return roomCommand("REOPEN_ROOM", token, new RoomRequest(null, null, Long.toString(roomId), 0));
    }

    public AuthResponse listRooms(String token) throws IOException {
        return roomCommand("LIST_ROOMS", token, null);
    }

    public AuthResponse getRoom(String token, long roomId) throws IOException {
        return roomCommand("GET_ROOM", token, new RoomRequest(null, null, Long.toString(roomId), 0));
    }

    public AuthResponse leaveRoom(String token, long roomId) throws IOException {
        return roomCommand("LEAVE_ROOM", token, new RoomRequest(null, null, Long.toString(roomId), 0));
    }

    private AuthResponse roomCommand(String action, String token, RoomRequest parameters) throws IOException {
        return send(new AuthRequest(action, null, null, null, null, token, parameters));
    }

    private AuthResponse send(AuthRequest request) throws IOException {
        RequestTiming timing = new RequestTiming(request.action());
        try {
            AuthResponse response = sendMeasured(request, timing, AuthResponse.class);
            if (response.code() == null || response.message() == null
                    || (request.action().equals("LOGIN") && response.success()
                    && (response.user() == null || response.user().id() <= 0
                    || response.user().username() == null || response.user().displayName() == null
                    || response.token() == null || response.token().isBlank() || response.expiresAt() == null))) {
                throw new IOException("Phản hồi từ máy chủ không hợp lệ.");
            }
            if (response.success()) {
                switch (request.action()) {
                    case "UPDATE_PROFILE", "CHANGE_PASSWORD", "VERIFY_EMAIL", "RESET_ACCOUNT_PASSWORD" -> {
                        if (response.user() == null || response.user().id() <= 0 || response.user().username() == null
                                || response.user().displayName() == null)
                            throw new IOException("Thông tin tài khoản từ máy chủ không hợp lệ.");
                        if (Set.of("VERIFY_EMAIL", "RESET_ACCOUNT_PASSWORD").contains(request.action()) && (!response.user().emailVerified()
                                || response.user().email() == null || request.email() == null
                                || !response.user().email().strip().toLowerCase(java.util.Locale.ROOT)
                                        .equals(request.email().strip().toLowerCase(java.util.Locale.ROOT))))
                            throw new IOException("Phản hồi xác thực email từ máy chủ không hợp lệ.");
                    }
                    case "CREATE_ROOM", "REOPEN_ROOM", "JOIN_ROOM", "GET_ROOM", "LEAVE_ROOM" -> {
                        if (!validRoom(response.room())) throw new IOException("Thông tin phòng từ máy chủ không hợp lệ.");
                    }
                    case "LIST_ROOMS" -> {
                        if (response.rooms().size() > 20 || response.rooms().stream().anyMatch(room -> !validRoom(room))) {
                            throw new IOException("Danh sách phòng từ máy chủ không hợp lệ.");
                        }
                    }
                    default -> { }
                }
            }
            Map<String, Object> fields = timing.fields(response.success() ? "success" : "rejected");
            fields.put("response_code", RESPONSE_CODES.contains(response.code()) ? response.code() : "OTHER");
            Diagnostics.current().event("auth_client_request", fields);
            return response;
        } catch (IOException | RuntimeException failure) {
            Diagnostics.current().failure("auth_client_request", failure, timing.fields("error"));
            throw failure;
        }
    }

    public RoomResponse meeting(common.meeting.RoomRequest request) throws IOException {
        RoomResponse response = sendMeasured(request, new RequestTiming("OTHER"), RoomResponse.class);
        if (response.code() == null || response.message() == null) throw new IOException("Phản hồi phòng không hợp lệ.");
        if (!response.success()) throw new RoomException(response.code(), response.message());
        if (!"ROOM_LEAVE".equals(request.action()) && (response.room() == null
                || (!response.room().closed() && (response.ticket() == null || response.ticket().isBlank())))) {
            throw new IOException("Phản hồi phòng thiếu thông tin kết nối.");
        }
        return response;
    }

    private <T> T sendMeasured(Object request, RequestTiming timing, Class<T> responseType) throws IOException {
        NetworkMonitor monitor = networkMonitor.get();
        boolean poll = request instanceof common.meeting.RoomRequest room && "ROOM_POLL".equals(room.action());
        T response;
        try (SSLSocket socket = connect(timing)) {
            // Email delivery has bounded SMTP work after TLS; keep the UI asynchronous while allowing it to finish.
            if (request instanceof AuthRequest auth && Set.of("REGISTER", "RESEND_REGISTRATION_OTP", "REQUEST_PASSWORD_RESET", "REQUEST_EMAIL_VERIFICATION", "REQUEST_ACCOUNT_PASSWORD_RESET").contains(auth.action()))
                socket.setSoTimeout(Math.max(socket.getSoTimeout(), 60000));
            timing.next("response");
            var output = socket.getOutputStream(); var input = socket.getInputStream();
            if (monitor != null) { output = monitor.output(output); input = monitor.input(input); }
            AuthWire.write(new DataOutputStream(output), request);
            response = AuthWire.read(new DataInputStream(input), responseType);
            timing.responseMillis = timing.phaseMillis();
        } catch (SocketTimeoutException error) {
            if (monitor != null) monitor.exchange(poll, null);
            throw new IOException("Máy chủ phản hồi quá lâu. Vui lòng thử lại.", error);
        } catch (IOException error) {
            if (monitor != null) monitor.exchange(poll, null);
            throw error;
        }
        if (monitor != null) monitor.exchange(poll, timing.responseMillis);
        return response;
    }

    public ChatResponse chat(ChatRequest request) throws IOException {
        ChatResponse response = sendMeasured(request, new RequestTiming("OTHER"), ChatResponse.class);
        verifyChat(response, request.roomId()); return response;
    }
    public ChatConnection listenChat(ChatRequest request, Consumer<ChatResponse> response, Consumer<Throwable> failure) {
        return new ChatConnection(() -> connect(new RequestTiming("OTHER")), request, response, failure, networkMonitor.get());
    }
    static void verifyChat(ChatResponse response, long roomId) throws IOException {
        if (response.code() == null || response.message() == null) throw new IOException("Phản hồi chat không hợp lệ.");
        if (!response.success()) throw new ChatException(response.code(), response.message());
        for (ChatMessage message : response.messages()) {
            if (message.id() <= 0 || message.roomId() != roomId || message.userId() <= 0
                    || message.name() == null || message.text() == null || message.sentAt() == null) {
                throw new IOException("Tin nhắn từ máy chủ không hợp lệ.");
            }
            try { java.time.Instant.parse(message.sentAt()); }
            catch (java.time.format.DateTimeParseException error) { throw new IOException("Thời gian tin nhắn không hợp lệ.", error); }
        }
    }

    private SSLSocket connect(RequestTiming timing) throws IOException {
        Settings settings = fixedSettings != null ? fixedSettings : Settings.load();
        timing.port = settings.port();
        timing.next("tls_context");
        SSLContext tls = fixedTls;
        if (tls == null) {
            if (!Files.isRegularFile(settings.certificate())) {
                throw new IOException("Chưa có chứng chỉ máy chủ. Hãy chạy scripts/start-server.ps1 trước.");
            }
            try { tls = TlsSupport.client(settings.certificate()); }
            catch (GeneralSecurityException e) {
                throw new IOException("Không đọc được chứng chỉ máy chủ. Hãy kiểm tra cấu hình kết nối.", e);
            }
        }
        timing.tlsContextMillis = timing.phaseMillis();
        Socket connection = new Socket();
        SSLSocket socket = null;
        boolean ready = false;
        try {
            connection.setTcpNoDelay(true);
            timing.next("tcp_connect");
            connection.connect(new InetSocketAddress(settings.host(), settings.port()),
                    Math.min(5000, settings.timeoutMillis()));
            timing.tcpConnectMillis = timing.phaseMillis();
            timing.next("tls_handshake");
            socket = (SSLSocket) tls.getSocketFactory().createSocket(connection, settings.host(), settings.port(), true);
            socket.setSoTimeout(settings.timeoutMillis());
            socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            timing.tlsHandshakeMillis = timing.phaseMillis();
            ready = true; return socket;
        } catch (SSLHandshakeException e) {
            throw new IOException("Không xác minh được máy chủ. Kiểm tra tên máy chủ và chứng chỉ TLS.", e);
        } catch (ConnectException e) {
            throw new IOException("Không kết nối được máy chủ. Hãy khởi động server và kiểm tra địa chỉ/cổng.", e);
        } catch (SocketTimeoutException e) {
            throw new IOException("Máy chủ phản hồi quá lâu. Vui lòng thử lại.", e);
        } finally {
            if (!ready) {
                if (socket != null) try { socket.close(); } catch (IOException ignored) { }
                try { connection.close(); } catch (IOException ignored) { }
            }
        }
    }

    private static boolean validRoom(RoomInfo room) {
        return room != null && room.id() > 0 && room.hostId() > 0 && room.name() != null && !room.name().isBlank()
                && room.code() != null && room.createdAt() != null && room.status() != null
                && Set.of("CHO", "DANG_HOAT_DONG", "DA_DONG").contains(room.status())
                && RoomValidation.capacityError(room.capacity()) == null
                && room.people() >= 0 && room.people() <= room.capacity()
                && room.participants().size() <= room.capacity()
                && room.participants().stream().allMatch(person -> person.userId() > 0 && person.displayName() != null
                && Set.of("CHU_PHONG", "THANH_VIEN").contains(person.role()));
    }

    /** Only fixed categories and durations enter diagnostics, never authentication payloads. */
    private static final class RequestTiming {
        private final long started = System.nanoTime();
        private final String action;
        private String phase = "load_settings";
        private long phaseStarted = started;
        private int port;
        private Double tlsContextMillis, tcpConnectMillis, tlsHandshakeMillis, responseMillis;

        RequestTiming(String action) {
            this.action = ACTIONS.contains(action) ? action : "OTHER";
        }

        void next(String phase) { this.phase = phase; phaseStarted = System.nanoTime(); }
        double phaseMillis() { return (System.nanoTime() - phaseStarted) / 1_000_000.0; }

        Map<String, Object> fields(String outcome) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("action", action); fields.put("phase", phase); fields.put("outcome", outcome);
            fields.put("total_ms", (System.nanoTime() - started) / 1_000_000.0);
            fields.put("phase_elapsed_ms", phaseMillis());
            if (port > 0) fields.put("port", port);
            if (tlsContextMillis != null) fields.put("tls_context_ms", tlsContextMillis);
            if (tcpConnectMillis != null) fields.put("tcp_connect_ms", tcpConnectMillis);
            if (tlsHandshakeMillis != null) fields.put("tls_handshake_ms", tlsHandshakeMillis);
            if (responseMillis != null) fields.put("response_ms", responseMillis);
            return fields;
        }
    }

    private record Settings(String host, int port, Path certificate, int timeoutMillis) {
        static Settings load() throws IOException {
            Properties properties = new Properties();
            Path path = Path.of(System.getProperty("mmr.client.config", "config/auth-client.properties"));
            if (Files.isRegularFile(path)) {
                try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
            }
            try {
                String host = value(properties, "auth.host", "MMR_AUTH_HOST", "localhost");
                int port = Integer.parseInt(value(properties, "auth.port", "MMR_AUTH_PORT", "8443"));
                int timeout = Integer.parseInt(value(properties, "auth.timeoutMillis", "MMR_AUTH_TIMEOUT_MILLIS", "15000"));
                if (host.isBlank() || port < 1 || port > 65535 || timeout < 1000 || timeout > 60000) {
                    throw new IllegalArgumentException("Invalid client settings");
                }
                return new Settings(host, port, Path.of(value(properties, "auth.certificate",
                        "MMR_AUTH_CERTIFICATE", "config/auth-server.cer")), timeout);
            } catch (IllegalArgumentException e) {
                throw new IOException("Cấu hình máy chủ không hợp lệ. Kiểm tra config/auth-client.properties.", e);
            }
        }

        private static String value(Properties properties, String key, String env, String fallback) {
            String override = System.getenv(env);
            return override != null ? override : properties.getProperty(key, fallback);
        }
    }
}
