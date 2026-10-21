package server.filetransfer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.filetransfer.FileMetadata;
import common.filetransfer.TransferException;
import common.filetransfer.TransferProtocol;
import common.filetransfer.TransferProtocol.Frame;
import common.filetransfer.TransferProtocol.Type;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

/** Application operations. No file bytes pass through auth, chat or media. */
public final class FileTransferService {
    private static final Logger LOG = Logger.getLogger(FileTransferService.class.getName());
    private final FileMetadataRepository repository;
    private final TransferStorage storage;
    private final TransferConfig config;
    private final Map<Long, Integer> userCounts = new ConcurrentHashMap<>();
    private final Semaphore slots;

    public FileTransferService(FileMetadataRepository repository, TransferStorage storage, TransferConfig config) {
        this.repository = repository; this.storage = storage; this.config = config;
        slots = new Semaphore(config.maxConcurrent());
    }

    public void handle(Frame request, DataInputStream input, DataOutputStream output, Runnable activity)
            throws IOException, SQLException {
        String token = string(request.header(), "token", 256);
        long user = repository.authenticate(token);
        if (request.type() == Type.UPLOAD_INIT || request.type() == Type.DOWNLOAD_REQUEST) {
            if (!slots.tryAcquire()) throw failure("SERVER_BUSY", "Máy chủ đang có nhiều lượt truyền. Hãy thử lại.");
            boolean admitted = false;
            try {
                // compute locks only this user's counter, never a streaming operation.
                int count = userCounts.compute(user, (key, previous) -> previous == null ? 1 : previous + 1);
                admitted = true;
                if (count > config.maxPerUser()) throw failure("USER_BUSY", "Đã đạt giới hạn truyền đồng thời.");
                if (request.type() == Type.UPLOAD_INIT) upload(request, user, token, input, output, activity);
                else download(request, user, token, output, activity);
            } finally {
                if (admitted) userCounts.computeIfPresent(user, (key, count) -> count <= 1 ? null : count - 1);
                slots.release();
            }
            return;
        }
        switch (request.type()) {
            case ROOM_CREATE -> send(output, Type.ROOM_RESULT, request, repository.createRoom(user,
                    string(request.header(), "name", 100), optional(request.header(), "pin", 128),
                    Math.toIntExact(number(request.header(), "capacity"))));
            case ROOM_JOIN -> send(output, Type.ROOM_RESULT, request, repository.joinRoom(user,
                    string(request.header(), "code", 20), optional(request.header(), "pin", 128)));
            case ROOM_LEAVE -> {
                repository.leaveRoom(user, number(request.header(), "roomId"));
                send(output, Type.ROOM_RESULT, request, Map.of());
            }
            case FILE_LIST -> {
                long after = number(request.header(), "afterId");
                if (after < 0) throw failure("INVALID_REQUEST", "Mốc danh sách không hợp lệ.");
                JsonObject header = new JsonObject(); JsonArray files = new JsonArray(); header.add("files", files);
                for (FileMetadata file : repository.list(user, number(request.header(), "roomId"), after)) {
                    files.add(TransferProtocol.GSON.toJsonTree(file));
                    if (TransferProtocol.GSON.toJson(header).getBytes(StandardCharsets.UTF_8).length > TransferProtocol.MAX_HEADER) {
                        files.remove(files.size() - 1); break;
                    }
                }
                send(output, Type.FILE_AVAILABLE, request, header);
            }
            case TRANSFER_STATUS, TRANSFER_CANCEL -> {
                FileMetadata done = repository.findByTransfer(request.transferId());
                if (done != null) {
                    if (done.userId() != user) throw failure("PERMISSION_DENIED", "Không có quyền với lượt truyền này.");
                    repository.authorize(user, done.roomId());
                    reconcileOne(request.transferId(), user);
                    send(output, Type.UPLOAD_COMPLETE, request, done); return;
                }
                var session = storage.get(request.transferId(), user);
                repository.authorize(user, session.roomId());
                if (!session.lock().tryLock()) throw failure("TRANSFER_BUSY", "Lượt truyền đang hoạt động.");
                try {
                    if (request.type() == Type.TRANSFER_CANCEL) {
                        storage.cancel(session);
                        event("TRANSFER_CANCELLED", request, user, session.roomId(), 0);
                        send(output, Type.TRANSFER_STATUS, request, Map.of("status", "CANCELLED", "offset", 0));
                    } else send(output, Type.TRANSFER_STATUS, request,
                            Map.of("status", "PAUSED", "offset", storage.offset(session)));
                } finally { session.lock().unlock(); }
            }
            default -> throw failure("INVALID_REQUEST", "Loại yêu cầu không hợp lệ.");
        }
    }

