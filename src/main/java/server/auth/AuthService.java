package server.auth;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.auth.AuthUser;
import common.auth.AuthValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.regex.Pattern;
import server.config.ServerConfig;
import server.database.DatabaseConnection;

/** Account and session operations against the project's existing MySQL schema. */
public final class AuthService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final String INVALID_CREDENTIALS = "Tên đăng nhập hoặc mật khẩu không đúng.";
    private final ConnectionProvider connections;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final Duration sessionLifetime;
    private final SessionRevocationListener revocations;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService() {
        this(SessionRevocationListener.NONE);
    }

    public AuthService(SessionRevocationListener revocations) {
        this(DatabaseConnection::getConnection, new PasswordHasher(), Clock.systemUTC(),
                Duration.ofHours(ServerConfig.getInt("auth.sessionHours", 24)), revocations);
    }

    public AuthService(ConnectionProvider connections, PasswordHasher passwords, Clock clock,
            Duration sessionLifetime) {
        this(connections, passwords, clock, sessionLifetime, SessionRevocationListener.NONE);
    }

    public AuthService(ConnectionProvider connections, PasswordHasher passwords, Clock clock,
            Duration sessionLifetime, SessionRevocationListener revocations) {
        this.connections = Objects.requireNonNull(connections);
        this.passwords = Objects.requireNonNull(passwords);
        this.clock = Objects.requireNonNull(clock);
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime);
        this.revocations = Objects.requireNonNull(revocations);
        if (sessionLifetime.isNegative() || sessionLifetime.isZero()
                || sessionLifetime.compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("Session lifetime must be between 1 second and 365 days.");
        }
        this.dummyHash = passwords.hash("dummy-credentials-" + randomToken());
    }

    public AuthResponse handle(AuthRequest request) throws SQLException {
        if (request == null || request.action() == null) {
            return AuthResponse.error("INVALID_REQUEST", "Yêu cầu không hợp lệ.");
        }
        return switch (request.action().toUpperCase(Locale.ROOT)) {
            case "REGISTER" -> register(request);
            case "LOGIN" -> login(request);
            case "LOGOUT" -> logout(request.token());
            case "UPDATE_PROFILE" -> updateProfile(request);
            case "CHANGE_PASSWORD" -> changePassword(request);
            default -> AuthResponse.error("INVALID_REQUEST", "Chức năng không được hỗ trợ.");
        };
    }

    private AuthResponse register(AuthRequest request) throws SQLException {
        String username = request.username();
        String displayName = clean(request.displayName());
        String email = clean(request.email());
        String password = request.password();
        AuthResponse validation = validateRegistration(username, password, displayName, email);
        if (validation != null) {
            return validation;
        }
        String hash = passwords.hash(password);
        try (Connection connection = connections.getConnection()) {
            AuthResponse duplicate = duplicateAccount(connection, username, email);
            if (duplicate != null) {
                return duplicate;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Nguoi_Dung (Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi, Email) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, username);
                statement.setString(2, hash);
                statement.setString(3, displayName);
                statement.setString(4, email.isEmpty() ? null : email);
                statement.executeUpdate();
            } catch (SQLException exception) {
                // The unique constraints remain authoritative when registrations race.
                if (exception.getSQLState() != null && exception.getSQLState().startsWith("23")) {
                    duplicate = duplicateAccount(connection, username, email);
                    if (duplicate != null) {
                        return duplicate;
                    }
                }
                throw exception;
            }
        }
        return AuthResponse.ok("Đăng ký thành công. Hãy đăng nhập bằng tài khoản mới.", null, null, null);
    }

    private AuthResponse login(AuthRequest request) throws SQLException {
        String username = request.username();
        String password = request.password();
        if (AuthValidation.loginUsernameError(username) != null
                || AuthValidation.loginPasswordError(password) != null) {
            return AuthResponse.error("INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Account account = findAccount(connection, username);
                // MySQL's existing case-insensitive collation may find TranKhoa for trankhoa.
                // Require the exact stored username before accepting credentials, independent of collation.
                boolean exactUsername = account != null && account.user().username().equals(username);
                boolean matches = passwords.verify(password, exactUsername ? account.passwordHash() : dummyHash);
                if (!exactUsername || !matches || !"HOAT_DONG".equals(account.status())) {
                    connection.rollback();
                    return AuthResponse.error("INVALID_CREDENTIALS", INVALID_CREDENTIALS);
                }
                String token = randomToken();
                Instant now = clock.instant();
                Instant expiry = now.plus(sessionLifetime);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO Phien_Dang_Nhap (Ma_Nguoi_Dung, Token_Bam, Ngay_Tao, Het_Han_Luc) VALUES (?, ?, ?, ?)")) {
                    statement.setLong(1, account.user().id());
                    statement.setString(2, tokenHash(token));
                    statement.setTimestamp(3, Timestamp.from(now), utc());
                    statement.setTimestamp(4, Timestamp.from(expiry), utc());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Nguoi_Dung SET Lan_Dang_Nhap_Cuoi = ? WHERE Ma_Nguoi_Dung = ?")) {
                    statement.setTimestamp(1, Timestamp.from(now), utc());
                    statement.setLong(2, account.user().id());
                    statement.executeUpdate();
                }
                connection.commit();
                return AuthResponse.ok("Đăng nhập thành công.", account.user(), token, expiry.toString());
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        }
    }

    private AuthResponse logout(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return AuthResponse.ok("Đã đăng xuất.", null, null, null);
        }
        if (token.length() > 256) {
            return AuthResponse.error("INVALID_REQUEST", "Phiên đăng nhập không hợp lệ.");
        }
        Instant now = clock.instant();
        String hash = tokenHash(token);
        int updated;
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc = ? WHERE Token_Bam = ? AND Thu_Hoi_Luc IS NULL AND Het_Han_Luc > ?")) {
                    statement.setTimestamp(1, Timestamp.from(now), utc());
                    statement.setString(2, hash);
                    statement.setTimestamp(3, Timestamp.from(now), utc());
                    updated = statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(connection, failure);
                throw failure;
            }
        }
        if (updated > 0) {
            try { revocations.revoked(hash); }
            catch (RuntimeException ignored) { /* Logout is already committed; periodic validation is the fallback. */ }
        }
        return AuthResponse.ok("Đã đăng xuất.", null, null, null);
    }

    private AuthResponse updateProfile(AuthRequest request) throws SQLException {
        String name = clean(request.displayName()), email = clean(request.email());
        String error = AuthValidation.profileError(request.username(), name, email);
        if (error != null) return AuthResponse.error("VALIDATION_ERROR", error);
        try (Connection connection = connections.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            long userId = -1;
            try {
                Account account = lockAuthenticatedAccount(connection, request.token());
                if (account == null) { connection.rollback(); return expiredSession(); }
                userId = account.user().id();
                AuthResponse duplicate = duplicateProfile(connection, request.username(), email, userId);
                if (duplicate != null) { connection.rollback(); return duplicate; }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Nguoi_Dung SET Ten_Dang_Nhap=?, Ten_Hien_Thi=?, Email=? WHERE Ma_Nguoi_Dung=?")) {
                    statement.setString(1, request.username()); statement.setString(2, name);
                    statement.setString(3, email.isEmpty() ? null : email); statement.setLong(4, userId);
                    statement.executeUpdate();
                }
                connection.commit();
                return AuthResponse.ok("Đã lưu thông tin tài khoản.",
                        new AuthUser(userId, request.username(), name, email.isEmpty() ? null : email), null, null);
            } catch (SQLException exception) {
                rollback(connection, exception);
                if (userId > 0 && exception.getSQLState() != null && exception.getSQLState().startsWith("23")) {
                    AuthResponse duplicate = duplicateProfile(connection, request.username(), email, userId);
                    if (duplicate != null) return duplicate;
                }
                throw exception;
            } catch (RuntimeException exception) { rollback(connection, exception); throw exception; }
        }
    }

    private AuthResponse changePassword(AuthRequest request) throws SQLException {
        String error = AuthValidation.loginPasswordError(request.password());
        if (error == null) error = AuthValidation.registrationPasswordError(request.newPassword());
        if (error != null) return AuthResponse.error("VALIDATION_ERROR", error);
        List<String> revoked = new ArrayList<>();
        AuthUser user;
        try (Connection connection = connections.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Account account = lockAuthenticatedAccount(connection, request.token());
                if (account == null) { connection.rollback(); return expiredSession(); }
                if (!passwords.verify(request.password(), account.passwordHash())) {
                    connection.rollback();
                    return AuthResponse.error("WRONG_PASSWORD", "Mật khẩu hiện tại không đúng.");
                }
                if (request.password().equals(request.newPassword())) {
                    connection.rollback();
                    return AuthResponse.error("VALIDATION_ERROR", "Mật khẩu mới phải khác mật khẩu hiện tại.");
                }
                user = account.user();
                Instant now = clock.instant();
                String currentHash = tokenHash(request.token());
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Nguoi_Dung SET Mat_Khau_Bam=? WHERE Ma_Nguoi_Dung=?")) {
                    statement.setString(1, passwords.hash(request.newPassword())); statement.setLong(2, user.id());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT Token_Bam FROM Phien_Dang_Nhap WHERE Ma_Nguoi_Dung=? AND Token_Bam<>?
                        AND Thu_Hoi_Luc IS NULL AND Het_Han_Luc>? ORDER BY Ma_Phien FOR UPDATE
                        """)) {
                    statement.setLong(1, user.id()); statement.setString(2, currentHash);
                    statement.setTimestamp(3, Timestamp.from(now), utc());
                    try (ResultSet rows = statement.executeQuery()) { while (rows.next()) revoked.add(rows.getString(1)); }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc=? WHERE Ma_Nguoi_Dung=? AND Token_Bam<>?
                        AND Thu_Hoi_Luc IS NULL AND Het_Han_Luc>?
                        """)) {
                    statement.setTimestamp(1, Timestamp.from(now), utc()); statement.setLong(2, user.id());
                    statement.setString(3, currentHash); statement.setTimestamp(4, Timestamp.from(now), utc());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) { rollback(connection, exception); throw exception; }
        }
        for (String hash : revoked) {
            try { revocations.revoked(hash); }
            catch (RuntimeException ignored) { /* Committed revocation is also enforced by periodic validation. */ }
        }
        return AuthResponse.ok("Đã đổi mật khẩu. Các phiên đăng nhập khác đã hết hiệu lực.", user, null, null);
    }

    /** Account first, session second: matches login and serializes concurrent password changes. */
    private Account lockAuthenticatedAccount(Connection connection, String token) throws SQLException {
        if (token == null || token.isBlank() || token.length() > 256) return null;
        String hash = tokenHash(token);
        long userId;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Ma_Nguoi_Dung, Token_Bam FROM Phien_Dang_Nhap WHERE Token_Bam=? AND Thu_Hoi_Luc IS NULL AND Het_Han_Luc>?")) {
            statement.setString(1, hash); statement.setTimestamp(2, Timestamp.from(clock.instant()), utc());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !hash.equals(rows.getString(2))) return null;
                userId = rows.getLong(1);
            }
        }
        Account account;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT Ma_Nguoi_Dung, Ten_Dang_Nhap, Ten_Hien_Thi, Email, Mat_Khau_Bam, Trang_Thai
                FROM Nguoi_Dung WHERE Ma_Nguoi_Dung=? FOR UPDATE
                """)) {
            statement.setLong(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !"HOAT_DONG".equals(rows.getString("Trang_Thai"))) return null;
                account = new Account(new AuthUser(userId, rows.getString("Ten_Dang_Nhap"), rows.getString("Ten_Hien_Thi"),
                        rows.getString("Email")), rows.getString("Mat_Khau_Bam"), rows.getString("Trang_Thai"));
            }
        }
        // Recheck after waiting for the account lock: logout or another password change may have revoked this token.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Token_Bam FROM Phien_Dang_Nhap WHERE Token_Bam=? AND Thu_Hoi_Luc IS NULL AND Het_Han_Luc>? FOR UPDATE")) {
            statement.setString(1, hash); statement.setTimestamp(2, Timestamp.from(clock.instant()), utc());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && hash.equals(rows.getString(1)) ? account : null; }
        }
    }

    private static AuthResponse duplicateProfile(Connection connection, String username, String email, long userId) throws SQLException {
        for (String column : List.of("Ten_Dang_Nhap", "Email")) {
            String value = column.equals("Ten_Dang_Nhap") ? username : email;
            if (value.isEmpty()) continue;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM Nguoi_Dung WHERE " + column + "=? AND Ma_Nguoi_Dung<>?")) {
                statement.setString(1, value); statement.setLong(2, userId);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) return column.equals("Ten_Dang_Nhap")
                            ? AuthResponse.error("USERNAME_TAKEN", "Tên đăng nhập đã được sử dụng.")
                            : AuthResponse.error("EMAIL_TAKEN", "Email đã được sử dụng.");
                }
            }
        }
        return null;
    }

    private static AuthResponse expiredSession() {
        return AuthResponse.error("SESSION_EXPIRED", "Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.");
    }

    private static AuthResponse validateRegistration(String username, String password, String displayName, String email) {
        String usernameError = AuthValidation.registrationUsernameError(username);
        if (usernameError != null) {
            return AuthResponse.error("VALIDATION_ERROR", usernameError);
        }
        if (displayName.isEmpty() || displayName.length() > 100) {
            return AuthResponse.error("VALIDATION_ERROR", "Tên hiển thị phải có 1–100 ký tự.");
        }
        String passwordError = AuthValidation.registrationPasswordError(password);
        if (passwordError != null) {
            return AuthResponse.error("VALIDATION_ERROR", passwordError);
        }
        if (!email.isEmpty() && (email.length() > 255 || !EMAIL.matcher(email).matches())) {
            return AuthResponse.error("VALIDATION_ERROR", "Địa chỉ email không hợp lệ (tối đa 255 ký tự).");
        }
        return null;
    }

    private static AuthResponse duplicateAccount(Connection connection, String username, String email) throws SQLException {
        if (exists(connection, "SELECT 1 FROM Nguoi_Dung WHERE Ten_Dang_Nhap = ?", username)) {
            return AuthResponse.error("USERNAME_TAKEN", "Tên đăng nhập đã được sử dụng.");
        }
        if (!email.isEmpty() && exists(connection, "SELECT 1 FROM Nguoi_Dung WHERE Email = ?", email)) {
            return AuthResponse.error("EMAIL_TAKEN", "Email đã được sử dụng.");
        }
        return null;
    }

    private static boolean exists(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static Account findAccount(Connection connection, String username) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Ma_Nguoi_Dung, Ten_Dang_Nhap, Ten_Hien_Thi, Email, Mat_Khau_Bam, Trang_Thai FROM Nguoi_Dung WHERE Ten_Dang_Nhap = ? FOR UPDATE")) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                AuthUser user = new AuthUser(result.getLong("Ma_Nguoi_Dung"), result.getString("Ten_Dang_Nhap"),
                        result.getString("Ten_Hien_Thi"), result.getString("Email"));
                return new Account(user, result.getString("Mat_Khau_Bam"), result.getString("Trang_Thai"));
            }
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String tokenHash(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Session hashing is unavailable", exception);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static void rollback(Connection connection, Exception cause) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            cause.addSuppressed(rollbackFailure);
        }
    }

    private record Account(AuthUser user, String passwordHash, String status) {
    }
}
