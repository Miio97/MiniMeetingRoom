package server.schedule;

import common.auth.AuthRequest;
import common.auth.AuthResponse;
import common.schedule.ScheduleInfo;
import common.schedule.ScheduleRequest;
import common.schedule.ScheduleValidation;
import server.auth.ConnectionProvider;
import server.database.DatabaseConnection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Dịch vụ quản lý lịch hẹn (ScheduleService).
 * Lưu trữ bền vững trong bảng Lich_Hen.
 * Xác thực danh tính người dùng thông qua token phiên đăng nhập (không tin userId từ client).
 * Chỉ chủ sở hữu lịch mới có quyền xem và xóa lịch của mình.
 */
public final class ScheduleService {
    private final ConnectionProvider connections;
    private final Clock clock;

    public ScheduleService() {
        this(DatabaseConnection::getConnection, Clock.systemUTC());
    }

    public ScheduleService(ConnectionProvider connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
    }

    public AuthResponse handle(AuthRequest request) throws SQLException {
        if (request == null || request.action() == null) {
            return AuthResponse.error("INVALID_REQUEST", "Thiếu thông tin yêu cầu lịch hẹn.");
        }
        String action = request.action().toUpperCase(Locale.ROOT);
        try (Connection connection = connections.getConnection()) {
            boolean readOnly = "LIST_SCHEDULES".equals(action);
            if (!readOnly) {
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                connection.setAutoCommit(false);
            }
            try {
                Long userId = authenticatedUser(connection, request.token());
                if (userId == null) {
                    if (!readOnly) connection.rollback();
                    return AuthResponse.error("SESSION_EXPIRED", "Phiên đăng nhập không còn hợp lệ. Vui lòng đăng nhập lại.");
                }
                AuthResponse response = switch (action) {
                    case "CREATE_SCHEDULE" -> create(connection, userId, request.schedule());
                    case "LIST_SCHEDULES" -> list(connection, userId, request.schedule());
                    case "DELETE_SCHEDULE" -> delete(connection, userId, request.schedule());
                    default -> throw new IllegalStateException("Unsupported schedule action: " + action);
                };
                if (readOnly) return response;
                if (response.success()) {
                    connection.commit();
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

    private AuthResponse create(Connection connection, long userId, ScheduleRequest req) throws SQLException {
        if (req == null) {
            return AuthResponse.error("INVALID_REQUEST", "Thiếu dữ liệu lịch hẹn.");
        }
        String titleError = ScheduleValidation.titleError(req.title());
        if (titleError != null) {
            return AuthResponse.error("VALIDATION_ERROR", titleError);
        }
        String descError = ScheduleValidation.descriptionError(req.description());
        if (descError != null) {
            return AuthResponse.error("VALIDATION_ERROR", descError);
        }
        String timeError = ScheduleValidation.timeRangeError(req.startsAt(), req.endsAt());
        if (timeError != null) {
            return AuthResponse.error("VALIDATION_ERROR", timeError);
        }

        // Serialize creates for this account so simultaneous requests cannot both pass the overlap check.
        try (PreparedStatement lock = connection.prepareStatement(
                "SELECT Ma_Nguoi_Dung FROM Nguoi_Dung WHERE Ma_Nguoi_Dung = ? FOR UPDATE")) {
            lock.setLong(1, userId);
            try (ResultSet lockedUser = lock.executeQuery()) { lockedUser.next(); }
        }

        // Kiểm tra giới hạn 200 lịch hẹn chưa xóa cho mỗi người dùng
        try (PreparedStatement countStmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM Lich_Hen WHERE Ma_Nguoi_Tao = ? AND Ngay_Xoa IS NULL")) {
            countStmt.setLong(1, userId);
            try (ResultSet countRs = countStmt.executeQuery()) {
                if (countRs.next() && countRs.getInt(1) >= ScheduleValidation.MAX_USER_SCHEDULES) {
                    return AuthResponse.error("SCHEDULE_LIMIT_EXCEEDED",
                            "Bạn đã đạt giới hạn tối đa " + ScheduleValidation.MAX_USER_SCHEDULES + " lịch hẹn chưa xóa.");
                }
            }
        }

        // Xử lý gắn phòng họp nếu có
        Long roomId = null;
        String roomCode = null;
        if (req.roomCode() != null && !req.roomCode().isBlank()) {
            String cleanCode = req.roomCode().strip().toUpperCase(Locale.ROOT);
            try (PreparedStatement roomStmt = connection.prepareStatement(
                    "SELECT Ma_Phong, Ma_Phong_Tham_Gia FROM Phong_Hop WHERE Ma_Phong_Tham_Gia = ?")) {
                roomStmt.setString(1, cleanCode);
                try (ResultSet roomRs = roomStmt.executeQuery()) {
                    if (!roomRs.next()) {
                        return AuthResponse.error("ROOM_NOT_FOUND", "Phòng họp gắn kèm không tồn tại.");
                    }
                    roomId = roomRs.getLong("Ma_Phong");
                    roomCode = roomRs.getString("Ma_Phong_Tham_Gia");
                }
            }
        } else if (req.roomId() != null && req.roomId() > 0) {
            try (PreparedStatement roomStmt = connection.prepareStatement(
                    "SELECT Ma_Phong, Ma_Phong_Tham_Gia FROM Phong_Hop WHERE Ma_Phong = ?")) {
                roomStmt.setLong(1, req.roomId());
                try (ResultSet roomRs = roomStmt.executeQuery()) {
                    if (!roomRs.next()) {
                        return AuthResponse.error("ROOM_NOT_FOUND", "Phòng họp gắn kèm không tồn tại.");
                    }
                    roomId = roomRs.getLong("Ma_Phong");
                    roomCode = roomRs.getString("Ma_Phong_Tham_Gia");
                }
            }
        }

        Instant startInstant = Instant.parse(req.startsAt().strip());
        Instant endInstant = Instant.parse(req.endsAt().strip());
        Instant now = clock.instant();
        String repeatRule = ScheduleValidation.repeatRuleOrFallback(req.repeatRule());
        String description = req.description() != null && !req.description().isBlank() ? req.description().strip() : null;
        String title = req.title().strip();

        // Kiểm tra thời gian tạo lịch phải trước giờ bắt đầu tối thiểu 15 phút
        String advanceError = ScheduleValidation.advanceTimeError(startInstant, now);
        if (advanceError != null) {
            return AuthResponse.error("VALIDATION_ERROR", advanceError);
        }

        // Kiểm tra không trùng giờ trùng ngày với các lịch khác của chính tài khoản này
        try (PreparedStatement overlapStmt = connection.prepareStatement(
                "SELECT Tieu_De FROM Lich_Hen "
                        + "WHERE Ma_Nguoi_Tao = ? AND Ngay_Xoa IS NULL "
                        + "AND Bat_Dau_Luc < ? AND Ket_Thuc_Luc > ? LIMIT 1")) {
            overlapStmt.setLong(1, userId);
            overlapStmt.setTimestamp(2, Timestamp.from(endInstant), utc());
            overlapStmt.setTimestamp(3, Timestamp.from(startInstant), utc());
            try (ResultSet overlapRs = overlapStmt.executeQuery()) {
                if (overlapRs.next()) {
                    String conflictTitle = overlapRs.getString("Tieu_De");
                    return AuthResponse.error("SCHEDULE_TIME_CONFLICT",
                            "Thời gian lịch hẹn bị trùng với một lịch hẹn khác của bạn (\"" + conflictTitle + "\").");
                }
            }
        }

        long scheduleId;
        try (PreparedStatement insertStmt = connection.prepareStatement(
                "INSERT INTO Lich_Hen (Ma_Nguoi_Tao, Tieu_De, Mo_Ta, Bat_Dau_Luc, Ket_Thuc_Luc, Lap_Lai, Ma_Phong, Ngay_Tao) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insertStmt.setLong(1, userId);
            insertStmt.setString(2, title);
            if (description != null) insertStmt.setString(3, description); else insertStmt.setNull(3, Types.VARCHAR);
            insertStmt.setTimestamp(4, Timestamp.from(startInstant), utc());
            insertStmt.setTimestamp(5, Timestamp.from(endInstant), utc());
            insertStmt.setString(6, repeatRule);
            if (roomId != null) insertStmt.setLong(7, roomId); else insertStmt.setNull(7, Types.BIGINT);
            insertStmt.setTimestamp(8, Timestamp.from(now), utc());
            insertStmt.executeUpdate();

            try (ResultSet keys = insertStmt.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("Schedule insert did not return a generated ID.");
                }
                scheduleId = keys.getLong(1);
            }
        }

        ScheduleInfo info = new ScheduleInfo(scheduleId, userId, title, description,
                startInstant.toString(), endInstant.toString(), repeatRule, roomId, roomCode, now.toString());
        return AuthResponse.scheduleOk("Tạo lịch hẹn thành công.", info);
    }

    private AuthResponse list(Connection connection, long userId, ScheduleRequest req) throws SQLException {
        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = (req != null && req.from() != null && !req.from().isBlank())
                    ? Instant.parse(req.from().strip())
                    : clock.instant().minus(30, ChronoUnit.DAYS);
            toInstant = (req != null && req.to() != null && !req.to().isBlank())
                    ? Instant.parse(req.to().strip())
                    : clock.instant().plus(31, ChronoUnit.DAYS);
        } catch (DateTimeParseException e) {
            return AuthResponse.error("VALIDATION_ERROR", "Định dạng thời gian tìm kiếm không hợp lệ (cần ISO-8601).");
        }

        if (toInstant.isBefore(fromInstant)) {
            return AuthResponse.error("VALIDATION_ERROR", "Thời gian kết thúc tìm kiếm phải sau thời gian bắt đầu.");
        }
        if (Duration.between(fromInstant, toInstant).toDays() > ScheduleValidation.MAX_LIST_RANGE_DAYS) {
            return AuthResponse.error("VALIDATION_ERROR",
                    "Khoảng thời gian tra cứu lịch hẹn không được vượt quá " + ScheduleValidation.MAX_LIST_RANGE_DAYS + " ngày.");
        }

        List<ScheduleInfo> list = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT l.Ma_Lich_Hen, l.Ma_Nguoi_Tao, l.Tieu_De, l.Mo_Ta, l.Bat_Dau_Luc, l.Ket_Thuc_Luc, "
                        + "l.Lap_Lai, l.Ma_Phong, p.Ma_Phong_Tham_Gia, l.Ngay_Tao "
                        + "FROM Lich_Hen l LEFT JOIN Phong_Hop p ON p.Ma_Phong = l.Ma_Phong "
                        + "WHERE l.Ma_Nguoi_Tao = ? AND l.Ngay_Xoa IS NULL "
                        + "AND l.Bat_Dau_Luc < ? AND l.Ket_Thuc_Luc > ? "
                        + "ORDER BY l.Bat_Dau_Luc ASC")) {
            stmt.setLong(1, userId);
            stmt.setTimestamp(2, Timestamp.from(toInstant), utc());
            stmt.setTimestamp(3, Timestamp.from(fromInstant), utc());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("Ma_Lich_Hen");
                    long creatorId = rs.getLong("Ma_Nguoi_Tao");
                    String title = rs.getString("Tieu_De");
                    String desc = rs.getString("Mo_Ta");
                    Timestamp startTs = rs.getTimestamp("Bat_Dau_Luc", utc());
                    Timestamp endTs = rs.getTimestamp("Ket_Thuc_Luc", utc());
                    String repeat = rs.getString("Lap_Lai");
                    long rId = rs.getLong("Ma_Phong");
                    Long roomId = rs.wasNull() ? null : rId;
                    String roomCode = rs.getString("Ma_Phong_Tham_Gia");
                    Timestamp createTs = rs.getTimestamp("Ngay_Tao", utc());

                    list.add(new ScheduleInfo(id, creatorId, title, desc,
                            startTs.toInstant().toString(),
                            endTs.toInstant().toString(),
                            repeat, roomId, roomCode,
                            createTs.toInstant().toString()));
                }
            }
        }