    private void upload(Frame request, long user, String token, DataInputStream input,
            DataOutputStream output, Runnable activity) throws IOException, SQLException {
        long room = number(request.header(), "roomId");
        repository.authorize(user, room);
        String name = string(request.header(), "originalName", 255);
        String mime = optional(request.header(), "mimeType", 100);
        if (mime.isBlank()) mime = "application/octet-stream";
        String checksum = string(request.header(), "checksum", 64).toLowerCase(Locale.ROOT);
        long size = number(request.header(), "sizeBytes");
        FileMetadata done = repository.findByTransfer(request.transferId());
        if (done != null) {
            if (done.userId() != user || done.roomId() != room) throw failure("PERMISSION_DENIED", "Không có quyền với lượt truyền này.");
            if (!done.originalName().equals(name) || done.sizeBytes() != size || !done.checksum().equals(checksum))
                throw failure("TRANSFER_CONFLICT", "File nguồn không khớp lượt truyền trước.");
            reconcileOne(request.transferId(), user);
            send(output, Type.UPLOAD_COMPLETE, request, done); return;
        }
        final String selectedMime=mime;
        var bound=repository.beginUpload(user,room,optional(request.header(),"roomIdentity",96),token,
                generation -> storage.begin(request.transferId(),user,room,name,selectedMime,size,checksum,generation));
        String generation=bound.generation();var session=bound.session();
        if (!session.lock().tryLock()) throw failure("TRANSFER_BUSY", "Lượt truyền đang hoạt động.");
        long started = System.nanoTime();
        try {
            long offset = storage.offset(session);
            event(offset == 0 ? "UPLOAD_STARTED" : "TRANSFER_RESUMED", request, user, room, size);
            send(output, Type.UPLOAD_ACCEPT, request, Map.of("offset", offset, "chunkSize", config.chunkSize()));
            long lastAuth = System.nanoTime();
            boolean complete = false;
            // A promoted file from a DB failure has no .part and must not be reopened for append.
            try (OutputStream file = offset == size ? OutputStream.nullOutputStream() : storage.append(session)) {
                while (!complete) {
                    Frame frame = TransferProtocol.read(input); activity.run();
                    if (!request.transferId().equals(frame.transferId())) throw failure("INVALID_TRANSFER", "Mã lượt truyền không khớp.");
                    if (System.nanoTime() - lastAuth > 1_000_000_000L) {
                        reauthorize(token, user, room); lastAuth = System.nanoTime();
                    }
                    switch (frame.type()) {
                        case UPLOAD_CHUNK -> {
                            if (number(frame.header(), "offset") != offset || frame.payload().length == 0
                                    || frame.payload().length > config.chunkSize() || frame.payload().length > size - offset)
                                throw failure("INVALID_OFFSET", "Vị trí hoặc kích thước dữ liệu không hợp lệ.");
                            file.write(frame.payload()); offset += frame.payload().length;
                        }
                        case UPLOAD_COMPLETE -> {
                            if (offset != size) throw failure("SIZE_MISMATCH", "Chưa nhận đủ dữ liệu file.");
                            complete = true;
                        }
                        case TRANSFER_CANCEL -> {
                            file.close(); storage.cancel(session);
                            event("TRANSFER_CANCELLED", request, user, room, offset);
                            send(output, Type.TRANSFER_STATUS, request, Map.of("status", "CANCELLED", "offset", 0)); return;
                        }
                        default -> throw failure("INVALID_FRAME", "Khung dữ liệu không hợp lệ trong upload.");
                    }
                }
            }
            reauthorize(token, user, room);
            String key;
            try { key = storage.verifyAndPromote(session, activity); }
            catch (TransferException e) {
                if ("CHECKSUM_MISMATCH".equals(e.code()) || "SIZE_MISMATCH".equals(e.code())) storage.cancel(session);
                throw e;
            }
            activity.run();
            reauthorize(token, user, room);
            // On DB failure keep promoted object + manifest for deterministic retry/recovery.
            FileMetadata metadata = repository.complete(request.transferId(), user, room, name, mime, size, checksum, key,generation,token);
            storage.completed(session);
            send(output, Type.UPLOAD_COMPLETE, request, metadata);
            event("UPLOAD_COMPLETED durationMs=" + (System.nanoTime() - started) / 1_000_000, request, user, room, size);
        } finally {
            if (!config.resumeEnabled()) {
                try { storage.cancel(session); }
                catch (IOException e) { LOG.warning("Temporary cleanup: " + e.getClass().getSimpleName()); }
            }
            session.lock().unlock();
        }
    }

