package server.filetransfer;

import common.filetransfer.FileMetadata;
import common.filetransfer.RoomInfo;
import common.filetransfer.TransferException;
import java.io.IOException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;
import server.auth.ConnectionProvider;
import server.auth.PasswordHasher;
import server.auth.SessionService;
import server.database.DatabaseConnection;

/** Room access and AVAILABLE file metadata in the project's existing Vietnamese schema. */
public final class FileMetadataRepository {
    public static final int PAGE_SIZE = 100;
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final String FILE_SELECT = """
            SELECT f.Ma_Tep, t.Ma_Phong, t.Ma_Nguoi_Gui, n.Ten_Hien_Thi,
                   f.Ten_Tep_Goc, f.Loai_MIME, f.Kich_Thuoc_Byte, f.Ma_Kiem_Tra,
                   f.Ngay_Tao, f.Duong_Dan_Luu_Tru
            FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan = f.Ma_Tin_Nhan
            JOIN Nguoi_Dung n ON n.Ma_Nguoi_Dung = t.Ma_Nguoi_Gui
            """;
    private final ConnectionProvider connections;
    private final SessionService sessions;
    private final Clock clock;
    private final PasswordHasher passwords = new PasswordHasher();
    private final SecureRandom random = new SecureRandom();
    private String legacyOwner;
    public void legacyOwner(String instance) {legacyOwner=Objects.requireNonNull(instance);}
    @FunctionalInterface public interface RoomSessionGate {
        boolean permits(long roomId, long userId, String tokenHash);
    }
    private volatile RoomSessionGate roomSessionGate;
    public void roomSessionGate(RoomSessionGate gate) { roomSessionGate = Objects.requireNonNull(gate); }

    public FileMetadataRepository() { this(DatabaseConnection::getConnection); }

    public FileMetadataRepository(ConnectionProvider connections) { this(connections, Clock.systemUTC()); }

    public FileMetadataRepository(ConnectionProvider connections, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.clock = Objects.requireNonNull(clock);
        sessions = new SessionService(connections, clock);
    }

    public List<RetiredFile> closedRoomFiles(long roomId) throws SQLException {
        try(var connection=connections.getConnection();var query=connection.prepareStatement(
                "SELECT f.Ma_Tep,f.Duong_Dan_Luu_Tru,t.Ma_Tin_Nhan,t.Ma_Phong FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan=f.Ma_Tin_Nhan WHERE t.Ma_Phong=? ORDER BY f.Ma_Tep LIMIT 100")) {
            query.setLong(1,roomId);var result=new ArrayList<RetiredFile>();
            try(var rows=query.executeQuery()) {while(rows.next()) result.add(new RetiredFile(rows.getLong(1),rows.getString(2),rows.getLong(3),rows.getLong(4)));}
            return result;
        }
    }

    public long authenticate(String token) throws SQLException, TransferException {
        return sessions.authenticate(token);
    }

