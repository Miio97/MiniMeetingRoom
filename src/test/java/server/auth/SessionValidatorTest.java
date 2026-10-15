package server.auth;

import common.auth.AuthRequest;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SessionValidatorTest {
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final String TOKEN = "A".repeat(43);
    private static final String HASH = AuthService.tokenHash(TOKEN);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private String url;
    private ConnectionProvider connections;
    private SessionValidator validator;

    @BeforeEach
    void isolatedDatabase() throws SQLException {
        url = "jdbc:h2:mem:session_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        connections = () -> DriverManager.getConnection(url);
        execute("CREATE TABLE Nguoi_Dung (Ma_Nguoi_Dung BIGINT PRIMARY KEY, Ten_Hien_Thi VARCHAR(100) NOT NULL, Trang_Thai VARCHAR(20) NOT NULL)");
        // Match the case-insensitive comparison in the real MySQL schema.
        execute("CREATE TABLE Phien_Dang_Nhap (Ma_Phien BIGINT PRIMARY KEY, Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung), Token_Bam VARCHAR_IGNORECASE(255) UNIQUE NOT NULL, Het_Han_Luc TIMESTAMP NOT NULL, Thu_Hoi_Luc TIMESTAMP)");
        execute("INSERT INTO Nguoi_Dung VALUES (7, 'Private display name', 'HOAT_DONG')");
        try (var connection = connections.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO Phien_Dang_Nhap VALUES (11, 7, ?, ?, NULL)")) {
            statement.setString(1, HASH);
            statement.setTimestamp(2, Timestamp.from(NOW.plusSeconds(3600)), utc());
            statement.executeUpdate();
        }
        validator = new SessionValidator(connections, CLOCK);
    }

    @AfterEach void closeDatabase() throws SQLException { execute("DROP ALL OBJECTS"); }

    @Test
    void returnsOnlyServerDerivedPrincipalWithUtcExpiryAndRedactedToString() throws SQLException {
        SessionPrincipal principal = validator.validate(TOKEN).orElseThrow();
        assertEquals(11, principal.sessionId());
        assertEquals(7, principal.userId());
        assertEquals("Private display name", principal.displayName());
        assertEquals(NOW.plusSeconds(3600), principal.expiresAt());
        assertEquals(HASH, principal.tokenHash());
        assertEquals(principal, validator.validateHash(HASH).orElseThrow());
        assertEquals("SessionPrincipal[redacted]", principal.toString());
        assertFalse(principal.toString().contains(TOKEN));
        assertFalse(principal.toString().contains(HASH));
    }

    @Test
    void rejectsMalformedWithoutOpeningDatabaseAndRejectsUnknownValues() throws SQLException {
        SessionValidator disconnected = new SessionValidator(() -> { throw new AssertionError("Unexpected database call"); }, CLOCK);
        for (String value : new String[] {null, "", " ", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "=", "A".repeat(42) + "'"}) {
            assertTrue(disconnected.validate(value).isEmpty());
            assertTrue(disconnected.validateHash(value).isEmpty());
        }
        assertTrue(validator.validate("B".repeat(43)).isEmpty());
        assertTrue(validator.validateHash("B".repeat(43)).isEmpty());
    }

    @Test
    void rejectsExpiredAtExactDeadlineAndRevokedSessions() throws SQLException {
        SessionValidator atExpiry = new SessionValidator(connections, Clock.fixed(NOW.plusSeconds(3600), ZoneOffset.UTC));
        assertTrue(atExpiry.validate(TOKEN).isEmpty());
        assertTrue(new SessionValidator(connections, Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC)).validate(TOKEN).isEmpty());
        execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc = TIMESTAMP '2026-10-14 00:00:00'");
        assertTrue(validator.validate(TOKEN).isEmpty());
    }

    @Test
    void checksAccountStateEachTimeWithoutCachingAuthorization() throws SQLException {
        assertTrue(validator.validate(TOKEN).isPresent());
        for (String status : List.of("BI_KHOA", "VO_HIEU_HOA")) {
            try (var connection = connections.getConnection(); var statement = connection.prepareStatement("UPDATE Nguoi_Dung SET Trang_Thai = ?")) {
                statement.setString(1, status); statement.executeUpdate();
            }
            assertTrue(validator.validate(TOKEN).isEmpty());
        }
        execute("UPDATE Nguoi_Dung SET Trang_Thai = 'HOAT_DONG'");
        assertTrue(validator.validate(TOKEN).isPresent());
    }

    @Test
    void refusesDifferentHashCaseEvenWhenDatabaseMatchesIt() throws SQLException {
        char[] changed = HASH.toCharArray();
        for (int index = 0; index < changed.length; index++) {
            if (Character.isLetter(changed[index])) {
                changed[index] = Character.isUpperCase(changed[index]) ? Character.toLowerCase(changed[index]) : Character.toUpperCase(changed[index]);
                break;
            }
        }
        assertNotEquals(HASH, new String(changed));
        assertTrue(validator.validateHash(new String(changed)).isEmpty());
    }

    @Test
    void propagatesDatabaseFailureInsteadOfAcceptingOrHidingIt() {
        SQLException failure = new SQLException("private database details", "08001");
        SessionValidator unavailable = new SessionValidator(() -> { throw failure; }, CLOCK);
        assertSame(failure, assertThrows(SQLException.class, () -> unavailable.validate(TOKEN)));
        assertSame(failure, assertThrows(SQLException.class, () -> unavailable.validateHash(HASH)));
    }

    @Test
    void logoutNotifiesOnceOnlyAfterCommitAndDoesNotNotifyForMissingUnknownExpiredOrSqlFailure() throws SQLException {
        AtomicInteger notifications = new AtomicInteger();
        AuthService auth = service(hash -> {
            assertEquals(HASH, hash);
            assertTrue(assertDoesNotThrow(() -> validator.validate(TOKEN)).isEmpty(), "Callback must observe committed revocation");
            notifications.incrementAndGet();
        });
        assertTrue(auth.handle(logout(TOKEN)).success());
        assertEquals(1, notifications.get());
        assertTrue(auth.handle(logout(TOKEN)).success());
        assertTrue(auth.handle(logout(null)).success());
        assertTrue(auth.handle(logout("unknown-token")).success());
        execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc = NULL, Het_Han_Luc = TIMESTAMP '2026-10-13 00:00:00'");
        assertTrue(auth.handle(logout(TOKEN)).success());
        assertEquals(1, notifications.get());
        execute("DROP TABLE Phien_Dang_Nhap");
        assertThrows(SQLException.class, () -> auth.handle(logout(TOKEN)));
        assertEquals(1, notifications.get());
    }

    @Test
    void listenerRuntimeFailureDoesNotUndoCommittedLogout() throws SQLException {
        AtomicInteger notifications = new AtomicInteger();
        AuthService auth = service(hash -> {
            notifications.incrementAndGet();
            throw new IllegalStateException("private listener details");
        });
        assertTrue(auth.handle(logout(TOKEN)).success());
        assertTrue(validator.validate(TOKEN).isEmpty());
        assertTrue(auth.handle(logout(TOKEN)).success());
        assertEquals(1, notifications.get());
    }

    private AuthService service(SessionRevocationListener listener) {
        return new AuthService(connections, new PasswordHasher(), CLOCK, Duration.ofHours(24), listener);
    }

    private static AuthRequest logout(String token) { return new AuthRequest("LOGOUT", null, null, null, null, token); }
    private void execute(String sql) throws SQLException {
        try (var connection = connections.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
}
