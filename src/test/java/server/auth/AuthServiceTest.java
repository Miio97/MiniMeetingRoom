package server.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");
    private static final String PASSWORD = "Mật-khẩu-123!";
    private String url;
    private AuthService service;

    @BeforeEach
    void createIsolatedDatabase() throws SQLException {
        url = "jdbc:h2:mem:auth_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ten_Dang_Nhap VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                    Mat_Khau_Bam VARCHAR(255) NOT NULL,
                    Ten_Hien_Thi VARCHAR(100) NOT NULL,
                    Email VARCHAR(255) UNIQUE,
                    Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG',
                    Lan_Dang_Nhap_Cuoi TIMESTAMP NULL
                )
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE,
                    Ngay_Tao TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    Het_Han_Luc TIMESTAMP NOT NULL,
                    Thu_Hoi_Luc TIMESTAMP NULL
                )
                """);
        service = new AuthService(() -> DriverManager.getConnection(url), new PasswordHasher(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
    }

    @AfterEach
    void clearDatabase() throws SQLException {
        execute("DROP ALL OBJECTS");
    }

    @Test
    void registerStoresHashAndNormalizedFieldsWithoutCreatingSession() throws SQLException {
        AuthResponse response = register("khoa", "  Trần Văn Khoa  ", "  khoa@example.com  ");
        assertTrue(response.success());
        assertNull(response.token());
        assertNull(response.user());
        assertEquals(0, count("Phien_Dang_Nhap"));
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT * FROM Nguoi_Dung")) {
            assertTrue(result.next());
            assertEquals("khoa", result.getString("Ten_Dang_Nhap"));
            assertEquals("Trần Văn Khoa", result.getString("Ten_Hien_Thi"));
            assertEquals("khoa@example.com", result.getString("Email"));
            String hash = result.getString("Mat_Khau_Bam");
            assertNotEquals(PASSWORD, hash);
            assertTrue(new PasswordHasher().verify(PASSWORD, hash));
        }
    }

    @Test
    void duplicatesHaveSpecificErrorsAndEmailIsOptional() throws SQLException {
        assertTrue(register("khoa", "Khoa", "khoa@example.com").success());
        assertEquals("USERNAME_TAKEN", register("khoa", "Other", "other@example.com").code());
        assertEquals("USERNAME_TAKEN", register("KHOA", "Other", "other@example.com").code());
        assertEquals("EMAIL_TAKEN", register("other", "Other", "khoa@example.com").code());
        assertTrue(register("no-email-1", "One", null).success());
        assertTrue(register("no-email-2", "Two", " ").success());
        assertEquals(3, count("Nguoi_Dung"));
    }

    @Test
    void validatesEveryRegistrationFieldBeforeOpeningDatabase() throws SQLException {
        AuthService disconnected = new AuthService(() -> { throw new AssertionError("Database must not be used"); },
                new PasswordHasher(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));
        List<AuthRequest> invalid = List.of(
                request("REGISTER", " ", PASSWORD, "Name", null, null),
                request("REGISTER", null, PASSWORD, "Name", null, null),
                request("REGISTER", "Tran Khoa", PASSWORD, "Name", null, null),
                request("REGISTER", " TranKhoa", PASSWORD, "Name", null, null),
                request("REGISTER", "TranKhoa ", PASSWORD, "Name", null, null),
                request("REGISTER", "Tran\tKhoa", PASSWORD, "Name", null, null),
                request("REGISTER", "Tran\u00a0Khoa", PASSWORD, "Name", null, null),
                request("REGISTER", "Tran\u2003Khoa", PASSWORD, "Name", null, null),
                request("REGISTER", "x".repeat(51), PASSWORD, "Name", null, null),
                request("REGISTER", "name", "short", "Name", null, null),
                request("REGISTER", "name", null, "Name", null, null),
                request("REGISTER", "name", "12345678", "Name", null, null),
                request("REGISTER", "name", "abcdefgh", "Name", null, null),
                request("REGISTER", "name", "ABCDEFGH", "Name", null, null),
                request("REGISTER", "name", "TranKhoa123", "Name", null, null),
                request("REGISTER", "name", "trankhoa123!", "Name", null, null),
                request("REGISTER", "name", "TRANKHOA123!", "Name", null, null),
                request("REGISTER", "name", "TranKhoa!!!", "Name", null, null),
                request("REGISTER", "name", "TranKhoa123 ", "Name", null, null),
                request("REGISTER", "name", "TranKhoa123\t", "Name", null, null),
                request("REGISTER", "name", " ".repeat(8), "Name", null, null),
                request("REGISTER", "name", "x".repeat(129), "Name", null, null),
                request("REGISTER", "name", PASSWORD, " ", null, null),
                request("REGISTER", "name", PASSWORD, "x".repeat(101), null, null),
                request("REGISTER", "name", PASSWORD, "Name", "missing-at", null),
                request("REGISTER", "name", PASSWORD, "Name", "x".repeat(250) + "@example.com", null));
        for (AuthRequest request : invalid) {
            assertEquals("VALIDATION_ERROR", disconnected.handle(request).code());
        }
        assertEquals("INVALID_REQUEST", disconnected.handle(null).code());
        assertEquals("INVALID_REQUEST", disconnected.handle(request("DELETE", null, null, null, null, null)).code());
    }

    @Test
    void loginCreatesUniqueHashedSessionsWithUtcExpiryAndUpdatesLastLogin() throws SQLException {
        assertTrue(register("khoa", "Khoa", null).success());
        AuthResponse first = login("khoa", PASSWORD);
        AuthResponse second = login("khoa", PASSWORD);
        assertTrue(first.success());
        assertTrue(second.success());
        assertNotNull(first.user());
        assertEquals("khoa", first.user().username());
        assertEquals("Khoa", first.user().displayName());
        assertEquals(NOW.plus(Duration.ofHours(24)), Instant.parse(first.expiresAt()));
        assertNotEquals(first.token(), second.token());
        assertEquals(43, first.token().length());
        assertEquals(2, count("Phien_Dang_Nhap"));
        List<String> storedTokens = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT Token_Bam, Ngay_Tao, Het_Han_Luc FROM Phien_Dang_Nhap")) {
            while (result.next()) {
                storedTokens.add(result.getString("Token_Bam"));
                assertEquals(NOW, result.getTimestamp("Ngay_Tao", utc()).toInstant());
                assertEquals(NOW.plus(Duration.ofHours(24)), result.getTimestamp("Het_Han_Luc", utc()).toInstant());
            }
        }
        assertTrue(storedTokens.contains(AuthService.tokenHash(first.token())));
        assertFalse(storedTokens.contains(first.token()));
        assertEquals(NOW, timestamp("SELECT Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung").toInstant());
    }

    @Test
    void loginRequiresExactUsernameWithCaseInsensitiveDatabase() throws SQLException {
        assertTrue(register("TranKhoa", "Khoa", null).success());
        // Reproduce the real MySQL behavior: the database finds this account regardless of case.
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT Ten_Dang_Nhap FROM Nguoi_Dung WHERE Ten_Dang_Nhap = 'trankhoa'")) {
            assertTrue(result.next());
            assertEquals("TranKhoa", result.getString(1));
        }
        for (String username : List.of("trankhoa", "TRANKHOA", "TranKhoa ", " TranKhoa")) {
            AuthResponse response = login(username, PASSWORD);
            assertFalse(response.success(), "Must reject username: " + username);
            assertEquals("INVALID_CREDENTIALS", response.code());
            assertNull(response.token());
        }
        assertEquals(0, count("Phien_Dang_Nhap"));
        assertNull(timestamp("SELECT Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung"));
        assertTrue(login("TranKhoa", PASSWORD).success());
        assertEquals(1, count("Phien_Dang_Nhap"));
    }

    @Test
    void existingAccountCanLoginWithoutNewRegistrationPasswordPolicy() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                var statement = connection.prepareStatement(
                        "INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi) VALUES (?, ?, ?)")) {
            statement.setString(1, "legacy");
            statement.setString(2, new PasswordHasher().hash("12345678"));
            statement.setString(3, "Legacy account");
            statement.executeUpdate();
        }
        assertTrue(login("legacy", "12345678").success());
    }

    @Test
    void wrongMissingInactiveAndSqlInjectionCredentialsShareErrorAndCreateNoSessions() throws SQLException {
        assertTrue(register("khoa", "Khoa", null).success());
        AuthResponse wrong = login("khoa", "wrong-password");
        AuthResponse missing = login("missing-user", PASSWORD);
        AuthResponse injection = login("' OR '1'='1", PASSWORD);
        execute("UPDATE Nguoi_Dung SET Trang_Thai = 'BI_KHOA'");
        AuthResponse locked = login("khoa", PASSWORD);
        execute("UPDATE Nguoi_Dung SET Trang_Thai = 'VO_HIEU_HOA'");
        AuthResponse disabled = login("khoa", PASSWORD);
        for (AuthResponse response : List.of(wrong, missing, injection, locked, disabled)) {
            assertFalse(response.success());
            assertEquals("INVALID_CREDENTIALS", response.code());
            assertEquals(wrong.message(), response.message());
            assertNull(response.token());
        }
        assertEquals(0, count("Phien_Dang_Nhap"));
        assertNull(timestamp("SELECT Lan_Dang_Nhap_Cuoi FROM Nguoi_Dung"));
    }

    @Test
    void sessionInsertRollsBackWhenLastLoginUpdateFails() throws SQLException {
        assertTrue(register("khoa", "Khoa", null).success());
        execute("ALTER TABLE Nguoi_Dung DROP COLUMN Lan_Dang_Nhap_Cuoi");
        assertThrows(SQLException.class, () -> login("khoa", PASSWORD));
        assertEquals(0, count("Phien_Dang_Nhap"));
    }

    @Test
    void logoutRevokesOnceAndExpiredUnknownOrMissingTokensAreIdempotent() throws SQLException {
        assertTrue(register("khoa", "Khoa", null).success());
        String token = login("khoa", PASSWORD).token();
        assertTrue(logout(token).success());
        assertEquals(NOW, timestamp("SELECT Thu_Hoi_Luc FROM Phien_Dang_Nhap").toInstant());
        assertTrue(logout(token).success());
        assertTrue(logout("unknown-token").success());
        assertTrue(logout(null).success());
        String expiredToken = login("khoa", PASSWORD).token();
        execute("UPDATE Phien_Dang_Nhap SET Het_Han_Luc = TIMESTAMP '2026-10-07 10:00:00' WHERE Thu_Hoi_Luc IS NULL");
        assertTrue(logout(expiredToken).success());
        assertNull(timestamp("SELECT Thu_Hoi_Luc FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NULL"));
    }

    @Test
    void simultaneousRegistrationProducesOneAccountAndSpecificDuplicateResponse() throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return register("same-user", "One", null); });
            var second = executor.submit(() -> { start.await(); return register("same-user", "Two", null); });
            start.countDown();
            AuthResponse one = first.get(10, TimeUnit.SECONDS);
            AuthResponse two = second.get(10, TimeUnit.SECONDS);
            assertNotEquals(one.success(), two.success());
            assertEquals("USERNAME_TAKEN", one.success() ? two.code() : one.code());
            assertEquals(1, count("Nguoi_Dung"));
        }
    }

    private AuthResponse register(String username, String name, String email) throws SQLException {
        return service.handle(request("REGISTER", username, PASSWORD, name, email, null));
    }

    private AuthResponse login(String username, String password) throws SQLException {
        return service.handle(request("LOGIN", username, password, null, null, null));
    }

    private AuthResponse logout(String token) throws SQLException {
        return service.handle(request("LOGOUT", null, null, null, null, token));
    }

    private static AuthRequest request(String action, String username, String password, String name, String email, String token) {
        return new AuthRequest(action, username, password, name, email, token);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private Timestamp timestamp(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getTimestamp(1, utc());
        }
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
