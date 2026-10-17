package server.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.tools.RunScript;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailOtpServiceTest {
    private static final String PASSWORD = "Khoa123!abc", NEW_PASSWORD = "NewKhoa456@";
    private String url;
    private final PasswordHasher passwords = new PasswordHasher();
    private final MutableClock clock = new MutableClock();
    private final FakeMail mail = new FakeMail();
    private final List<String> revoked = new CopyOnWriteArrayList<>();
    private final Map<String, String> registrationChallenges = new HashMap<>();
    private AuthService service, legacy;

    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:email_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
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
        runMigration();
        service = newService();
        legacy = new AuthService(this::connection, passwords, clock, Duration.ofHours(24));
    }

    @AfterEach void cleanup() throws SQLException { execute("DROP ALL OBJECTS"); }

    @Test void registrationRequiresEmailAndDoesNotCreateAccountUntilVerified() throws Exception {
        assertEquals("VALIDATION_ERROR", register("Khoa", null).code());
        assertEquals("VALIDATION_ERROR", register("Khoa", "bademail").code());
        AuthResponse response = register("Khoa", "  KHOA@example.com ");
        assertTrue(response.success()); assertEquals("OTP_REQUIRED", response.code());
        assertTrue(response.registrationChallenge().matches("[A-Za-z0-9_-]{43}"));
        assertNotEquals(response.registrationChallenge(), scalar("SELECT Registration_Challenge_Hash FROM Auth_Email_OTP"));
        assertFalse(response.toString().contains(response.registrationChallenge()));
        assertEquals(0, count("Nguoi_Dung")); assertEquals(0, count("Phien_Dang_Nhap"));
        assertEquals("khoa@example.com", mail.last().email());
        assertTrue(mail.last().code().matches("[0-9]{6}"));
        assertNull(response.user()); assertNull(response.token()); assertFalse(response.message().contains(mail.last().code()));
        assertFalse(login(PASSWORD).success());
        assertNotEquals(mail.last().code(), scalar("SELECT Code_Hash FROM Auth_Email_OTP"));
        String pendingHash = scalar("SELECT Pending_Password_Hash FROM Auth_Email_OTP");
        assertNotEquals(PASSWORD, pendingHash); assertTrue(passwords.verify(PASSWORD, pendingHash));
        assertTrue(verify("khoa@example.com", mail.last().code()).success());
        assertEquals(1, count("Nguoi_Dung")); assertEquals(0, count("Phien_Dang_Nhap"));
        assertNull(scalar("SELECT Code_Hash FROM Auth_Email_OTP"));
        assertNull(scalar("SELECT Pending_Password_Hash FROM Auth_Email_OTP"));
        assertTrue(login(PASSWORD).success());
    }

    @Test void verificationIsOneUseAndSurvivesNewServiceInstance() throws Exception {
        register("Khoa", "khoa@example.com");
        String code = mail.last().code();
        service = newService();
        assertTrue(verify("khoa@example.com", code).success());
        assertEquals("OTP_INVALID", verify("khoa@example.com", code).code());
        assertEquals("EMAIL_TAKEN", register("NewName", "khoa@example.com").code());
        assertEquals(1, count("Nguoi_Dung"));
    }

    @Test void fiveIncorrectAttemptsArePersistedAndLockTheCodeUntilResent() throws Exception {
        register("Khoa", "khoa@example.com");
        String good = mail.last().code(), wrong = good.equals("000000") ? "111111" : "000000";
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(attempt == 5 ? "OTP_LOCKED" : "OTP_INVALID", verify("khoa@example.com", wrong).code());
        }
        service = newService();
        assertEquals("OTP_LOCKED", verify("khoa@example.com", good).code());
        assertEquals("5", scalar("SELECT Attempts FROM Auth_Email_OTP"));
        assertEquals(0, count("Nguoi_Dung"));
        clock.advance(Duration.ofSeconds(60));
        assertEquals("OTP_REQUIRED", resend().code());
        assertEquals("0", scalar("SELECT Attempts FROM Auth_Email_OTP"));
        assertTrue(verify("khoa@example.com", mail.last().code()).success());
    }

    @Test void codeExpiresExactlyAfterFiveMinutes() throws Exception {
        register("Khoa", "khoa@example.com");
        clock.advance(Duration.ofMinutes(5));
        assertEquals("OTP_EXPIRED", verify("khoa@example.com", mail.last().code()).code());
        assertEquals(0, count("Nguoi_Dung"));
    }

    @Test void resendWaitsSixtySecondsAndInvalidatesOldCode() throws Exception {
        register("Khoa", "khoa@example.com");
        String original = mail.last().code();
        assertEquals("OTP_COOLDOWN", resend().code()); assertEquals(1, mail.sent.size());
        clock.advance(Duration.ofSeconds(59)); assertEquals("OTP_COOLDOWN", resend().code());
        clock.advance(Duration.ofSeconds(1)); assertEquals("OTP_REQUIRED", resend().code());
        assertNotEquals(original, mail.last().code());
        assertEquals("OTP_INVALID", verify("khoa@example.com", original).code());
        assertTrue(verify("khoa@example.com", mail.last().code()).success());
    }

    @Test void rollingHourLimitDoesNotResetAtClockHourBoundary() throws Exception {
        register("Khoa", "khoa@example.com");
        for (int i = 0; i < 4; i++) { clock.advance(Duration.ofSeconds(60)); assertEquals("OTP_REQUIRED", resend().code()); }
        clock.advance(Duration.ofSeconds(60)); assertEquals("OTP_COOLDOWN", resend().code());
        assertEquals(5, mail.sent.size());
        clock.advance(Duration.ofSeconds(3301)); // First send is now outside the rolling window.
        assertEquals("OTP_REQUIRED", resend().code()); assertEquals(6, mail.sent.size());
        clock.advance(Duration.ofSeconds(60)); assertEquals("OTP_REQUIRED", resend().code());
    }

    @Test void failedDeliveryPreservesPreviousOtpAndStillThrottlesAttempts() throws Exception {
        register("Khoa", "khoa@example.com"); String original = mail.last().code();
        clock.advance(Duration.ofSeconds(60)); mail.fail = true;
        assertEquals("MAIL_UNAVAILABLE", resend().code());
        assertEquals(2, count("Auth_Email_OTP_Send"));
        assertEquals("OTP_COOLDOWN", resend().code());
        assertTrue(verify("khoa@example.com", original).success());
    }

    @Test void noExistingPendingRegistrationCannotBeResentOrUsedAsReset() throws Exception {
        assertEquals("OTP_INVALID", resend().code());
        assertEquals("OTP_INVALID", verify("khoa@example.com", "123456").code());
        register("Khoa", "khoa@example.com");
        assertEquals("OTP_INVALID", reset(mail.last().code(), NEW_PASSWORD).code());
        assertTrue(verify("khoa@example.com", mail.last().code()).success());
    }

    @Test void requestResetNeverRevealsMissingInactiveOrFailedEmailDelivery() throws Exception {
        seed();
        AuthResponse existing = requestReset("khoa@example.com"); assertEquals(1, mail.sent.size());
        AuthResponse limited = requestReset("khoa@example.com");
        AuthResponse missing = requestReset("missing@example.com"); assertEquals(1, mail.sent.size());
        execute("UPDATE Nguoi_Dung SET Trang_Thai='BI_KHOA'");
        clock.advance(Duration.ofSeconds(60)); AuthResponse inactive = requestReset("khoa@example.com");
        execute("UPDATE Nguoi_Dung SET Trang_Thai='HOAT_DONG'");
        clock.advance(Duration.ofSeconds(60)); mail.fail = true;
        AuthResponse failed = requestReset("khoa@example.com");
        for (AuthResponse response : List.of(limited, missing, inactive, failed)) {
            assertEquals(existing, response); assertTrue(response.success()); assertNull(response.user()); assertNull(response.token());
        }
        assertEquals(1, mail.sent.size());
    }

    @Test void resetAtomicallyChangesHashRevokesEverySessionAndConsumesCode() throws Exception {
        seed();
        String first = login(PASSWORD).token(), second = login(PASSWORD).token();
        requestReset("KHOA@example.com"); String code = mail.last().code();
        assertTrue(reset(code, NEW_PASSWORD).success());
        assertFalse(login(PASSWORD).success()); assertTrue(login(NEW_PASSWORD).success());
        assertEquals(2, revoked.size());
        assertTrue(revoked.contains(AuthService.tokenHash(first))); assertTrue(revoked.contains(AuthService.tokenHash(second)));
        assertEquals("2", scalar("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NOT NULL"));
        assertEquals("OTP_INVALID", reset(code, "OtherKhoa789@").code());
        assertTrue(login(NEW_PASSWORD).success());
    }

    @Test void invalidNewPasswordDoesNotConsumeOtpOrRevokeSessions() throws Exception {
        seed(); login(PASSWORD); requestReset("khoa@example.com"); String code = mail.last().code();
        assertEquals("VALIDATION_ERROR", reset(code, "weak").code());
        assertTrue(revoked.isEmpty()); assertNull(scalar("SELECT Thu_Hoi_Luc FROM Phien_Dang_Nhap"));
        assertTrue(reset(code, NEW_PASSWORD).success());
    }

    @Test void resetCannotUseRegistrationCodeOrEmailChangedSinceRequest() throws Exception {
        seed(); requestReset("khoa@example.com"); String code = mail.last().code();
        assertEquals("OTP_INVALID", verify("khoa@example.com", code).code());
        execute("UPDATE Nguoi_Dung SET Email='different@example.com'");
        assertEquals("OTP_INVALID", reset(code, NEW_PASSWORD).code());
        assertTrue(login(PASSWORD).success()); assertTrue(revoked.isEmpty());
    }

    @Test void resetRejectsAccountLockedAfterOtpWasIssued() throws Exception {
        seed(); requestReset("khoa@example.com"); String code = mail.last().code();
        execute("UPDATE Nguoi_Dung SET Trang_Thai='BI_KHOA'");
        assertEquals("OTP_INVALID", reset(code, NEW_PASSWORD).code());
        assertTrue(passwords.verify(PASSWORD, scalar("SELECT Mat_Khau_Bam FROM Nguoi_Dung")));
    }

    @Test void resetTransactionFailureRollsBackPasswordOtpAndRevocations() throws Exception {
        seed(); login(PASSWORD); requestReset("khoa@example.com"); String code = mail.last().code();
        execute("ALTER TABLE Phien_Dang_Nhap DROP COLUMN Thu_Hoi_Luc");
        assertThrows(SQLException.class, () -> reset(code, NEW_PASSWORD));
        assertTrue(passwords.verify(PASSWORD, scalar("SELECT Mat_Khau_Bam FROM Nguoi_Dung")));
        assertEquals("FALSE", scalar("SELECT Consumed FROM Auth_Email_OTP WHERE Purpose='RESET'"));
        assertTrue(revoked.isEmpty());
        execute("ALTER TABLE Phien_Dang_Nhap ADD Thu_Hoi_Luc TIMESTAMP NULL");
        assertTrue(reset(code, NEW_PASSWORD).success());
    }

    @Test void revocationFailureCannotUndoCommittedPasswordChange() throws Exception {
        seed(); login(PASSWORD); requestReset("khoa@example.com");
        SessionRevocationListener failing = ignored -> { throw new IllegalStateException("listener failed"); };
        AuthService withFailingListener = new AuthService(this::connection, passwords, clock, Duration.ofHours(24), failing,
                new EmailOtpService(this::connection, passwords, clock, mail, failing));
        assertTrue(withFailingListener.handle(request("RESET_PASSWORD", null, NEW_PASSWORD, null, "khoa@example.com", mail.last().code())).success());
        assertTrue(login(NEW_PASSWORD).success());
        assertEquals("1", scalar("SELECT COUNT(*) FROM Phien_Dang_Nhap WHERE Thu_Hoi_Luc IS NOT NULL"));
    }

    @Test void concurrentVerificationsAcrossServiceInstancesCreateOnlyOneAccount() throws Exception {
        register("Khoa", "khoa@example.com"); String code = mail.last().code(); AuthService other = newService();
        List<AuthResponse> responses = race(() -> verify("khoa@example.com", code),
                () -> other.handle(registrationRequest("VERIFY_REGISTRATION", "khoa@example.com", code)));
        assertEquals(1, responses.stream().filter(AuthResponse::success).count());
        assertEquals(1, count("Nguoi_Dung"));
        assertTrue(responses.stream().anyMatch(r -> "OTP_INVALID".equals(r.code())));
    }

    @Test void concurrentResendsAcrossInstancesDeliverOnlyOneReplacement() throws Exception {
        register("Khoa", "khoa@example.com"); clock.advance(Duration.ofSeconds(60)); AuthService other = newService();
        List<AuthResponse> responses = race(this::resend, () -> other.handle(registrationRequest("RESEND_REGISTRATION_OTP", "khoa@example.com", null)));
        assertEquals(1, responses.stream().filter(AuthResponse::success).count());
        assertEquals(2, mail.sent.size()); assertEquals(2, count("Auth_Email_OTP_Send"));
        assertTrue(verify("khoa@example.com", mail.last().code()).success());
    }

    @Test void concurrentResetConsumesOnceAndEmitsRevocationsOnce() throws Exception {
        seed(); login(PASSWORD); requestReset("khoa@example.com"); String code = mail.last().code(); AuthService other = newService();
        List<AuthResponse> responses = race(() -> reset(code, NEW_PASSWORD),
                () -> other.handle(request("RESET_PASSWORD", null, "OtherKhoa789@", null, "khoa@example.com", code)));
        assertEquals(1, responses.stream().filter(AuthResponse::success).count());
        assertEquals(1, revoked.size());
    }

    @Test void duplicateUsernameAtFinalVerificationDoesNotConsumePendingCode() throws Exception {
        register("Khoa", "khoa@example.com"); String code = mail.last().code();
        assertTrue(legacy.handle(request("REGISTER", "Khoa", PASSWORD, "Other", "other@example.com", null)).success());
        assertEquals("USERNAME_TAKEN", verify("khoa@example.com", code).code());
        assertEquals("FALSE", scalar("SELECT Consumed FROM Auth_Email_OTP"));
        assertEquals(1, count("Nguoi_Dung"));
    }

    @Test void migrationCanRunTwiceAndMissingTableReturnsActionableSafeError() throws Exception {
        runMigration();
        execute("DROP TABLE Auth_Email_OTP_Send"); execute("DROP TABLE Auth_Email_OTP");
        AuthResponse response = register("Khoa", "khoa@example.com");
        assertEquals("DB_MIGRATION_REQUIRED", response.code());
        assertTrue(response.message().contains("002-email-otp.sql")); assertFalse(response.message().contains(url));
    }

    @Test void accentInsensitiveDatabaseCannotSendResetForADifferentMailbox() throws Exception {
        assertTrue(legacy.handle(request("REGISTER", "Khoa", PASSWORD, "Khoa", "résumé@example.com", null)).success());
        service = accentInsensitiveService();
        assertTrue(requestReset("resume@example.com").success());
        assertTrue(mail.sent.isEmpty(), "MySQL collation candidates must pass exact mailbox validation before SMTP");
        assertTrue(requestReset("RÉSUMÉ@example.com").success());
        assertEquals(1, mail.sent.size()); assertEquals("résumé@example.com", mail.last().email());
        assertTrue(service.handle(request("RESET_PASSWORD", null, NEW_PASSWORD, null, "résumé@example.com", mail.last().code())).success());
    }

    @Test void accentOnlyEmailChangeAfterIssueInvalidatesResetAssociation() throws Exception {
        assertTrue(legacy.handle(request("REGISTER", "Khoa", PASSWORD, "Khoa", "resume@example.com", null)).success());
        service = accentInsensitiveService();
        requestReset("resume@example.com"); String code = mail.last().code();
        execute("UPDATE Nguoi_Dung SET Email='résumé@example.com'");
        assertEquals("OTP_INVALID", service.handle(request("RESET_PASSWORD", null, NEW_PASSWORD, null, "resume@example.com", code)).code());
        assertTrue(passwords.verify(PASSWORD, scalar("SELECT Mat_Khau_Bam FROM Nguoi_Dung")));
        assertTrue(revoked.isEmpty());
    }

    @Test void mailboxComparisonAllowsNormalizedCaseButPreservesAccentsAndCharacters() {
        assertTrue(EmailOtpService.sameMailbox(" KHOA@example.com ", "khoa@example.com"));
        assertFalse(EmailOtpService.sameMailbox("résumé@example.com", "resume@example.com"));
        assertFalse(EmailOtpService.sameMailbox("a+b@example.com", "ab@example.com"));
    }

    @Test void replacingPendingRegistrationCannotMakeOldUiActivateAttackersPassword() throws Exception {
        AuthResponse legitimate = register("Khoa", "khoa@example.com");
        String oldFlow = legitimate.registrationChallenge(), oldCode = mail.last().code();
        clock.advance(Duration.ofSeconds(60));
        AuthResponse attacker = service.handle(request("REGISTER", "Attacker", NEW_PASSWORD, "Other", "khoa@example.com", null));
        assertEquals("OTP_REQUIRED", attacker.code()); assertNotEquals(oldFlow, attacker.registrationChallenge());
        assertEquals("OTP_INVALID", service.handle(new AuthRequest("RESEND_REGISTRATION_OTP", null, null, null,
                "khoa@example.com", null, null, null, oldFlow)).code());
        assertEquals("OTP_INVALID", service.handle(new AuthRequest("VERIFY_REGISTRATION", null, null, null,
                "khoa@example.com", null, null, mail.last().code(), oldFlow)).code());
        assertEquals("OTP_INVALID", service.handle(new AuthRequest("VERIFY_REGISTRATION", null, null, null,
                "khoa@example.com", null, null, oldCode, oldFlow)).code());
        assertEquals(0, count("Nguoi_Dung")); assertEquals(2, mail.sent.size());
        assertEquals("0", scalar("SELECT Attempts FROM Auth_Email_OTP"));
        assertEquals("OTP_INVALID", service.handle(request("VERIFY_REGISTRATION", null, null, null,
                "khoa@example.com", mail.last().code())).code(), "Email OTP alone cannot activate another registration flow");
        assertTrue(service.handle(new AuthRequest("VERIFY_REGISTRATION", null, null, null,
                "khoa@example.com", null, null, mail.last().code(), attacker.registrationChallenge())).success());
    }

    @Test void registrationResendKeepsItsFlowAndRejectsMissingOrForeignFlow() throws Exception {
        AuthResponse registration = register("Khoa", "khoa@example.com");
        String flow = registration.registrationChallenge();
        clock.advance(Duration.ofSeconds(60));
        assertEquals("OTP_INVALID", service.handle(request("RESEND_REGISTRATION_OTP", null, null, null, "khoa@example.com", null)).code());
        AuthResponse resent = resend();
        assertEquals("OTP_REQUIRED", resent.code()); assertEquals(flow, resent.registrationChallenge());
        assertTrue(verify("khoa@example.com", mail.last().code()).success());
    }

    private AuthService newService() {
        EmailOtpService otp = new EmailOtpService(this::connection, passwords, clock, mail, revoked::add);
        return new AuthService(this::connection, passwords, clock, Duration.ofHours(24), revoked::add, otp);
    }
    private AuthService accentInsensitiveService() {
        // Deliberately reproduce the dangerous MySQL candidate selection inside H2.
        // Both sides are folded, so the service must inspect the actual returned Email.
        ConnectionProvider accentConnections = () -> {
            Connection delegate = connection();
            return (Connection) Proxy.newProxyInstance(EmailOtpServiceTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                            args[0] = sql.replace("LOWER(Email)=?", "TRANSLATE(LOWER(Email), 'é', 'e')=TRANSLATE(?, 'é', 'e')");
                        }
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        };
        return new AuthService(accentConnections, passwords, clock, Duration.ofHours(24), revoked::add,
                new EmailOtpService(accentConnections, passwords, clock, mail, revoked::add));
    }
    private void seed() throws SQLException {
        assertTrue(legacy.handle(request("REGISTER", "Khoa", PASSWORD, "Khoa", "khoa@example.com", null)).success());
    }
    private AuthResponse register(String username, String email) throws SQLException {
        AuthResponse response = service.handle(request("REGISTER", username, PASSWORD, "Khoa", email, null));
        if ("OTP_REQUIRED".equals(response.code())) registrationChallenges.put(EmailOtpService.normalizedEmail(email), response.registrationChallenge());
        return response;
    }
    private AuthResponse login(String password) throws SQLException { return service.handle(request("LOGIN", "Khoa", password, null, null, null)); }
    private AuthResponse verify(String email, String code) throws SQLException { return service.handle(registrationRequest("VERIFY_REGISTRATION", email, code)); }
    private AuthResponse resend() throws SQLException { return service.handle(registrationRequest("RESEND_REGISTRATION_OTP", "khoa@example.com", null)); }
    private AuthResponse requestReset(String email) throws SQLException { return service.handle(request("REQUEST_PASSWORD_RESET", null, null, null, email, null)); }
    private AuthResponse reset(String code, String password) throws SQLException { return service.handle(request("RESET_PASSWORD", null, password, null, "khoa@example.com", code)); }
    private static AuthRequest request(String action, String user, String password, String display, String email, String code) {
        return new AuthRequest(action, user, password, display, email, null, null, code);
    }
    private AuthRequest registrationRequest(String action, String email, String code) {
        return new AuthRequest(action, null, null, null, email, null, null, code,
                registrationChallenges.get(EmailOtpService.normalizedEmail(email)));
    }
    private void runMigration() throws Exception {
        String sql = Files.readString(Path.of("database/migrations/002-email-otp.sql"))
                .replace(" COLLATE utf8mb4_bin", "")
                .replaceAll("(?m)\\) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;", ");");
        try (Connection connection = connection()) { RunScript.execute(connection, new StringReader(sql)); }
    }
    private Connection connection() throws SQLException { return DriverManager.getConnection(url); }
    private void execute(String sql) throws SQLException { try (Connection c = connection(); Statement s = c.createStatement()) { s.execute(sql); } }
    private String scalar(String sql) throws SQLException {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { assertTrue(r.next()); return r.getString(1); }
    }
    private int count(String table) throws SQLException { return Integer.parseInt(scalar("SELECT COUNT(*) FROM " + table)); }
    private static List<AuthResponse> race(Checked first, Checked second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var one = executor.submit(() -> { start.await(); return first.run(); });
            var two = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown(); return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
        }
    }
    @FunctionalInterface private interface Checked { AuthResponse run() throws Exception; }
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T07:59:30Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private static final class FakeMail implements OtpMailSender {
        private final List<Delivery> sent = new CopyOnWriteArrayList<>(); private boolean fail;
        @Override public void send(String email, String purpose, String code) throws MailDeliveryException {
            if (fail) throw new MailDeliveryException(); sent.add(new Delivery(email, purpose, code));
        }
        Delivery last() { return sent.getLast(); }
    }
    private record Delivery(String email, String purpose, String code) { }
}
