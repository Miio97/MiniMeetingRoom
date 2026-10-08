package server;

import static org.junit.jupiter.api.Assertions.*;

import client.auth.AuthClient;
import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.auth.AuthWire;
import common.auth.TlsSupport;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real TLS sockets and production framing without connecting to a database. */
@Timeout(20)
class AuthServerTest {
    @TempDir static Path temporary;
    private static SSLContext serverTls;
    private static SSLContext trustedClientTls;
    private static SSLContext wrongHostnameServerTls;
    private static SSLContext wrongHostnameClientTls;
    private static final String KEY_PASSWORD = "test-transport-only";
    private static final AuthUser USER = new AuthUser(42, "khoa", "Trần Văn Khoa", "khoa@example.com");

    @BeforeAll
    @Timeout(60)
    static void prepareCertificates() throws Exception {
        Path serverKeys = generateCertificate("server", "dns:localhost,ip:127.0.0.1");
        serverTls = TlsSupport.server(serverKeys, KEY_PASSWORD.toCharArray());
        trustedClientTls = TlsSupport.client(temporary.resolve("server.cer"));
        Path wrongKeys = generateCertificate("wrong-host", "dns:wrong.example.invalid");
        wrongHostnameServerTls = TlsSupport.server(wrongKeys, KEY_PASSWORD.toCharArray());
        wrongHostnameClientTls = TlsSupport.client(temporary.resolve("wrong-host.cer"));
    }

    @Test
    void clientAndServerRoundTripAllAuthenticationCommands() throws Exception {
        List<AuthRequest> requests = new CopyOnWriteArrayList<>();
        try (AuthServer server = server(serverTls, request -> {
            requests.add(request);
            return switch (request.action()) {
                case "LOGIN" -> AuthResponse.ok("Đăng nhập thành công", USER, "opaque-session-token", "2026-10-09T00:00:00Z");
                case "REGISTER" -> AuthResponse.ok("Đăng ký thành công", USER, null, null);
                case "LOGOUT" -> AuthResponse.ok("Đã đăng xuất", null, null, null);
                default -> AuthResponse.error("INVALID_REQUEST", "Unknown action");
            };
        })) {
            AuthClient client = client(server, trustedClientTls);
            AuthResponse registered = client.register("khoa", "Trần Văn Khoa", "khoa@example.com", "mật-khẩu-🔐");
            assertTrue(registered.success());
            assertEquals(USER, registered.user());
            AuthResponse loggedIn = client.login("khoa", "mật-khẩu-🔐");
            assertTrue(loggedIn.success());
            assertEquals("opaque-session-token", loggedIn.token());
            assertTrue(client.logout(loggedIn.token()).success());

            assertEquals(List.of("REGISTER", "LOGIN", "LOGOUT"), requests.stream().map(AuthRequest::action).toList());
            assertEquals("Trần Văn Khoa", requests.get(0).displayName());
            assertEquals("khoa@example.com", requests.get(0).email());
            assertEquals("mật-khẩu-🔐", requests.get(0).password());
            assertEquals("mật-khẩu-🔐", requests.get(1).password());
            assertEquals("opaque-session-token", requests.get(2).token());
        }
    }

    @Test
    void rejectsPortAlreadyInUseWithoutInterruptingRunningServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (AuthServer original = server(serverTls, request -> {
            calls.incrementAndGet();
            return AuthResponse.ok("Đã đăng xuất", null, null, null);
        })) {
            assertThrows(BindException.class, () -> {
                try (AuthServer duplicate = new AuthServer(serverTls,
                        new InetSocketAddress("127.0.0.1", original.port()), request -> {
                            fail("The duplicate server must not receive requests");
                            return null;
                        })) {
                    duplicate.start();
                }
            });

            assertTrue(client(original, trustedClientTls).logout("valid-token").success());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void sqlFailureReturnsSanitizedErrorAndServerRemainsUsable() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (AuthServer server = server(serverTls, request -> {
            if (calls.getAndIncrement() == 0) {
                throw new SQLException("jdbc:mysql://private-db secret-password SELECT Mat_Khau_Bam", "08001");
            }
            return AuthResponse.ok("Đã đăng xuất", null, null, null);
        })) {
            AuthClient client = client(server, trustedClientTls);
            AuthResponse response = client.login("khoa", "password");
            assertFalse(response.success());
            assertEquals("DATABASE_UNAVAILABLE", response.code());
            assertNull(response.user());
            assertNull(response.token());
            assertFalse(response.message().contains("private-db"));
            assertFalse(response.message().contains("secret-password"));
            assertFalse(response.message().contains("SELECT"));
            assertTrue(client.logout("a-token").success());
        }
    }

