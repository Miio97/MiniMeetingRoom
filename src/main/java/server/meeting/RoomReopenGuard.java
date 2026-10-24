package server.meeting;

import java.sql.Connection;
import java.sql.SQLException;

/** Invoked with the room row locked, before every reopen. No timers authorize a reopen. */
@FunctionalInterface
public interface RoomReopenGuard {
    void check(Connection connection, long roomId) throws SQLException;

    static void requireEmpty(Connection connection, long roomId) throws SQLException {
        try (var query =
                connection.prepareStatement("SELECT 1 FROM Tin_Nhan WHERE Ma_Phong=? LIMIT 1")) {
            query.setLong(1, roomId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) throw new RoomException("ROOM_CLEANING");
            }
        }
    }
}