    private void download(Frame request, long user, String token, DataOutputStream output, Runnable activity)
            throws IOException, SQLException {
        FileMetadata file = repository.find(user, number(request.header(), "fileId"));
        if (file.checksum() == null || !file.checksum().matches("[0-9a-f]{64}"))
            throw failure("STORAGE_ERROR", "Tệp cũ chưa có SHA-256 hợp lệ. Cần tải lên lại.");
        long offset = number(request.header(), "offset");
        if (offset < 0 || offset > file.sizeBytes() || (!config.resumeEnabled() && offset != 0))
            throw failure("INVALID_OFFSET", "Vị trí tải tiếp không hợp lệ.");
        if (!file.checksum().equals(string(request.header(), "checksum", 64)))
            throw failure("VERSION_MISMATCH", "Phiên bản file không khớp.");
        String key = repository.storageKey(file.id());
        if (storage.size(key) != file.sizeBytes())
            throw failure("STORAGE_ERROR", "File lưu trữ không khả dụng.");
        byte[] buffer = new byte[config.chunkSize()];
        long started = System.nanoTime();
        try (InputStream disk = storage.open(key)) {
            MessageDigest prefix = sha256();
            long skipped = 0;
            while (skipped < offset) {
                int n = disk.read(buffer, 0, (int) Math.min(buffer.length, offset - skipped));
                if (n < 0) throw failure("STORAGE_ERROR", "File lưu trữ không đầy đủ.");
                prefix.update(buffer, 0, n); skipped += n; activity.run();
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Transfer interrupted");
            }
            if (offset > 0 && !HexFormat.of().formatHex(prefix.digest()).equals(string(request.header(), "prefixChecksum", 64)))
                throw failure("PREFIX_MISMATCH", "Dữ liệu tải dở không khớp. Hãy tải lại file.");
            reauthorize(token, user, file.roomId());
            send(output, Type.DOWNLOAD_ACCEPT, request, Map.of("file", file, "offset", offset, "chunkSize", config.chunkSize()));
            event(offset == 0 ? "DOWNLOAD_STARTED" : "TRANSFER_RESUMED", request, user, file.roomId(), file.sizeBytes());
            long lastAuth = System.nanoTime();
            while (offset < file.sizeBytes()) {
                int count = disk.read(buffer, 0, (int) Math.min(buffer.length, file.sizeBytes() - offset));
                if (count < 0) throw failure("STORAGE_ERROR", "File lưu trữ không đầy đủ.");
                if (System.nanoTime() - lastAuth > 1_000_000_000L) {
                    reauthorize(token, user, file.roomId()); lastAuth = System.nanoTime();
                }
                JsonObject header = new JsonObject(); header.addProperty("offset", offset);
                TransferProtocol.write(output, new Frame(Type.DOWNLOAD_CHUNK, request.transferId(), header,
                        count == buffer.length ? buffer : Arrays.copyOf(buffer, count)));
                offset += count; activity.run();
            }
            reauthorize(token, user, file.roomId());
            send(output, Type.DOWNLOAD_COMPLETE, request, file);
            event("DOWNLOAD_COMPLETED durationMs=" + (System.nanoTime() - started) / 1_000_000, request, user, file.roomId(), file.sizeBytes());
        }
    }

    private void reauthorize(String token, long user, long room) throws SQLException, TransferException {
        if (repository.authenticate(token) != user) throw failure("INVALID_SESSION", "Phiên đăng nhập không hợp lệ.");
        repository.authorize(user, room);
    }
    /** Recover a committed file whose acknowledgement/manifest cleanup was interrupted. */
    public void reconcile() throws SQLException, IOException {
        for (var session : storage.pendingSessions()) {
            if (!session.lock().tryLock()) continue;
            try {
                FileMetadata file = repository.findByTransfer(session.id());
                if (file != null && file.userId() == session.userId() && file.roomId() == session.roomId()
                        && file.sizeBytes() == session.size() && file.checksum().equals(session.checksum()))
                    storage.completed(session);
            } finally { session.lock().unlock(); }
        }
    }
    private void reconcileOne(UUID id, long user) throws IOException {
        TransferStorage.UploadSession session;
        try { session = storage.get(id, user); }
        catch (TransferException e) { if ("TRANSFER_NOT_FOUND".equals(e.code())) return; throw e; }
        if (!session.lock().tryLock()) return; // Active completing worker owns cleanup.
        try { storage.completed(session); }
        finally { session.lock().unlock(); }
    }
    static void send(DataOutputStream output, Type type, Frame request, Object header) throws IOException {
        TransferProtocol.write(output, new Frame(type, request.transferId(), TransferProtocol.json(header), new byte[0]));
    }
    static String string(JsonObject header, String key, int max) throws TransferException {
        try {
            if (!header.has(key) || !header.get(key).isJsonPrimitive() || !header.getAsJsonPrimitive(key).isString()) throw new IllegalArgumentException();
            String value = header.get(key).getAsString();
            if (value.length() > max) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) { throw failure("INVALID_REQUEST", "Trường yêu cầu không hợp lệ: " + key); }
    }
    private static String optional(JsonObject header, String key, int max) throws TransferException {
        return !header.has(key) || header.get(key).isJsonNull() ? "" : string(header, key, max);
    }
    static long number(JsonObject header, String key) throws TransferException {
        try {
            if (!header.has(key) || !header.get(key).isJsonPrimitive() || !header.getAsJsonPrimitive(key).isNumber()) throw new IllegalArgumentException();
            return Long.parseLong(header.get(key).getAsString());
        } catch (RuntimeException e) { throw failure("INVALID_REQUEST", "Trường số không hợp lệ: " + key); }
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static TransferException failure(String code, String message) { return new TransferException(code, message); }
    private static void event(String type, Frame request, long user, long room, long bytes) {
        LOG.info(() -> type + " transferId=" + request.transferId() + " userId=" + user + " roomId=" + room + " bytes=" + bytes);
    }
}