    public void authorize(long userId, long roomId) throws SQLException, TransferException {
        try (Connection connection = connections.getConnection()) { authorize(connection, userId, roomId); }
    }
    /** Signaling room access belongs to the active device, even when another login token remains valid. */
    public void authorizeSession(String token, long userId, long roomId) throws SQLException, TransferException {
        try (Connection connection = connections.getConnection()) {
            authorize(connection, userId, roomId);
            requireRoomSession(connection, token, userId, roomId);
        }
    }
    private void requireRoomSession(Connection connection, String token, long userId, long roomId)
            throws SQLException, TransferException {
        RoomSessionGate gate = roomSessionGate;
        if (gate == null) return;
        try (var query = connection.prepareStatement("SELECT Server_Epoch FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=?")) {
            query.setLong(1, roomId);
            try (var rows = query.executeQuery()) {
                if (rows.next() && !"legacy".equals(rows.getString(1))
                        && (token == null || !gate.permits(roomId, userId, server.auth.AuthService.tokenHash(token)))) {
                    throw new TransferException("DEVICE_REPLACED", "Tài khoản đã chuyển phòng họp sang thiết bị khác.");
                }
            }
        }
    }
    public String membershipGeneration(long userId,long roomId) throws SQLException,TransferException {
        try(var connection=connections.getConnection()) {authorize(connection,userId,roomId);return membershipGeneration(connection,userId,roomId);}
    }
    public record BoundUpload(TransferStorage.UploadSession session,String generation) { }
    @FunctionalInterface public interface UploadBegin {TransferStorage.UploadSession begin(String generation) throws IOException;}
    public BoundUpload beginUpload(long user,long room,String opaque,UploadBegin action) throws SQLException,TransferException,IOException {
        return beginUpload(user,room,opaque,null,action);
    }
    public BoundUpload beginUpload(long user,long room,String opaque,String token,UploadBegin action) throws SQLException,TransferException,IOException {
        try(var connection=connections.getConnection()) {
            beginMutation(connection);
            try {
                if(token!=null && sessions.authenticate(connection,token,true)!=user) throw new TransferException("INVALID_SESSION","Phiên đăng nhập không hợp lệ.");
                requireOpenRoom(lockRoom(connection,room));authorize(connection,user,room);
                requireRoomSession(connection, token, user, room);
                if(opaque!=null && !opaque.isBlank()) try(var query=connection.prepareStatement("SELECT Room_Opaque_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=?")) {
                    query.setLong(1,room);try(var rows=query.executeQuery()) {if(!rows.next() || !opaque.equals(rows.getString(1))) throw new TransferException("STALE_TRANSFER","Phòng đã được mở lại.");}
                }
                String generation=membershipGeneration(connection,user,room);
                var session=action.begin(generation);connection.commit();return new BoundUpload(session,generation);
            } catch(SQLException | IOException | RuntimeException failure) {rollback(connection,failure);throw failure;}
        }
    }
    private static String membershipGeneration(Connection connection,long userId,long roomId) throws SQLException,TransferException {
        try(var query=connection.prepareStatement("SELECT Ma_Thanh_Vien_Phong FROM Thanh_Vien_Phong WHERE Ma_Phong=? AND Ma_Nguoi_Dung=? AND Roi_Phong_Luc IS NULL ORDER BY Ma_Thanh_Vien_Phong DESC LIMIT 1")) {
            query.setLong(1,roomId);query.setLong(2,userId);try(var rows=query.executeQuery()) {if(rows.next()) return Long.toString(rows.getLong(1));}
        }
        throw denied();
    }

