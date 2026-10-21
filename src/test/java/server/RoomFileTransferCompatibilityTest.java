package server;

import client.auth.AuthClient;
import client.filetransfer.FileTransferClient;
import client.filetransfer.FileTransferClient.Cancellation;
import common.auth.AuthResponse;
import common.filetransfer.FileMetadata;
import common.filetransfer.TransferException;
import common.filetransfer.TransferProtocol;
import common.filetransfer.TransferProtocol.Frame;
import common.filetransfer.TransferProtocol.Type;
import common.room.RoomInfo;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import server.filetransfer.FileMetadataRepository;
import server.filetransfer.FileStorageService;
import server.filetransfer.FileTransferServer;
import server.filetransfer.TransferConfig;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** The auth/room listener and file listener share production membership and session records. */
@Timeout(60)
class RoomFileTransferCompatibilityTest {
    @TempDir static Path certificates;
    @TempDir Path temporary;
    private static RoomTestFixture.Tls tls;
    private RoomTestFixture fixture;
    private AuthClient control;
    private AuthResponse host;
    private AuthResponse guest;
    private FileStorageService storage;
    private FileTransferServer fileServer;
    private FileTransferClient files;

    @BeforeAll
    static void certificates() throws Exception {
        tls = RoomTestFixture.certificates(certificates);
    }

    @BeforeEach
    void servicesShareOneIsolatedDatabase() throws Exception {
        fixture = new RoomTestFixture(tls);
        // Reuse actual file table definitions while retaining the room fixture's isolated H2 account tables.
        String schema = Files.readString(Path.of("database/schema.sql"))
                .replaceAll("(?m)^\\s*--.*$", "").replace("ENGINE = InnoDB", "");
        for (String sql : schema.split(";")) {
            String statement = sql.strip();
            if (statement.startsWith("CREATE TABLE Tin_Nhan") || statement.startsWith("CREATE TABLE Tep_Dinh_Kem")) {
                fixture.execute(statement);
            }
        }
        control = fixture.client();
        host = fixture.account("FileRoomHost", "Người gửi thật");
        guest = fixture.account("FileRoomGuest", "Người nhận thật");
        TransferConfig config = new TransferConfig(true, "127.0.0.1", 0, 65536, 4, 2, 10000,
                temporary.resolve("storage"), 2L * 1024 * 1024, 8L * 1024 * 1024,
                4, Duration.ofHours(1), true);
        storage = new FileStorageService(config.storagePath(), config.quotaBytes(),
                config.maxFileBytes(), config.maxPendingPerUser(), config.expiry(), config.resumeEnabled());
        fileServer = new FileTransferServer(tls.server(), config, new FileMetadataRepository(fixture::connection), storage);
        fileServer.start();
        files = new FileTransferClient("localhost", fileServer.port(), tls.client(), 10000);
    }

    @AfterEach
    void closeServicesAndIsolatedDatabase() throws Exception {
        try {
            if (files != null) files.close();
            if (fileServer != null) fileServer.close();
        } finally {
            try {
                if (storage != null) storage.close();
            } finally {
                if (fixture != null) fixture.close();
            }
        }
    }

