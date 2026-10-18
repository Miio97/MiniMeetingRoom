package server.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.filetransfer.TransferException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the account-settings merge with real persisted OTP/session state, without SMTP. */
class EmailOtpAccountSettingsIntegrationTest {
    private static final String EMAIL = "khoa@example.com", PASSWORD = "Khoa123!abc";
    private final PasswordHasher passwords = new PasswordHasher();
    private final MutableClock clock = new MutableClock();
    private final List<String> revoked = new CopyOnWriteArrayList<>();
    private final List<Delivery> sent = new CopyOnWriteArrayList<>();
    private String url;
    private AuthService service, legacy;
    private SessionService sessions;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:merged_accounts_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        execute("""
                CREATE TABLE Nguoi_Dung (
                  Ma_Nguoi_Dung BIGINT AUTO_INCREMENT PRIMARY KEY,
                  Ten_Dang_Nhap VARCHAR_IGNORECASE(50) NOT NULL UNIQUE,
                  Mat_Khau_Bam VARCHAR(255) NOT NULL, Ten_Hien_Thi VARCHAR(100) NOT NULL,
                  Email VARCHAR_IGNORECASE(255) UNIQUE, Trang_Thai VARCHAR(20) NOT NULL DEFAULT 'HOAT_DONG',
                  Lan_Dang_Nhap_Cuoi TIMESTAMP NULL)
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                  Ma_Phien BIGINT AUTO_INCREMENT PRIMARY KEY,
                  Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                  Token_Bam VARCHAR(255) NOT NULL UNIQUE, Ngay_Tao TIMESTAMP NOT NULL,
                  Het_Han_Luc TIMESTAMP NOT NULL, Thu_Hoi_Luc TIMESTAMP NULL)
                """);
        String migration = Files.readString(Path.of("database/migrations/002-email-otp.sql"))
                .replace(" COLLATE utf8mb4_bin", "")
                .replaceAll("(?m)\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;", ");");
        try (Connection connection = connection()) { RunScript.execute(connection, new StringReader(migration)); }
        service = newService();
        legacy = new AuthService(this::connection, passwords, clock, Duration.ofHours(24));
        sessions = new SessionService(this::connection, clock);
    }

    @AfterEach void cleanup() throws SQLException { execute("DROP ALL OBJECTS"); }

    @Test void verifiedRegistrationProfilePasswordChangeAndForgotPasswordKeepBothSessionPolicies() throws Exception {
        registerVerified("Khoa", EMAIL);
        var current = login("Khoa", PASSWORD);
        var another = login("Khoa", PASSWORD);
        assertTrue(current.user().emailVerified());
        var saved = profile(current.token(), "ChangedKhoa", "  Tên mới 日本語  ", " KHOA@example.com ");
        assertTrue(saved.success(), saved::message);
        assertEquals(current.user().id(), saved.user().id());
        assertEquals("Tên mới 日本語", saved.user().displayName());
        assertTrue(saved.user().emailVerified());
        assertEquals("INVALID_CREDENTIALS", login("Khoa", PASSWORD).code());
        var changed = password(current.token(), PASSWORD, "NewKhoa456@");
        assertTrue(changed.success(), changed::message);
        assertTrue(changed.user().emailVerified());
        assertEquals(current.user().id(), sessions.authenticate(current.token()));
        assertThrows(TransferException.class, () -> sessions.authenticate(another.token()));
        assertEquals(List.of(AuthService.tokenHash(another.token())), revoked);
        assertEquals("INVALID_CREDENTIALS", login("ChangedKhoa", PASSWORD).code());
        var newest = login("ChangedKhoa", "NewKhoa456@");
        assertTrue(newest.success()); assertTrue(newest.user().emailVerified());
        assertTrue(resetRequest(EMAIL).success());
        assertTrue(reset(EMAIL, sent.getLast().code(), "ResetKhoa789!").success());
        assertThrows(TransferException.class, () -> sessions.authenticate(current.token()));
        assertThrows(TransferException.class, () -> sessions.authenticate(newest.token()));
        var resetLogin = login("ChangedKhoa", "ResetKhoa789!");
        assertTrue(resetLogin.success()); assertTrue(resetLogin.user().emailVerified());
    }

    @Test void changedEmailIsUnverifiedAndReturningDoesNotRevivePreviousProofOrResetCode() throws Exception {
        registerVerified("Khoa", EMAIL);
        var current = login("Khoa", PASSWORD);
        assertTrue(resetRequest(EMAIL).success());
        String oldCode = sent.getLast().code();
        var moved = profile(current.token(), "Khoa", "Khoa", "new@example.com");
        assertTrue(moved.success()); assertFalse(moved.user().emailVerified());
        assertEquals("OTP_INVALID", reset(EMAIL, oldCode, "Attacker456!").code());
        var returned = profile(current.token(), "Khoa", "Khoa", EMAIL);
        assertTrue(returned.success()); assertFalse(returned.user().emailVerified());
        assertFalse(login("Khoa", PASSWORD).user().emailVerified());
        assertEquals("OTP_INVALID", reset(EMAIL, oldCode, "Attacker456!").code());
        clock.advance(Duration.ofSeconds(60));
        resetRequest(EMAIL);
        assertTrue(reset(EMAIL, sent.getLast().code(), "Restored456!").success());
        assertTrue(login("Khoa", "Restored456!").user().emailVerified());
    }

    @Test void transferringMailboxDoesNotTransferRegistrationProofToAnotherAccount() throws Exception {
        registerVerified("Khoa", EMAIL);
        var first = login("Khoa", PASSWORD);
        assertTrue(profile(first.token(), "Khoa", "Khoa", "moved@example.com").success());
        assertTrue(legacy.handle(new AuthRequest("REGISTER", "Other", PASSWORD, "Other", "other@example.com", null)).success());
        var other = login("Other", PASSWORD);
        var claimed = profile(other.token(), "Other", "Other", EMAIL);
        assertTrue(claimed.success()); assertFalse(claimed.user().emailVerified());
        assertFalse(login("Other", PASSWORD).user().emailVerified());
        // Historical consumed registrations did not store Account_Id; a matching username alone is unsafe.
        execute("DELETE FROM Auth_Email_OTP WHERE Purpose='VERIFIED'");
        execute("UPDATE Auth_Email_OTP SET Account_Id=NULL, Pending_Username='Other' WHERE Purpose='REGISTER'");
        assertFalse(login("Other", PASSWORD).user().emailVerified());
    }

    @Test void requestingAnotherResetDoesNotRemovePreviouslyVerifiedEmail() throws Exception {
        registerVerified("Khoa", EMAIL);
        assertTrue(resetRequest(EMAIL).success());
        service = newService();
        assertTrue(login("Khoa", PASSWORD).user().emailVerified());
        var current = login("Khoa", PASSWORD);
        assertTrue(profile(current.token(), "Khoa", "New name", EMAIL).user().emailVerified());
    }

    @Test void oldAccountBoundResetProofMigratesWithoutTrustingUnboundRegistration() throws Exception {
        assertTrue(legacy.handle(new AuthRequest("REGISTER", "Khoa", PASSWORD, "Khoa", EMAIL, null)).success());
        assertFalse(login("Khoa", PASSWORD).user().emailVerified());
        resetRequest(EMAIL);
        assertTrue(reset(EMAIL, sent.getLast().code(), "NewKhoa456@").success());
        execute("DELETE FROM Auth_Email_OTP WHERE Purpose='VERIFIED'");
        assertTrue(login("Khoa", "NewKhoa456@").user().emailVerified());
        clock.advance(Duration.ofSeconds(60)); resetRequest(EMAIL);
        assertTrue(login("Khoa", "NewKhoa456@").user().emailVerified());
    }

    @Test void missingOtpMigrationKeepsExistingLoginProfileAndPasswordChangeUsable() throws Exception {
        assertTrue(legacy.handle(new AuthRequest("REGISTER", "Khoa", PASSWORD, "Khoa", EMAIL, null)).success());
        execute("DROP TABLE Auth_Email_OTP_Send"); execute("DROP TABLE Auth_Email_OTP");
        var current = login("Khoa", PASSWORD);
        assertTrue(current.success()); assertFalse(current.user().emailVerified());
        assertTrue(profile(current.token(), "Changed", "Changed", "new@example.com").success());
        assertTrue(password(current.token(), PASSWORD, "NewKhoa456@").success());
        assertTrue(login("Changed", "NewKhoa456@").success());
        assertEquals("DB_MIGRATION_REQUIRED", service.handle(new AuthRequest(
                "REGISTER", "NewAccount", PASSWORD, "New", "next@example.com", null)).code());
    }

    @Test void concurrentResetAndEmailUpdateSerializeWithoutDeadlockOrLeavingOldEmailAuthority() throws Exception {
        registerVerified("Khoa", EMAIL);
        var current = login("Khoa", PASSWORD);
        resetRequest(EMAIL); String code = sent.getLast().code();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var update = executor.submit(() -> { start.await(); return profile(current.token(), "Khoa", "Khoa", "new@example.com"); });
            var reset = executor.submit(() -> { start.await(); return reset(EMAIL, code, "ResetKhoa789!"); });
            start.countDown();
            var updated = update.get(15, TimeUnit.SECONDS);
            var resetResult = reset.get(15, TimeUnit.SECONDS);
            assertNotEquals(updated.success(), resetResult.success());
            if (updated.success()) {
                assertEquals("OTP_INVALID", resetResult.code());
                assertEquals("new@example.com", login("Khoa", PASSWORD).user().email());
            } else {
                assertEquals("SESSION_EXPIRED", updated.code());
                assertEquals(EMAIL, login("Khoa", "ResetKhoa789!").user().email());
            }
        }
    }

    @Test void concurrentProfileAndOtpRegistrationCannotClaimTheSameMailbox() throws Exception {
        registerVerified("Khoa", EMAIL);
        var current = login("Khoa", PASSWORD);
        String newEmail = "contested@example.com";
        var pending = service.handle(new AuthRequest("REGISTER", "Other", PASSWORD, "Other", newEmail, null));
        assertEquals("OTP_REQUIRED", pending.code());
        String code = sent.getLast().code();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var update = executor.submit(() -> { start.await(); return profile(current.token(), "Khoa", "Khoa", newEmail); });
            var registration = executor.submit(() -> {
                start.await();
                return service.handle(new AuthRequest("VERIFY_REGISTRATION", null, null, null, newEmail,
                        null, null, code, pending.registrationChallenge()));
            });
            start.countDown();
            var updated = update.get(15, TimeUnit.SECONDS);
            var registered = registration.get(15, TimeUnit.SECONDS);
            assertNotEquals(updated.success(), registered.success());
            assertEquals("EMAIL_TAKEN", updated.success() ? registered.code() : updated.code());
            if (registered.success()) assertTrue(login("Other", PASSWORD).user().emailVerified());
            else assertFalse(login("Khoa", PASSWORD).user().emailVerified());
        }
    }

    private AuthService newService() {
        return new AuthService(this::connection, passwords, clock, Duration.ofHours(24), revoked::add,
                new EmailOtpService(this::connection, passwords, clock,
                        (email, purpose, code) -> sent.add(new Delivery(email, code)), revoked::add));
    }
    private void registerVerified(String username, String email) throws SQLException {
        var pending = service.handle(new AuthRequest("REGISTER", username, PASSWORD, username, email, null));
        assertEquals("OTP_REQUIRED", pending.code());
        var verified = service.handle(new AuthRequest("VERIFY_REGISTRATION", null, null, null, email,
                null, null, sent.getLast().code(), pending.registrationChallenge()));
        assertTrue(verified.success(), verified::message);
    }
    private AuthResponse login(String username, String password) throws SQLException {
        return service.handle(new AuthRequest("LOGIN", username, password, null, null, null));
    }
    private AuthResponse profile(String token, String username, String name, String email) throws SQLException {
        return service.handle(new AuthRequest("UPDATE_PROFILE", username, null, name, email, token));
    }
    private AuthResponse password(String token, String current, String next) throws SQLException {
        return service.handle(new AuthRequest("CHANGE_PASSWORD", null, current, null, null, token, null, null, null, next));
    }
    private AuthResponse resetRequest(String email) throws SQLException {
        return service.handle(new AuthRequest("REQUEST_PASSWORD_RESET", null, null, null, email, null));
    }
    private AuthResponse reset(String email, String code, String next) throws SQLException {
        return service.handle(new AuthRequest("RESET_PASSWORD", null, next, null, email, null, null, code));
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url); }
    private void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
    private record Delivery(String email, String code) { }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T07:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
