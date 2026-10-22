package server.filetransfer;

import common.filetransfer.FileMetadata;
import common.filetransfer.RoomInfo;
import common.filetransfer.TransferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FileMetadataRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00Z");
    private static final String CHECKSUM = "a".repeat(64);
    private String url;
    private FileMetadataRepository repository;

    @BeforeEach void setUp() throws Exception {
        url = "jdbc:h2:mem:files_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        // Exercise the real project schema, with only database-selection/MySQL engine syntax removed.
        String schema = Files.readString(Path.of("database/schema.sql"))
                .replaceAll("(?m)^\\s*--.*$", "")
                .replaceFirst("(?s)CREATE DATABASE IF NOT EXISTS Mini_Meeting_Room.*?;", "")
                .replace("USE Mini_Meeting_Room;", "").replace("ENGINE = InnoDB", "");
        for (String sql : schema.split(";")) if (!sql.isBlank()) execute(sql);
        for (int user = 1; user <= 6; user++) {
            execute("INSERT INTO Nguoi_Dung (Ma_Nguoi_Dung, Ten_Dang_Nhap, Mat_Khau_Bam, Ten_Hien_Thi) VALUES ("
                    + user + ", 'user" + user + "', 'hash', 'Người dùng " + user + "')");
        }
        repository = new FileMetadataRepository(() -> DriverManager.getConnection(url), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void tearDown() throws SQLException { execute("DROP ALL OBJECTS"); }

    @Test void createsRealRoomAndHashedPinThenJoinsLeavesAndRevokesFileAccess() throws Exception {
        RoomInfo room = repository.createRoom(1, "  Phòng tiếng Việt  ", "1234", 3);
        assertEquals("Phòng tiếng Việt", room.name());
        assertEquals(1, room.ownerId());
        assertEquals(3, room.capacity());
        assertNotEquals("1234", text("SELECT Ma_PIN_Bam FROM Phong_Hop"));
        assertEquals("CHU_PHONG", text("SELECT Vai_Tro FROM Thanh_Vien_Phong"));
        repository.authorize(1, room.id());
        assertCode("PERMISSION_DENIED", () -> repository.joinRoom(2, room.code(), "1111"));
        assertEquals(room, repository.joinRoom(2, " " + room.code().toLowerCase(Locale.ROOT) + " ", "1234"));
        assertEquals(room, repository.joinRoom(2, room.code(), "1234"));
        assertEquals(2, number("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
        repository.authorize(2, room.id());
        repository.leaveRoom(2, room.id());
        repository.leaveRoom(2, room.id());
        assertCode("PERMISSION_DENIED", () -> repository.authorize(2, room.id()));
        repository.joinRoom(2, room.code(), "1234");
        assertEquals(3, number("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
        assertEquals("TU_ROI", text("SELECT Ly_Do_Roi FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NOT NULL"));
    }

    @Test void validatesRoomRequestsAndPreservesExistingCodeFormats() throws Exception {
        assertCode("INVALID_REQUEST", () -> repository.createRoom(1, "", null, 2));
        assertCode("INVALID_REQUEST", () -> repository.createRoom(1, "Room", "12", 2));
        assertCode("INVALID_REQUEST", () -> repository.createRoom(1, "Room", null, 251));
        assertCode("INVALID_SESSION", () -> repository.createRoom(999, "Room", null, 2));
        assertCode("INVALID_REQUEST", () -> repository.joinRoom(1, "' OR 1=1--", "1234"));
        assertCode("ROOM_NOT_FOUND", () -> repository.joinRoom(1, "ABC-123", null));
        RoomInfo room = repository.createRoom(1, "Room", null, 2);
        execute("UPDATE Phong_Hop SET Ma_Phong_Tham_Gia = 'ABC-123' WHERE Ma_Phong = " + room.id());
        assertEquals(room.id(), repository.joinRoom(2, "abc-123", null).id());
    }

    @Test void lockedClosedAndDisabledAccountsCannotGainAccess() throws Exception {
        RoomInfo room = repository.createRoom(1, "Room", null, 3);
        execute("UPDATE Phong_Hop SET Da_Khoa = TRUE");
        assertCode("ROOM_LOCKED", () -> repository.joinRoom(2, room.code(), null));
        assertEquals(room, repository.joinRoom(1, room.code(), null));
        execute("UPDATE Phong_Hop SET Trang_Thai = 'DA_DONG'");
        assertCode("PERMISSION_DENIED", () -> repository.authorize(1, room.id()));
        assertCode("PERMISSION_DENIED", () -> repository.joinRoom(1, room.code(), null));
        execute("UPDATE Nguoi_Dung SET Trang_Thai = 'VO_HIEU_HOA' WHERE Ma_Nguoi_Dung = 2");
        assertCode("INVALID_SESSION", () -> repository.joinRoom(2, room.code(), null));
    }

    @Test void simultaneousJoinsRespectCapacityAndAvoidDuplicateActiveMemberships() throws Exception {
        repository = new FileMetadataRepository(() -> {
            Connection connection = DriverManager.getConnection(url);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return connection;
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        RoomInfo room = repository.createRoom(1, "Room", null, 2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long user = 2; user <= 6; user++) {
                long userId = user;
                results.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { repository.joinRoom(userId, room.code(), null); return true; }
                    catch (TransferException error) { assertEquals("ROOM_FULL", error.code()); return false; }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) if (result.get(15, TimeUnit.SECONDS)) admitted++;
            assertEquals(1, admitted);
        }
        assertEquals(2, number("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));

        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Future<RoomInfo>> joins = new ArrayList<>();
            for (int index = 0; index < 5; index++) joins.add(pool.submit(() -> repository.joinRoom(1, room.code(), null)));
            for (Future<RoomInfo> join : joins) assertEquals(room, join.get(15, TimeUnit.SECONDS));
        }
        assertEquals(2, number("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.txt", "báo cáo.pdf", "image.png", "movie.mp4", "archive.zip", "program.exe", "empty.txt"})
    void publishesMetadataAndMessageForAnyFileTypeAndOnlySameRoomCanRead(String name) throws Exception {
        RoomInfo room = repository.createRoom(1, "Room", null, 3);
        repository.joinRoom(2, room.code(), null);
        RoomInfo other = repository.createRoom(3, "Other", null, 3);
        UUID transfer = UUID.randomUUID();
        long size = name.equals("empty.txt") ? 0 : 17;
        FileMetadata file = repository.complete(transfer, 1, room.id(), name, "application/octet-stream", size,
                CHECKSUM, "objects/" + transfer + ".bin");
        assertEquals(name, file.originalName());
        assertEquals("Người dùng 1", file.senderName());
        assertEquals(NOW.toString(), file.createdAt());
        assertEquals(size, file.sizeBytes());
        assertEquals(file, repository.findByTransfer(transfer));
        assertEquals(file, repository.find(2, file.id()));
        assertEquals(List.of(file), repository.list(2, room.id(), 0));
        assertEquals(List.of(), repository.list(2, room.id(), file.id()));
        assertEquals(List.of(), repository.list(3, other.id(), 0));
        assertCode("PERMISSION_DENIED", () -> repository.find(3, file.id()));
        assertCode("PERMISSION_DENIED", () -> repository.list(3, room.id(), 0));
        assertEquals("TEP", text("SELECT Loai_Tin_Nhan FROM Tin_Nhan"));
        assertEquals(name, text("SELECT Noi_Dung FROM Tin_Nhan"));
        assertEquals("objects/" + transfer + ".bin", repository.storageKey(file.id()));
        repository.leaveRoom(2, room.id());
        assertCode("PERMISSION_DENIED", () -> repository.find(2, file.id()));
    }

    @Test void completionRetryIsIdempotentEvenUnderConcurrencyAndCannotChangeOwnerOrMetadata() throws Exception {
        RoomInfo room = repository.createRoom(1, "Room", null, 3);
        repository.joinRoom(2, room.code(), null);
        UUID transfer = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        FileMetadata first;
        try (var pool = Executors.newFixedThreadPool(5)) {
            List<Future<FileMetadata>> results = new ArrayList<>();
            for (int index = 0; index < 5; index++) results.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return complete(transfer, 1, room.id(), "same.txt");
            }));
            start.countDown();
            first = results.getFirst().get(15, TimeUnit.SECONDS);
            for (Future<FileMetadata> result : results) assertEquals(first, result.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, number("SELECT COUNT(*) FROM Tin_Nhan"));
        assertEquals(1, number("SELECT COUNT(*) FROM Tep_Dinh_Kem"));
        assertCode("TRANSFER_CONFLICT", () -> complete(transfer, 2, room.id(), "same.txt"));
        assertCode("TRANSFER_CONFLICT", () -> complete(transfer, 1, room.id(), "changed.txt"));
        RoomInfo other = repository.createRoom(1, "Other", null, 3);
        assertCode("TRANSFER_CONFLICT", () -> complete(transfer, 1, other.id(), "same.txt"));
        assertEquals(1, number("SELECT COUNT(*) FROM Tin_Nhan"));
    }

    @Test void metadataFailureRollsBackMessageAndRoomCreationFailureRollsBackRoom() throws Exception {
        RoomInfo room = repository.createRoom(1, "Room", null, 3);
        execute("ALTER TABLE Tep_Dinh_Kem ADD CONSTRAINT test_disk_metadata_failure CHECK (Kich_Thuoc_Byte = 0)");
        UUID transfer = UUID.randomUUID();
        assertThrows(SQLException.class, () -> complete(transfer, 1, room.id(), "failed.txt"));
        assertEquals(0, number("SELECT COUNT(*) FROM Tin_Nhan"));
        assertEquals(0, number("SELECT COUNT(*) FROM Tep_Dinh_Kem"));
        assertNull(repository.findByTransfer(transfer));
        execute("ALTER TABLE Thanh_Vien_Phong ADD CONSTRAINT test_membership_failure CHECK (Ma_Nguoi_Dung = 1)");
        assertThrows(SQLException.class, () -> repository.createRoom(2, "Failed room", null, 3));
        assertEquals(1, number("SELECT COUNT(*) FROM Phong_Hop"));
    }

    @Test void rejectsPathTraversalInvalidMetadataAndNonMembersBeforePublishing() throws Exception {
        RoomInfo room = repository.createRoom(1, "Room", null, 3);
        assertCode("PERMISSION_DENIED", () -> complete(UUID.randomUUID(), 2, room.id(), "file.txt"));
        for (String name : List.of("../file.txt", "..\\file.txt", "..", "a\u0000b", "a".repeat(256))) {
            assertCode("INVALID_REQUEST", () -> complete(UUID.randomUUID(), 1, room.id(), name));
        }
        for (String key : List.of("../file", "/etc/file", "C:/file", "objects/../../file", "objects\\file", "objects//file")) {
            assertCode("INVALID_REQUEST", () -> repository.complete(UUID.randomUUID(), 1, room.id(), "file.txt",
                    "text/plain", 1, CHECKSUM, key));
        }
        assertCode("INVALID_REQUEST", () -> repository.complete(UUID.randomUUID(), 1, room.id(), "file.txt",
                "text/plain", -1, CHECKSUM, "objects/file"));
        assertCode("INVALID_REQUEST", () -> repository.complete(UUID.randomUUID(), 1, room.id(), "file.txt",
                "text/plain", 1, "invalid", "objects/file"));
        assertCode("FILE_NOT_FOUND", () -> repository.find(1, 999));
        assertEquals(0, number("SELECT COUNT(*) FROM Tin_Nhan"));
    }

    @Test void pagesAreBoundedAndSoftDeletedMessagesAreNotExposed() throws Exception {
        RoomInfo room = repository.createRoom(1, "Room", null, 3);
        for (int index = 0; index < 105; index++) complete(UUID.randomUUID(), 1, room.id(), index + ".txt");
        List<FileMetadata> first = repository.list(1, room.id(), 0);
        assertEquals(100, first.size());
        List<FileMetadata> second = repository.list(1, room.id(), first.getLast().id());
        assertEquals(5, second.size());
        assertTrue(second.getFirst().id() > first.getLast().id());
        assertCode("INVALID_REQUEST", () -> repository.list(1, room.id(), -1));
        execute("UPDATE Tin_Nhan SET Ngay_Xoa = CURRENT_TIMESTAMP");
        assertEquals(List.of(), repository.list(1, room.id(), 0));
        assertCode("FILE_NOT_FOUND", () -> repository.find(1, first.getFirst().id()));
    }

    @Test void oldMembershipGenerationCannotFinalizeAfterLeavingAndJoiningAgain() throws Exception {
        var room=repository.createRoom(1,"Generation",null,3);repository.joinRoom(2,room.code(),null);
        String previous=repository.membershipGeneration(2,room.id());repository.leaveRoom(2,room.id());repository.joinRoom(2,room.code(),null);
        var transfer=UUID.randomUUID();
        assertCode("STALE_TRANSFER",() -> repository.complete(transfer,2,room.id(),"old.txt","text/plain",17,CHECKSUM,"objects/"+transfer+".bin",previous));
        assertEquals(0,number("SELECT COUNT(*) FROM Tin_Nhan"));
    }
    @Test void revokedSessionCannotPublishEvenWhenRoomMembershipStillExists() throws Exception {
        var room=repository.createRoom(1,"Revocation",null,3);var transfer=UUID.randomUUID();String token="test-upload-token";
        String hash=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        execute("INSERT INTO Phien_Dang_Nhap(Ma_Nguoi_Dung,Token_Bam,Ngay_Tao,Het_Han_Luc,Thu_Hoi_Luc) VALUES(1,'"+hash+"',TIMESTAMP '2026-10-14 05:00:00',TIMESTAMP '2026-10-15 06:00:00',CURRENT_TIMESTAMP)");
        assertCode("INVALID_SESSION",() -> repository.complete(transfer,1,room.id(),"revoked.txt","text/plain",17,CHECKSUM,"objects/"+transfer+".bin",repository.membershipGeneration(1,room.id()),token));
        assertEquals(0,number("SELECT COUNT(*) FROM Tep_Dinh_Kem"));
    }
    @Test void fileProtocolRoomCreationPersistsOwnerAndRejectsForeignServerWrites() throws Exception {
        support.RoomTestFixture.ownershipSchema(() -> DriverManager.getConnection(url));repository.legacyOwner("file-owner-a");
        var room=repository.createRoom(1,"Owned file protocol",null,3);assertEquals("file-owner-a",text("SELECT Instance_ID FROM MMR_Phong_Van_Hanh"));
        repository.legacyOwner("file-owner-b");assertCode("PERMISSION_DENIED",() -> complete(UUID.randomUUID(),1,room.id(),"foreign.txt"));
        assertEquals(0,number("SELECT COUNT(*) FROM Tin_Nhan"));
    }
    @Test void deviceSessionGateBlocksStaleFilesButPreservesLegacyRoomAccess() throws Exception {
        var room = repository.createRoom(1, "Device gate", null, 3);
        support.RoomTestFixture.ownLegacyTestRoom(() -> DriverManager.getConnection(url), room.id(), "local");
        repository.roomSessionGate((roomId, userId, hash) -> hash.equals(server.auth.AuthService.tokenHash("new-device")));
        repository.authorizeSession("old-device", 1, room.id()); // Legacy rooms retain their existing behavior.
        execute("UPDATE MMR_Phong_Van_Hanh SET Server_Epoch='signaling-epoch'");
        repository.authorizeSession("new-device", 1, room.id());
        assertCode("DEVICE_REPLACED", () -> repository.authorizeSession("old-device", 1, room.id()));
        execute("INSERT INTO Phien_Dang_Nhap(Ma_Nguoi_Dung,Token_Bam,Het_Han_Luc) VALUES(1,'"
                + server.auth.AuthService.tokenHash("old-device") + "',TIMESTAMP '2099-01-01 00:00:00')");
        assertCode("DEVICE_REPLACED", () -> repository.beginUpload(1, room.id(), null, "old-device",
                generation -> { fail("A stale device must not allocate storage"); return null; }));
    }
    private FileMetadata complete(UUID transfer, long user, long room, String name) throws Exception {
        return repository.complete(transfer, user, room, name, "text/plain", 17, CHECKSUM, "objects/" + transfer + ".bin");
    }

    private static void assertCode(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(TransferException.class, operation).code());
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long number(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String text(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