    @Test
    void realRoomJoinGrantsFileAccessAndAuthLeaveRevokesItWithoutDeletingSenderFiles() throws Exception {
        AuthResponse created = control.createRoom(host.token(), "Phòng chia sẻ liên giao thức", "543210", 10);
        assertTrue(created.success(), created.message());
        RoomInfo room = created.room();
        assertEquals(1, room.people());
        assertEquals(host.user().id(), room.hostId());
        assertEquals("INVALID_ROOM_PIN", control.joinRoom(guest.token(), room.code(), "111111").code());
        assertDenied(() -> files.listFiles(guest.token(), room.id(), 0));
        assertEquals(1, activeMembers());

        AuthResponse deniedId = control.joinRoom(guest.token(), Long.toString(room.id()), "543210");
        assertFalse(deniedId.success());
        assertEquals(1, activeMembers(), "A numeric room ID must never grant membership");
        AuthResponse joined = control.joinRoom(guest.token(), room.code(), "543210");
        assertTrue(joined.success(), joined.message());
        assertEquals(2, joined.room().people());
        assertTrue(control.joinRoom(guest.token(), room.code(), "543210").success());
        assertEquals(2, activeMembers(), "Repeated room joins must not manufacture duplicate file memberships");
        assertEquals(2, control.getRoom(host.token(), room.id()).room().participants().size());

        Path source = source("Báo cáo tương thích.zip", 180_123);
        var upload = files.prepareUpload(room.id(), source, UUID.randomUUID(), new Cancellation(), null);
        FileMetadata metadata = files.upload(host.token(), upload, new Cancellation(), null);
        assertEquals(host.user().id(), metadata.userId());
        assertEquals("Người gửi thật", metadata.senderName());
        assertEquals(room.id(), metadata.roomId());
        assertEquals(List.of(metadata), files.listFiles(guest.token(), room.id(), 0));
        assertEquals(metadata.id(), files.upload(host.token(), upload, new Cancellation(), null).id());
        Path received = temporary.resolve("guest-download.zip");
        files.download(guest.token(), metadata, received, new Cancellation(), null);
        assertEquals(sha256(source), sha256(received));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Tep_Dinh_Kem"));

        AuthResponse left = control.leaveRoom(guest.token(), room.id());
        assertTrue(left.success(), left.message());
        assertEquals(1, left.room().people());
        assertEquals(1, activeMembers());
        assertEquals(1, control.getRoom(host.token(), room.id()).room().people());
        assertDenied(() -> files.listFiles(guest.token(), room.id(), 0));
        assertDenied(() -> files.download(guest.token(), metadata, temporary.resolve("denied-download"), new Cancellation(), null));
        var deniedUpload = files.prepareUpload(room.id(), source, UUID.randomUUID(), new Cancellation(), null);
        assertDenied(() -> files.upload(guest.token(), deniedUpload, new Cancellation(), null));
        assertEquals(List.of(metadata), files.listFiles(host.token(), room.id(), 0));

        assertTrue(control.joinRoom(guest.token(), room.code(), "543210").success());
        assertTrue(control.leaveRoom(host.token(), room.id()).success());
        assertEquals(1, control.getRoom(guest.token(), room.id()).room().people());
        assertEquals(List.of(metadata), files.listFiles(guest.token(), room.id(), 0), "Sender leaving cannot remove shared files");
        Path afterSenderLeft = temporary.resolve("after-sender-left.zip");
        files.download(guest.token(), metadata, afterSenderLeft, new Cancellation(), null);
        assertEquals(sha256(source), sha256(afterSenderLeft));
        assertEquals(1, fixture.count("SELECT COUNT(*) FROM Tep_Dinh_Kem"));

        assertTrue(control.logout(guest.token()).success());
        assertEquals("INVALID_SESSION", assertThrows(TransferException.class,
                () -> files.listFiles(guest.token(), room.id(), 0)).code(), "Auth logout must revoke tokens on the file listener too");
    }

