package server.meeting;

import common.meeting.*;
import common.chat.ChatException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import server.auth.ConnectionProvider;
import server.auth.PasswordHasher;
import server.database.DatabaseConnection;

/** Duc: authenticated membership and host commands; live participants are never generated. */
public final class LegacyRoomService implements AutoCloseable {
    private final ConnectionProvider connections;
    private final Clock clock;
    private final PasswordHasher pins = new PasswordHasher();
    private final Map<Long, Room> rooms = new LinkedHashMap<>();
    public LegacyRoomService() { this(DatabaseConnection::getConnection, Clock.systemUTC()); }
    public LegacyRoomService(ConnectionProvider connections, Clock clock) { this.connections = connections; this.clock = clock; }

    @FunctionalInterface public interface ChatAction<T> {
        T handle(Connection connection, Participant participant) throws SQLException;
    }
    /** Duc: chat cannot bypass approval, a revoked session or a departed membership. */
    public synchronized <T> T chat(String token, long roomId, String ticket, ChatAction<T> action)
            throws SQLException, ChatException {
        try (Connection connection = connections.getConnection()) {
            User user = authenticate(connection, token);
            if (user == null) throw new ChatException("SESSION_INVALID", "Phiên đăng nhập không hợp lệ.");
            Room room = rooms.get(roomId);
            if (room == null) throw new ChatException("ROOM_NOT_FOUND", "Không tìm thấy phòng.");
            if (room.closed) throw new ChatException("ROOM_CLOSED", "Chủ phòng đã đóng phòng.");
            Member member = room.members.get(user.id());
            if (member == null || ticket == null || !ticket.equals(member.ticket)
                    || member.lastSeen.isBefore(now().minusSeconds(10))) {
                throw new ChatException("NOT_IN_ROOM", "Bạn không còn trong phòng hoặc chưa được duyệt.");
            }
            return action.handle(connection, member.view(room.host));
        }
    }

