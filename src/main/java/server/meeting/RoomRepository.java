package server.meeting;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import common.meeting.RoomStatus;
import server.auth.ConnectionProvider;
import server.auth.SessionPrincipal;
import server.database.DatabaseConnection;

/** Durable room/history operations. Every mutation uses an isolated JDBC transaction. */
public final class RoomRepository {
    private static final String ROOM_SELECT = "SELECT r.Ma_Phong, x.Room_Opaque_ID, r.Ma_Phong_Tham_Gia, "
            + "r.Ten_Phong, r.Ma_PIN_Bam, r.Ma_Chu_Phong, r.Trang_Thai, r.Da_Khoa, r.So_Nguoi_Toi_Da, "
            + "x.Instance_ID, x.Server_Epoch, r.Ngay_Tao FROM Phong_Hop r JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=r.Ma_Phong ";
    private final ConnectionProvider connections;
    public RoomRepository() { this(DatabaseConnection::getConnection); }
    public RoomRepository(ConnectionProvider connections) { this.connections = java.util.Objects.requireNonNull(connections); }

    public void checkSchema() throws SQLException {
        try (Connection connection = connections.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT Ma_Phong, Room_Opaque_ID, Instance_ID, Server_Epoch, Ngay_Tao FROM MMR_Phong_Van_Hanh WHERE 1=0").close();
            statement.executeQuery("SELECT Ma_Phien, Request_ID, Operation, Body_HMAC, Result_Code, Ma_Phong, Ma_Thanh_Vien_Phong, Participant_Opaque_ID, Ngay_Tao, Het_Han_Luc FROM MMR_Yeu_Cau_Phong WHERE 1=0").close();
        }
    }

