package support;

import client.auth.AuthClient;
import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.TlsSupport;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import server.ApplicationService;
import server.AuthServer;
import server.auth.AuthService;
import server.auth.PasswordHasher;
import server.rooms.RoomService;
import static org.junit.jupiter.api.Assertions.*;

/** Real application services over TLS; every record and key is temporary test data. */
public final class RoomTestFixture implements AutoCloseable {
    public static final String PASSWORD = "Meeting-test-123!";
    private static final String KEY_PASSWORD = "room-integration-test-only";
    private final String database = "jdbc:h2:mem:rooms_" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    public final AuthServer server;
    private final SSLContext clientTls;

    public record Tls(SSLContext server, SSLContext client) { }

    public RoomTestFixture(Tls tls) throws Exception { this(tls, request -> { }); }

    public RoomTestFixture(Tls tls, Consumer<AuthRequest> beforeRequest) throws Exception {
        clientTls = tls.client();
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ten_Dang_Nhap VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                    Mat_Khau_Bam VARCHAR(255) NOT NULL, Ten_Hien_Thi VARCHAR(100) NOT NULL,
                    Email VARCHAR(255) UNIQUE, Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG',
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Ngay_Cap_Nhat TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Lan_Dang_Nhap_Cuoi TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE, Ngay_Tao TIMESTAMP NOT NULL,
                    Het_Han_Luc TIMESTAMP NOT NULL, Thu_Hoi_Luc TIMESTAMP NULL,
                    Thong_Tin_Client VARCHAR(255))
                """);
        execute("""
                CREATE TABLE Phong_Hop (
                    Ma_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL UNIQUE, Ten_Phong VARCHAR(100) NOT NULL,
                    Ma_PIN_Bam VARCHAR(255), Ma_Chu_Phong BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Trang_Thai VARCHAR(30) NOT NULL DEFAULT 'CHO', Da_Khoa BOOLEAN NOT NULL DEFAULT FALSE,
                    So_Nguoi_Toi_Da SMALLINT NOT NULL DEFAULT 10 CHECK (So_Nguoi_Toi_Da > 0),
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, Ngay_Dong TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Thanh_Vien_Phong (
                    Ma_Thanh_Vien_Phong BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop(Ma_Phong),
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Vai_Tro VARCHAR(30) NOT NULL DEFAULT 'THANH_VIEN',
                    Tham_Gia_Luc TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Roi_Phong_Luc TIMESTAMP NULL, Ly_Do_Roi VARCHAR(30))
                """);
        Clock clock = Clock.systemUTC();
        PasswordHasher hasher = new PasswordHasher();
        ApplicationService application = new ApplicationService(
                new AuthService(this::connection, hasher, clock, Duration.ofHours(24)),
                new RoomService(this::connection, hasher, clock));
        server = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), request -> {
            beforeRequest.accept(request);
            return application.handle(request);
        });
        server.start();
    }

    public AuthClient client() { return new AuthClient("localhost", server.port(), clientTls, 5000); }

    public AuthResponse account(String username, String displayName) throws Exception {
        AuthClient client = client();
        assertTrue(client.register(username, displayName, username + "@test.invalid", PASSWORD).success());
        AuthResponse login = client.login(username, PASSWORD);
        assertTrue(login.success()); assertNotNull(login.token()); assertNotNull(login.user());
        return login;
    }

    public Connection connection() throws java.sql.SQLException { return DriverManager.getConnection(database); }

    public void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public long count(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next()); return result.getLong(1);
        }
    }

    @Override public void close() throws Exception {
        try { server.close(); }
        finally { execute("DROP ALL OBJECTS"); }
    }

    public static Tls certificates(Path temporary) throws Exception {
        Path keys = temporary.resolve("room-test.p12");
        Path certificate = temporary.resolve("room-test.cer");
        keytool(temporary, "-genkeypair", "-alias", "room-test", "-keyalg", "RSA", "-keysize", "2048",
                "-storetype", "PKCS12", "-keystore", keys.toString(), "-storepass", KEY_PASSWORD,
                "-keypass", KEY_PASSWORD, "-dname", "CN=localhost", "-validity", "2",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-noprompt");
        keytool(temporary, "-exportcert", "-alias", "room-test", "-keystore", keys.toString(),
                "-storepass", KEY_PASSWORD, "-rfc", "-file", certificate.toString());
        return new Tls(TlsSupport.server(keys, KEY_PASSWORD.toCharArray()), TlsSupport.client(certificate));
    }

    private static void keytool(Path temporary, String... arguments) throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin", "keytool.exe");
        if (!Files.isRegularFile(executable)) executable = Path.of(System.getProperty("java.home"), "bin", "keytool");
        List<String> command = new ArrayList<>();
        command.add(Files.isRegularFile(executable) ? executable.toString() : "keytool");
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(temporary, "keytool-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly(); fail("Timed out creating the temporary room TLS certificate");
        }
        assertEquals(0, process.exitValue(), () -> "keytool failed; see " + log);
    }
}