    public RoomInfo createRoom(long userId, String name, String pin, int capacity)
            throws SQLException, TransferException {
        String roomName = name == null ? "" : name.strip();
        String cleanPin = pin == null ? "" : pin;
        if (roomName.isEmpty() || roomName.length() > 100 || hasControl(roomName)
                || capacity < 2 || capacity > 50 || (!cleanPin.isEmpty() && !cleanPin.matches("[0-9]{4,6}"))) {
            throw invalid("Tên phòng, PIN hoặc số người tối đa không hợp lệ.");
        }
        String pinHash = cleanPin.isEmpty() ? null : passwords.hash(cleanPin);
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = newRoomCode();
            try (Connection connection = connections.getConnection()) {
                beginMutation(connection);
                try {
                    requireActiveUser(connection, userId);
                    long roomId;
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO Phong_Hop (Ma_Phong_Tham_Gia, Ten_Phong, Ma_PIN_Bam,
                                Ma_Chu_Phong, Trang_Thai, So_Nguoi_Toi_Da)
                            VALUES (?, ?, ?, ?, 'DANG_HOAT_DONG', ?)
                            """, Statement.RETURN_GENERATED_KEYS)) {
                        statement.setString(1, code);
                        statement.setString(2, roomName);
                        statement.setString(3, pinHash);
                        statement.setLong(4, userId);
                        statement.setInt(5, capacity);
                        statement.executeUpdate();
                        roomId = generatedId(statement);
                    }
                    if(legacyOwner!=null) server.meeting.RoomRepository.markLegacyOwnership(connection,roomId,legacyOwner,clock.instant());
                    addMembership(connection, roomId, userId, "CHU_PHONG");
                    connection.commit();
                    return new RoomInfo(roomId, code, roomName, capacity, userId);
                } catch (SQLException | TransferException | RuntimeException error) {
                    rollback(connection, error);
                    // The only generated unique field is the room code. Retry a rare collision.
                    if (error instanceof SQLException sql && isUniqueViolation(sql) && attempt < 4) continue;
                    throw error;
                }
            }
        }
        throw new SQLException("Unable to allocate a unique room code");
    }

    public RoomInfo joinRoom(long userId, String code, String pin) throws SQLException, TransferException {
        return joinRoom(userId, code, pin, null);
    }
    public RoomInfo joinRoom(long userId, String code, String pin, String token) throws SQLException, TransferException {
        String canonicalCode = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!canonicalCode.matches("[A-Z0-9][A-Z0-9-]{2,19}") || pin != null && pin.length() > 128) {
            throw invalid("Mã phòng hoặc PIN không hợp lệ.");
        }
        try (Connection connection = connections.getConnection()) {
            beginMutation(connection);
            try {
                requireActiveUser(connection, userId);
                RoomRow room = lockRoom(connection, canonicalCode);
                requireOpenRoom(room);
                requireRoomSession(connection, token, userId, room.info().id());
                if (!hasMembership(connection, userId, room.info().id())) {
                    if (room.locked()) throw new TransferException("ROOM_LOCKED", "Phòng đã khóa.");
                    if (room.pinHash() != null && !passwords.verify(pin, room.pinHash())) {
                        throw new TransferException("PERMISSION_DENIED", "PIN phòng không đúng.");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            SELECT COUNT(DISTINCT Ma_Nguoi_Dung) FROM Thanh_Vien_Phong
                            WHERE Ma_Phong = ? AND Roi_Phong_Luc IS NULL
                            """)) {
                        statement.setLong(1, room.info().id());
                        try (ResultSet result = statement.executeQuery()) {
                            result.next();
                            if (result.getLong(1) >= Math.min(50,room.info().capacity())) {
                                throw new TransferException("ROOM_FULL", "Phòng đã đủ người.");
                            }
                        }
                    }
                    addMembership(connection, room.info().id(), userId,
                            room.info().ownerId() == userId ? "CHU_PHONG" : "THANH_VIEN");
                }
                connection.commit();
                return room.info();
            } catch (SQLException | TransferException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    public void leaveRoom(long userId, long roomId) throws SQLException, TransferException {
        leaveRoom(userId, roomId, null);
    }
    public void leaveRoom(long userId, long roomId, String token) throws SQLException, TransferException {
        if (userId <= 0 || roomId <= 0) throw invalid("Phòng không hợp lệ.");
        try (Connection connection = connections.getConnection()) {
            beginMutation(connection);
            try {
                lockRoom(connection, roomId); // Same lock as join/complete: leaving revokes future commits.
                requireRoomSession(connection, token, userId, roomId);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE Thanh_Vien_Phong SET Roi_Phong_Luc = ?, Ly_Do_Roi = 'TU_ROI'
                        WHERE Ma_Phong = ? AND Ma_Nguoi_Dung = ? AND Roi_Phong_Luc IS NULL
                        """)) {
                    statement.setTimestamp(1, now(), utc());
                    statement.setLong(2, roomId);
                    statement.setLong(3, userId);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | TransferException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    /** Cursor-based room metadata delivery; file bytes and storage paths are never included. */
    public List<FileMetadata> list(long userId, long roomId, long afterId)
            throws SQLException, TransferException {
        if (afterId < 0) throw invalid("Mốc danh sách tệp không hợp lệ.");
        try (Connection connection = connections.getConnection()) {
            authorize(connection, userId, roomId);
            try (PreparedStatement statement = connection.prepareStatement(FILE_SELECT + """
                    WHERE t.Ma_Phong = ? AND f.Ma_Tep > ? AND t.Ngay_Xoa IS NULL
                    ORDER BY f.Ma_Tep LIMIT ?
                    """)) {
                statement.setLong(1, roomId);
                statement.setLong(2, afterId);
                statement.setInt(3, PAGE_SIZE);
                List<FileMetadata> files = new ArrayList<>();
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) files.add(readFile(result).metadata());
                }
                return List.copyOf(files);
            }
        }
    }

    public FileMetadata find(long userId, long fileId) throws SQLException, TransferException {
        try (Connection connection = connections.getConnection()) {
            StoredFile file = findFile(connection, "f.Ma_Tep = ? AND t.Ngay_Xoa IS NULL", fileId);
            if (file == null) throw notFound();
            authorize(connection, userId, file.metadata().roomId());
            return file.metadata();
        }
    }

    /** Internal recovery API. Callers must verify the returned owner and room before responding. */
    public FileMetadata findByTransfer(UUID transferId) throws SQLException {
        Objects.requireNonNull(transferId);
        try (Connection connection = connections.getConnection()) {
            StoredFile file = findFile(connection, "f.Transfer_Id = ?", transferId.toString());
            return file == null ? null : file.metadata();
        }
    }

    /** Internal storage lookup; call find(userId, fileId) first to enforce room authorization. */
    public String storageKey(long fileId) throws SQLException, TransferException {
        try (Connection connection = connections.getConnection()) {
            StoredFile file = findFile(connection, "f.Ma_Tep = ? AND t.Ngay_Xoa IS NULL", fileId);
            if (file == null) throw notFound();
            return file.storageKey();
        }
    }

    /** Bounded cursor scan; every candidate is rechecked under the room lock before deletion. */
    public List<RetiredFile> retirementCandidates(Instant cutoff, long afterId) throws SQLException {
        try (Connection connection = connections.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT f.Ma_Tep, f.Duong_Dan_Luu_Tru, t.Ma_Tin_Nhan, t.Ma_Phong
                FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan = f.Ma_Tin_Nhan
                JOIN Phong_Hop p ON p.Ma_Phong = t.Ma_Phong
                WHERE p.Trang_Thai = 'DA_DONG' AND p.Ngay_Dong <= ? AND f.Ma_Tep > ?
                ORDER BY f.Ma_Tep LIMIT 100
                """)) {
            statement.setTimestamp(1, Timestamp.from(cutoff), utc());
            statement.setLong(2, afterId);
            List<RetiredFile> files = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) files.add(new RetiredFile(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getLong(4)));
            }
            return List.copyOf(files);
        }
    }

    /** Lock the same room row as both reopen implementations, including during object deletion. */
    public boolean inExpiredRoom(long roomId, Instant cutoff, RetirementAction action) throws SQLException, IOException {
        try (Connection connection = connections.getConnection()) {
            beginMutation(connection);
            try {
                boolean expired;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT Trang_Thai, Ngay_Dong FROM Phong_Hop WHERE Ma_Phong = ? FOR UPDATE")) {
                    statement.setLong(1, roomId);
                    try (ResultSet rows = statement.executeQuery()) {
                        expired = false;
                        if (rows.next()) {
                            Timestamp closedAt = rows.getTimestamp(2, utc());
                            expired = "DA_DONG".equals(rows.getString(1)) && closedAt != null
                                    && !closedAt.toInstant().isAfter(cutoff);
                        }
                    }
                }
                if (expired) action.run(connection);
                connection.commit();
                return expired;
            } catch (SQLException | IOException | RuntimeException error) {
                rollback(connection, error);
                throw error;
            }
        }
    }

    public void retireOrphan(long roomId,String key,Instant cutoff,RetirementAction deleteObject) throws SQLException,IOException {
        if(roomId<1 || key==null || !key.startsWith("rooms/"+roomId+"/files/")) throw new IOException("INVALID_ROOM_OBJECT");
        inExpiredRoom(roomId,cutoff,connection -> {
            try(var query=connection.prepareStatement("SELECT 1 FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON t.Ma_Tin_Nhan=f.Ma_Tin_Nhan WHERE t.Ma_Phong=? AND f.Duong_Dan_Luu_Tru=? LIMIT 1")) {
                query.setLong(1,roomId);query.setString(2,key);try(var rows=query.executeQuery()) {if(rows.next()) return;}
            }
            deleteObject.run(connection);
        });
    }
    /** Disk errors roll back metadata so reopening preserves files that could not yet be removed. */
    public boolean retireFile(RetiredFile file, Instant cutoff, RetirementAction deleteObject) throws SQLException, IOException {
        return inExpiredRoom(file.roomId(), cutoff, connection -> {
            // Revalidate the attachment after acquiring the room lock; the scan may be stale.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT Duong_Dan_Luu_Tru FROM Tep_Dinh_Kem WHERE Ma_Tep = ? AND Ma_Tin_Nhan = ? FOR UPDATE")) {
                statement.setLong(1, file.fileId()); statement.setLong(2, file.messageId());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !file.storageKey().equals(rows.getString(1))) return;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE Tin_Nhan SET Ngay_Xoa = ? WHERE Ma_Tin_Nhan = ? AND Ngay_Xoa IS NULL")) {
                statement.setTimestamp(1, now(), utc()); statement.setLong(2, file.messageId()); statement.executeUpdate();
            }
            deleteObject.run(connection);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM Tep_Dinh_Kem WHERE Ma_Tep = ?")) {
                statement.setLong(1, file.fileId()); statement.executeUpdate();
            }
        });
    }

    @FunctionalInterface
    public interface RetirementAction { void run(Connection connection) throws SQLException, IOException; }
    public record RetiredFile(long fileId, String storageKey, long messageId, long roomId) { }

    /** Publishes one verified file and its TEP message together, exactly once per transfer. */
    public FileMetadata complete(UUID transferId, long userId, long roomId, String originalName,
            String mimeType, long size, String checksum, String storageKey)
            throws SQLException, TransferException {
        return complete(transferId,userId,roomId,originalName,mimeType,size,checksum,storageKey,null);
    }
    public FileMetadata complete(UUID transferId,long userId,long roomId,String originalName,String mimeType,long size,String checksum,String storageKey,String generation) throws SQLException,TransferException {
        return complete(transferId,userId,roomId,originalName,mimeType,size,checksum,storageKey,generation,null);
    }
    public FileMetadata complete(UUID transferId,long userId,long roomId,String originalName,String mimeType,long size,String checksum,String storageKey,String generation,String token) throws SQLException,TransferException {
        validateFile(transferId, originalName, mimeType, size, checksum, storageKey);
        String normalizedChecksum = checksum.toLowerCase(Locale.ROOT);
        try (Connection connection = connections.getConnection()) {
            beginMutation(connection);
            try {
                if(token!=null && sessions.authenticate(connection,token,true)!=userId) throw new TransferException("INVALID_SESSION","Phiên đăng nhập không hợp lệ.");
                requireOpenRoom(lockRoom(connection, roomId));
                authorize(connection, userId, roomId);
                requireRoomSession(connection, token, userId, roomId);
                if(generation!=null && !generation.equals(membershipGeneration(connection,userId,roomId))) throw new TransferException("STALE_TRANSFER","Lượt truyền thuộc lần tham gia phòng cũ.");
                StoredFile existing = findFile(connection, "f.Transfer_Id = ?", transferId.toString());
                if (existing != null) {
                    requireSameFile(existing, userId, roomId, originalName, mimeType, size,
                            normalizedChecksum, storageKey);
                    connection.commit();
                    return existing.metadata();
                }
                Timestamp timestamp = now();
                long messageId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO Tin_Nhan (Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Noi_Dung, Ngay_Gui)
                        VALUES (?, ?, 'TEP', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, roomId);
                    statement.setLong(2, userId);
                    statement.setString(3, originalName);
                    statement.setTimestamp(4, timestamp, utc());
                    statement.executeUpdate();
                    messageId = generatedId(statement);
                }
                long fileId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO Tep_Dinh_Kem (Ma_Tin_Nhan, Ten_Tep_Goc, Duong_Dan_Luu_Tru,
                            Loai_MIME, Kich_Thuoc_Byte, Ma_Kiem_Tra, Ngay_Tao, Transfer_Id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, messageId);
                    statement.setString(2, originalName);
                    statement.setString(3, storageKey);
                    statement.setString(4, mimeType);
                    statement.setLong(5, size);
                    statement.setString(6, normalizedChecksum);
                    statement.setTimestamp(7, timestamp, utc());
                    statement.setString(8, transferId.toString());
                    statement.executeUpdate();
                    fileId = generatedId(statement);
                }
                StoredFile published = findFile(connection, "f.Ma_Tep = ?", fileId);
                if (published == null) throw new SQLException("Published file could not be read");
                connection.commit();
                return published.metadata();
            } catch (SQLException | TransferException | RuntimeException error) {
                rollback(connection, error);
                if (error instanceof SQLException sql && isUniqueViolation(sql)) {
                    // Also handles two requests that supplied the same transfer id with different rooms.
                    StoredFile existing = findFile(connection, "f.Transfer_Id = ?", transferId.toString());
                    if (existing != null) {
                        requireSameFile(existing, userId, roomId, originalName, mimeType, size,
                                normalizedChecksum, storageKey);
                        return existing.metadata();
                    }
                }
                throw error;
            }
        }
    }

    private static void authorize(Connection connection, long userId, long roomId)
            throws SQLException, TransferException {
        if (userId <= 0 || roomId <= 0) throw denied();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM Thanh_Vien_Phong v
                JOIN Phong_Hop p ON p.Ma_Phong = v.Ma_Phong
                JOIN Nguoi_Dung n ON n.Ma_Nguoi_Dung = v.Ma_Nguoi_Dung
                WHERE v.Ma_Nguoi_Dung = ? AND v.Ma_Phong = ? AND v.Roi_Phong_Luc IS NULL
                AND p.Trang_Thai IN ('CHO', 'DANG_HOAT_DONG') AND n.Trang_Thai = 'HOAT_DONG'
                LIMIT 1
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, roomId);
            try (ResultSet result = statement.executeQuery()) { if (!result.next()) throw denied(); }
        }
    }

    private static void requireActiveUser(Connection connection, long userId)
            throws SQLException, TransferException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM Nguoi_Dung WHERE Ma_Nguoi_Dung = ? AND Trang_Thai = 'HOAT_DONG'")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new TransferException("INVALID_SESSION", "Tài khoản không hoạt động.");
            }
        }
    }

    private RoomRow lockRoom(Connection connection, Object idOrCode) throws SQLException, TransferException {
        String predicate = idOrCode instanceof String ? "Ma_Phong_Tham_Gia = ?" : "Ma_Phong = ?";
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT Ma_Phong, Ma_Phong_Tham_Gia, Ten_Phong, So_Nguoi_Toi_Da,
                    Ma_Chu_Phong, Ma_PIN_Bam, Da_Khoa, Trang_Thai FROM Phong_Hop WHERE
                """ + predicate + " FOR UPDATE")) {
            statement.setObject(1, idOrCode);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new TransferException("ROOM_NOT_FOUND", "Không tìm thấy phòng.");
                RoomRow room=new RoomRow(new RoomInfo(result.getLong(1), result.getString(2), result.getString(3),
                        result.getInt(4), result.getLong(5)), result.getString(6), result.getBoolean(7), result.getString(8));
                if(legacyOwner!=null) try(var owner=connection.prepareStatement("SELECT Instance_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=?")) {
                    owner.setLong(1,room.info().id());try(var rows=owner.executeQuery()) {if(!rows.next() || !legacyOwner.equals(rows.getString(1))) throw new TransferException("PERMISSION_DENIED","Phòng không thuộc server quản lý hiện tại.");}
                }
                return room;
            }
        }
    }

    private static void requireOpenRoom(RoomRow room) throws TransferException {
        if (!"CHO".equals(room.status()) && !"DANG_HOAT_DONG".equals(room.status())) {
            throw new TransferException("PERMISSION_DENIED", "Phòng đã đóng.");
        }
    }

    private static boolean hasMembership(Connection connection, long userId, long roomId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM Thanh_Vien_Phong WHERE Ma_Nguoi_Dung = ?
                AND Ma_Phong = ? AND Roi_Phong_Luc IS NULL LIMIT 1
                """)) {
            statement.setLong(1, userId);
            statement.setLong(2, roomId);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private void addMembership(Connection connection, long roomId, long userId, String role) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO Thanh_Vien_Phong (Ma_Phong, Ma_Nguoi_Dung, Vai_Tro, Tham_Gia_Luc)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setLong(1, roomId);
            statement.setLong(2, userId);
            statement.setString(3, role);
            statement.setTimestamp(4, now(), utc());
            statement.executeUpdate();
        }
    }

    private static StoredFile findFile(Connection connection, String predicate, Object argument) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FILE_SELECT + " WHERE " + predicate)) {
            statement.setObject(1, argument);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readFile(result) : null; }
        }
    }

    private static StoredFile readFile(ResultSet result) throws SQLException {
        FileMetadata metadata = new FileMetadata(result.getLong("Ma_Tep"), result.getLong("Ma_Phong"),
                result.getLong("Ma_Nguoi_Gui"), result.getString("Ten_Hien_Thi"), result.getString("Ten_Tep_Goc"),
                result.getString("Loai_MIME"), result.getLong("Kich_Thuoc_Byte"), result.getString("Ma_Kiem_Tra"),
                result.getTimestamp("Ngay_Tao", utc()).toInstant().toString());
        return new StoredFile(metadata, result.getString("Duong_Dan_Luu_Tru"));
    }

    private static void validateFile(UUID transferId, String name, String mime, long size,
            String checksum, String storageKey) throws TransferException {
        if (transferId == null || name == null || name.isBlank() || name.length() > 255
                || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || hasControl(name) || size < 0 || mime == null || mime.isBlank() || mime.length() > 100
                || hasControl(mime) || checksum == null || !checksum.matches("[a-fA-F0-9]{64}")
                || storageKey == null || !storageKey.matches("[A-Za-z0-9_./-]{1,500}")
                || storageKey.startsWith("/") || storageKey.endsWith("/")
                || List.of(storageKey.split("/", -1)).stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            throw invalid("Thông tin tệp không hợp lệ.");
        }
    }

    private static void requireSameFile(StoredFile existing, long userId, long roomId, String name,
            String mime, long size, String checksum, String key) throws TransferException {
        FileMetadata file = existing.metadata();
        if (file.userId() != userId || file.roomId() != roomId || !file.originalName().equals(name)
                || !Objects.equals(file.mimeType(), mime) || file.sizeBytes() != size
                || !Objects.equals(file.checksum(), checksum) || !existing.storageKey().equals(key)) {
            throw new TransferException("TRANSFER_CONFLICT", "Mã truyền tệp đã được sử dụng cho tệp khác.");
        }
    }

    private String newRoomCode() {
        StringBuilder code = new StringBuilder("MMR-");
        for (int index = 0; index < 8; index++) code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        return code.toString();
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) throw new SQLException("Missing generated identifier");
            return keys.getLong(1);
        }
    }

    private static boolean isUniqueViolation(SQLException error) {
        return "23505".equals(error.getSQLState()) || "23000".equals(error.getSQLState()) && error.getErrorCode() == 1062;
    }

    private static void beginMutation(Connection connection) throws SQLException {
        // MySQL defaults to REPEATABLE READ. An earlier account lookup must not freeze the
        // membership snapshot before waiting for another join to release the room row lock.
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setAutoCommit(false);
    }

    private static boolean hasControl(String text) { return text.codePoints().anyMatch(Character::isISOControl); }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static Calendar utc() { return Calendar.getInstance(TimeZone.getTimeZone("UTC")); }
    private static TransferException invalid(String message) { return new TransferException("INVALID_REQUEST", message); }
    private static TransferException denied() { return new TransferException("PERMISSION_DENIED", "Bạn không có quyền truy cập tệp của phòng này."); }
    private static TransferException notFound() { return new TransferException("FILE_NOT_FOUND", "Không tìm thấy tệp."); }

    private static void rollback(Connection connection, Exception cause) {
        try { connection.rollback(); }
        catch (SQLException failure) { cause.addSuppressed(failure); }
    }

    private record RoomRow(RoomInfo info, String pinHash, boolean locked, String status) { }
    private record StoredFile(FileMetadata metadata, String storageKey) { }
}
