package server.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthValidation;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Durable, transactionally consumed email challenges. No account exists until registration verification. */
public final class EmailOtpService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CODE = Pattern.compile("[0-9]{6}");
    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final String REGISTER = "REGISTER", RESET = "RESET", VERIFIED = "VERIFIED";
    private static final String RESET_MESSAGE = "Nếu email thuộc tài khoản đang hoạt động, mã OTP đã được gửi. Mã có hiệu lực 5 phút.";
    private final ConnectionProvider connections;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final OtpMailSender mail;
    private final SessionRevocationListener revocations;
    private final SecureRandom random = new SecureRandom();

    public EmailOtpService(ConnectionProvider connections, PasswordHasher passwords, Clock clock,
            OtpMailSender mail, SessionRevocationListener revocations) {
        this.connections = Objects.requireNonNull(connections);
        this.passwords = Objects.requireNonNull(passwords);
        this.clock = Objects.requireNonNull(clock);
        this.mail = Objects.requireNonNull(mail);
        this.revocations = Objects.requireNonNull(revocations);
    }

    public AuthResponse beginRegistration(AuthRequest request) throws SQLException {
        String email = normalizedEmail(request.email());
        AuthResponse validation = AuthService.validateRegistration(request.username(), request.password(),
                clean(request.displayName()), email);
        if (validation != null) return validation;
        if (!validEmail(email)) return invalidEmail();
        String passwordHash = passwords.hash(request.password());
        return issue(email, REGISTER, new Pending(request.username(), clean(request.displayName()), passwordHash), false, newRegistrationChallenge());
    }

    public AuthResponse resendRegistration(String rawEmail) throws SQLException {
        return resendRegistration(rawEmail, null);
    }

    public AuthResponse resendRegistration(String rawEmail, String registrationChallenge) throws SQLException {
        String email = normalizedEmail(rawEmail);
        if (!validEmail(email)) return invalidEmail();
        return issue(email, REGISTER, null, true, registrationChallenge);
    }

    public AuthResponse requestReset(String rawEmail) throws SQLException {
        String email = normalizedEmail(rawEmail);
        if (!validEmail(email)) return invalidEmail();
        return issue(email, RESET, null, false, null);
    }

    private AuthResponse issue(String email, String purpose, Pending pending, boolean resend,
            String registrationChallenge) throws SQLException {
        Instant now = clock.instant();
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Challenge row = lock(connection, email, purpose);
                if (REGISTER.equals(purpose)) {
                    if (resend && (row.pending() == null || row.consumed())) {
                        connection.rollback();
                        return invalidCode();
                    }
                    if (resend && !matchesRegistrationChallenge(row, registrationChallenge)) {
                        connection.rollback(); return invalidRegistrationFlow();
                    }
                    if (pending == null) pending = row.pending();
                    AuthResponse duplicate = AuthService.duplicateAccount(connection, pending.username(), email);
                    if (duplicate != null) { connection.rollback(); return duplicate; }
                }
                boolean limited = row.lastSent() != null && now.isBefore(row.lastSent().plus(COOLDOWN));
                limited |= sendsInLastHour(connection, email, purpose, now) >= 5;
                if (limited) {
                    connection.rollback();
                    return RESET.equals(purpose) ? resetRequested()
                            : AuthResponse.error("OTP_COOLDOWN", "Vui lòng chờ ít nhất 60 giây giữa các lần gửi; tối đa 5 lần mỗi giờ.");
                }
                Long accountId = RESET.equals(purpose) ? activeAccountId(connection, email) : null;
                String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                while (row.codeHash() != null && passwords.verify(code, row.codeHash())) {
                    code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
                }
                String codeHash = passwords.hash(code);
                // Keep the previous challenge intact until delivery succeeds. The row lock also
                // prevents a verification/resend from racing this delivery on another server.
                if (REGISTER.equals(purpose) || accountId != null) mail.send(email, purpose, code);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE Auth_Email_OTP SET Code_Hash=?, Expires_At=?, Attempts=0, Consumed=FALSE,
                            Last_Sent_At=?, Pending_Username=?, Pending_Display_Name=?, Pending_Password_Hash=?, Account_Id=?,
                            Registration_Challenge_Hash=?
                        WHERE Email=? AND Purpose=?
                        """)) {
                    statement.setString(1, codeHash);
                    statement.setTimestamp(2, Timestamp.from(now.plus(VALIDITY)), utc());
                    statement.setTimestamp(3, Timestamp.from(now), utc());
                    statement.setString(4, pending == null ? null : pending.username());
                    statement.setString(5, pending == null ? null : pending.displayName());
                    statement.setString(6, pending == null ? null : pending.passwordHash());
                    if (accountId == null) statement.setNull(7, java.sql.Types.BIGINT); else statement.setLong(7, accountId);
                    statement.setString(8, REGISTER.equals(purpose) ? AuthService.tokenHash(registrationChallenge) : null);
                    statement.setString(9, email);
                    statement.setString(10, purpose);
                    statement.executeUpdate();
                }
                recordSend(connection, email, purpose, now);
                connection.commit();
                return RESET.equals(purpose) ? resetRequested()
                        : new AuthResponse(true, "OTP_REQUIRED", "Đã gửi mã OTP đến email. Mã có hiệu lực 5 phút.",
                                null, null, null, null, List.of(), registrationChallenge);
            } catch (OtpMailSender.MailDeliveryException failure) {
                // Count failed delivery attempts too, so an SMTP outage cannot bypass throttles.
                // No new OTP or pending account was stored before the delivery attempt.
                try (PreparedStatement statement = connection.prepareStatement("UPDATE Auth_Email_OTP SET Last_Sent_At=? WHERE Email=? AND Purpose=?")) {
                    statement.setTimestamp(1, Timestamp.from(now), utc());
                    statement.setString(2, email); statement.setString(3, purpose); statement.executeUpdate();
                }
                recordSend(connection, email, purpose, now);
                connection.commit();
                // Reset responses never reveal whether the address exists, including SMTP outages.
                return RESET.equals(purpose) ? resetRequested()
                        : AuthResponse.error("MAIL_UNAVAILABLE", "Chưa gửi được email xác thực. Vui lòng thử lại sau.");
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
    }

    public AuthResponse verifyRegistration(String rawEmail, String code) throws SQLException {
        return verifyRegistration(rawEmail, code, null);
    }

    public AuthResponse verifyRegistration(String rawEmail, String code, String registrationChallenge) throws SQLException {
        String email = normalizedEmail(rawEmail);
        if (!validEmail(email)) return invalidEmail();
        if (!validCode(code)) return invalidCode();
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Challenge row = lock(connection, email, REGISTER);
                if (!matchesRegistrationChallenge(row, registrationChallenge)) {
                    connection.rollback(); return invalidRegistrationFlow();
                }
                AuthResponse validation = validateAndCount(connection, email, REGISTER, row, code);
                if (validation != null) { connection.commit(); return validation; }
                if (row.pending() == null) { connection.rollback(); return invalidCode(); }
                Pending pending = row.pending();
                AuthResponse duplicate = AuthService.duplicateAccount(connection, pending.username(), email);
                if (duplicate != null) { connection.rollback(); return duplicate; }
                long accountId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi, Email) VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, pending.username());
                    statement.setString(2, pending.passwordHash());
                    statement.setString(3, pending.displayName());
                    statement.setString(4, email);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("The verified account identity was not returned.");
                        accountId = keys.getLong(1);
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Auth_Email_OTP SET Account_Id=? WHERE Email=? AND Purpose=?")) {
                    statement.setLong(1, accountId); statement.setString(2, email); statement.setString(3, REGISTER);
                    statement.executeUpdate();
                }
                storeVerification(connection, accountId, email, true);
                consume(connection, email, REGISTER);
                connection.commit();
                return AuthResponse.ok("Xác thực email và đăng ký thành công. Hãy đăng nhập bằng tài khoản mới.", null, null, null);
            } catch (SQLException failure) {
                rollback(connection, failure);
                if (failure.getSQLState() != null && failure.getSQLState().startsWith("23")) {
                    // A different email can concurrently verify the same username.
                    Challenge row = read(connection, email, REGISTER, false);
                    if (row != null && row.pending() != null) {
                        AuthResponse duplicate = AuthService.duplicateAccount(connection, row.pending().username(), email);
                        if (duplicate != null) return duplicate;
                    }
                }
                throw failure;
            } catch (RuntimeException failure) { rollback(connection, failure); throw failure; }
        }
    }

    public AuthResponse resetPassword(String rawEmail, String code, String password) throws SQLException {
        String email = normalizedEmail(rawEmail);
        if (!validEmail(email)) return invalidEmail();
        String error = AuthValidation.registrationPasswordError(password);
        if (error != null) return AuthResponse.error("VALIDATION_ERROR", error);
        if (!validCode(code)) return invalidCode();
        String newHash = passwords.hash(password);
        List<String> revoked = new ArrayList<>();
        try (Connection connection = connections.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                // Profile updates and password changes lock the account first. Resolve the bound
                // challenge without locking, then use the same order and recheck it under its lock.
                Challenge previous = read(connection, email, RESET, false);
                Long accountId = previous == null ? null : previous.accountId();
                boolean activeAccount = accountId != null && lockActiveAccount(connection, accountId, email);
                if (accountId != null && !activeAccount) { connection.rollback(); return invalidCode(); }
                Challenge row = lock(connection, email, RESET);
                AuthResponse validation = validateAndCount(connection, email, RESET, row, code);
                if (validation != null) { connection.commit(); return validation; }
                if (!activeAccount || !accountId.equals(row.accountId())) {
                    connection.rollback(); return invalidCode();
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE Nguoi_Dung SET Mat_Khau_Bam=? WHERE Ma_Nguoi_Dung=?")) {
                    statement.setString(1, newHash);
                    statement.setLong(2, row.accountId());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("SELECT Token_Bam FROM Phien_Dang_Nhap WHERE Ma_Nguoi_Dung=? AND Thu_Hoi_Luc IS NULL FOR UPDATE")) {
                    statement.setLong(1, row.accountId());
                    try (ResultSet result = statement.executeQuery()) { while (result.next()) revoked.add(result.getString(1)); }
                }
                try (PreparedStatement statement = connection.prepareStatement("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc=? WHERE Ma_Nguoi_Dung=? AND Thu_Hoi_Luc IS NULL")) {
                    statement.setTimestamp(1, Timestamp.from(clock.instant()), utc());
                    statement.setLong(2, row.accountId());
                    statement.executeUpdate();
                }
                storeVerification(connection, row.accountId(), email, true);
                consume(connection, email, RESET);
                connection.commit();
            } catch (SQLException | RuntimeException failure) { rollback(connection, failure); throw failure; }
        }
        for (String tokenHash : revoked) {
            try { revocations.revoked(tokenHash); }
            catch (RuntimeException ignored) { /* Already committed; periodic session checks provide a fallback. */ }
        }
        return AuthResponse.ok("Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại.", null, null, null);
    }

    /**
     * Uses a separate purpose in the existing OTP table so requesting a new reset cannot
     * erase an already verified email. The caller holds the account lock, never a challenge lock.
     */
    boolean isEmailVerified(Connection connection, long accountId, String rawEmail) throws SQLException {
        String email = normalizedEmail(rawEmail);
        if (email.isEmpty()) return false;
        try {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT Email, Account_Id, Consumed FROM Auth_Email_OTP WHERE Email=? AND Purpose=?")) {
                statement.setString(1, email); statement.setString(2, VERIFIED);
                try (ResultSet row = statement.executeQuery()) {
                    // False proof rows are tombstones after profile changes; do not revive older OTPs.
                    if (row.next()) return sameMailbox(row.getString("Email"), email)
                            && row.getLong("Account_Id") == accountId && row.getBoolean("Consumed");
                }
            }
            // Successful old resets carry a trustworthy account binding. Historical registrations
            // without Account_Id cannot be linked by username alone after an email transfer/recreation.
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT Email FROM Auth_Email_OTP WHERE Email=? AND Account_Id=?
                    AND Consumed=TRUE AND Purpose IN ('REGISTER', 'RESET')
                    """)) {
                statement.setString(1, email); statement.setLong(2, accountId);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || !sameMailbox(row.getString("Email"), email)) return false;
                }
            }
            storeVerification(connection, accountId, email, true);
            return true;
        } catch (SQLException failure) {
            if (missingOtpSchema(failure)) return false;
            throw failure;
        }
    }

    /** Email updates retire reset codes and proof without touching a registration flow. */
    void clearEmailVerification(Connection connection, long accountId, String oldEmail, String newEmail) throws SQLException {
        try {
            // Deterministic proof-row order also covers concurrent updates on different accounts.
            for (String email : java.util.stream.Stream.of(normalizedEmail(oldEmail), normalizedEmail(newEmail))
                    .filter(value -> !value.isEmpty()).distinct().sorted().toList()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE Auth_Email_OTP SET Consumed=TRUE, Code_Hash=NULL
                        WHERE Email=? AND Purpose=? AND Account_Id=?
                        """)) {
                    statement.setString(1, email); statement.setString(2, RESET);
                    statement.setLong(3, accountId); statement.executeUpdate();
                }
                storeVerification(connection, accountId, email, false);
            }
        } catch (SQLException failure) {
            if (!missingOtpSchema(failure)) throw failure;
        }
    }

    private static void storeVerification(Connection connection, long accountId, String email, boolean verified) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO Auth_Email_OTP (Email, Purpose, Account_Id, Consumed) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE Account_Id=VALUES(Account_Id), Consumed=VALUES(Consumed)
                """)) {
            statement.setString(1, email); statement.setString(2, VERIFIED);
            statement.setLong(3, accountId); statement.setBoolean(4, verified); statement.executeUpdate();
        }
    }

    private static boolean missingOtpSchema(SQLException failure) {
        return failure.getSQLState() != null && failure.getSQLState().startsWith("42")
                && failure.getMessage() != null && failure.getMessage().toLowerCase(Locale.ROOT).contains("auth_email_otp");
    }

    private AuthResponse validateAndCount(Connection connection, String email, String purpose,
            Challenge row, String code) throws SQLException {
        if (row == null || row.consumed() || row.codeHash() == null) return invalidCode();
        if (row.attempts() >= 5) return AuthResponse.error("OTP_LOCKED", "Đã nhập sai OTP 5 lần. Vui lòng yêu cầu mã mới.");
        if (row.expires() == null || !clock.instant().isBefore(row.expires()))
            return AuthResponse.error("OTP_EXPIRED", "Mã OTP đã hết hạn. Vui lòng yêu cầu mã mới.");
        if (passwords.verify(code, row.codeHash())) return null;
        try (PreparedStatement statement = connection.prepareStatement("UPDATE Auth_Email_OTP SET Attempts=Attempts+1 WHERE Email=? AND Purpose=?")) {
            statement.setString(1, email); statement.setString(2, purpose); statement.executeUpdate();
        }
        return row.attempts() + 1 >= 5 ? AuthResponse.error("OTP_LOCKED", "Đã nhập sai OTP 5 lần. Vui lòng yêu cầu mã mới.") : invalidCode();
    }

    private Challenge lock(Connection connection, String email, String purpose) throws SQLException {
        // MySQL's upsert obtains an exclusive row lock even when the row already exists.
        // It also serializes first requests, which SELECT FOR UPDATE on a missing row cannot do safely.
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO Auth_Email_OTP (Email, Purpose) VALUES (?, ?) ON DUPLICATE KEY UPDATE Email=VALUES(Email)")) {
            statement.setString(1, email); statement.setString(2, purpose); statement.executeUpdate();
        }
        return read(connection, email, purpose, true);
    }

    private Challenge read(Connection connection, String email, String purpose, boolean locking) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM Auth_Email_OTP WHERE Email=? AND Purpose=?" + (locking ? " FOR UPDATE" : ""))) {
            statement.setString(1, email); statement.setString(2, purpose);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                String username = row.getString("Pending_Username");
                long accountId = row.getLong("Account_Id");
                Long account = row.wasNull() ? null : accountId;
                return new Challenge(row.getString("Code_Hash"), instant(row, "Expires_At"), row.getInt("Attempts"),
                        row.getBoolean("Consumed"), instant(row, "Last_Sent_At"), username == null ? null
                        : new Pending(username, row.getString("Pending_Display_Name"), row.getString("Pending_Password_Hash")),
                        account, row.getString("Registration_Challenge_Hash"));
            }
        }
    }

    private static Long activeAccountId(Connection connection, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT Ma_Nguoi_Dung, Email FROM Nguoi_Dung WHERE LOWER(Email)=? AND Trang_Thai='HOAT_DONG'")) {
            statement.setString(1, email);
            try (ResultSet result = statement.executeQuery()) {
                // Existing MySQL account columns may equate résumé and resume. SMTP does not.
                return result.next() && sameMailbox(result.getString("Email"), email) ? result.getLong(1) : null;
            }
        }
    }

    private static boolean lockActiveAccount(Connection connection, long account, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT Ma_Nguoi_Dung, Email FROM Nguoi_Dung WHERE Ma_Nguoi_Dung=? AND LOWER(Email)=? AND Trang_Thai='HOAT_DONG' FOR UPDATE")) {
            statement.setLong(1, account); statement.setString(2, email);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && sameMailbox(result.getString("Email"), email);
            }
        }
    }

    private static int sendsInLastHour(Connection connection, String email, String purpose, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM Auth_Email_OTP_Send WHERE Email=? AND Purpose=? AND Sent_At>?")) {
            statement.setString(1, email); statement.setString(2, purpose);
            statement.setTimestamp(3, Timestamp.from(now.minus(Duration.ofHours(1))), utc());
            try (ResultSet result = statement.executeQuery()) { result.next(); return result.getInt(1); }
        }
    }

    private static void recordSend(Connection connection, String email, String purpose, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM Auth_Email_OTP_Send WHERE Email=? AND Purpose=? AND Sent_At<=?")) {
            statement.setString(1, email); statement.setString(2, purpose);
            statement.setTimestamp(3, Timestamp.from(now.minus(Duration.ofHours(1))), utc()); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO Auth_Email_OTP_Send (Email, Purpose, Sent_At) VALUES (?,?,?)")) {
            statement.setString(1, email); statement.setString(2, purpose);
            statement.setTimestamp(3, Timestamp.from(now), utc()); statement.executeUpdate();
        }
    }

    private static void consume(Connection connection, String email, String purpose) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE Auth_Email_OTP SET Consumed=TRUE, Code_Hash=NULL, Pending_Password_Hash=NULL, Registration_Challenge_Hash=NULL WHERE Email=? AND Purpose=?")) {
            statement.setString(1, email); statement.setString(2, purpose); statement.executeUpdate();
        }
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp timestamp = row.getTimestamp(name, utc()); return timestamp == null ? null : timestamp.toInstant();
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static void rollback(Connection connection, Exception failure) {
        try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }
    private static String clean(String value) { return value == null ? "" : value.strip(); }
    private String newRegistrationChallenge() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static boolean matchesRegistrationChallenge(Challenge row, String challenge) {
        if (row == null || row.registrationChallengeHash() == null || challenge == null
                || !challenge.matches("[A-Za-z0-9_-]{43}")) return false;
        return MessageDigest.isEqual(row.registrationChallengeHash().getBytes(StandardCharsets.US_ASCII),
                AuthService.tokenHash(challenge).getBytes(StandardCharsets.US_ASCII));
    }
    static String normalizedEmail(String value) { return clean(value).toLowerCase(Locale.ROOT); }
    static boolean sameMailbox(String storedEmail, String suppliedEmail) {
        return normalizedEmail(storedEmail).equals(normalizedEmail(suppliedEmail));
    }
    private static boolean validEmail(String email) { return email.length() <= 255 && EMAIL.matcher(email).matches(); }
    private static boolean validCode(String code) { return code != null && CODE.matcher(code).matches(); }
    private static AuthResponse invalidEmail() { return AuthResponse.error("VALIDATION_ERROR", "Vui lòng nhập địa chỉ email hợp lệ (tối đa 255 ký tự)."); }
    private static AuthResponse invalidCode() { return AuthResponse.error("OTP_INVALID", "Mã OTP không đúng hoặc không còn hiệu lực."); }
    private static AuthResponse invalidRegistrationFlow() {
        return AuthResponse.error("OTP_INVALID", "Yêu cầu đăng ký đã thay đổi hoặc không còn hiệu lực. Vui lòng đăng ký lại.");
    }
    private static AuthResponse resetRequested() { return AuthResponse.ok(RESET_MESSAGE, null, null, null); }
    private record Pending(String username, String displayName, String passwordHash) { }
    private record Challenge(String codeHash, Instant expires, int attempts, boolean consumed,
            Instant lastSent, Pending pending, Long accountId, String registrationChallengeHash) { }
}
