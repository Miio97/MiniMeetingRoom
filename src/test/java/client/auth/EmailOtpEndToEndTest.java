package client.auth;

import common.auth.AuthResponse;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.AuthServer;
import server.auth.AuthService;
import server.auth.EmailOtpService;
import server.auth.PasswordHasher;
import server.auth.SessionRevocationListener;
import server.auth.SessionValidator;
import support.RoomTestFixture;

import static org.junit.jupiter.api.Assertions.*;

/** Full client/TLS/auth/SQL flow. Only email delivery is replaced with a test mailbox. */
@Timeout(45)
class EmailOtpEndToEndTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private final String database = "jdbc:h2:mem:otp_tls_" + UUID.randomUUID()
            + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";

    @BeforeAll
    @Timeout(60)
    static void certificates() throws Exception {
        tls = RoomTestFixture.certificates(temporary);
    }

    @BeforeEach
    void schema() throws Exception {
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ten_Dang_Nhap VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                    Mat_Khau_Bam VARCHAR(255) NOT NULL,
                    Ten_Hien_Thi VARCHAR(100) NOT NULL,
                    Email VARCHAR(255) UNIQUE,
                    Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG',
                    Lan_Dang_Nhap_Cuoi TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE,
                    Ngay_Tao TIMESTAMP NOT NULL,
                    Het_Han_Luc TIMESTAMP NOT NULL,
                    Thu_Hoi_Luc TIMESTAMP NULL)
                """);
        String migration = Files.readString(Path.of("database/migrations/002-email-otp.sql"))
                .replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("(?i)\\s+COLLATE utf8mb4_bin", "")
                .replaceAll("(?i)\\s+ENGINE=InnoDB\\s+DEFAULT CHARSET=utf8mb4\\s+COLLATE=utf8mb4_unicode_ci", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) execute(sql);
    }

    @AfterEach
    void cleanup() throws Exception {
        execute("DROP ALL OBJECTS");
    }

    @Test
    void verifiedRegistrationAndPasswordRecoveryWorkOverTlsAndRevokeEveryOldSession() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC);
        PasswordHasher passwords = new PasswordHasher();
        List<Delivery> mailbox = new CopyOnWriteArrayList<>();
        List<String> revokedHashes = new CopyOnWriteArrayList<>();
        SessionRevocationListener revocations = revokedHashes::add;
        EmailOtpService otp = new EmailOtpService(this::connection, passwords, clock,
                (email, purpose, code) -> mailbox.add(new Delivery(email, purpose, code)), revocations);
        AuthService auth = new AuthService(this::connection, passwords, clock, Duration.ofHours(24), revocations, otp);
        SessionValidator validator = new SessionValidator(this::connection, clock);
        String username = "KhoaOtpTls";
        String email = "khoa-otp-tls@example.invalid";
        String oldPassword = "Original-password-82!";
        String newPassword = "Replacement-password-46!";

        try (AuthServer server = new AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0), auth::handle)) {
            server.start();
            AuthClient client = new AuthClient("localhost", server.port(), tls.client(), 10000);

            AuthResponse pending = client.register(username, "Trần Khoa", email.toUpperCase(java.util.Locale.ROOT), oldPassword);
            assertTrue(pending.success());
            assertEquals("OTP_REQUIRED", pending.code());
            assertNull(pending.token());
            assertNull(pending.user());
            assertNotNull(pending.registrationChallenge());
            assertTrue(pending.registrationChallenge().matches("[A-Za-z0-9_-]{43}"));
            assertEquals(0, count("SELECT COUNT(*) FROM Nguoi_Dung"));
            assertEquals(0, count("SELECT COUNT(*) FROM Phien_Dang_Nhap"));
            assertEquals("INVALID_CREDENTIALS", client.login(username, oldPassword).code());
            assertEquals(1, mailbox.size());
            Delivery registration = mailbox.getFirst();
            assertEquals(email, registration.email());
            assertEquals("REGISTER", registration.purpose());
            assertTrue(registration.code().matches("[0-9]{6}"));
            assertFalse(pending.message().contains(registration.code()), "OTP must be delivered only through email");

            assertEquals("OTP_INVALID", client.verifyRegistration(email, registration.code(), "Z".repeat(43)).code());
            assertEquals(0, count("SELECT COUNT(*) FROM Nguoi_Dung"), "An email OTP alone must not authorize a different registration flow");
            AuthResponse verified = client.verifyRegistration(email, registration.code(), pending.registrationChallenge());
            assertTrue(verified.success());
            assertEquals("OK", verified.code());
            assertNull(verified.token());
            assertEquals(1, count("SELECT COUNT(*) FROM Nguoi_Dung"));
            assertEquals("OTP_INVALID", client.verifyRegistration(email, registration.code(), pending.registrationChallenge()).code());

            AuthResponse firstLogin = client.login(username, oldPassword);
            AuthResponse secondLogin = client.login(username, oldPassword);
            assertTrue(firstLogin.success());
            assertTrue(secondLogin.success());
            var firstSession = validator.validate(firstLogin.token()).orElseThrow();
            var secondSession = validator.validate(secondLogin.token()).orElseThrow();
            assertNotEquals(firstSession.tokenHash(), secondSession.tokenHash());

            assertTrue(client.requestPasswordReset(email).success());
            assertEquals(2, mailbox.size());
            Delivery reset = mailbox.getLast();
            assertEquals(email, reset.email());
            assertEquals("RESET", reset.purpose());
            assertTrue(reset.code().matches("[0-9]{6}"));
            assertTrue(client.resetPassword(email, reset.code(), newPassword).success());

            assertTrue(validator.validate(firstLogin.token()).isEmpty());
            assertTrue(validator.validate(secondLogin.token()).isEmpty());
            assertEquals(2, count("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NOT NULL"));
            assertEquals(2, revokedHashes.size());
            assertTrue(revokedHashes.containsAll(List.of(firstSession.tokenHash(), secondSession.tokenHash())));
            assertEquals("INVALID_CREDENTIALS", client.login(username, oldPassword).code());
            assertEquals("OTP_INVALID", client.resetPassword(email, reset.code(), "Another-password-83!").code());

            AuthResponse newLogin = client.login(username, newPassword);
            assertTrue(newLogin.success());
            assertTrue(validator.validate(newLogin.token()).isPresent());
            assertEquals(firstLogin.user().id(), newLogin.user().id());
            assertEquals(1, count("SELECT COUNT(*) FROM Nguoi_Dung"));
        }
    }

    private Connection connection() throws java.sql.SQLException {
        return DriverManager.getConnection(database);
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private record Delivery(String email, String purpose, String code) {
        @Override public String toString() { return "Delivery[purpose=" + purpose + "]"; }
    }
}