    @Test
    void reopeningClearsSharedFilesAndRequiresFormerMembersToJoinAgain() throws Exception {
        RoomInfo original = control.createRoom(host.token(), "Mở lại phòng chia sẻ", "543210", 10).room();
        assertTrue(control.joinRoom(guest.token(), original.code(), "543210").success());
        Path source = source("kept-after-reopen.bin", 1024);
        var upload = files.prepareUpload(original.id(), source, UUID.randomUUID(), new Cancellation(), null);
        FileMetadata metadata = files.upload(host.token(), upload, new Cancellation(), null);

        assertTrue(control.leaveRoom(guest.token(), original.id()).success());
        assertEquals("DA_DONG", control.leaveRoom(host.token(), original.id()).room().status());
        assertDenied(() -> files.listFiles(host.token(), original.id(), 0));
        assertDenied(() -> files.listFiles(guest.token(), original.id(), 0));

        assertEquals("ROOM_CLEANING",control.reopenRoom(host.token(),original.id()).code());
        cleanClosedContent(original.id());
        AuthResponse reopened = control.reopenRoom(host.token(), original.id());
        assertTrue(reopened.success(), reopened.message());
        assertEquals(original.id(), reopened.room().id());
        assertEquals(original.code(), reopened.room().code());
        assertEquals(1, reopened.room().people());
        assertEquals(2, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NOT NULL"));
        assertTrue(files.listFiles(host.token(), original.id(), 0).isEmpty());
        assertDenied(() -> files.listFiles(guest.token(), original.id(), 0));
        assertEquals("INVALID_ROOM_PIN", control.joinRoom(guest.token(), original.code(), "111111").code());
        assertTrue(control.joinRoom(guest.token(), original.code(), "543210").success());
        assertTrue(files.listFiles(guest.token(), original.id(), 0).isEmpty());
        Path received = temporary.resolve("after-reopen.bin");
        assertTrue(Files.exists(source),"Cleanup must preserve the sender personal file");
    }

    @Test
    void signalingReopenAlsoRevokesOldFileMembershipsUntilFreshJoin() throws Exception {
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "")
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) fixture.execute(sql.strip());
        var clock = java.time.Clock.systemUTC();
        var validator = new server.auth.SessionValidator(fixture::connection, clock);
        var hostPrincipal = validator.validate(host.token()).orElseThrow();
        var guestPrincipal = validator.validate(guest.token()).orElseThrow();
        try (var rooms = new server.meeting.RoomService(new server.meeting.RoomRepository(fixture::connection),
                clock, "file-reopen-test", new byte[32], 10, event -> { })) {
            rooms.start();
            var original = rooms.create(hostPrincipal, UUID.randomUUID().toString(),
                    new common.meeting.CreateRoomParams("Phòng video mở lại", "543210", 10, false));
            rooms.join(hostPrincipal, "file-reopen-host", 1, UUID.randomUUID().toString(), original.joinCode(), "", false, false);
            rooms.join(guestPrincipal, "file-reopen-guest", 1, UUID.randomUUID().toString(), original.joinCode(), "543210", false, false);
            long roomId = original.collaborationRoom().id();
            Path source = source("video-kept-after-reopen.bin", 1024);
            var upload = files.prepareUpload(roomId, source, UUID.randomUUID(), new Cancellation(), null);
            FileMetadata metadata = files.upload(host.token(), upload, new Cancellation(), null);

            rooms.leave("file-reopen-host", "PHONG_DONG");
            assertEquals(0, activeMembers());
            assertDenied(() -> files.listFiles(host.token(), roomId, 0));
            assertDenied(() -> files.listFiles(guest.token(), roomId, 0));
            cleanClosedContent(roomId);
            var reopened = rooms.reopen(hostPrincipal, UUID.randomUUID().toString(), original.joinCode());
            assertEquals(roomId, reopened.collaborationRoom().id());
            assertNotEquals(original.roomId(), reopened.roomId());
            assertEquals(0, activeMembers(), "Opening the room must not revive past attendance");
            rooms.join(hostPrincipal, "file-reopen-host", 1, UUID.randomUUID().toString(), reopened.joinCode(), "", false, false);
            assertTrue(files.listFiles(host.token(), roomId, 0).isEmpty());
            assertDenied(() -> files.listFiles(guest.token(), roomId, 0));
            rooms.join(guestPrincipal, "file-reopen-guest", 1, UUID.randomUUID().toString(), reopened.joinCode(), "543210", false, false);
            assertTrue(files.listFiles(guest.token(), roomId, 0).isEmpty());
            assertEquals(4, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong"));
            assertEquals(2, fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NOT NULL"));
        }
    }

    @Test
    void authLeaveDuringAcceptedUploadPreventsPublicationAndLeavesOtherMemberUsable() throws Exception {
        AuthResponse created = control.createRoom(host.token(), "Đổi quyền khi đang gửi", "7654", 10);
        assertTrue(created.success());
        RoomInfo room = created.room();
        assertTrue(control.joinRoom(guest.token(), room.code(), "7654").success());
        Path source = source("membership-revoked.bin", 1024);
        var upload = files.prepareUpload(room.id(), source, UUID.randomUUID(), new Cancellation(), null);
        try (SSLSocket socket = (SSLSocket) tls.client().getSocketFactory().createSocket("localhost", fileServer.port())) {
            socket.setSoTimeout(10000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
            socket.startHandshake();
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            TransferProtocol.write(output, new Frame(Type.UPLOAD_INIT, upload.transferId(), TransferProtocol.json(Map.of(
                    "token", guest.token(), "roomId", room.id(), "originalName", upload.originalName(),
                    "mimeType", upload.mimeType(), "sizeBytes", upload.sizeBytes(), "checksum", upload.checksum())), new byte[0]));
            assertEquals(Type.UPLOAD_ACCEPT, TransferProtocol.read(input).type());
            assertTrue(control.leaveRoom(guest.token(), room.id()).success());
            TransferProtocol.write(output, new Frame(Type.UPLOAD_CHUNK, upload.transferId(),
                    TransferProtocol.json(Map.of("offset", 0)), Files.readAllBytes(source)));
            TransferProtocol.write(output, new Frame(Type.UPLOAD_COMPLETE, upload.transferId(),
                    TransferProtocol.json(Map.of()), new byte[0]));
            Frame denied = TransferProtocol.read(input);
            assertEquals(Type.UPLOAD_FAILED, denied.type());
            assertEquals("PERMISSION_DENIED", denied.header().get("code").getAsString());
        }
        assertEquals(0, fixture.count("SELECT COUNT(*) FROM Tin_Nhan"));
        assertEquals(0, fixture.count("SELECT COUNT(*) FROM Tep_Dinh_Kem"));
        assertEquals(1, control.getRoom(host.token(), room.id()).room().people());
        var hostUpload = files.prepareUpload(room.id(), source, UUID.randomUUID(), new Cancellation(), null);
        FileMetadata metadata = files.upload(host.token(), hostUpload, new Cancellation(), null);
        assertEquals(List.of(metadata), files.listFiles(host.token(), room.id(), 0));
    }

    private void cleanClosedContent(long id) throws Exception {
        String owner=support.RoomTestFixture.ownLegacyTestRoom(fixture::connection,id,"local");
        try(var cleanup=new server.meeting.ImmediateRoomCleanup(fixture::connection,owner,fileServer::cleanupRoom,fileServer::hasPendingRoom,() -> {})) {assertTrue(cleanup.clean(id));}
    }
    private long activeMembers() throws Exception {
        return fixture.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL");
    }

    private Path source(String name, int size) throws Exception {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 17);
        return Files.write(temporary.resolve(name), bytes);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable request) {
        assertEquals("PERMISSION_DENIED", assertThrows(TransferException.class, request).code());
    }
}
