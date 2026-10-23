package server.rooms;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.room.RoomInfo;
import common.room.RoomParticipant;
import common.room.RoomRequest;
import common.room.RoomValidation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import server.auth.ConnectionProvider;
import server.auth.PasswordHasher;
import server.database.DatabaseConnection;

/** Authenticated room operations against the existing Phong_Hop and Thanh_Vien_Phong tables. */
public final class RoomService {
    private static final int CODE_ATTEMPTS = 5;
    private static final String ROOM_COLUMNS = "p.Ma_Phong, p.Ten_Phong, p.Ma_Phong_Tham_Gia, "
            + "p.Ma_Chu_Phong, p.Ngay_Tao, p.Trang_Thai, p.Da_Khoa, p.So_Nguoi_Toi_Da, p.Ngay_Dong";
    private final ConnectionProvider connections;
    private final PasswordHasher passwords;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final boolean excludeSignalingRooms;
    private volatile String legacyOwner;
    public void legacyOwner(String instance) {
        if(instance==null || !instance.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("INSTANCE_ID");legacyOwner=instance;
    }
    private void markLegacyOwner(Connection connection,long roomId) throws SQLException {
        if(legacyOwner==null) return;
        server.meeting.RoomRepository.markLegacyOwnership(connection,roomId,legacyOwner,clock.instant());
    }
    private volatile server.meeting.RoomReopenGuard reopenGuard=server.meeting.RoomReopenGuard::requireEmpty;
    public void setReopenGuard(server.meeting.RoomReopenGuard guard) {reopenGuard=Objects.requireNonNull(guard);}
    private volatile Runnable changed=() -> { };
    public void onChanged(Runnable listener) {changed=Objects.requireNonNull(listener);}
    private volatile java.util.function.LongConsumer closedRoom=id -> {};
    public void onRoomClosed(java.util.function.LongConsumer listener) {closedRoom=Objects.requireNonNull(listener);}

    public RoomService() {
        this(DatabaseConnection::getConnection, new PasswordHasher(), Clock.systemUTC());
    }

    /** Combined server keeps signaling membership under its connection owner. */
    public RoomService(boolean excludeSignalingRooms) {
        this(DatabaseConnection::getConnection, new PasswordHasher(), Clock.systemUTC(), excludeSignalingRooms);
    }

    public RoomService(ConnectionProvider connections, PasswordHasher passwords, Clock clock) {
        this(connections, passwords, clock, false);
    }

    public RoomService(ConnectionProvider connections, PasswordHasher passwords, Clock clock, boolean excludeSignalingRooms) {
        this.connections = Objects.requireNonNull(connections);
        this.passwords = Objects.requireNonNull(passwords);
        this.clock = Objects.requireNonNull(clock);
        this.excludeSignalingRooms = excludeSignalingRooms;
    }

    public AuthResponse handle(AuthRequest request) throws SQLException {
        if (request == null || request.action() == null) {
            return AuthResponse.error("INVALID_REQUEST", "Yêu cầu không hợp lệ.");
        }
        String action = request.action().toUpperCase(Locale.ROOT);
        if (!List.of("CREATE_ROOM", "REOPEN_ROOM", "JOIN_ROOM", "LIST_ROOMS", "GET_ROOM", "LEAVE_ROOM").contains(action)) {
            return AuthResponse.error("INVALID_REQUEST", "Chức năng không được hỗ trợ.");
        }
        String validation = validate(action, request.room());
        if (validation != null) {
            return AuthResponse.error("VALIDATION_ERROR", validation);
        }
        if (request.token() == null || request.token().isBlank() || request.token().length() > 256) {
            return invalidSession();
        }
        try (Connection connection = connections.getConnection()) {
            boolean readOnly = "LIST_ROOMS".equals(action) || "GET_ROOM".equals(action);
            // Session validation must not establish a stale MySQL REPEATABLE READ snapshot before
            // the room lock is acquired. READ COMMITTED makes the capacity check see the last join.
            if (!readOnly) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
            }
            try {
                Long userId = authenticatedUser(connection, request.token());
                if (userId == null) {
                    if (!readOnly) connection.rollback();
                    return invalidSession();
                }
                AuthResponse response = switch (action) {
                    case "CREATE_ROOM" -> create(connection, userId, request.room());
                    case "REOPEN_ROOM" -> reopen(connection, userId, request.room());
                    case "JOIN_ROOM" -> join(connection, userId, request.room());
                    case "LIST_ROOMS" -> list(connection, userId, request.room());
                    case "GET_ROOM" -> get(connection, userId, request.room());
                    case "LEAVE_ROOM" -> leave(connection, userId, request.room());
                    default -> throw new IllegalStateException("Unsupported room action");
                };
                if (readOnly) return response;
                if (response.success()) {
                    connection.commit();
                    if("LEAVE_ROOM".equals(action) && response.room()!=null && "DA_DONG".equals(response.room().status())) closedRoom.accept(response.room().id());
                    changed.run();
                } else {
                    connection.rollback();
                }
                return response;
            } catch (SQLException | RuntimeException exception) {
                if (!readOnly) rollback(connection, exception);
                throw exception;
            }
        }
    }

