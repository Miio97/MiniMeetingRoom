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
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    public AuthService() {
        this(DatabaseConnection::getConnection, new PasswordHasher(), Clock.systemUTC(),
                Duration.ofHours(ServerConfig.getInt("auth.sessionHours", 24)));
    }

    public AuthService(ConnectionProvider connections, PasswordHasher passwords, Clock clock,
            Duration sessionLifetime) {
        this.connections = Objects.requireNonNull(connections);
        this.passwords = Objects.requireNonNull(passwords);
        this.clock = Objects.requireNonNull(clock);
        this.sessionLifetime = Objects.requireNonNull(sessionLifetime);
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
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc = ? WHERE Token_Bam = ? AND Thu_Hoi_Luc IS NULL AND Het_Han_Luc > ?")) {
            statement.setTimestamp(1, Timestamp.from(now), utc());
            statement.setString(2, tokenHash(token));
            statement.setTimestamp(3, Timestamp.from(now), utc());
            statement.executeUpdate();
        }
        return AuthResponse.ok("Đã đăng xuất.", null, null, null);
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
