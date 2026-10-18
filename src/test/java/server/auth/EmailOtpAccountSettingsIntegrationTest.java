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
    private boolean failMail;

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

    @Test void missingOtpMigrationKeepsLoginAndProfileButDoesNotBypassPasswordVerification() throws Exception {
        assertTrue(legacy.handle(new AuthRequest("REGISTER", "Khoa", PASSWORD, "Khoa", EMAIL, null)).success());
        execute("DROP TABLE Auth_Email_OTP_Send"); execute("DROP TABLE Auth_Email_OTP");
        var current = login("Khoa", PASSWORD);
        assertTrue(current.success()); assertFalse(current.user().emailVerified());
        assertTrue(profile(current.token(), "Changed", "Changed", "new@example.com").success());
        assertEquals("VALIDATION_ERROR", password(current.token(), PASSWORD, "NewKhoa456@").code());
        assertTrue(login("Changed", PASSWORD).success());
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

    @Test void existingAccountCanVerifySavedEmailWithoutResettingItsPasswordOrSessions() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", null, null));
        var current = login("Existing", PASSWORD); var other = login("Existing", PASSWORD);
        assertFalse(current.user().emailVerified());
        assertEquals("VALIDATION_ERROR", password(current.token(), PASSWORD, "NewPassword-456!").code());
        assertTrue(profile(current.token(), "Existing", "Existing", EMAIL).success());
        assertEquals("OTP_REQUIRED", emailRequest(current.token(), EMAIL).code());
        assertEquals("VERIFY", sent.getLast().purpose());
        String code = sent.getLast().code();
        assertEquals("OTP_INVALID", emailVerify(current.token(), EMAIL, wrongCode(code)).code());
        var verified = emailVerify(current.token(), EMAIL, code);
        assertTrue(verified.success()); assertTrue(verified.user().emailVerified());
        assertNull(verified.token()); assertEquals(current.user().id(), verified.user().id());
        assertEquals(current.user().id(), sessions.authenticate(current.token()));
        assertEquals(current.user().id(), sessions.authenticate(other.token())); assertTrue(revoked.isEmpty());
        service = newService(); assertTrue(login("Existing", PASSWORD).user().emailVerified());
        assertTrue(password(current.token(), PASSWORD, "NewPassword-456!").success());
        assertThrows(TransferException.class, () -> sessions.authenticate(other.token()));
    }

    @Test void verificationRequiresActiveSessionAndTheExactSavedMailbox() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD);
        assertEquals("SESSION_EXPIRED", emailRequest("invalid-token", EMAIL).code());
        assertEquals("SESSION_EXPIRED", emailVerify(null, EMAIL, "123456").code());
        assertEquals("VALIDATION_ERROR", emailRequest(current.token(), "other@example.com").code());
        assertEquals("VALIDATION_ERROR", emailRequest(current.token(), "khóa@example.com").code());
        assertTrue(sent.isEmpty());
        assertTrue(profile(current.token(), "Existing", "Existing", "").success());
        assertEquals("VALIDATION_ERROR", emailRequest(current.token(), "").code());
        service.handle(new AuthRequest("LOGOUT", null, null, null, null, current.token()));
        assertEquals("SESSION_EXPIRED", emailRequest(current.token(), EMAIL).code());
        var next = login("Existing", PASSWORD);
        execute("UPDATE Nguoi_Dung SET Trang_Thai='BI_KHOA'");
        assertEquals("SESSION_EXPIRED", emailRequest(next.token(), EMAIL).code());
        assertTrue(sent.isEmpty());
    }

    @Test void verificationCodesAreSingleUseAndCannotReplacePasswordResetCodes() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD);
        emailRequest(current.token(), EMAIL); String verification = sent.getLast().code();
        resetRequest(EMAIL); String resetCode = sent.getLast().code();
        // Make the negative purpose test deterministic even if random six-digit values coincide.
        if (verification.equals(resetCode)) {
            clock.advance(Duration.ofSeconds(60)); resetRequest(EMAIL); resetCode = sent.getLast().code();
        }
        if (!verification.equals(resetCode)) {
            assertEquals("OTP_INVALID", emailVerify(current.token(), EMAIL, resetCode).code());
            assertEquals("OTP_INVALID", reset(EMAIL, verification, "NewPassword-456!").code());
        }
        assertTrue(emailVerify(current.token(), EMAIL, verification).success());
        assertEquals("OTP_INVALID", emailVerify(current.token(), EMAIL, verification).code());
        assertTrue(login("Existing", PASSWORD).success());
        assertTrue(reset(EMAIL, resetCode, "Recovered-789!").success());
        assertThrows(TransferException.class, () -> sessions.authenticate(current.token()));
    }

    @Test void verificationResendRotatesCodesAndSmtpFailurePreservesTheLastWorkingCode() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD);
        emailRequest(current.token(), EMAIL); String original = sent.getLast().code();
        assertEquals("OTP_COOLDOWN", emailRequest(current.token(), EMAIL).code()); assertEquals(1, sent.size());
        clock.advance(Duration.ofSeconds(60));
        emailRequest(current.token(), EMAIL); String replacement = sent.getLast().code();
        assertNotEquals(original, replacement);
        assertEquals("OTP_INVALID", emailVerify(current.token(), EMAIL, original).code());
        clock.advance(Duration.ofSeconds(60)); failMail = true;
        assertEquals("MAIL_UNAVAILABLE", emailRequest(current.token(), EMAIL).code());
        assertEquals("OTP_COOLDOWN", emailRequest(current.token(), EMAIL).code());
        assertEquals(2, sent.size());
        assertTrue(emailVerify(current.token(), EMAIL, replacement).success());
    }

    @Test void verificationRejectsExpiredCodesAndLocksAfterFiveWrongAttempts() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD);
        emailRequest(current.token(), EMAIL); String code = sent.getLast().code();
        for (int attempt = 1; attempt <= 5; attempt++)
            assertEquals(attempt < 5 ? "OTP_INVALID" : "OTP_LOCKED",
                    emailVerify(current.token(), EMAIL, wrongCode(code)).code());
        assertEquals("OTP_LOCKED", emailVerify(current.token(), EMAIL, code).code());
        clock.advance(Duration.ofSeconds(60)); emailRequest(current.token(), EMAIL); code = sent.getLast().code();
        clock.advance(Duration.ofMinutes(5));
        assertEquals("OTP_EXPIRED", emailVerify(current.token(), EMAIL, code).code());
        assertFalse(login("Existing", PASSWORD).user().emailVerified());
    }

    @Test void changingEmailCancelsVerificationAndOldCodeCannotVerifyANewOwner() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD);
        emailRequest(current.token(), EMAIL); String old = sent.getLast().code();
        profile(current.token(), "Existing", "Existing", "moved@example.com");
        assertEquals("VALIDATION_ERROR", emailVerify(current.token(), EMAIL, old).code());
        legacy.handle(new AuthRequest("REGISTER", "NewOwner", PASSWORD, "New owner", EMAIL, null));
        var newOwner = login("NewOwner", PASSWORD);
        assertEquals("OTP_INVALID", emailVerify(newOwner.token(), EMAIL, old).code());
        assertFalse(login("NewOwner", PASSWORD).user().emailVerified());
        profile(current.token(), "Existing", "Existing", "another@example.com");
        emailRequest(current.token(), "another@example.com"); String abandoned = sent.getLast().code();
        profile(current.token(), "Existing", "Existing", "moved@example.com");
        profile(current.token(), "Existing", "Existing", "another@example.com");
        assertEquals("OTP_INVALID", emailVerify(current.token(), "another@example.com", abandoned).code());
    }

    @Test void verificationDeliveryFailuresAlsoCountTowardTheRollingHourlyLimit() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD); failMail = true;
        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals("MAIL_UNAVAILABLE", emailRequest(current.token(), EMAIL).code());
            clock.advance(Duration.ofSeconds(60));
        }
        failMail = false;
        assertEquals("OTP_COOLDOWN", emailRequest(current.token(), EMAIL).code()); assertTrue(sent.isEmpty());
        clock.advance(Duration.ofHours(1));
        assertEquals("OTP_REQUIRED", emailRequest(current.token(), EMAIL).code());
    }

    @Test void simultaneousEmailVerificationOnlyConsumesTheCodeOnce() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD);
        emailRequest(current.token(), EMAIL); String code = sent.getLast().code();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return emailVerify(current.token(), EMAIL, code); });
            var second = executor.submit(() -> { start.await(); return emailVerify(current.token(), EMAIL, code); });
            start.countDown();
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
            assertNotEquals(a.success(), b.success()); assertEquals("OTP_INVALID", a.success() ? b.code() : a.code());
            assertTrue(login("Existing", PASSWORD).user().emailVerified());
        }
    }

    @Test void settingsRecoveryKeepsTheCurrentTokenAndRevokesOnlyOtherSessionsOfThatAccount() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        legacy.handle(new AuthRequest("REGISTER", "Other", PASSWORD, "Other", "other@example.com", null));
        var current = login("Existing", PASSWORD); var previous = login("Existing", PASSWORD); var unrelated = login("Other", PASSWORD);
        assertFalse(current.user().emailVerified());
        assertEquals("OTP_REQUIRED", accountResetRequest(current.token(), EMAIL).code());
        assertEquals("RESET", sent.getLast().purpose()); String code = sent.getLast().code();
        assertEquals("OTP_COOLDOWN", accountResetRequest(current.token(), EMAIL).code());
        var response = accountReset(current.token(), EMAIL, code, "Recovered-123!");
        assertTrue(response.success(), response::message); assertTrue(response.user().emailVerified()); assertNull(response.token());
        assertEquals(current.user().id(), sessions.authenticate(current.token()));
        assertEquals(unrelated.user().id(), sessions.authenticate(unrelated.token()));
        assertThrows(TransferException.class, () -> sessions.authenticate(previous.token()));
        assertEquals(List.of(AuthService.tokenHash(previous.token())), revoked);
        assertEquals("INVALID_CREDENTIALS", login("Existing", PASSWORD).code());
        assertTrue(login("Existing", "Recovered-123!").success());
        assertEquals("OTP_INVALID", accountReset(current.token(), EMAIL, code, "Replay-password-456!").code());
    }

    @Test void settingsRecoveryRejectsAnonymousOrDifferentAccountRequestsAndWeakPasswordsWithoutConsumingCode() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        legacy.handle(new AuthRequest("REGISTER", "Other", PASSWORD, "Other", "other@example.com", null));
        var current = login("Existing", PASSWORD); var other = login("Other", PASSWORD);
        assertEquals("SESSION_EXPIRED", accountResetRequest(null, EMAIL).code());
        assertEquals("SESSION_EXPIRED", accountReset(null, EMAIL, "123456", "Recovered-123!").code());
        assertEquals("VALIDATION_ERROR", accountResetRequest(other.token(), EMAIL).code()); assertTrue(sent.isEmpty());
        accountResetRequest(current.token(), EMAIL); String code = sent.getLast().code();
        assertEquals("VALIDATION_ERROR", accountReset(other.token(), EMAIL, code, "Recovered-123!").code());
        assertEquals("VALIDATION_ERROR", accountReset(current.token(), EMAIL, code, "weak").code());
        assertTrue(accountReset(current.token(), EMAIL, code, "Recovered-123!").success());
        assertEquals(other.user().id(), sessions.authenticate(other.token()));
    }

    @Test void settingsRecoveryRejectsOldEmailCodesAndRevokedSessions() throws Exception {
        registerVerified("Khoa", EMAIL); var current = login("Khoa", PASSWORD);
        accountResetRequest(current.token(), EMAIL); String code = sent.getLast().code();
        profile(current.token(), "Khoa", "Khoa", "changed@example.com");
        assertEquals("VALIDATION_ERROR", accountReset(current.token(), EMAIL, code, "Recovered-123!").code());
        profile(current.token(), "Khoa", "Khoa", EMAIL);
        assertEquals("OTP_INVALID", accountReset(current.token(), EMAIL, code, "Recovered-123!").code());
        assertTrue(login("Khoa", PASSWORD).success());
        service.handle(new AuthRequest("LOGOUT", null, null, null, null, current.token()));
        assertEquals("SESSION_EXPIRED", accountResetRequest(current.token(), EMAIL).code());
        assertEquals("SESSION_EXPIRED", accountReset(current.token(), EMAIL, code, "Recovered-123!").code());
    }

    @Test void settingsRecoveryCountsWrongAttemptsAndRejectsExpiredCodesWithoutEndingTheSession() throws Exception {
        registerVerified("Khoa", EMAIL); var current = login("Khoa", PASSWORD);
        accountResetRequest(current.token(), EMAIL); String code = sent.getLast().code();
        for (int attempt = 1; attempt <= 5; attempt++)
            assertEquals(attempt < 5 ? "OTP_INVALID" : "OTP_LOCKED", accountReset(current.token(), EMAIL, wrongCode(code), "Recovered-123!").code());
        assertEquals("OTP_LOCKED", accountReset(current.token(), EMAIL, code, "Recovered-123!").code());
        clock.advance(Duration.ofSeconds(60)); accountResetRequest(current.token(), EMAIL); code = sent.getLast().code();
        clock.advance(Duration.ofMinutes(5));
        assertEquals("OTP_EXPIRED", accountReset(current.token(), EMAIL, code, "Recovered-123!").code());
        assertEquals(current.user().id(), sessions.authenticate(current.token())); assertTrue(revoked.isEmpty());
        assertTrue(login("Khoa", PASSWORD).success());
    }

    @Test void concurrentSettingsRecoveryKeepsOnlyTheSuccessfulRequestSession() throws Exception {
        registerVerified("Khoa", EMAIL); var first = login("Khoa", PASSWORD); var second = login("Khoa", PASSWORD);
        accountResetRequest(first.token(), EMAIL); String code = sent.getLast().code();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return accountReset(first.token(), EMAIL, code, "First-recovery-123!"); });
            var b = executor.submit(() -> { start.await(); return accountReset(second.token(), EMAIL, code, "Second-recovery-456!"); });
            start.countDown(); var firstResult = a.get(15, TimeUnit.SECONDS); var secondResult = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstResult.success(), secondResult.success());
            assertEquals("SESSION_EXPIRED", firstResult.success() ? secondResult.code() : firstResult.code());
            String keep = firstResult.success() ? first.token() : second.token(); String expired = firstResult.success() ? second.token() : first.token();
            assertEquals(first.user().id(), sessions.authenticate(keep));
            assertThrows(TransferException.class, () -> sessions.authenticate(expired));
            assertEquals(List.of(AuthService.tokenHash(expired)), revoked);
        }
    }

    @Test void publicAndSettingsRecoveryCannotConsumeTheSameCodeTwice() throws Exception {
        registerVerified("Khoa", EMAIL); var current = login("Khoa", PASSWORD);
        accountResetRequest(current.token(), EMAIL); String code = sent.getLast().code();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var inSettings = executor.submit(() -> { start.await(); return accountReset(current.token(), EMAIL, code, "Settings-recovery-123!"); });
            var publicReset = executor.submit(() -> { start.await(); return reset(EMAIL, code, "Public-recovery-456!"); });
            start.countDown(); var kept = inSettings.get(15, TimeUnit.SECONDS); var signedOut = publicReset.get(15, TimeUnit.SECONDS);
            assertNotEquals(kept.success(), signedOut.success());
            if (kept.success()) {
                assertEquals("OTP_INVALID", signedOut.code()); assertEquals(current.user().id(), sessions.authenticate(current.token()));
            } else {
                assertEquals("SESSION_EXPIRED", kept.code()); assertThrows(TransferException.class, () -> sessions.authenticate(current.token()));
            }
        }
    }

    @Test void failedPasswordWriteRollsBackOtpConsumptionProofAndSessionRevocation() throws Exception {
        legacy.handle(new AuthRequest("REGISTER", "Existing", PASSWORD, "Existing", EMAIL, null));
        var current = login("Existing", PASSWORD); var other = login("Existing", PASSWORD);
        accountResetRequest(current.token(), EMAIL); String code = sent.getLast().code();
        String originalHash;
        try (var connection = connection(); var statement = connection.createStatement(); var row = statement.executeQuery("SELECT Mat_Khau_Bam FROM Nguoi_Dung")) {
            assertTrue(row.next()); originalHash = row.getString(1);
        }
        execute("ALTER TABLE Nguoi_Dung ADD CONSTRAINT keep_original_password CHECK (Mat_Khau_Bam='" + originalHash + "')");
        assertThrows(SQLException.class, () -> accountReset(current.token(), EMAIL, code, "Recovered-123!"));
        assertFalse(login("Existing", PASSWORD).user().emailVerified());
        assertEquals(current.user().id(), sessions.authenticate(current.token())); assertEquals(current.user().id(), sessions.authenticate(other.token()));
        assertTrue(revoked.isEmpty()); execute("ALTER TABLE Nguoi_Dung DROP CONSTRAINT keep_original_password");
        assertTrue(accountReset(current.token(), EMAIL, code, "Recovered-123!").success());
    }

    private AuthResponse accountResetRequest(String token, String email) throws SQLException {
        return service.handle(new AuthRequest("REQUEST_ACCOUNT_PASSWORD_RESET", null, null, null, email, token));
    }
    private AuthResponse accountReset(String token, String email, String code, String password) throws SQLException {
        return service.handle(new AuthRequest("RESET_ACCOUNT_PASSWORD", null, password, null, email, token, null, code));
    }

    private static String wrongCode(String code) { return "000000".equals(code) ? "000001" : "000000"; }
    private AuthResponse emailRequest(String token, String email) throws SQLException {
        return service.handle(new AuthRequest("REQUEST_EMAIL_VERIFICATION", null, null, null, email, token));
    }
    private AuthResponse emailVerify(String token, String email, String code) throws SQLException {
        return service.handle(new AuthRequest("VERIFY_EMAIL", null, null, null, email, token, null, code));
    }

    private AuthService newService() {
        return new AuthService(this::connection, passwords, clock, Duration.ofHours(24), revoked::add,
                new EmailOtpService(this::connection, passwords, clock,
                        (email, purpose, code) -> {
                            if (failMail) throw new OtpMailSender.MailDeliveryException();
                            sent.add(new Delivery(email, purpose, code));
                        }, revoked::add));
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
    private record Delivery(String email, String purpose, String code) { }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T07:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
