package client.filetransfer;

import com.google.gson.JsonObject;
import common.auth.TlsSupport;
import common.filetransfer.FileMetadata;
import common.filetransfer.RoomInfo;
import common.filetransfer.TransferException;
import common.filetransfer.TransferProtocol;
import common.filetransfer.TransferProtocol.Frame;
import common.filetransfer.TransferProtocol.Type;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/** Blocking TLS/TCP API. All calls, including source hashing, belong on a background worker. */
public final class FileTransferClient implements AutoCloseable {
    public record Upload(UUID transferId, long roomId, Path source, String originalName, String mimeType,
            long sizeBytes, long modifiedMillis, String checksum,String roomIdentity) {
        public Upload(UUID transferId,long roomId,Path source,String originalName,String mimeType,long sizeBytes,long modifiedMillis,String checksum) {this(transferId,roomId,source,originalName,mimeType,sizeBytes,modifiedMillis,checksum,null);}
        public Upload inMeeting(String identity) {return new Upload(transferId,roomId,source,originalName,mimeType,sizeBytes,modifiedMillis,checksum,identity);}
    }
    public record Progress(String status, long transferred, long total, double bytesPerSecond) { }
    @FunctionalInterface public interface ProgressListener { void onProgress(Progress progress); }
    private static final ProgressListener SILENT = progress -> { };
    private final Settings fixedSettings;
    private final SSLContext fixedTls;
    private final Set<Cancellation> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /** Closing the active socket interrupts both reads and blocked writes, preserving resume data. */
    public static final class Cancellation implements AutoCloseable {
        private boolean cancelled;
        private Socket socket;
        public synchronized boolean isCancelled() { return cancelled; }
        private synchronized void bind(Socket value) throws IOException {
            if (cancelled) { value.close(); throw new TransferException("CANCELLED", "Đã dừng truyền tệp."); }
            socket = value;
        }
        private synchronized void unbind(Socket value) { if (socket == value) socket = null; }
        public synchronized void cancel() {
            cancelled = true;
            if (socket != null) try { socket.close(); } catch (IOException error) {
                System.getLogger(FileTransferClient.class.getName()).log(System.Logger.Level.DEBUG, "Socket close failed", error);
            }
        }
        private void check() throws IOException {
            if (isCancelled() || Thread.currentThread().isInterrupted()) throw new TransferException("CANCELLED", "Đã dừng truyền tệp.");
        }
        @Override public void close() { cancel(); }
    }

