package server.auth;

import server.database.DatabaseConnection;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Calendar;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.regex.Pattern;

/** Authoritative server-side validation; no identity supplied by the client is trusted. */
public final class SessionValidator {
    private static final Pattern SESSION_VALUE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final ConnectionProvider connections;
    private final Clock clock;

    public SessionValidator() {
        this(DatabaseConnection::getConnection, Clock.systemUTC());
    }

    public SessionValidator(ConnectionProvider connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    public Optional<SessionPrincipal> validate(String rawToken) throws SQLException {
        if (!valid(rawToken)) return Optional.empty();
        return validateHash(AuthService.tokenHash(rawToken));
    }

    /** For periodic revalidation, retain only a server-side hash rather than a raw token. */
    public Optional<SessionPrincipal> validateHash(String hash) throws SQLException {
        if (!valid(hash)) return Optional.empty();
        try (var connection = connections.getConnection();
                var query =
                        connection.prepareStatement(
                                """
SELECT p.Ma_Phien, p.Ma_Nguoi_Dung, p.Token_Bam, p.Het_Han_Luc,
       p.Thu_Hoi_Luc, u.Ten_Hien_Thi, u.Trang_Thai
FROM Phien_Dang_Nhap p JOIN Nguoi_Dung u ON u.Ma_Nguoi_Dung = p.Ma_Nguoi_Dung
WHERE p.Token_Bam = ?
""")) {
            query.setString(1, hash);
            try (var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                // MySQL's existing collation is case-insensitive; hashes are not.
                if (!hash.equals(rows.getString("Token_Bam"))
                        || rows.getTimestamp("Thu_Hoi_Luc", utc()) != null
                        || !"HOAT_DONG".equals(rows.getString("Trang_Thai")))
                    return Optional.empty();
                var expiry = rows.getTimestamp("Het_Han_Luc", utc());
                long sessionId = rows.getLong("Ma_Phien"), userId = rows.getLong("Ma_Nguoi_Dung");
                String displayName = rows.getString("Ten_Hien_Thi");
                if (expiry == null
                        || !expiry.toInstant().isAfter(clock.instant())
                        || sessionId <= 0
                        || userId <= 0
                        || displayName == null
                        || displayName.length() > 100) return Optional.empty();
                return Optional.of(
                        new SessionPrincipal(
                                sessionId, userId, displayName, expiry.toInstant(), hash));
            }
        }
    }

    private static boolean valid(String value) {
        return value != null && SESSION_VALUE.matcher(value).matches();
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