    /** Only marked rooms belonging to this instance are reconciled after a server restart. */
    public void reconcile(String instanceId, String epoch, Instant now) throws SQLException {
        transaction(connection -> {
            List<Long> rooms = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT r.Ma_Phong FROM Phong_Hop r JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=r.Ma_Phong "
                            + "WHERE x.Instance_ID=? AND x.Server_Epoch<>? AND r.Trang_Thai<>'DA_DONG' ORDER BY r.Ma_Phong FOR UPDATE")) {
                statement.setString(1, instanceId); statement.setString(2, epoch);
                try (ResultSet rows = statement.executeQuery()) { while (rows.next()) rooms.add(rows.getLong(1)); }
            }
            for (long id : rooms) closeRoom(connection, id, now, "MAT_KET_NOI");
            return null;
        });
    }

    StoredRoom findByCode(String instanceId, String code) throws SQLException {
        try (Connection connection = connections.getConnection(); PreparedStatement statement = connection.prepareStatement(
                ROOM_SELECT + "WHERE x.Instance_ID=? AND r.Ma_Phong_Tham_Gia=?")) {
            statement.setString(1, instanceId); statement.setString(2, code);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? room(rows) : null; }
        }
    }

    CreateResult create(SessionPrincipal principal, String requestId, byte[] bodyHash, String instance,
            String epoch, String roomId, String code, String name, String pinHash, int capacity,
            boolean locked, boolean admissionAllowed, Instant now) throws SQLException {
        return transaction(connection -> {
            lockSession(connection, principal, now);
            Receipt receipt = receipt(connection, principal, requestId, "CREATE_ROOM", bodyHash, now);
            if (receipt != null) return new CreateResult(loadRoom(connection, receipt.roomId()), true);
            if (!admissionAllowed) throw new RoomException("SERVER_CAPACITY");
            long id;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam, Ma_Chu_Phong, Trang_Thai, Da_Khoa, So_Nguoi_Toi_Da, Ngay_Tao) VALUES (?, ?, ?, ?, 'CHO', ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, code); statement.setString(2, name); statement.setString(3, pinHash);
                statement.setLong(4, principal.userId()); statement.setBoolean(5, locked); statement.setInt(6, capacity);
                timestamp(statement, 7, now); statement.executeUpdate(); id = generated(statement);
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO MMR_Phong_Van_Hanh (Ma_Phong, Room_Opaque_ID, Instance_ID, Server_Epoch, Ngay_Tao) VALUES (?, ?, ?, ?, ?)")) {
                statement.setLong(1, id); statement.setString(2, roomId); statement.setString(3, instance);
                statement.setString(4, epoch); timestamp(statement, 5, now); statement.executeUpdate();
            }
            completeReceipt(connection, principal, requestId, "CREATE_ROOM", bodyHash, id, null, null, now);
            return new CreateResult(new StoredRoom(id, roomId, code, name, pinHash, principal.userId(),
                    RoomStatus.READY, locked, capacity, instance, epoch, now.toString()), false);
        });
    }

    JoinResult join(SessionPrincipal principal, String requestId, byte[] bodyHash, long roomId,
            String participantId, String instanceId, String epoch, Instant now) throws SQLException {
        return transaction(connection -> {
            lockSession(connection, principal, now);
            Receipt receipt = receipt(connection, principal, requestId, "JOIN_ROOM", bodyHash, now);
            if (receipt != null) {
                StoredRoom existing = loadRoom(connection, receipt.roomId());
                return new JoinResult(existing, receipt.membershipId(), receipt.participantId(), true,
                        membershipActive(connection, receipt.membershipId()));
            }
            StoredRoom room = loadRoomForUpdate(connection, roomId);
            if (room == null || !instanceId.equals(room.instanceId()) || !epoch.equals(room.epoch())) throw new RoomException("ROOM_NOT_FOUND");
            if (room.status() == RoomStatus.CLOSED) throw new RoomException("ROOM_CLOSED");
            boolean host = room.hostId() == principal.userId();
            if (!host && room.status() == RoomStatus.READY) throw new RoomException("ROOM_NOT_STARTED");
            if (!host && room.locked()) throw new RoomException("ROOM_LOCKED");
            int count = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT Ma_Nguoi_Dung FROM Thanh_Vien_Phong WHERE Ma_Phong=? AND Roi_Phong_Luc IS NULL")) {
                statement.setLong(1, roomId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        count++;
                        if (rows.getLong(1) == principal.userId()) throw new RoomException("ALREADY_JOINED");
                    }
                }
            }
            if (count >= room.capacity()) throw new RoomException("ROOM_FULL");
            long membership;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Thanh_Vien_Phong (Ma_Phong, Ma_Nguoi_Dung, Vai_Tro, Tham_Gia_Luc) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, roomId); statement.setLong(2, principal.userId());
                statement.setString(3, host ? "CHU_PHONG" : "THANH_VIEN"); timestamp(statement, 4, now);
                statement.executeUpdate(); membership = generated(statement);
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE Phong_Hop SET Trang_Thai='DANG_HOAT_DONG' WHERE Ma_Phong=?")) {
                statement.setLong(1, roomId); statement.executeUpdate();
            }
            completeReceipt(connection, principal, requestId, "JOIN_ROOM", bodyHash, roomId, membership, participantId, now);
            return new JoinResult(room.withStatus(RoomStatus.ACTIVE), membership, participantId, false, true);
        });
    }

    void leave(long membershipId, Instant now, String reason) throws SQLException {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc=?, Ly_Do_Roi=? WHERE Ma_Thanh_Vien_Phong=? AND Roi_Phong_Luc IS NULL")) {
                timestamp(statement, 1, now); statement.setString(2, reason); statement.setLong(3, membershipId); statement.executeUpdate();
            }
            return null;
        });
    }

    void closeRoom(long roomId, Instant now, String reason) throws SQLException {
        transaction(connection -> { loadRoomForUpdate(connection, roomId); closeRoom(connection, roomId, now, reason); return null; });
    }

    void setLocked(long roomId, boolean locked) throws SQLException {
        transaction(connection -> {
            StoredRoom room = loadRoomForUpdate(connection, roomId);
            if (room == null || room.status() == RoomStatus.CLOSED) throw new RoomException("ROOM_CLOSED");
            try (PreparedStatement statement = connection.prepareStatement("UPDATE Phong_Hop SET Da_Khoa=? WHERE Ma_Phong=?")) {
                statement.setBoolean(1, locked); statement.setLong(2, roomId); statement.executeUpdate();
            }
            return null;
        });
    }

    private static void closeRoom(Connection connection, long id, Instant now, String reason) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE Phong_Hop SET Trang_Thai='DA_DONG', Ngay_Dong=? WHERE Ma_Phong=? AND Trang_Thai<>'DA_DONG'")) {
            timestamp(statement, 1, now); statement.setLong(2, id); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc=?, Ly_Do_Roi=? WHERE Ma_Phong=? AND Roi_Phong_Luc IS NULL")) {
            timestamp(statement, 1, now); statement.setString(2, reason); statement.setLong(3, id); statement.executeUpdate();
        }
    }

    private static void lockSession(Connection connection, SessionPrincipal principal, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.Ma_Nguoi_Dung, s.Token_Bam, s.Het_Han_Luc, s.Thu_Hoi_Luc, u.Trang_Thai FROM Phien_Dang_Nhap s "
                        + "JOIN Nguoi_Dung u ON u.Ma_Nguoi_Dung=s.Ma_Nguoi_Dung WHERE s.Ma_Phien=? FOR UPDATE")) {
            statement.setLong(1, principal.sessionId());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) != principal.userId() || !principal.tokenHash().equals(rows.getString(2))
                        || !rows.getTimestamp(3, utc()).toInstant().isAfter(now) || rows.getTimestamp(4, utc()) != null
                        || !"HOAT_DONG".equals(rows.getString(5))) throw new RoomException("SESSION_INVALID");
            }
        }
    }

    private static Receipt receipt(Connection connection, SessionPrincipal principal, String requestId,
            String operation, byte[] bodyHash, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT Operation, Body_HMAC, Result_Code, Ma_Phong, Ma_Thanh_Vien_Phong, Participant_Opaque_ID, Het_Han_Luc "
                        + "FROM MMR_Yeu_Cau_Phong WHERE Ma_Phien=? AND Request_ID=? FOR UPDATE")) {
            statement.setLong(1, principal.sessionId()); statement.setString(2, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    if (!operation.equals(rows.getString(1)) || !MessageDigest.isEqual(bodyHash, rows.getBytes(2))) throw new RoomException("REQUEST_ID_REUSED");
                    if (!rows.getTimestamp(7, utc()).toInstant().isAfter(now)) throw new RoomException("REQUEST_EXPIRED");
                    if (!"OK".equals(rows.getString(3))) throw new RoomException("REQUEST_INCOMPLETE");
                    return new Receipt(rows.getLong(4), rows.getLong(5), rows.getString(6));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM MMR_Yeu_Cau_Phong WHERE Ma_Phien=?")) {
            statement.setLong(1, principal.sessionId());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next(); if (rows.getInt(1) >= 1024) throw new RoomException("REQUEST_LIMIT");
            }
        }
        return null;
    }

    private static void completeReceipt(Connection connection, SessionPrincipal principal, String requestId,
            String operation, byte[] bodyHash, long roomId, Long membership, String participantId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO MMR_Yeu_Cau_Phong (Ma_Phien, Request_ID, Operation, Body_HMAC, Result_Code, Ma_Phong, "
                        + "Ma_Thanh_Vien_Phong, Participant_Opaque_ID, Ngay_Tao, Het_Han_Luc) VALUES (?, ?, ?, ?, 'OK', ?, ?, ?, ?, ?)")) {
            statement.setLong(1, principal.sessionId()); statement.setString(2, requestId); statement.setString(3, operation);
            statement.setBytes(4, bodyHash); statement.setLong(5, roomId);
            if (membership == null) statement.setNull(6, java.sql.Types.BIGINT); else statement.setLong(6, membership);
            statement.setString(7, participantId); timestamp(statement, 8, now); timestamp(statement, 9, principal.expiresAt());
            statement.executeUpdate();
        }
    }

    private static boolean membershipActive(Connection connection, long membership) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT Roi_Phong_Luc FROM Thanh_Vien_Phong WHERE Ma_Thanh_Vien_Phong=?")) {
            statement.setLong(1, membership);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getTimestamp(1, utc()) == null; }
        }
    }

    private static StoredRoom loadRoom(Connection connection, long id) throws SQLException {
        return loadRoom(connection, id, false);
    }
    private static StoredRoom loadRoomForUpdate(Connection connection, long id) throws SQLException {
        return loadRoom(connection, id, true);
    }
    private static StoredRoom loadRoom(Connection connection, long id, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ROOM_SELECT + "WHERE r.Ma_Phong=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? room(rows) : null; }
        }
    }
    private static StoredRoom room(ResultSet rows) throws SQLException {
        RoomStatus status = switch (rows.getString(7)) {
            case "CHO" -> RoomStatus.READY;
            case "DANG_HOAT_DONG" -> RoomStatus.ACTIVE;
            default -> RoomStatus.CLOSED;
        };
        return new StoredRoom(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4),
                rows.getString(5), rows.getLong(6), status, rows.getBoolean(8), rows.getInt(9), rows.getString(10), rows.getString(11),
                rows.getTimestamp(12, utc()).toInstant().toString());
    }
    private static long generated(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("Generated key unavailable");
            return keys.getLong(1);
        }
    }
    private <T> T transaction(SqlTask<T> task) throws SQLException {
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T value = task.execute(connection); connection.commit(); return value;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
    }
    private static void timestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        statement.setTimestamp(index, Timestamp.from(value), utc());
    }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private interface SqlTask<T> { T execute(Connection connection) throws SQLException; }
    record StoredRoom(long id, String roomId, String joinCode, String name, String pinHash, long hostId,
            RoomStatus status, boolean locked, int capacity, String instanceId, String epoch, String createdAt) {
        StoredRoom withStatus(RoomStatus value) { return new StoredRoom(id, roomId, joinCode, name, pinHash, hostId, value, locked, capacity, instanceId, epoch, createdAt); }
        StoredRoom withLock(boolean value) { return new StoredRoom(id, roomId, joinCode, name, pinHash, hostId, status, value, capacity, instanceId, epoch, createdAt); }
        @Override public String toString() { return "StoredRoom[status=" + status + ", capacity=" + capacity + "]"; }
    }
    record CreateResult(StoredRoom room, boolean replay) { }
    record JoinResult(StoredRoom room, long membershipId, String participantId, boolean replay, boolean active) { }
    private record Receipt(long roomId, long membershipId, String participantId) { }
}