    public FileTransferClient() { fixedSettings = null; fixedTls = null; }
    public FileTransferClient(String host, int port, SSLContext tls, int timeoutMillis) {
        fixedSettings = new Settings(host, port, null, timeoutMillis); fixedTls = Objects.requireNonNull(tls);
    }
    public RoomInfo createRoom(String token, String name, String pin, int capacity) throws IOException {
        JsonObject header = authenticated(token); header.addProperty("name", name); header.addProperty("pin", pin); header.addProperty("capacity", capacity);
        return TransferProtocol.GSON.fromJson(request(Type.ROOM_CREATE, UUID.randomUUID(), header, Type.ROOM_RESULT).header(), RoomInfo.class);
    }
    public RoomInfo joinRoom(String token, String code, String pin) throws IOException {
        JsonObject header = authenticated(token); header.addProperty("code", code); header.addProperty("pin", pin);
        return TransferProtocol.GSON.fromJson(request(Type.ROOM_JOIN, UUID.randomUUID(), header, Type.ROOM_RESULT).header(), RoomInfo.class);
    }
    public void leaveRoom(String token, long roomId) throws IOException {
        leaveRoom(token, roomId, new Cancellation());
    }
    public void leaveRoom(String token, long roomId, Cancellation cancellation) throws IOException {
        JsonObject header = authenticated(token); header.addProperty("roomId", roomId);
        UUID id = UUID.randomUUID();
        try (Connection connection = connect(cancellation)) {
            connection.send(Type.ROOM_LEAVE, id, header, new byte[0]); require(connection.receive(id), Type.ROOM_RESULT);
        }
    }
    public List<FileMetadata> listFiles(String token, long roomId, long afterId) throws IOException {
        return listFiles(token, roomId, afterId, new Cancellation());
    }
    public List<FileMetadata> listFiles(String token, long roomId, long afterId, Cancellation cancellation) throws IOException {
        JsonObject header = authenticated(token); header.addProperty("roomId", roomId); header.addProperty("afterId", afterId);
        Frame result;
        UUID id = UUID.randomUUID();
        try (Connection connection = connect(cancellation)) {
            connection.send(Type.FILE_LIST, id, header, new byte[0]); result = connection.receive(id); require(result, Type.FILE_AVAILABLE);
        }
        if (!result.header().has("files") || !result.header().get("files").isJsonArray()) throw invalid();
        return List.of(TransferProtocol.GSON.fromJson(result.header().get("files"), FileMetadata[].class));
    }
    public Upload prepareUpload(long roomId, Path source, UUID transferId, Cancellation cancellation,
            ProgressListener listener) throws IOException {
        Path path = source.toAbsolutePath().normalize();
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink()) throw new TransferException("INVALID_FILE", "Chọn tệp thông thường, không phải liên kết.");
        ProgressListener progress = listener == null ? SILENT : listener;
        progress.onProgress(new Progress("Đang kiểm tra SHA-256", 0, before.size(), 0));
        String checksum = hash(path, before.size(), cancellation);
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())) throw changed();
        String mime = Files.probeContentType(path);
        return new Upload(transferId, roomId, path, path.getFileName().toString(), mime == null ? "application/octet-stream" : mime,
                before.size(), before.lastModifiedTime().toMillis(), checksum);
    }
    public FileMetadata upload(String token, Upload upload, Cancellation cancellation, ProgressListener listener) throws IOException {
        checkSource(upload); cancellation.check();
        JsonObject header = authenticated(token); header.addProperty("roomId", upload.roomId());
        header.addProperty("originalName", upload.originalName()); header.addProperty("mimeType", upload.mimeType());
        header.addProperty("sizeBytes", upload.sizeBytes()); header.addProperty("checksum", upload.checksum());
        if(upload.roomIdentity()!=null) header.addProperty("roomIdentity",upload.roomIdentity());
        try (Connection connection = connect(cancellation)) {
            connection.send(Type.UPLOAD_INIT, upload.transferId(), header, new byte[0]);
            Frame accepted = connection.receive(upload.transferId());
            if (accepted.type() == Type.UPLOAD_COMPLETE) return metadata(accepted);
            require(accepted, Type.UPLOAD_ACCEPT);
            long offset = number(accepted.header(), "offset"); int chunk = chunkSize(accepted.header());
            if (offset < 0 || offset > upload.sizeBytes()) throw invalid();
            Reporter reporter = new Reporter(listener, "Đang gửi", upload.sizeBytes(), offset);
            reporter.report(offset, true);
            try (FileChannel source = FileChannel.open(upload.source(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                source.position(offset); byte[] bytes = new byte[chunk];
                while (offset < upload.sizeBytes()) {
                    cancellation.check();
                    int count = source.read(ByteBuffer.wrap(bytes, 0, (int) Math.min(bytes.length, upload.sizeBytes() - offset)));
                    if (count < 0) throw changed();
                    if (count == 0) continue;
                    JsonObject part = new JsonObject(); part.addProperty("offset", offset);
                    connection.send(Type.UPLOAD_CHUNK, upload.transferId(), part, count == bytes.length ? bytes : Arrays.copyOf(bytes, count));
                    offset += count; reporter.report(offset, false);
                }
            }
            checkSource(upload); cancellation.check();
            reporter.status("Đang xác minh trên máy chủ", offset);
            connection.send(Type.UPLOAD_COMPLETE, upload.transferId(), new JsonObject(), new byte[0]);
            Frame complete = connection.receive(upload.transferId()); require(complete, Type.UPLOAD_COMPLETE);
            FileMetadata file = metadata(complete);
            if (file.sizeBytes() != upload.sizeBytes() || !file.checksum().equals(upload.checksum())) throw invalid();
            reporter.status("Gửi thành công", offset); return file;
        }
    }
    public Path download(String token, FileMetadata file, Path destination, Cancellation cancellation,
            ProgressListener listener) throws IOException {
        validateFile(file); cancellation.check();
        Path target = destination.toAbsolutePath().normalize(); Path part = partialPath(file, target);
        if (!Files.isDirectory(target.getParent()) || Files.isSymbolicLink(target)) throw new TransferException("INVALID_PATH", "Nơi lưu tệp không hợp lệ.");
        long offset;
        try (FileChannel output = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = output.tryLock()) {
            if (lock == null) throw new TransferException("BUSY", "Tệp này đang được tải ở tác vụ khác.");
            offset = output.size();
            if (offset > file.sizeBytes()) throw new TransferException("INVALID_PARTIAL", "Tệp tải dở không hợp lệ. Hủy tác vụ và tải lại.");
            Reporter reporter = new Reporter(listener, "Đang tải", file.sizeBytes(), offset);
            reporter.status("Đang kiểm tra dữ liệu tải dở", offset);
            String prefix = offset == 0 ? "" : hash(output, offset, cancellation);
            JsonObject header = authenticated(token); header.addProperty("fileId", file.id()); header.addProperty("offset", offset);
            header.addProperty("checksum", file.checksum()); header.addProperty("prefixChecksum", prefix);
            UUID requestId = UUID.randomUUID();
            try (Connection connection = connect(cancellation)) {
                connection.send(Type.DOWNLOAD_REQUEST, requestId, header, new byte[0]);
                Frame accepted = connection.receive(requestId); require(accepted, Type.DOWNLOAD_ACCEPT);
                FileMetadata current = TransferProtocol.GSON.fromJson(accepted.header().get("file"), FileMetadata.class);
                validateFile(current);
                int chunk = chunkSize(accepted.header());
                if (current.id() != file.id() || current.sizeBytes() != file.sizeBytes() || !current.checksum().equals(file.checksum())
                        || number(accepted.header(), "offset") != offset) throw invalid();
                output.position(offset); reporter.report(offset, true);
                while (true) {
                    cancellation.check(); Frame frame = connection.receive(requestId);
                    if (frame.type() == Type.DOWNLOAD_COMPLETE) {
                        FileMetadata completed = metadata(frame);
                        if (offset != file.sizeBytes() || completed.id() != file.id() || !completed.checksum().equals(file.checksum())) throw invalid();
                        break;
                    }
                    require(frame, Type.DOWNLOAD_CHUNK);
                    if (number(frame.header(), "offset") != offset || frame.payload().length == 0
                            || frame.payload().length > chunk || frame.payload().length > file.sizeBytes() - offset) throw invalid();
                    ByteBuffer buffer = ByteBuffer.wrap(frame.payload()); while (buffer.hasRemaining()) output.write(buffer);
                    offset += frame.payload().length; reporter.report(offset, false);
                }
            }
            output.force(true); reporter.status("Đang xác minh SHA-256", offset);
            if (!hash(output, file.sizeBytes(), cancellation).equals(file.checksum())) throw new TransferException("CHECKSUM_MISMATCH", "SHA-256 không khớp. Hủy tác vụ và tải lại.");
            cancellation.check();
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new TransferException("BUSY", "Tệp này đang được tải ở tác vụ khác.");
        }
        try { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(part, target, StandardCopyOption.REPLACE_EXISTING); }
        if (listener != null) listener.onProgress(new Progress("Tải thành công", file.sizeBytes(), file.sizeBytes(), 0));
        return target;
    }
    /** Returns the file when completion won a concurrent cancellation; an available file is never deleted. */
    public FileMetadata cancelUpload(String token, UUID transferId) throws IOException {
        try (Connection connection = connect(new Cancellation())) {
            connection.send(Type.TRANSFER_CANCEL, transferId, authenticated(token), new byte[0]);
            Frame result = connection.receive(transferId);
            if (result.type() == Type.UPLOAD_COMPLETE) return metadata(result);
            require(result, Type.TRANSFER_STATUS); return null;
        } catch (TransferException error) {
            if ("TRANSFER_NOT_FOUND".equals(error.code())) return null;
            throw error;
        }
    }
    public void deletePartialDownload(FileMetadata file, Path destination) throws IOException {
        Path part = partialPath(file, destination.toAbsolutePath().normalize());
        if (Files.isSymbolicLink(part)) throw new TransferException("INVALID_PATH", "Không xóa liên kết tượng trưng.");
        if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) return;
        try (FileChannel channel = FileChannel.open(part, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock = channel.tryLock()) {
            if (lock == null) throw new TransferException("BUSY", "Tệp này đang được tải ở tác vụ khác.");
            // Closing happens before delete on Windows, which disallows deleting the open handle.
        } catch (java.nio.channels.OverlappingFileLockException busy) { throw new TransferException("BUSY", "Tệp này đang được tải ở tác vụ khác."); }
        Files.deleteIfExists(part);
    }
    public static Path partialPath(FileMetadata file, Path destination) throws IOException {
        validateFile(file);
        return destination.resolveSibling(".mmr-" + file.id() + "-" + file.checksum().substring(0, 16) + ".part");
    }
    private Frame request(Type type, UUID id, JsonObject header, Type expected) throws IOException {
        try (Connection connection = connect(new Cancellation())) {
            connection.send(type, id, header, new byte[0]); Frame frame = connection.receive(id); require(frame, expected); return frame;
        }
    }
    private Connection connect(Cancellation cancellation) throws IOException {
        if (closed) throw new TransferException("CLOSED", "Kết nối truyền tệp đã đóng.");
        Settings settings = fixedSettings == null ? Settings.load() : fixedSettings;
        SSLContext tls = fixedTls;
        if (tls == null) try { tls = TlsSupport.client(settings.certificate()); }
        catch (GeneralSecurityException error) { throw new IOException("Không đọc được chứng chỉ TLS của máy chủ.", error); }
        Socket plain = new Socket(); active.add(cancellation);
        try {
            cancellation.bind(plain);
            if (closed) { cancellation.cancel(); cancellation.check(); }
            plain.connect(new InetSocketAddress(settings.host(), settings.port()), Math.min(settings.timeoutMillis(), 5000));
            SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(plain, settings.host(), settings.port(), true);
            cancellation.bind(socket); socket.setSoTimeout(settings.timeoutMillis()); socket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            SSLParameters parameters = socket.getSSLParameters(); parameters.setEndpointIdentificationAlgorithm("HTTPS"); socket.setSSLParameters(parameters);
            socket.startHandshake(); return new Connection(socket, cancellation);
        } catch (IOException | RuntimeException failure) { active.remove(cancellation); plain.close(); cancellation.unbind(plain); throw failure; }
    }
    private final class Connection implements AutoCloseable {
        private final SSLSocket socket; private final Cancellation cancellation;
        private final DataInputStream input; private final DataOutputStream output;
        Connection(SSLSocket socket, Cancellation cancellation) throws IOException {
            this.socket = socket; this.cancellation = cancellation;
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
        }
        void send(Type type, UUID id, JsonObject header, byte[] payload) throws IOException {
            cancellation.check(); TransferProtocol.write(output, new Frame(type, id, header, payload)); output.flush();
        }
        Frame receive(UUID id) throws IOException {
            Frame frame = TransferProtocol.read(input);
            if (!id.equals(frame.transferId())) throw invalid();
            if (frame.type() == Type.ERROR || frame.type() == Type.UPLOAD_FAILED || frame.type() == Type.DOWNLOAD_FAILED) {
                String code = string(frame.header(), "code"), message = string(frame.header(), "message");
                throw new TransferException(code, message);
            }
            return frame;
        }
        @Override public void close() throws IOException { active.remove(cancellation); cancellation.unbind(socket); socket.close(); }
    }
    @Override public void close() { closed = true; active.forEach(Cancellation::cancel); active.clear(); }
    private static JsonObject authenticated(String token) throws IOException {
        if (token == null || token.isBlank()) throw new TransferException("INVALID_SESSION", "Vui lòng đăng nhập lại.");
        JsonObject header = new JsonObject(); header.addProperty("token", token); return header;
    }
    private static void require(Frame frame, Type expected) throws IOException { if (frame.type() != expected || (expected != Type.DOWNLOAD_CHUNK && frame.payload().length != 0)) throw invalid(); }
    private static FileMetadata metadata(Frame frame) throws IOException {
        FileMetadata result = TransferProtocol.GSON.fromJson(frame.header(), FileMetadata.class); validateFile(result); return result;
    }
    private static void validateFile(FileMetadata file) throws IOException {
        if (file == null || file.id() <= 0 || file.sizeBytes() < 0 || file.checksum() == null || !file.checksum().matches("[0-9a-f]{64}")) throw invalid();
    }
    private static long number(JsonObject header, String key) throws IOException {
        try { return header.get(key).getAsLong(); } catch (RuntimeException error) { throw invalid(); }
    }
    private static String string(JsonObject header, String key) throws IOException {
        try { return header.get(key).getAsString(); } catch (RuntimeException error) { throw invalid(); }
    }
    private static int chunkSize(JsonObject header) throws IOException {
        long value = number(header, "chunkSize"); if (value < 1 || value > TransferProtocol.MAX_PAYLOAD) throw invalid(); return (int) value;
    }
    private static void checkSource(Upload upload) throws IOException {
        BasicFileAttributes current = Files.readAttributes(upload.source(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!current.isRegularFile() || current.isSymbolicLink() || current.size() != upload.sizeBytes()
                || current.lastModifiedTime().toMillis() != upload.modifiedMillis()) throw changed();
    }
    private static String hash(Path path, long length, Cancellation cancellation) throws IOException {
        try (FileChannel source = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return hash(source, length, cancellation);
        }
    }
    private static String hash(FileChannel source, long length, Cancellation cancellation) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        ByteBuffer buffer = ByteBuffer.allocate(65536); long remaining = length; source.position(0);
        while (remaining > 0) {
            cancellation.check(); buffer.clear(); buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int count = source.read(buffer);
            if (count < 0) throw changed(); if (count == 0) continue;
            buffer.flip(); digest.update(buffer); remaining -= count;
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static TransferException invalid() { return new TransferException("INVALID_RESPONSE", "Phản hồi truyền tệp từ máy chủ không hợp lệ."); }
    private static TransferException changed() { return new TransferException("SOURCE_CHANGED", "Tệp nguồn đã thay đổi. Hãy hủy tác vụ và chọn lại tệp."); }
    private static final class Reporter {
        private final ProgressListener listener; private final String status; private final long total, initial, start = System.nanoTime(); private long last;
        Reporter(ProgressListener listener, String status, long total, long initial) { this.listener = listener == null ? SILENT : listener; this.status = status; this.total = total; this.initial = initial; }
        void report(long sent, boolean force) { long now = System.nanoTime(); if (force || now - last >= 100_000_000L) { last = now; status(status, sent); } }
        void status(String value, long sent) { double elapsed = Math.max(.001, (System.nanoTime() - start) / 1_000_000_000.0); listener.onProgress(new Progress(value, sent, total, Math.max(0, sent - initial) / elapsed)); }
    }
    private record Settings(String host, int port, Path certificate, int timeoutMillis) {
        static Settings load() throws IOException {
            Properties properties = new Properties(); Path config = Path.of(System.getProperty("mmr.client.config", "config/auth-client.properties"));
            if (Files.isRegularFile(config)) try (Reader reader = Files.newBufferedReader(config, StandardCharsets.UTF_8)) { properties.load(reader); }
            try {
                String host = value(properties, "file.transfer.host", "MMR_FILE_HOST", value(properties, "auth.host", "MMR_AUTH_HOST", "localhost"));
                int port = Integer.parseInt(value(properties, "file.transfer.port", "MMR_FILE_PORT", "9001"));
                int timeout = Integer.parseInt(value(properties, "file.transfer.socket-timeout-ms", "MMR_FILE_TIMEOUT_MILLIS", "30000"));
                if (host.isBlank() || port < 1 || port > 65535 || timeout < 1000 || timeout > 300000) throw new IllegalArgumentException();
                return new Settings(host, port, Path.of(value(properties, "auth.certificate", "MMR_AUTH_CERTIFICATE", "config/auth-server.cer")), timeout);
            } catch (IllegalArgumentException error) { throw new IOException("Cấu hình TCP truyền tệp không hợp lệ.", error); }
        }
        private static String value(Properties properties, String key, String env, String fallback) { String value = System.getenv(env); return value == null ? properties.getProperty(key, fallback) : value; }
    }
}