        return AuthResponse.schedulesOk("Lấy danh sách lịch hẹn thành công.", list);
    }

    private AuthResponse delete(Connection connection, long userId, ScheduleRequest req) throws SQLException {
        if (req == null || req.id() == null || req.id() <= 0) {
            return AuthResponse.error("INVALID_REQUEST", "Thiếu mã lịch hẹn cần xóa.");
        }
        long scheduleId = req.id();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT Ma_Nguoi_Tao, Ngay_Xoa FROM Lich_Hen WHERE Ma_Lich_Hen = ?")) {
            stmt.setLong(1, scheduleId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return AuthResponse.error("SCHEDULE_NOT_FOUND", "Không tìm thấy lịch hẹn.");
                }
                long creatorId = rs.getLong("Ma_Nguoi_Tao");
                if (creatorId != userId) {
                    return AuthResponse.error("PERMISSION_DENIED", "Bạn không có quyền xóa lịch hẹn này.");
                }
                Timestamp deletedAt = rs.getTimestamp("Ngay_Xoa");
                if (deletedAt != null) {
                    return AuthResponse.error("SCHEDULE_ALREADY_DELETED", "Lịch hẹn đã được xóa trước đó.");
                }
            }
        }

        // Xóa mềm bằng cách cập nhật Ngay_Xoa
        Instant now = clock.instant();
        try (PreparedStatement updateStmt = connection.prepareStatement(
                "UPDATE Lich_Hen SET Ngay_Xoa = ? WHERE Ma_Lich_Hen = ? AND Ma_Nguoi_Tao = ?")) {
            updateStmt.setTimestamp(1, Timestamp.from(now), utc());
            updateStmt.setLong(2, scheduleId);
            updateStmt.setLong(3, userId);
            int rows = updateStmt.executeUpdate();
            if (rows == 0) {
                return AuthResponse.error("SCHEDULE_NOT_FOUND", "Không thể xóa lịch hẹn.");
            }
        }

        return AuthResponse.ok("Đã xóa lịch hẹn thành công.", null, null, null);
    }

    private Long authenticatedUser(Connection connection, String token) throws SQLException {
        if (token == null || token.isBlank()) return null;
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
}