    public synchronized RoomResponse handle(RoomRequest request) throws SQLException {
        if (request == null || request.action() == null) return error("INVALID_REQUEST", "Yêu cầu phòng không hợp lệ.");
        try (Connection connection = connections.getConnection()) {
            connection.setAutoCommit(false);
            Map<Long, Room> before = new LinkedHashMap<>(); rooms.forEach((id, room) -> before.put(id, room.copy()));
            try {
                User user = authenticate(connection, request.token());
                if (user == null) return error("SESSION_INVALID", "Phiên đăng nhập không hợp lệ.");
                expire(connection);
                RoomResponse result;
                if ("ROOM_CREATE".equals(request.action())) result = create(connection, request, user);
                else if ("ROOM_JOIN".equals(request.action())) result = join(connection, request, user);
                else result = command(connection, request, user);
                connection.commit(); return result;
            } catch (SQLException | RuntimeException error) {
                rooms.clear(); rooms.putAll(before); throw error;
            } finally { connection.rollback(); }
        }
    }
    private RoomResponse create(Connection connection, RoomRequest request, User user) throws SQLException {
        String name = request.name() == null ? "" : request.name().strip();
        if (name.isBlank() || name.length() > 100 || request.pin() == null || !request.pin().matches("\\d{4,6}")
                || request.capacity() < 2 || request.capacity() > 50) return error("VALIDATION_ERROR", "Nhập tên phòng, PIN 4–6 số và sức chứa 2–50 người.");
        if (present(user.id())) return error("ALREADY_IN_ROOM", "Tài khoản đang ở trong một phòng khác.");
        Room room = new Room(); room.name = name; room.code = "MMR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        room.pin = pins.hash(request.pin()); room.capacity = request.capacity(); room.host = user.id();
        try (PreparedStatement sql = connection.prepareStatement("INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam, Ma_Chu_Phong, Trang_Thai, Da_Khoa, So_Nguoi_Toi_Da) VALUES (?, ?, ?, ?, 'DANG_HOAT_DONG', FALSE, ?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setString(1, room.code); sql.setString(2, name); sql.setString(3, room.pin); sql.setLong(4, room.host); sql.setInt(5, room.capacity); sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Missing room ID"); room.id = keys.getLong(1); }
        }
        admit(connection, room, new Member(user, UUID.randomUUID().toString(), now()));
        rooms.put(room.id, room); return view(room, user.id(), false);
    }
    private RoomResponse join(Connection connection, RoomRequest request, User user) throws SQLException {
        if (request.code() == null || request.code().length() > 20 || request.pin() == null || request.pin().length() > 6) return error("VALIDATION_ERROR", "Nhập mã phòng và PIN hợp lệ.");
        Room room = load(connection, 0, request.code().strip().toUpperCase(Locale.ROOT));
        if (room == null) return error("ROOM_NOT_FOUND", "Không tìm thấy phòng.");
        if (room.closed) return error("ROOM_CLOSED", "Phòng đã đóng.");
        if (!pins.verify(request.pin(), room.pin)) return error("WRONG_PIN", "PIN không đúng.");
        if (room.waiting.containsKey(user.id())) return view(room, user.id(), true);
        if (present(user.id())) return error("ALREADY_IN_ROOM", "Tài khoản đã tham gia hoặc đang chờ ở phòng khác.");
        if (room.locked && room.host != user.id()) return error("ROOM_LOCKED", "Phòng đang khóa.");
        if (room.members.size() >= room.capacity) return error("ROOM_FULL", "Phòng đã đủ người.");
        Member member = new Member(user, UUID.randomUUID().toString(), now());
        if (room.approval && !room.members.isEmpty() && room.host != user.id()) {
            if (room.waiting.size() >= 20) return error("QUEUE_FULL", "Danh sách chờ đã đầy.");
            room.waiting.put(user.id(), member); return view(room, user.id(), true);
        }
        admit(connection, room, member); return view(room, user.id(), false);
    }
    private RoomResponse command(Connection connection, RoomRequest request, User user) throws SQLException {
        Room room = load(connection, request.roomId(), null);
        if (room == null) return error("ROOM_NOT_FOUND", "Không tìm thấy phòng.");
        if (room.closed) return error("ROOM_CLOSED", "Chủ phòng đã đóng phòng.");
        Member member = room.members.get(user.id());
        Member waiter = room.waiting.get(user.id());
        if (member == null && waiter != null && Objects.equals(waiter.ticket, request.ticket())) {
            waiter.lastSeen = now();
            if ("ROOM_LEAVE".equals(request.action())) { room.waiting.remove(user.id()); return new RoomResponse(true, "OK", "", null, null, null, null, false); }
            if ("ROOM_POLL".equals(request.action())) return view(room, user.id(), true);
        }
        if (member == null || !Objects.equals(member.ticket, request.ticket())) return error("NOT_IN_ROOM", "Bạn không còn trong phòng hoặc đã bị mời ra.");
        member.lastSeen = now();
        switch (request.action()) {
            case "ROOM_POLL": return view(room, user.id(), false);
            case "ROOM_LEAVE": depart(connection, room, user.id(), "TU_ROI"); return new RoomResponse(true, "OK", "", null, null, null, null, false);
            case "ROOM_MEDIA":
                if (request.microphone() != null) member.microphone = request.microphone();
                if (request.camera() != null) member.camera = request.camera();
                return view(room, user.id(), false);
            default: break;
        }
        if (room.host != user.id()) return error("HOST_REQUIRED", "Chỉ chủ phòng được thực hiện thao tác này.");
        Member target = room.members.get(request.target());
        switch (request.action()) {
            case "ROOM_SETTINGS":
                if (request.locked() != null) {
                    try (PreparedStatement sql = connection.prepareStatement("UPDATE Phong_Hop SET Da_Khoa = ? WHERE Ma_Phong = ?")) { sql.setBoolean(1, request.locked()); sql.setLong(2, room.id); sql.executeUpdate(); }
                    room.locked = request.locked();
                }
                if (request.approval() != null) {
                    room.approval = request.approval();
                }
                if (!room.approval && !room.locked) {
                    for (Member candidate : List.copyOf(room.waiting.values())) {
                        if (room.members.size() >= room.capacity) break;
                        if (authenticateHash(connection, candidate.user.sessionHash()) != null) admit(connection, room, candidate);
                        room.waiting.remove(candidate.user.id());
                    }
                }
                return view(room, user.id(), false);
            case "ROOM_CLOSE":
                for (long id : List.copyOf(room.members.keySet())) depart(connection, room, id, "PHONG_DONG");
                execute(connection, "UPDATE Phong_Hop SET Trang_Thai = 'DA_DONG', Ngay_Dong = CURRENT_TIMESTAMP WHERE Ma_Phong = ?", room.id);
                room.closed = true; room.waiting.clear(); return new RoomResponse(true, "OK", "", room.view(), null, null, null, false);
            case "ROOM_PENDING_APPROVE":
                Member pending = room.waiting.get(request.target());
                if (pending == null) return error("INVALID_TARGET", "Yêu cầu chờ không còn tồn tại.");
                if (authenticateHash(connection, pending.user.sessionHash()) == null) {
                    room.waiting.remove(request.target()); return error("SESSION_INVALID", "Người chờ đã đăng xuất hoặc hết phiên.");
                }
                if (room.locked) return error("ROOM_LOCKED", "Mở khóa phòng trước khi duyệt.");
                if (room.members.size() >= room.capacity) return error("ROOM_FULL", "Phòng đã đủ người.");
                admit(connection, room, pending); room.waiting.remove(pending.user.id()); return view(room, user.id(), false);
            case "ROOM_PENDING_REJECT": room.waiting.remove(request.target()); return view(room, user.id(), false);
            case "ROOM_KICK", "ROOM_MIC_OFF", "ROOM_CAMERA_OFF", "ROOM_TRANSFER":
                if (target == null || target.user.id() == user.id()) return error("INVALID_TARGET", "Chọn một thành viên khác đang trong phòng.");
                if ("ROOM_KICK".equals(request.action())) depart(connection, room, target.user.id(), "BI_MOI_RA");
                if ("ROOM_MIC_OFF".equals(request.action())) target.microphone = false;
                if ("ROOM_CAMERA_OFF".equals(request.action())) target.camera = false;
                if ("ROOM_TRANSFER".equals(request.action())) transfer(connection, room, target.user.id());
                return view(room, user.id(), false);
            default: return error("INVALID_REQUEST", "Thao tác không được hỗ trợ.");
        }
    }
    private void admit(Connection connection, Room room, Member member) throws SQLException {
        if (room.members.isEmpty()) transfer(connection, room, member.user.id());
        try (PreparedStatement sql = connection.prepareStatement("UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc = CURRENT_TIMESTAMP, Ly_Do_Roi = 'MAT_KET_NOI' WHERE Ma_Phong = ? AND Ma_Nguoi_Dung = ? AND Roi_Phong_Luc IS NULL")) {
            sql.setLong(1, room.id); sql.setLong(2, member.user.id()); sql.executeUpdate();
        }
        try (PreparedStatement sql = connection.prepareStatement("INSERT INTO Thanh_Vien_Phong (Ma_Phong, Ma_Nguoi_Dung, Vai_Tro) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            sql.setLong(1, room.id); sql.setLong(2, member.user.id()); sql.setString(3, room.host == member.user.id() ? "CHU_PHONG" : "THANH_VIEN"); sql.executeUpdate();
            try (ResultSet keys = sql.getGeneratedKeys()) { if (!keys.next()) throw new SQLException("Missing membership ID"); member.membership = keys.getLong(1); }
        }
        member.lastSeen = now(); room.members.put(member.user.id(), member);
    }
    private void depart(Connection connection, Room room, long user, String reason) throws SQLException {
        Member member = room.members.get(user); if (member == null) return;
        try (PreparedStatement sql = connection.prepareStatement("UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc = CURRENT_TIMESTAMP, Ly_Do_Roi = ? WHERE Ma_Thanh_Vien_Phong = ? AND Roi_Phong_Luc IS NULL")) {
            sql.setString(1, reason); sql.setLong(2, member.membership); sql.executeUpdate();
        }
        room.members.remove(user);
        if (room.host == user && !room.members.isEmpty() && !"PHONG_DONG".equals(reason)) transfer(connection, room, room.members.keySet().iterator().next());
        if (room.members.isEmpty()) room.waiting.clear();
    }
    private void transfer(Connection connection, Room room, long host) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("UPDATE Phong_Hop SET Ma_Chu_Phong = ? WHERE Ma_Phong = ?")) { sql.setLong(1, host); sql.setLong(2, room.id); sql.executeUpdate(); }
        try (PreparedStatement sql = connection.prepareStatement("UPDATE Thanh_Vien_Phong SET Vai_Tro = CASE WHEN Ma_Nguoi_Dung = ? THEN 'CHU_PHONG' ELSE 'THANH_VIEN' END WHERE Ma_Phong = ? AND Roi_Phong_Luc IS NULL")) { sql.setLong(1, host); sql.setLong(2, room.id); sql.executeUpdate(); }
        room.host = host;
    }
    private void expire(Connection connection) throws SQLException {
        Instant cutoff = now().minusSeconds(10);
        for (Room room : rooms.values()) {
            for (Member member : List.copyOf(room.members.values())) if (member.lastSeen.isBefore(cutoff)) depart(connection, room, member.user.id(), "MAT_KET_NOI");
            room.waiting.values().removeIf(member -> member.lastSeen.isBefore(cutoff));
        }
    }
    private Room load(Connection connection, long id, String code) throws SQLException {
        for (Room room : rooms.values()) if (code == null ? room.id == id : room.code.equals(code)) return room;
        try (PreparedStatement sql = connection.prepareStatement("SELECT Ma_Phong, Ten_Phong, Ma_Phong_Tham_Gia, Ma_PIN_Bam, Ma_Chu_Phong, Da_Khoa, So_Nguoi_Toi_Da, Trang_Thai FROM Phong_Hop WHERE " + (code == null ? "Ma_Phong = ?" : "Ma_Phong_Tham_Gia = ?"))) {
            if (code == null) sql.setLong(1, id); else sql.setString(1, code);
            try (ResultSet result = sql.executeQuery()) {
                if (!result.next()) return null;
                Room room = new Room(); room.id = result.getLong(1); room.name = result.getString(2); room.code = result.getString(3); room.pin = result.getString(4); room.host = result.getLong(5); room.locked = result.getBoolean(6); room.capacity = result.getInt(7); room.closed = "DA_DONG".equals(result.getString(8)); rooms.put(room.id, room); return room;
            }
        }
    }
    private User authenticate(Connection connection, String token) throws SQLException {
        if (token == null || token.isBlank() || token.length() > 256) return null;
        return authenticateHash(connection, hash(token));
    }
    private User authenticateHash(Connection connection, String tokenHash) throws SQLException {
        try (PreparedStatement sql = connection.prepareStatement("SELECT u.Ma_Nguoi_Dung, u.Ten_Hien_Thi FROM Phien_Dang_Nhap p JOIN Nguoi_Dung u ON u.Ma_Nguoi_Dung = p.Ma_Nguoi_Dung WHERE p.Token_Bam = ? AND p.Thu_Hoi_Luc IS NULL AND p.Het_Han_Luc > ? AND u.Trang_Thai = 'HOAT_DONG'")) {
            sql.setString(1, tokenHash); sql.setTimestamp(2, Timestamp.from(now()), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            try (ResultSet result = sql.executeQuery()) { return result.next() ? new User(result.getLong(1), result.getString(2), tokenHash) : null; }
        }
    }
    private RoomResponse view(Room room, long user, boolean pending) {
        Member self = pending ? room.waiting.get(user) : room.members.get(user);
        return new RoomResponse(true, "OK", "", room.view(), self.ticket,
                pending ? List.of() : room.members.values().stream().map(member -> member.view(room.host)).toList(),
                room.host == user ? room.waiting.values().stream().map(member -> member.view(0)).toList() : List.of(), pending);
    }
    private boolean present(long user) { return rooms.values().stream().anyMatch(room -> room.members.containsKey(user) || room.waiting.containsKey(user)); }
    private Instant now() { return clock.instant(); }
    private static RoomResponse error(String code, String message) { return RoomResponse.error(code, message); }
    private static String hash(String token) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void execute(Connection connection, String query, long id) throws SQLException { try (PreparedStatement sql = connection.prepareStatement(query)) { sql.setLong(1, id); sql.executeUpdate(); } }
    @Override public synchronized void close() throws SQLException {
        try (Connection connection = connections.getConnection()) { for (Room room : rooms.values()) for (long user : List.copyOf(room.members.keySet())) depart(connection, room, user, "MAT_KET_NOI"); }
        rooms.clear();
    }
    private record User(long id, String name, String sessionHash) { }
    private static final class Member {
        final User user; final String ticket; long membership; Instant lastSeen; boolean microphone, camera;
        Member(User user, String ticket, Instant time) { this.user = user; this.ticket = ticket; lastSeen = time; }
        Participant view(long host) { return new Participant(user.id(), user.name(), user.id() == host, microphone, camera); }
        Member copy() { Member member = new Member(user, ticket, lastSeen); member.membership = membership; member.microphone = microphone; member.camera = camera; return member; }
    }
    private static final class Room {
        long id, host; String name, code, pin; int capacity; boolean locked, approval, closed;
        final Map<Long, Member> members = new LinkedHashMap<>(), waiting = new LinkedHashMap<>();
        RoomView view() { return new RoomView(id, name, code, capacity, locked, approval, closed); }
        Room copy() { Room room = new Room(); room.id = id; room.host = host; room.name = name; room.code = code; room.pin = pin; room.capacity = capacity; room.locked = locked; room.approval = approval; room.closed = closed; members.forEach((id, member) -> room.members.put(id, member.copy())); waiting.forEach((id, member) -> room.waiting.put(id, member.copy())); return room; }
    }
}