    private AuthResponse create(Connection connection, long userId, RoomRequest request) throws SQLException {
        String pinHash = passwords.hash(request.pin());
        Instant createdAt = clock.instant();
        long roomId = 0;
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = randomCode();
            Savepoint savepoint = connection.setSavepoint();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam, Ma_Chu_Phong, "
                            + "Trang_Thai, Da_Khoa, So_Nguoi_Toi_Da, Ngay_Tao) VALUES (?, ?, ?, ?, 'DANG_HOAT_DONG', FALSE, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, code);
                statement.setString(2, request.name().strip());
                statement.setString(3, pinHash);
                statement.setLong(4, userId);
                statement.setInt(5, request.capacity());
                statement.setTimestamp(6, Timestamp.from(createdAt), utc());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("Room insert did not return a generated ID.");
                    }
                    roomId = keys.getLong(1);
                }
                connection.releaseSavepoint(savepoint);
                break;
            } catch (SQLException exception) {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
                if (exception.getSQLState() == null || !exception.getSQLState().startsWith("23")
                        || !codeExists(connection, code) || attempt == CODE_ATTEMPTS - 1) {
                    throw exception;
                }
            }
        }
        markLegacyOwner(connection,roomId);addMember(connection, roomId, userId, "CHU_PHONG", createdAt);
        return AuthResponse.roomOk("Tạo phòng thành công.", snapshot(connection, findRoom(connection, roomId), true));
    }

    private AuthResponse join(Connection connection, long userId, RoomRequest request) throws SQLException {
        RoomRow room = findRoom(connection, "p.Ma_Phong_Tham_Gia", request.identifier().strip().toUpperCase(Locale.ROOT));
        AuthResponse unavailable = unavailable(room);
        if (unavailable != null) {
            return unavailable;
        }
        if (room.locked()) {
            return AuthResponse.error("ROOM_LOCKED", "Phòng đang bị khóa, chưa thể tham gia.");
        }
        String pinHash;
        try (PreparedStatement statement = connection.prepareStatement("SELECT Ma_PIN_Bam FROM Phong_Hop WHERE Ma_Phong = ?")) {
            statement.setLong(1, room.id());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Locked room disappeared.");
                }
                pinHash = result.getString(1);
            }
        }
        if (!passwords.verify(request.pin(), pinHash)) {
            return AuthResponse.error("INVALID_ROOM_PIN", "PIN phòng không đúng.");
        }
        if (!isActiveMember(connection, room.id(), userId)) {
            if (activeCount(connection, room.id()) >= Math.min(50,room.capacity())) {
                return AuthResponse.error("ROOM_FULL", "Phòng đã đủ số người tham gia.");
            }
            addMember(connection, room.id(), userId, room.hostId() == userId ? "CHU_PHONG" : "THANH_VIEN", clock.instant());
        }
        if ("CHO".equals(room.status())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE Phong_Hop SET Trang_Thai = 'DANG_HOAT_DONG' WHERE Ma_Phong = ?")) {
                statement.setLong(1, room.id());
                statement.executeUpdate();
            }
            room = findRoom(connection, room.id());
        }
        return AuthResponse.roomOk("Tham gia phòng thành công.", snapshot(connection, room, true));
    }

    private AuthResponse reopen(Connection connection, long userId, RoomRequest request) throws SQLException {
        RoomRow room = findRoom(connection, request.identifier());
        if (room == null) {
            return AuthResponse.error("ROOM_NOT_FOUND", "Không tìm thấy phòng theo mã đã nhập.");
        }
        if (room.hostId() != userId) {
            return AuthResponse.error("ROOM_ACCESS_DENIED", "Chỉ chủ phòng mới có thể mở lại phòng.");
        }
        if (!"DA_DONG".equals(room.status())) {
            return AuthResponse.error("ROOM_ALREADY_ACTIVE", "Phòng chưa kết thúc hoặc đã được mở lại.");
        }
        var stale=staleGeneration(connection,room.id(),request);if(stale!=null) return stale;
        if(room.capacity()>50) return AuthResponse.error("ROOM_CAPACITY_POLICY","Phòng cũ vượt giới hạn 50 người. Cần điều chỉnh sức chứa trước khi mở lại.");
        Instant now = clock.instant();
        // Keep every previous attendance record ended, including stale active rows from an older server.
        try {reopenGuard.check(connection,room.id());}
        catch(server.meeting.RoomException cleaning) {return AuthResponse.error(cleaning.code(),cleaning.code().equals("ROOM_CLEANUP_OWNER_REQUIRED")?"Phòng legacy cũ chưa xác định server quản lý. Cần xác nhận trước khi dọn và mở lại.":"Đang dọn dữ liệu phòng. Vui lòng thử lại sau.");}
        markLegacyOwner(connection,room.id());
        if(legacyOwner!=null) try(var s=connection.prepareStatement("UPDATE MMR_Phong_Van_Hanh SET Room_Opaque_ID=? WHERE Ma_Phong=? AND Instance_ID=?")) {
            s.setString(1,"l_"+Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(16)));s.setLong(2,room.id());s.setString(3,legacyOwner);
            if(s.executeUpdate()!=1) throw new SQLException("ROOM_GENERATION_MISSING");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc = ?, Ly_Do_Roi = 'PHONG_DONG' "
                        + "WHERE Ma_Phong = ? AND Roi_Phong_Luc IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(now), utc());
            statement.setLong(2, room.id());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE Phong_Hop SET Trang_Thai = 'DANG_HOAT_DONG', Ngay_Dong = NULL, Da_Khoa = FALSE WHERE Ma_Phong = ?")) {
            statement.setLong(1, room.id());
            statement.executeUpdate();
        }
        addMember(connection, room.id(), userId, "CHU_PHONG", now);
        return AuthResponse.roomOk("Đã mở lại phòng.", snapshot(connection, findRoom(connection, room.id()), true));
    }

    private AuthResponse list(Connection connection, long userId, RoomRequest request) throws SQLException {
        int offset = request == null ? 0 : request.offset();
        int limit = request == null ? 20 : request.limit();
        List<RoomInfo> rooms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ROOM_COLUMNS + ", (SELECT COUNT(DISTINCT t.Ma_Nguoi_Dung) FROM Thanh_Vien_Phong t "
                        + "WHERE t.Ma_Phong = p.Ma_Phong AND t.Roi_Phong_Luc IS NULL) AS So_Thanh_Vien, "
                        + (legacyOwner==null?"NULL":"(SELECT x.Room_Opaque_ID FROM MMR_Phong_Van_Hanh x WHERE x.Ma_Phong=p.Ma_Phong)")+" AS Room_Identity "
                        + "FROM Phong_Hop p WHERE (p.Ma_Chu_Phong = ? OR EXISTS "
                        + "(SELECT 1 FROM Thanh_Vien_Phong h WHERE h.Ma_Phong = p.Ma_Phong AND h.Ma_Nguoi_Dung = ?)) "
                        + "ORDER BY p.Ngay_Tao DESC, p.Ma_Phong DESC LIMIT ? OFFSET ?")) {
            statement.setLong(1, userId);
            statement.setLong(2, userId);
            statement.setInt(3, limit + 1);
            statement.setInt(4, offset);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    var room=toInfo(readRoom(result), result.getInt("So_Thanh_Vien"), List.of());
                    rooms.add(new RoomInfo(room.id(),room.name(),room.code(),room.hostId(),room.createdAt(),room.status(),room.locked(),room.capacity(),room.people(),room.participants(),result.getString("Room_Identity")));
                }
            }
        }
        return AuthResponse.roomsPage(List.copyOf(rooms.subList(0, Math.min(limit, rooms.size()))), rooms.size() > limit);
    }

    private AuthResponse get(Connection connection, long userId, RoomRequest request) throws SQLException {
        RoomRow room = findRoom(connection, request.identifier(), false);
        AuthResponse unavailable = unavailable(room);
        if (unavailable != null) {
            return unavailable;
        }
        if (!isActiveMember(connection, room.id(), userId)) {
            return AuthResponse.error("ROOM_ACCESS_DENIED", "Bạn chưa tham gia phòng này hoặc đã rời phòng.");
        }
        return AuthResponse.roomOk("Đã cập nhật thông tin phòng.", snapshot(connection, room, true));
    }

    private AuthResponse leave(Connection connection, long userId, RoomRequest request) throws SQLException {
        RoomRow room = findRoom(connection, request.identifier());
        if (room == null) {
            return AuthResponse.error("ROOM_NOT_FOUND", "Không tìm thấy phòng theo mã đã nhập.");
        }
        if (room.hostId() != userId && !hasEverParticipated(connection, room.id(), userId)) {
            return AuthResponse.error("ROOM_ACCESS_DENIED", "Bạn chưa từng tham gia phòng này.");
        }
        var stale=staleGeneration(connection,room.id(),request);if(stale!=null) return stale;
        Instant now = clock.instant();
        int removed;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc = ?, Ly_Do_Roi = 'TU_ROI' "
                        + "WHERE Ma_Phong = ? AND Ma_Nguoi_Dung = ? AND Roi_Phong_Luc IS NULL")) {
            statement.setTimestamp(1, Timestamp.from(now), utc());
            statement.setLong(2, room.id());
            statement.setLong(3, userId);
            removed = statement.executeUpdate();
        }
        // Repeated leave is harmless; an unrelated user cannot close a waiting empty room.
        if (removed > 0 && activeCount(connection, room.id()) == 0 && room.closedAt() == null) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE Phong_Hop SET Trang_Thai = 'DA_DONG', Ngay_Dong = ? WHERE Ma_Phong = ?")) {
                statement.setTimestamp(1, Timestamp.from(now), utc());
                statement.setLong(2, room.id());
                statement.executeUpdate();
            }
            room = findRoom(connection, room.id());
        }
        return AuthResponse.roomOk("Đã rời phòng.", snapshot(connection, room, false));
    }

    private Long authenticatedUser(Connection connection, String token) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT n.Ma_Nguoi_Dung FROM Phien_Dang_Nhap s JOIN Nguoi_Dung n ON n.Ma_Nguoi_Dung = s.Ma_Nguoi_Dung "
                        + "WHERE s.Token_Bam = ? AND s.Thu_Hoi_Luc IS NULL AND s.Het_Han_Luc > ? AND n.Trang_Thai = 'HOAT_DONG'")) {
            statement.setString(1, tokenHash(token));
            statement.setTimestamp(2, Timestamp.from(clock.instant()), utc());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        }
    }
    private AuthResponse staleGeneration(Connection c,long room,RoomRequest request) throws SQLException {
        if(legacyOwner==null) return null; // Isolated reference adapters have no runtime marker.
        String expected=request.roomIdentity();
        if(expected==null || expected.isBlank()) return AuthResponse.error("ROOM_GENERATION_REQUIRED","Cần cập nhật ứng dụng và tải lại thông tin phòng.");
        try(var s=c.prepareStatement("SELECT Room_Opaque_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=? AND Instance_ID=?")) {
            s.setLong(1,room);s.setString(2,legacyOwner);try(var r=s.executeQuery()) {
                return r.next() && expected.equals(r.getString(1))?null:AuthResponse.error("STALE_ROOM","Yêu cầu thuộc phiên phòng cũ. Hãy tải lại thông tin phòng.");
            }
        }
    }

    private static String validate(String action, RoomRequest request) {
        if ("LIST_ROOMS".equals(action)) {
            if (request != null && (request.offset() < 0 || request.limit() < 1 || request.limit() > 20)) {
                return "Phân trang phòng không hợp lệ.";
            }
            return null;
        }
        if (request == null) {
            return "Thiếu thông tin phòng.";
        }
        if(request.roomIdentity()!=null && (request.roomIdentity().isBlank() || request.roomIdentity().length()>24)) return "Phiên phòng không hợp lệ.";
        String error;
        if ("CREATE_ROOM".equals(action)) {
            error = RoomValidation.nameError(request.name());
            if (error == null) error = RoomValidation.pinError(request.pin());
            if (error == null) error = RoomValidation.capacityError(request.capacity());
        } else {
            error = "JOIN_ROOM".equals(action) ? RoomValidation.codeError(request.identifier())
                    : RoomValidation.identifierError(request.identifier());
            if (error == null && "JOIN_ROOM".equals(action)) error = RoomValidation.pinError(request.pin());
        }
        return error;
    }

    private static AuthResponse invalidSession() {
        return AuthResponse.error("SESSION_EXPIRED", "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.");
    }

    private static AuthResponse unavailable(RoomRow room) {
        if (room == null) {
            return AuthResponse.error("ROOM_NOT_FOUND", "Không tìm thấy phòng theo mã đã nhập.");
        }
        if (room.closedAt() != null || !("CHO".equals(room.status()) || "DANG_HOAT_DONG".equals(room.status()))) {
            return AuthResponse.error("ROOM_CLOSED", "Phòng đã đóng hoặc không còn hoạt động.");
        }
        return null;
    }

    private RoomRow findRoom(Connection connection, String identifier) throws SQLException {
        return findRoom(connection, identifier, true);
    }
    private RoomRow findRoom(Connection connection, String identifier, boolean forUpdate) throws SQLException {
        String normalized = identifier.strip().toUpperCase(Locale.ROOT);
        if (normalized.matches("[0-9]+")) {
            try {
                return findRoom(connection, "p.Ma_Phong", Long.parseLong(normalized), forUpdate);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return findRoom(connection, "p.Ma_Phong_Tham_Gia", normalized, forUpdate);
    }

    private RoomRow findRoom(Connection connection, long roomId) throws SQLException {
        return findRoom(connection, "p.Ma_Phong", roomId);
    }

    private RoomRow findRoom(Connection connection, String column, Object value) throws SQLException {
        return findRoom(connection, column, value, true);
    }
    private RoomRow findRoom(Connection connection, String column, Object value, boolean forUpdate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + ROOM_COLUMNS + " FROM Phong_Hop p WHERE " + column + " = ?"
                        + (excludeSignalingRooms || legacyOwner!=null ? " AND NOT EXISTS (SELECT 1 FROM MMR_Phong_Van_Hanh x WHERE x.Ma_Phong=p.Ma_Phong AND x.Server_Epoch<>'legacy')" : "")
                        + (legacyOwner!=null ? " AND NOT EXISTS (SELECT 1 FROM MMR_Phong_Van_Hanh x WHERE x.Ma_Phong=p.Ma_Phong AND x.Instance_ID<>?)" : "")
                        + (forUpdate ? " FOR UPDATE" : ""))) {
            statement.setObject(1, value);
            if(legacyOwner!=null) statement.setString(2,legacyOwner);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readRoom(result) : null;
            }
        }
    }

    private static RoomRow readRoom(ResultSet result) throws SQLException {
        Timestamp createdAt = result.getTimestamp("Ngay_Tao", utc());
        return new RoomRow(result.getLong("Ma_Phong"), result.getString("Ten_Phong"),
                result.getString("Ma_Phong_Tham_Gia"), result.getLong("Ma_Chu_Phong"), createdAt.toInstant().toString(),
                result.getString("Trang_Thai"), result.getBoolean("Da_Khoa"), result.getInt("So_Nguoi_Toi_Da"),
                result.getTimestamp("Ngay_Dong", utc()));
    }

    private static boolean isActiveMember(Connection connection, long roomId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM Thanh_Vien_Phong WHERE Ma_Phong = ? AND Ma_Nguoi_Dung = ? AND Roi_Phong_Luc IS NULL")) {
            statement.setLong(1, roomId);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean hasEverParticipated(Connection connection, long roomId, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM Thanh_Vien_Phong WHERE Ma_Phong = ? AND Ma_Nguoi_Dung = ?")) {
            statement.setLong(1, roomId);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void addMember(Connection connection, long roomId, long userId, String role, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO Thanh_Vien_Phong (Ma_Phong, Ma_Nguoi_Dung, Vai_Tro, Tham_Gia_Luc) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, roomId);
            statement.setLong(2, userId);
            statement.setString(3, role);
            statement.setTimestamp(4, Timestamp.from(now), utc());
            statement.executeUpdate();
        }
    }

    private static int activeCount(Connection connection, long roomId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(DISTINCT Ma_Nguoi_Dung) FROM Thanh_Vien_Phong WHERE Ma_Phong = ? AND Roi_Phong_Luc IS NULL")) {
            statement.setLong(1, roomId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private RoomInfo snapshot(Connection connection, RoomRow room, boolean includeParticipants) throws SQLException {
        List<RoomParticipant> participants = new ArrayList<>();
        if (includeParticipants) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT DISTINCT n.Ma_Nguoi_Dung, n.Ten_Hien_Thi FROM Thanh_Vien_Phong t "
                            + "JOIN Nguoi_Dung n ON n.Ma_Nguoi_Dung = t.Ma_Nguoi_Dung "
                            + "WHERE t.Ma_Phong = ? AND t.Roi_Phong_Luc IS NULL ORDER BY n.Ma_Nguoi_Dung")) {
                statement.setLong(1, room.id());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long userId = result.getLong("Ma_Nguoi_Dung");
                        participants.add(new RoomParticipant(userId, result.getString("Ten_Hien_Thi"),
                                userId == room.hostId() ? "CHU_PHONG" : "THANH_VIEN"));
                    }
                }
            }
        }
        int people = includeParticipants ? participants.size() : activeCount(connection, room.id());
        var info=toInfo(room, people, List.copyOf(participants));String identity=null;
        if(legacyOwner!=null) try(var s=connection.prepareStatement("SELECT Room_Opaque_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=? AND Instance_ID=?")) {
            s.setLong(1,room.id());s.setString(2,legacyOwner);try(var r=s.executeQuery()) {if(r.next()) identity=r.getString(1);}
        }
        return new RoomInfo(info.id(),info.name(),info.code(),info.hostId(),info.createdAt(),info.status(),info.locked(),info.capacity(),info.people(),info.participants(),identity);
    }

    private static RoomInfo toInfo(RoomRow room, int people, List<RoomParticipant> participants) {
        return new RoomInfo(room.id(), room.name(), room.code(), room.hostId(), room.createdAt(), room.status(),
                room.locked(), room.capacity(), people, participants);
    }

    private static boolean codeExists(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM Phong_Hop WHERE Ma_Phong_Tham_Gia = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private String randomCode() {
        byte[] bytes = new byte[4];
        random.nextBytes(bytes);
        return "MMR-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static String tokenHash(String token) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Session hashing is unavailable", exception);
        }
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

    private record RoomRow(long id, String name, String code, long hostId, String createdAt, String status,
            boolean locked, int capacity, Timestamp closedAt) {
    }
}
