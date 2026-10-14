package server.filetransfer;

import client.filetransfer.FileTransferClient;
import client.filetransfer.FileTransferClient.Cancellation;
import common.auth.TlsSupport;
import common.filetransfer.FileMetadata;
import common.filetransfer.RoomInfo;
import common.filetransfer.TransferException;
import common.filetransfer.TransferProtocol;
import common.filetransfer.TransferProtocol.Frame;
import common.filetransfer.TransferProtocol.Type;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP/TLS + production JDBC repository + local disk; no external database writes. */
@Timeout(60)
class FileTransferIntegrationTest {
    @TempDir static Path certificates;
    @TempDir Path temporary;
    static SSLContext serverTls, clientTls;
    String url;
    FileMetadataRepository repository;
    FileStorageService storage;
    FileTransferServer server;
    FileTransferClient client;
    RoomInfo room;
    static final String MIME = "application/octet-stream";

    @BeforeAll static void tls() throws Exception {
        String password = "test-transfer-only";
        Path keys = certificates.resolve("server.p12"), cert = certificates.resolve("server.cer");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-storetype", "PKCS12", "-keystore", keys.toString(),
                "-storepass", password, "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2", "-noprompt");
        keytool("-exportcert", "-alias", "server", "-keystore", keys.toString(), "-storepass", password, "-rfc", "-file", cert.toString());
        serverTls = TlsSupport.server(keys, password.toCharArray()); clientTls = TlsSupport.client(cert);
    }
    @BeforeEach void setup() throws Exception {
        url = "jdbc:h2:mem:files_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        String schema = Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^\\s*--.*$", "")
                .replaceAll("(?is)CREATE DATABASE.*?;", "").replaceAll("(?is)USE Mini_Meeting_Room;", "").replace("ENGINE = InnoDB", "");
        try (Connection connection = db(); Statement sql = connection.createStatement()) {
            for (String statement : schema.split(";")) if (!statement.isBlank()) sql.execute(statement);
            for (int user = 1; user <= 8; user++) {
                sql.execute("INSERT INTO Nguoi_Dung (Ma_Nguoi_Dung,Ten_Dang_Nhap,Mat_Khau_Bam,Ten_Hien_Thi) VALUES (" + user + ",'user" + user + "','unused','Người " + user + "')");
                String hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token(user).getBytes(StandardCharsets.UTF_8)));
                sql.execute("INSERT INTO Phien_Dang_Nhap (Ma_Nguoi_Dung,Token_Bam,Het_Han_Luc) VALUES (" + user + ",'" + hash + "',TIMESTAMP '2099-01-01 00:00:00')");
            }
        }
        repository = new FileMetadataRepository(this::db); start();
        room = client.createRoom(token(1), "Phòng kiểm thử", "1234", 20);
        for (int i = 2; i <= 6; i++) client.joinRoom(token(i), room.code(), "1234");
    }
    @AfterEach void cleanup() throws Exception {
        if (server != null) server.close();
        try (Connection connection = db(); Statement statement = connection.createStatement()) { statement.execute("DROP ALL OBJECTS"); }
    }
    void start() throws Exception {
        var config = new TransferConfig(true, "127.0.0.1", 0, 65536, 12, 4, 10000,
                temporary.resolve("storage"), 2L << 30, 8L << 30, 10, Duration.ofHours(24), true);
        storage = new FileStorageService(config.storagePath(), config.quotaBytes(), config.maxFileBytes(), 10, config.expiry(), true);
        server = new FileTransferServer(serverTls, config, repository, storage); server.start();
        client = new FileTransferClient("localhost", server.port(), clientTls, 10000);
    }

    @Test void arbitraryTypesUnicodeAndEmptyArePublishedOnceAndDownloadedByRoomPeer() throws Exception {
        int count = 0;
        for (String name : List.of("note.txt", "paper.pdf", "photo.png", "movie.mp4", "archive.zip", "application.exe", "Đồ án 日本語.txt", "empty.bin")) {
            Path source = file(name, name.startsWith("empty") ? 0 : 70000);
            var prepared = prepare(source);
            FileMetadata uploaded = client.upload(token(1), prepared, new Cancellation(), null);
            assertEquals(uploaded.id(), client.upload(token(1), prepared, new Cancellation(), null).id());
            assertEquals(name, uploaded.originalName());
            Path downloaded = temporary.resolve("download-" + name);
            client.download(token(2), uploaded, downloaded, new Cancellation(), null);
            assertEquals(digest(source), digest(downloaded));
            assertFalse(Files.exists(FileTransferClient.partialPath(uploaded, downloaded)));
            count++;
        }
        assertEquals(count, client.listFiles(token(2), room.id(), 0).size());
        assertEquals(count, count("Tin_Nhan")); assertEquals(count, count("Tep_Dinh_Kem"));
        client.leaveRoom(token(1), room.id());
        assertEquals(count, client.listFiles(token(2), room.id(), 0).size(), "Sender leaving must not delete files");
    }

    @Test void fiveUploadsFiveDownloadsAndMixedTransfers() throws Exception {
        Path source = file("concurrent.zip", 1024 * 1024);
        List<FileMetadata> files = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(10)) {
            List<Future<FileMetadata>> uploaded = new ArrayList<>();
            for (int user = 1; user <= 5; user++) {
                int identity = user;
                uploaded.add(pool.submit(() -> client.upload(token(identity), prepare(source), new Cancellation(), null)));
            }
            for (var future : uploaded) files.add(future.get(30, TimeUnit.SECONDS));
            List<Future<Path>> downloads = new ArrayList<>();
            for (int user = 1; user <= 5; user++) {
                int identity = user;
                downloads.add(pool.submit(() -> client.download(token(identity), files.get(identity - 1),
                        temporary.resolve("download" + identity), new Cancellation(), null)));
            }
            for (var future : downloads) assertEquals(digest(source), digest(future.get(30, TimeUnit.SECONDS)));
            List<Future<?>> mixed = new ArrayList<>();
            for (int user = 1; user <= 5; user++) {
                int identity = user;
                mixed.add(pool.submit(() -> client.upload(token(identity), prepare(source), new Cancellation(), null)));
                mixed.add(pool.submit(() -> client.download(token(identity), files.get(identity - 1),
                        temporary.resolve("mixed" + identity), new Cancellation(), null)));
            }
            for (var future : mixed) future.get(30, TimeUnit.SECONDS);
        }
        assertEquals(10, client.listFiles(token(6), room.id(), 0).size());
    }

    @Test void authorizationInvalidSessionsOwnershipAndLostPermission() throws Exception {
        var upload = prepare(file("private.txt", 100000));
        var metadata = client.upload(token(1), upload, new Cancellation(), null);
        client.createRoom(token(7), "Phòng khác", "", 10);
        assertCode("PERMISSION_DENIED", () -> client.download(token(7), metadata, temporary.resolve("forbidden"), new Cancellation(), null));
        assertCode("PERMISSION_DENIED", () -> client.listFiles(token(7), room.id(), 0));
        assertCode("INVALID_SESSION", () -> client.listFiles("invalid-token", room.id(), 0));
        assertCode("PERMISSION_DENIED", () -> client.upload(token(2), upload, new Cancellation(), null));
        try (Raw wire = raw()) {
            var pending = prepare(file("revoked.zip", 100));
            wire.send(Type.UPLOAD_INIT, pending.transferId(), uploadHeader(pending, token(1)), new byte[0]);
            assertEquals(Type.UPLOAD_ACCEPT, wire.read().type());
            client.leaveRoom(token(1), room.id());
            wire.send(Type.UPLOAD_CHUNK, pending.transferId(), Map.of("offset", 0), Files.readAllBytes(pending.source()));
            wire.send(Type.UPLOAD_COMPLETE, pending.transferId(), Map.of(), new byte[0]);
            assertEquals("PERMISSION_DENIED", wire.read().header().get("code").getAsString());
        }
        assertEquals(1, count("Tep_Dinh_Kem"));
    }

    @Test void interruptedUploadResumesAcrossServerRestartAndUsesServerOffset() throws Exception {
        var upload = prepare(file("restart.zip", 500000));
        interruptedUpload(upload);
        assertEquals(65536, storage.offset(storage.get(upload.transferId(), 1)));
        server.close(); server = null; start();
        assertEquals(65536, storage.offset(storage.get(upload.transferId(), 1)));
        FileMetadata file = client.upload(token(1), upload, new Cancellation(), null);
        assertEquals(upload.checksum(), file.checksum());
        client.download(token(2), file, temporary.resolve("restart.download"), new Cancellation(), null);
        assertEquals(digest(upload.source()), digest(temporary.resolve("restart.download")));
    }

    @Test void interruptedDownloadResumesAndRejectsCorruptPrefix() throws Exception {
        var source = file("download.zip", 300000);
        var file = client.upload(token(1), prepare(source), new Cancellation(), null);
        Path target = temporary.resolve("resumed.zip"), part = FileTransferClient.partialPath(file, target);
        try (Raw wire = raw()) {
            UUID id = UUID.randomUUID();
            wire.send(Type.DOWNLOAD_REQUEST, id, Map.of("token", token(2), "fileId", file.id(), "offset", 0, "checksum", file.checksum()), new byte[0]);
            assertEquals(Type.DOWNLOAD_ACCEPT, wire.read().type());
            Frame chunk = wire.read(); assertEquals(Type.DOWNLOAD_CHUNK, chunk.type()); Files.write(part, chunk.payload());
        }
        assertEquals(65536, Files.size(part));
        client.download(token(2), file, target, new Cancellation(), null);
        assertEquals(digest(source), digest(target));
        Files.write(part, new byte[]{99, 88, 77});
        assertCode("PREFIX_MISMATCH", () -> client.download(token(2), file, target, new Cancellation(), null));
        assertEquals(digest(source), digest(target), "Failed resume must preserve existing destination");
        client.deletePartialDownload(file, target); assertFalse(Files.exists(part));
    }

    @Test void databaseFailureAfterPromotionCanRetryWithoutDuplicateMessages() throws Exception {
        var upload = prepare(file("db-failure.zip", 1000));
        sql("ALTER TABLE Tep_Dinh_Kem ADD CONSTRAINT simulate_failure CHECK (Ten_Tep_Goc <> 'db-failure.zip')");
        assertCode("DATABASE_UNAVAILABLE", () -> client.upload(token(1), upload, new Cancellation(), null));
        assertEquals(0, count("Tin_Nhan")); assertEquals(0, count("Tep_Dinh_Kem"));
        assertTrue(Files.isRegularFile(storage.resolve(storage.get(upload.transferId(), 1).storageKey())));
        sql("ALTER TABLE Tep_Dinh_Kem DROP CONSTRAINT simulate_failure");
        server.close(); server = null; start();
        FileMetadata file = client.upload(token(1), upload, new Cancellation(), null);
        assertEquals(file.id(), client.upload(token(1), upload, new Cancellation(), null).id());
        assertEquals(1, count("Tin_Nhan")); assertEquals(1, count("Tep_Dinh_Kem"));
    }

    @Test void checksumTraversalOversizeAndInvalidFramesDoNotCrashServer() throws Exception {
        var valid = prepare(file("checksum.txt", 1000));
        var corrupt = new FileTransferClient.Upload(valid.transferId(), valid.roomId(), valid.source(), valid.originalName(),
                valid.mimeType(), valid.sizeBytes(), valid.modifiedMillis(), "0".repeat(64));
        assertCode("CHECKSUM_MISMATCH", () -> client.upload(token(1), corrupt, new Cancellation(), null));
        try (Raw wire = raw()) {
            var header = uploadHeader(valid, token(1)); header.addProperty("originalName", "../../evil.exe");
            wire.send(Type.UPLOAD_INIT, UUID.randomUUID(), header, new byte[0]);
            assertEquals("INVALID_METADATA", wire.read().header().get("code").getAsString());
        }
        try (Raw wire = raw()) {
            var header = uploadHeader(valid, token(1)); header.addProperty("sizeBytes", 3L << 30);
            wire.send(Type.UPLOAD_INIT, UUID.randomUUID(), header, new byte[0]);
            assertEquals("FILE_TOO_LARGE", wire.read().header().get("code").getAsString());
        }
        try (Raw wire = raw()) {
            wire.out.writeInt(TransferProtocol.MAGIC); wire.out.writeShort(1); wire.out.writeShort(3);
            wire.out.writeInt(2); wire.out.writeInt(Integer.MAX_VALUE); wire.out.flush();
            assertEquals("INVALID_FRAME", wire.read().header().get("code").getAsString());
        }
        assertTrue(client.listFiles(token(2), room.id(), 0).isEmpty());
        client.upload(token(1), prepare(valid.source()), new Cancellation(), null);
        assertEquals(1, count("Tep_Dinh_Kem"));
    }

    @Test void cancelSourceMutationAndDisconnectAreIsolated() throws Exception {
        var upload = prepare(file("cancel.zip", 200000)); interruptedUpload(upload);
        assertCode("PERMISSION_DENIED", () -> client.cancelUpload(token(2), upload.transferId()));
        client.cancelUpload(token(1), upload.transferId());
        assertEquals(0, storage.accountedBytes());
        var changed = prepare(file("changed.zip", 100)); Files.write(changed.source(), new byte[101]);
        assertCode("SOURCE_CHANGED", () -> client.upload(token(1), changed, new Cancellation(), null));
        client.upload(token(3), prepare(file("unaffected.zip", 70000)), new Cancellation(), null);
        assertEquals(1, client.listFiles(token(4), room.id(), 0).size());
    }

    @Test void recoversCommittedManifestAndRejectsMismatchedTransferFrames() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        UUID id = UUID.randomUUID(); String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var session = storage.begin(id, 1, room.id(), "committed.txt", MIME, bytes.length, checksum);
        try (OutputStream output = storage.append(session)) { output.write(bytes); }
        String key = storage.verifyAndPromote(session);
        FileMetadata metadata = repository.complete(id, 1, room.id(), "committed.txt", MIME, bytes.length, checksum, key);
        server.close(); server = null; start();
        assertEquals(1, storage.pendingSessions().size());
        try (Raw wire = raw()) {
            wire.send(Type.TRANSFER_STATUS, id, Map.of("token", token(1)), new byte[0]);
            Frame status = wire.read(); assertEquals(Type.UPLOAD_COMPLETE, status.type());
            assertEquals(metadata.id(), status.header().get("id").getAsLong());
        }
        assertTrue(storage.pendingSessions().isEmpty());
        var upload = prepare(file("bad-frame.bin", 100));
        try (Raw wire = raw()) {
            wire.send(Type.UPLOAD_INIT, upload.transferId(), uploadHeader(upload, token(1)), new byte[0]);
            assertEquals(Type.UPLOAD_ACCEPT, wire.read().type());
            wire.send(Type.UPLOAD_CHUNK, UUID.randomUUID(), Map.of("offset", 0), bytes);
            assertEquals("INVALID_TRANSFER", wire.read().header().get("code").getAsString());
        }
        assertEquals(1, client.listFiles(token(2), room.id(), 0).size());
    }

    @Test void retentionOnlyPurgesClosedRoomsAndRetriesFailedObjectDeletion() throws Exception {
        var old = client.upload(token(1), prepare(file("retired.txt", 300)), new Cancellation(), null);
        RoomInfo active = client.createRoom(token(2), "Active room", "", 10);
        var pending = client.prepareUpload(active.id(), file("keep.txt", 200), UUID.randomUUID(), new Cancellation(), null);
        var kept = client.upload(token(2), pending, new Cancellation(), null);
        sql("UPDATE Phong_Hop SET Trang_Thai='DA_DONG', Ngay_Dong=TIMESTAMP '2020-01-01 00:00:00' WHERE Ma_Phong=" + room.id());
        String key = repository.storageKey(old.id());
        TransferStorage failedDisk = (TransferStorage) java.lang.reflect.Proxy.newProxyInstance(TransferStorage.class.getClassLoader(),
                new Class<?>[]{TransferStorage.class}, (proxy, method, args) -> {
                    if (method.getName().equals("deleteStoredFile")) throw new IOException("Simulated disk failure");
                    return method.invoke(storage, args);
                });
        var cutoff = java.time.Instant.now().minus(Duration.ofDays(30));
        new FileRetentionService(repository, failedDisk).sweep(cutoff);
        assertEquals(2, count("Tep_Dinh_Kem")); assertTrue(Files.exists(storage.resolve(key)));
        assertCode("FILE_NOT_FOUND", () -> repository.find(1, old.id()));
        new FileRetentionService(repository, storage).sweep(cutoff);
        assertEquals(1, count("Tep_Dinh_Kem")); assertFalse(Files.exists(storage.resolve(key)));
        assertEquals(kept.id(), repository.find(2, kept.id()).id());
        assertEquals(200, storage.accountedBytes());
    }

    @Test @Timeout(600) void measuredStreamingSizes() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("mmr.performance"), "Opt-in: -Dmmr.performance=true");
        var operatingSystem = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        List<String> rows = new ArrayList<>(); rows.add("sizeMiB,uploadSeconds,uploadMiBPerSec,downloadSeconds,downloadMiBPerSec,processCpuMillis,peakHeapMiB");
        for (int mib : new int[]{1, 10, 100, 500, 1024}) {
            Path source = temporary.resolve("large-" + mib + ".bin");
            try (FileChannel channel = FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.position(mib * 1024L * 1024 - 1); channel.write(ByteBuffer.wrap(new byte[]{0}));
            }
            for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
            long cpu = operatingSystem.getProcessCpuTime();
            var upload = prepare(source); long started = System.nanoTime();
            var file = client.upload(token(1), upload, new Cancellation(), null);
            double up = (System.nanoTime() - started) / 1e9;
            started = System.nanoTime(); Path destination = temporary.resolve("large-download.bin");
            client.download(token(2), file, destination, new Cancellation(), null);
            double down = (System.nanoTime() - started) / 1e9;
            assertEquals(upload.checksum(), digest(destination));
            double heap = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum() / 1048576.0;
            rows.add(String.format(Locale.ROOT, "%d,%.3f,%.2f,%.3f,%.2f,%.1f,%.1f", mib, up, mib / up, down, mib / down,
                    (operatingSystem.getProcessCpuTime() - cpu) / 1e6, heap));
            Files.delete(source); Files.delete(destination);
        }
        Files.createDirectories(Path.of("target")); Files.write(Path.of("target/file-transfer-performance.csv"), rows);
        rows.forEach(System.out::println);
    }

    private void interruptedUpload(FileTransferClient.Upload upload) throws Exception {
        try (Raw wire = raw(); InputStream source = Files.newInputStream(upload.source())) {
            wire.send(Type.UPLOAD_INIT, upload.transferId(), uploadHeader(upload, token(1)), new byte[0]);
            assertEquals(Type.UPLOAD_ACCEPT, wire.read().type());
            wire.send(Type.UPLOAD_CHUNK, upload.transferId(), Map.of("offset", 0), source.readNBytes(65536));
        }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            var session = storage.get(upload.transferId(), 1);
            if (session.lock().tryLock()) {
                try { if (storage.offset(session) == 65536) return; }
                finally { session.lock().unlock(); }
            }
            Thread.sleep(20);
        }
        fail("Disconnected upload did not settle");
    }
    private FileTransferClient.Upload prepare(Path file) throws IOException {
        return client.prepareUpload(room.id(), file, UUID.randomUUID(), new Cancellation(), null);
    }
    private Path file(String name, int size) throws IOException {
        byte[] data = new byte[size]; new Random(123).nextBytes(data); return Files.write(temporary.resolve(name), data);
    }
    private static com.google.gson.JsonObject uploadHeader(FileTransferClient.Upload upload, String token) {
        return TransferProtocol.json(Map.of("token", token, "roomId", upload.roomId(), "originalName", upload.originalName(),
                "mimeType", upload.mimeType(), "sizeBytes", upload.sizeBytes(), "checksum", upload.checksum()));
    }
    private static String digest(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = input.read(b)) >= 0) digest.update(b, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String token(int user) { return "integration-user-token-" + user; }
    private Connection db() throws SQLException { return DriverManager.getConnection(url); }
    private void sql(String sql) throws SQLException { try (Connection c = db(); Statement s = c.createStatement()) { s.execute(sql); } }
    private int count(String table) throws SQLException {
        try (Connection c = db(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); return r.getInt(1); }
    }
    private static void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(TransferException.class, action).code());
    }
    private Raw raw() throws IOException { return new Raw((SSLSocket) clientTls.getSocketFactory().createSocket("localhost", server.port())); }
    private static class Raw implements AutoCloseable {
        final SSLSocket socket; final DataInputStream in; final DataOutputStream out;
        Raw(SSLSocket socket) throws IOException { this.socket = socket; socket.setSoTimeout(10000); socket.startHandshake(); in = new DataInputStream(socket.getInputStream()); out = new DataOutputStream(socket.getOutputStream()); }
        void send(Type type, UUID id, Object header, byte[] payload) throws IOException { TransferProtocol.write(out, new Frame(type, id, TransferProtocol.json(header), payload)); }
        Frame read() throws IOException { return TransferProtocol.read(in); }
        public void close() throws IOException { socket.close(); }
    }
    private static void keytool(String... args) throws Exception {
        Path tool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        List<String> command = new ArrayList<>(); command.add(tool.toString()); command.addAll(List.of(args));
        Path log = Files.createTempFile(certificates, "keytool", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("TLS fixture keytool timeout"); }
        assertEquals(0, process.exitValue(), "TLS fixture keytool failed");
    }
}
