package client.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthWire;
import common.auth.TlsSupport;
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
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Blocking network API. Call from a background task, never the JavaFX thread. */
public final class AuthClient {
    private final Settings fixedSettings;
    private final SSLContext fixedTls;

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

    private AuthResponse send(AuthRequest request) throws IOException {
        Settings settings = fixedSettings != null ? fixedSettings : Settings.load();
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
        try (Socket connection = new Socket()) {
            connection.connect(new InetSocketAddress(settings.host(), settings.port()),
                    Math.min(5000, settings.timeoutMillis()));
            try (SSLSocket socket = (SSLSocket) tls.getSocketFactory()
                    .createSocket(connection, settings.host(), settings.port(), true)) {
                socket.setSoTimeout(settings.timeoutMillis());
                socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                AuthWire.write(new DataOutputStream(socket.getOutputStream()), request);
                AuthResponse response = AuthWire.read(new DataInputStream(socket.getInputStream()), AuthResponse.class);
                if (response.code() == null || response.message() == null
                        || (request.action().equals("LOGIN") && response.success()
                        && (response.user() == null || response.user().id() <= 0
                        || response.user().username() == null || response.user().displayName() == null
                        || response.token() == null || response.token().isBlank() || response.expiresAt() == null))) {
                    throw new IOException("Phản hồi từ máy chủ không hợp lệ.");
                }
                return response;
            }
        } catch (SSLHandshakeException e) {
            throw new IOException("Không xác minh được máy chủ. Kiểm tra tên máy chủ và chứng chỉ TLS.", e);
        } catch (ConnectException e) {
            throw new IOException("Không kết nối được máy chủ. Hãy khởi động server và kiểm tra địa chỉ/cổng.", e);
        } catch (SocketTimeoutException e) {
            throw new IOException("Máy chủ phản hồi quá lâu. Vui lòng thử lại.", e);
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
