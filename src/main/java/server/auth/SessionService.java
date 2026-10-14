package server.auth;

import common.filetransfer.TransferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import server.database.DatabaseConnection;

/** Resolves the existing opaque login token to a server-authoritative user id. */
public final class SessionService {
    private final ConnectionProvider connections;
    private final Clock clock;

    public SessionService() { this(DatabaseConnection::getConnection); }

    public SessionService(ConnectionProvider connections) { this(connections, Clock.systemUTC()); }

    public SessionService(ConnectionProvider connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Always rechecks expiry, revocation and account status; tokens are never logged or cached. */
    public long authenticate(String token) throws SQLException, TransferException {
        if (token == null || token.isBlank() || token.length() > 256) throw invalidSession();
        try (Connection connection = connections.getConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT p.Ma_Nguoi_Dung FROM Phien_Dang_Nhap p
                        JOIN Nguoi_Dung n ON n.Ma_Nguoi_Dung = p.Ma_Nguoi_Dung
                        WHERE p.Token_Bam = ? AND p.Thu_Hoi_Luc IS NULL
                        AND p.Het_Han_Luc > ? AND n.Trang_Thai = 'HOAT_DONG'
                        """)) {
            statement.setString(1, AuthService.tokenHash(token));
            statement.setTimestamp(2, Timestamp.from(clock.instant()),
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw invalidSession();
                return result.getLong(1);
            }
        }
    }

    private static TransferException invalidSession() {
        return new TransferException("INVALID_SESSION", "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
    }
}