    @Test
    void malformedFrameIsRejectedBeforeHandlerAndDoesNotStopServer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (AuthServer server = server(serverTls, request -> {
            calls.incrementAndGet();
            return AuthResponse.ok("Đã đăng xuất", null, null, null);
        })) {
            try (SSLSocket socket = (SSLSocket) trustedClientTls.getSocketFactory()
                    .createSocket("localhost", server.port())) {
                socket.setSoTimeout(5000);
                socket.startHandshake();
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeInt(AuthWire.MAX_FRAME_BYTES + 1);
                output.flush();
                AuthResponse response = AuthWire.read(new DataInputStream(socket.getInputStream()), AuthResponse.class);
                assertFalse(response.success());
                assertEquals("INVALID_REQUEST", response.code());
                assertEquals(0, calls.get());
            }
            assertTrue(client(server, trustedClientTls).logout("valid-token").success());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void rejectsUntrustedServerCertificateBeforeSendingCredentials() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SSLContext defaultTrust = SSLContext.getInstance("TLS");
        defaultTrust.init(null, null, null);
        try (AuthServer server = server(serverTls, request -> {
            calls.incrementAndGet();
            return AuthResponse.ok("Unexpected", USER, "token", "expiry");
        })) {
            IOException failure = assertThrows(IOException.class,
                    () -> client(server, defaultTrust).login("khoa", "private-password"));
            assertTrue(hasCause(failure, SSLHandshakeException.class));
            assertEquals(0, calls.get());
        }
    }

    @Test
    void rejectsTrustedCertificateWithWrongHostnameBeforeSendingCredentials() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (AuthServer server = server(wrongHostnameServerTls, request -> {
            calls.incrementAndGet();
            return AuthResponse.ok("Unexpected", USER, "token", "expiry");
        })) {
            IOException failure = assertThrows(IOException.class,
                    () -> client(server, wrongHostnameClientTls).login("khoa", "private-password"));
            assertTrue(hasCause(failure, SSLHandshakeException.class));
            assertEquals(0, calls.get());
        }
    }

    private static AuthServer server(SSLContext tls, AuthServer.Handler handler) throws IOException {
        AuthServer server = new AuthServer(tls, new InetSocketAddress("127.0.0.1", 0), handler);
        server.start();
        return server;
    }

    private static AuthClient client(AuthServer server, SSLContext tls) {
        return new AuthClient("localhost", server.port(), tls, 5000);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static Path generateCertificate(String name, String subjectAlternativeNames) throws Exception {
        Path keys = temporary.resolve(name + ".p12");
        keytool("-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keys.toString(), "-storepass", KEY_PASSWORD,
                "-keypass", KEY_PASSWORD, "-dname", "CN=localhost", "-validity", "2",
                "-ext", "SAN=" + subjectAlternativeNames, "-noprompt");
        keytool("-exportcert", "-alias", "test", "-keystore", keys.toString(),
                "-storepass", KEY_PASSWORD, "-rfc", "-file", temporary.resolve(name + ".cer").toString());
        return keys;
    }

    private static void keytool(String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        if (!Files.isRegularFile(executable)) {
            executable = Path.of(System.getProperty("java.home"), "bin", "keytool");
        }
        List<String> command = new ArrayList<>();
        command.add(Files.isRegularFile(executable) ? executable.toString() : "keytool");
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "keytool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Timed out creating a temporary TLS certificate");
        }
        assertEquals(0, process.exitValue(), () -> "keytool failed; see " + log);
    }
}
