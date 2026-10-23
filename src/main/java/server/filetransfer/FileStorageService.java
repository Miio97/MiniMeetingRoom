package server.filetransfer;

import common.filetransfer.TransferException;
import java.io.FilterOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Local disk object storage. Tokens and original filenames never become paths.
 * Reserve declared bytes once, then stream under a per-session lock. A durable
 * manifest survives disconnect/restart, including a move followed by a DB error.
 * Callers must serialize each session with {@link Session#lock()} and perform
 * authentication/room authorization separately. Use a private persistent directory.
 */
public final class FileStorageService implements TransferStorage {
    private static final Logger LOG = Logger.getLogger(FileStorageService.class.getName());
    private static final Pattern CHECKSUM = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern STORAGE_KEY = Pattern.compile("rooms/[1-9][0-9]*/files/[0-9a-f-]{36}\\.bin");
    private final Path root;
    private final Path temporary;
    private final long quotaBytes;
    private final long maxFileBytes;
    private final int maxPendingPerUser;
    private final int maxPending;
    private final long diskHeadroomBytes;
    private final long expiryMillis;
    private final boolean resumeEnabled;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> readers = new java.util.HashMap<>();
    private final Set<String> deleting = new java.util.HashSet<>();
    private final ReentrantLock accounting = new ReentrantLock();
    private final FileChannel processLockChannel;
    private final FileLock processLock;
    private volatile boolean closed;
    private long accountedBytes;

    public FileStorageService(Path root, long quotaBytes, long maxFileBytes,
            int maxPendingPerUser, Duration expiry, boolean resumeEnabled) throws IOException {
        this(root, quotaBytes, maxFileBytes, maxPendingPerUser, 1024, expiry, resumeEnabled);
    }

    public FileStorageService(Path root, long quotaBytes, long maxFileBytes,
            int maxPendingPerUser, int maxPending, Duration expiry, boolean resumeEnabled) throws IOException {
        this(root, quotaBytes, maxFileBytes, maxPendingPerUser, maxPending, expiry, resumeEnabled, 16L * 1024 * 1024);
    }

    /** Disk headroom reserves room for manifests and unrelated host activity (default 16 MiB). */
    public FileStorageService(Path root, long quotaBytes, long maxFileBytes,
            int maxPendingPerUser, int maxPending, Duration expiry, boolean resumeEnabled,
            long diskHeadroomBytes) throws IOException {
        if (quotaBytes <= 0 || maxFileBytes < 0 || maxPendingPerUser < 1 || maxPending < 1 || maxPending > 1024
                || diskHeadroomBytes < 0
                || expiry.isNegative() || expiry.isZero()) {
            throw new IllegalArgumentException("Invalid storage limits.");
        }
        this.root = Objects.requireNonNull(root).toAbsolutePath().normalize();
        this.temporary = this.root.resolve("temporary");
        this.quotaBytes = quotaBytes;
        this.maxFileBytes = maxFileBytes;
        this.maxPendingPerUser = maxPendingPerUser;
        this.maxPending = maxPending;
        this.diskHeadroomBytes = diskHeadroomBytes;
        this.expiryMillis = expiry.toMillis();
        this.resumeEnabled = resumeEnabled;
        rejectLinks(this.root);
        Files.createDirectories(this.root);
        processLockChannel = FileChannel.open(checked(this.root.resolve(".lock")),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = processLockChannel.tryLock();
            if (acquired == null) throw error("STORAGE_IN_USE", "Another server already uses this storage directory.");
        } catch (IOException | OverlappingFileLockException failure) {
            try { processLockChannel.close(); }
            catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (failure instanceof OverlappingFileLockException) {
                throw error("STORAGE_IN_USE", "Another server already uses this storage directory.");
            }
            throw failure;
        }
        processLock = acquired;
        try {
            createDirectory(temporary);
            createDirectory(this.root.resolve("rooms"));
            recover();
        } catch (IOException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public Session begin(UUID id, long userId, long roomId, String originalName,
            String mimeType, long size, String checksum) throws IOException {
        return begin(id,userId,roomId,originalName,mimeType,size,checksum,"");
    }
    @Override public Session begin(UUID id,long userId,long roomId,String originalName,String mimeType,long size,String checksum,String generation) throws IOException {
        validate(id, userId, roomId, originalName, mimeType, size, checksum);
        checksum = checksum.toLowerCase(Locale.ROOT);
        accounting.lock();
        try {
            if (closed) throw error("STORAGE_CLOSED", "Storage service is closed.");
            Session existing = sessions.get(id);
            if (existing != null) {
                authorize(existing, userId);
                if (!existing.membershipGeneration.equals(generation) || existing.roomId != roomId || existing.size != size
                        || !existing.originalName.equals(originalName)
                        || !existing.mimeType.equals(mimeType) || !existing.checksum.equals(checksum)) {
                    throw error("TRANSFER_CONFLICT", "Transfer metadata cannot change when resuming.");
                }
                if (!resumeEnabled && !Files.exists(finalPath(existing), LinkOption.NOFOLLOW_LINKS)) {
                    throw error("RESUME_DISABLED", "Upload resume is disabled.");
                }
                if (expired(existing) && !Files.exists(finalPath(existing), LinkOption.NOFOLLOW_LINKS)) {
                    throw error("TRANSFER_EXPIRED", "Upload session has expired.");
                }
                if (!Files.exists(finalPath(existing), LinkOption.NOFOLLOW_LINKS)) requirePhysicalCapacity(0);
                return existing;
            }
            if (size > maxFileBytes) throw error("FILE_TOO_LARGE", "File exceeds the configured size limit.");
            if (sessions.size() >= maxPending) throw error("TOO_MANY_TRANSFERS", "Too many unfinished uploads on the server.");
            if (sessions.values().stream().filter(s -> s.userId == userId).count() >= maxPendingPerUser) {
                throw error("TOO_MANY_TRANSFERS", "Too many unfinished uploads for this user.");
            }
            if (size > quotaBytes - accountedBytes) throw error("STORAGE_FULL", "Storage quota exceeded.");
            requirePhysicalCapacity(size);
            Session session = new Session(id, userId, roomId, originalName, mimeType, size,
                    checksum, System.currentTimeMillis(), temporary.resolve(id + ".part"));
            session.membershipGeneration=generation;
            if (Files.exists(finalPath(session), LinkOption.NOFOLLOW_LINKS)) {
                throw error("TRANSFER_CONFLICT", "This transfer already has a stored object.");
            }
            checked(session.part);
            Files.createFile(session.part);
            try {
                saveManifest(session);
            } catch (IOException failure) {
                try { Files.deleteIfExists(session.part); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            sessions.put(id, session);
            accountedBytes += size;
            return session;
        } finally {
            accounting.unlock();
        }
    }

    public Session get(UUID id, long userId) throws IOException {
        Session session = sessions.get(Objects.requireNonNull(id));
        if (session == null) throw error("TRANSFER_NOT_FOUND", "Upload session was not found.");
        authorize(session, userId);
        if (expired(session) && !Files.exists(finalPath(session), LinkOption.NOFOLLOW_LINKS)) {
            throw error("TRANSFER_EXPIRED", "Upload session has expired.");
        }
        return session;
    }

    /** Immutable, bounded snapshot for metadata reconciliation; caller locks each session before mutation. */
    public List<Session> pendingSessions() {
        accounting.lock();
        try { return List.copyOf(sessions.values()); }
        finally { accounting.unlock(); }
    }

    /** The persisted file length, never an offset supplied by a client. */
    public long offset(UploadSession upload) throws IOException {
        Session session = localSession(upload);
        requireActive(session);
        Path promoted = checked(finalPath(session));
        Path source = Files.exists(promoted, LinkOption.NOFOLLOW_LINKS) ? promoted : checked(session.part);
        long length = Files.size(source);
        if (length > session.size) throw error("SIZE_MISMATCH", "Stored upload exceeds declared length.");
        return length;
    }

    /** Caller holds the session lock until this stream is closed. */
    public OutputStream append(UploadSession upload) throws IOException {
        Session session = localSession(upload);
        requireActive(session);
        if (Files.exists(checked(finalPath(session)), LinkOption.NOFOLLOW_LINKS)) {
            throw error("TRANSFER_COMPLETE", "File data has already been verified.");
        }
        long initialOffset = offset(session);
        OutputStream output = Channels.newOutputStream(Files.newByteChannel(checked(session.part),
                Set.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)));
        return new FilterOutputStream(output) {
            private long written = initialOffset;

            @Override
            public void write(int value) throws IOException {
                if (written >= session.size) throw error("SIZE_MISMATCH", "Too many upload bytes.");
                out.write(value);
                written++;
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                Objects.checkFromIndexSize(offset, length, bytes.length);
                if (length > session.size - written) throw error("SIZE_MISMATCH", "Too many upload bytes.");
                out.write(bytes, offset, length);
                written += length;
            }
        };
    }

    /** Verify streaming SHA-256, then move on the same volume without overwriting. */
    public String verifyAndPromote(Session session) throws IOException {
        return verifyAndPromote(session, () -> { });
    }

    /** Reports disk activity so the transport watchdog does not mistake hashing for an idle peer. */
    public String verifyAndPromote(UploadSession upload, Runnable activity) throws IOException {
        Session session = localSession(upload);
        Objects.requireNonNull(activity);
        requireActive(session);
        checkInterrupted();
        Path target = checked(finalPath(session));
        boolean promoted = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        Path source = promoted ? target : checked(session.part);
        if (Files.size(source) != session.size) throw error("SIZE_MISMATCH", "Upload length is incorrect.");
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkInterrupted();
                digest.update(buffer, 0, count);
                activity.run();
            }
        }
        checkInterrupted();
        requireActive(session);
        if (!HexFormat.of().formatHex(digest.digest()).equals(session.checksum)) {
            throw error("CHECKSUM_MISMATCH", "Uploaded file checksum does not match.");
        }
        if (!promoted) {
            createDirectory(target.getParent());
            // Flush file data before publishing its durable name.
            try (FileChannel channel = FileChannel.open(source, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
            checkInterrupted();
            move(source, target);
        }
        return session.storageKey();
    }

    /** Called only after the metadata transaction is known to have committed. */
    public void completed(UploadSession upload) throws IOException {
        Session session = localSession(upload);
        accounting.lock();
        try {
            if (sessions.get(session.id) != session) return;
            if (!Files.isRegularFile(checked(finalPath(session)), LinkOption.NOFOLLOW_LINKS)) {
                throw error("STORAGE_ERROR", "Completed upload has no stored file.");
            }
            Files.deleteIfExists(checked(manifest(session)));
            Files.deleteIfExists(checked(session.part));
            sessions.remove(session.id, session);
            // Reserved bytes now represent committed bytes, so the total does not change.
        } finally {
            accounting.unlock();
        }
    }

    /** A promoted object may already be referenced by a committed but unacknowledged DB transaction. */
    public void cancel(UploadSession upload) throws IOException {
        Session session = localSession(upload);
        accounting.lock();
        try {
            if (sessions.get(session.id) != session) return;
            if (Files.exists(checked(finalPath(session)), LinkOption.NOFOLLOW_LINKS)) {
                throw error("COMMIT_PENDING", "Verified upload must be reconciled with metadata before deletion.");
            }
            Files.deleteIfExists(checked(session.part));
            Files.deleteIfExists(checked(manifest(session)));
            sessions.remove(session.id, session);
            accountedBytes -= session.size;
        } finally {
            accounting.unlock();
        }
    }

    @Override public void discardExpiredRoomUpload(UploadSession upload) throws IOException {
        Session session = localSession(upload);
        accounting.lock();
        try {
            if (sessions.get(session.id) != session) return;
            if (readers.containsKey(session.storageKey()) || !deleting.add(session.storageKey())) throw error("TRANSFER_BUSY", "Stored file is being read or deleted.");
        } finally {accounting.unlock();}
        boolean removed=false;
        try {
            Files.deleteIfExists(checked(session.part));
            Files.deleteIfExists(checked(finalPath(session)));
            Files.deleteIfExists(checked(manifest(session)));
            removed=true;
        } finally {
            accounting.lock();try {deleting.remove(session.storageKey());if(removed && sessions.remove(session.id,session)) accountedBytes-=session.size;}
            finally {accounting.unlock();}
        }
    }

    /** Resolves only generated object keys and rejects symlinks in every ancestor. */
    public Path resolve(String storageKey) throws IOException {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) {
            throw error("INVALID_STORAGE_KEY", "Invalid stored file key.");
        }
        String filename = storageKey.substring(storageKey.lastIndexOf('/') + 1);
        try {
            if (!UUID.fromString(filename.substring(0, filename.length() - 4)).toString()
                    .equals(filename.substring(0, filename.length() - 4))) {
                throw new IllegalArgumentException("Noncanonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw error("INVALID_STORAGE_KEY", "Invalid stored file identifier.");
        }
        return checked(root.resolve(storageKey));
    }

    /** Admin/room-retention hook: coordinator fences the closed room against reopen. */
    @Override public java.util.List<String> roomObjects(long roomId,int limit) throws IOException {
        if(roomId<1 || limit<1 || limit>100) throw new IllegalArgumentException("ROOM_SCAN_LIMIT");
        Path directory=checked(root.resolve("rooms/"+roomId+"/files"));
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) return java.util.List.of();
        var keys=new java.util.ArrayList<String>();
        try(var objects=Files.newDirectoryStream(directory,"*.bin")) {
            for(Path object:objects) {
                String key="rooms/"+roomId+"/files/"+object.getFileName();
                if(STORAGE_KEY.matcher(key).matches()) {checked(object);if(!Files.isRegularFile(object,LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_ROOM_OBJECT");keys.add(key);if(keys.size()==limit) break;}
            }
        }
        return java.util.List.copyOf(keys);
    }
    public void deleteStoredFile(String storageKey) throws IOException {
        Path path = resolve(storageKey);
        accounting.lock();
        try {
            if (readers.containsKey(storageKey)) throw error("TRANSFER_BUSY", "Stored file is being read.");
            if (sessions.values().stream().anyMatch(s -> s.storageKey().equals(storageKey))) {
                throw error("TRANSFER_BUSY", "Reconcile pending upload metadata before deleting this file.");
            }
            if(!deleting.add(storageKey)) throw error("TRANSFER_BUSY","Stored file is being deleted.");
        } finally {accounting.unlock();}
        long removed=0;
        try {if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {long size=Files.size(path);Files.delete(path);removed=size;}}
        finally {accounting.lock();try {deleting.remove(storageKey);accountedBytes-=removed;} finally {accounting.unlock();}}
    }

    @Override public InputStream open(String storageKey) throws IOException {
        accounting.lock();
        try {
            if(deleting.contains(storageKey)) throw error("TRANSFER_BUSY","Stored file is being deleted.");
            InputStream input = Files.newInputStream(regularObject(storageKey), LinkOption.NOFOLLOW_LINKS);
            readers.merge(storageKey, 1, Integer::sum);
            return new FilterInputStream(input) {
                private boolean released;
                @Override public void close() throws IOException {
                    accounting.lock();
                    try {
                        if (released) return;
                        try { super.close(); }
                        finally {
                            released = true;
                            readers.computeIfPresent(storageKey, (key, count) -> count == 1 ? null : count - 1);
                        }
                    } finally { accounting.unlock(); }
                }
            };
        } finally { accounting.unlock(); }
    }

    @Override public long size(String storageKey) throws IOException { return Files.size(regularObject(storageKey)); }

    private Path regularObject(String key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw error("STORAGE_ERROR", "Stored file is unavailable.");
        return path;
    }

    private static Session localSession(UploadSession upload) throws TransferException {
        if (upload instanceof Session session) return session;
        throw error("INVALID_TRANSFER", "Upload belongs to a different storage backend.");
    }

    /** Never waits behind active transfers, and never deletes a promoted DB-pending object. */
    public void cleanup() {
        for (Session session : sessions.values()) {
            if (expired(session) && session.lock.tryLock()) {
                try {
                    if (!Files.exists(checked(finalPath(session)), LinkOption.NOFOLLOW_LINKS)) cancel(session);
                } catch (IOException failure) {
                    LOG.log(Level.WARNING, "STORAGE_ERROR cleaning transfer " + session.id, failure);
                } finally {
                    session.lock.unlock();
                }
            }
        }
        accounting.lock();
        try {
            cleanupOrphans(true);
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "STORAGE_ERROR cleaning abandoned upload files", failure);
        } finally {
            accounting.unlock();
        }
    }

    public long accountedBytes() {
        accounting.lock();
        try { return accountedBytes; }
        finally { accounting.unlock(); }
    }

    @Override
    public void close() {
        accounting.lock();
        try {
            if (closed) return;
            closed = true;
            // Every append stream belongs to its request. Durable sessions survive shutdown.
            try { processLock.release(); }
            catch (IOException failure) { LOG.log(Level.WARNING, "STORAGE_ERROR releasing storage lock", failure); }
            try { processLockChannel.close(); }
            catch (IOException failure) { LOG.log(Level.WARNING, "STORAGE_ERROR closing storage lock", failure); }
        } finally {
            accounting.unlock();
        }
    }

    private void recover() throws IOException {
        try (var manifests = Files.newDirectoryStream(temporary, "*.properties")) {
            for (Path path : manifests) {
                try {
                    checked(path);
                    if (Files.size(path) > 16 * 1024) throw new IOException("Manifest is too large.");
                    Properties metadata = new Properties();
                    try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                        metadata.load(input);
                    }
                    UUID id = UUID.fromString(metadata.getProperty("id"));
                    if (!path.getFileName().toString().equals(id + ".properties")) {
                        throw new IOException("Manifest identifier does not match its filename.");
                    }
                    Session session = new Session(id, Long.parseLong(metadata.getProperty("userId")),
                            Long.parseLong(metadata.getProperty("roomId")), metadata.getProperty("originalName"),
                            metadata.getProperty("mimeType"), Long.parseLong(metadata.getProperty("size")),
                            metadata.getProperty("checksum"), Long.parseLong(metadata.getProperty("createdAtMillis")),
                            temporary.resolve(id + ".part"));
                    session.membershipGeneration=metadata.getProperty("membershipGeneration","");
                    validate(id, session.userId, session.roomId, session.originalName,
                            session.mimeType, session.size, session.checksum);
                    Path promoted = checked(finalPath(session));
                    boolean hasPromoted = Files.exists(promoted, LinkOption.NOFOLLOW_LINKS);
                    if ((!resumeEnabled || expired(session)) && !hasPromoted) {
                        Files.deleteIfExists(checked(session.part));
                        Files.delete(path);
                        continue;
                    }
                    if (!hasPromoted && !Files.isRegularFile(checked(session.part), LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Manifest has no upload data.");
                    }
                    long length = Files.size(hasPromoted ? promoted : session.part);
                    if (length > session.size || (hasPromoted && length != session.size)) {
                        throw new IOException("Upload data has an invalid size.");
                    }
                    if (sessions.size() >= maxPending) {
                        // Fail startup rather than silently lose reservations for durable sessions.
                        throw new IllegalStateException("Persisted uploads exceed the configured pending upload limit.");
                    }
                    sessions.put(id, session);
                } catch (IOException | IllegalArgumentException | NullPointerException invalid) {
                    LOG.log(Level.WARNING, "STORAGE_ERROR reading manifest " + path.getFileName(), invalid);
                }
            }
        }
        cleanupOrphans(false);
        accountedBytes = countFiles(root.resolve("rooms"));
        try (var parts = Files.newDirectoryStream(temporary, "*.part")) {
            for (Path part : parts) {
                checked(part);
                UUID id = partId(part);
                if (id == null || !sessions.containsKey(id)) accountedBytes = Math.addExact(accountedBytes, Files.size(part));
            }
        }
        for (Session session : sessions.values()) {
            if (!Files.exists(checked(finalPath(session)), LinkOption.NOFOLLOW_LINKS)) {
                accountedBytes = Math.addExact(accountedBytes, session.size);
            }
        }
    }

    private void cleanupOrphans(boolean adjustAccounting) throws IOException {
        try (var files = Files.newDirectoryStream(temporary)) {
            for (Path path : files) {
                checked(path);
                String name = path.getFileName().toString();
                boolean part = name.endsWith(".part");
                if (!part && !name.endsWith(".properties.tmp")) continue;
                UUID id = part ? partId(path) : null;
                if (id != null && sessions.containsKey(id)) continue;
                if (System.currentTimeMillis() - Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS)
                        .toMillis() < expiryMillis) continue;
                long bytes = Files.size(path);
                Files.delete(path);
                if (part && adjustAccounting) accountedBytes -= bytes;
            }
        }
    }

    /** Accounting lock protects admissions, not file writes; stat before usable-space gives a conservative estimate. */
    private void requirePhysicalCapacity(long incomingBytes) throws IOException {
        long remaining = 0;
        for (Session session : sessions.values()) {
            long written;
            try {
                written = Files.size(checked(session.part));
            } catch (NoSuchFileException moved) {
                if (Files.isRegularFile(checked(finalPath(session)), LinkOption.NOFOLLOW_LINKS)) continue;
                throw error("STORAGE_ERROR", "Unfinished upload data is missing.");
            }
            long unfilled = Math.max(0, session.size - written);
            if (unfilled > Long.MAX_VALUE - remaining) throw error("STORAGE_FULL", "Pending uploads exceed available disk space.");
            remaining += unfilled;
        }
        validatePhysicalCapacity(Files.getFileStore(root).getUsableSpace(), remaining, incomingBytes, diskHeadroomBytes);
    }

    static void validatePhysicalCapacity(long usable, long pendingBytes, long incomingBytes,
            long headroomBytes) throws TransferException {
        // Subtract in stages rather than adding sizes, which could overflow for malicious declarations.
        if (usable < headroomBytes || usable - headroomBytes < pendingBytes
                || usable - headroomBytes - pendingBytes < incomingBytes) {
            throw error("STORAGE_FULL", "Insufficient free disk space for this upload and pending uploads.");
        }
    }

    private long countFiles(Path directory) throws IOException {
        long total = 0;
        try (var files = Files.walk(directory)) {
            var iterator = files.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                checked(path);
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) total = Math.addExact(total, Files.size(path));
            }
        }
        return total;
    }

    private void saveManifest(Session session) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("id", session.id.toString());
        metadata.setProperty("userId", Long.toString(session.userId));
        metadata.setProperty("roomId", Long.toString(session.roomId));
        metadata.setProperty("membershipGeneration",session.membershipGeneration);
        metadata.setProperty("originalName", session.originalName);
        metadata.setProperty("mimeType", session.mimeType);
        metadata.setProperty("size", Long.toString(session.size));
        metadata.setProperty("checksum", session.checksum);
        metadata.setProperty("createdAtMillis", Long.toString(session.createdAtMillis));
        Path pending = checked(temporary.resolve(session.id + ".properties.tmp"));
        try (OutputStream output = Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            metadata.store(output, "MiniMeetingRoom upload; no credentials");
        }
        try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
        move(pending, checked(manifest(session)));
    }

    private Path checked(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw error("INVALID_STORAGE_KEY", "Path escapes storage root.");
        rejectLinks(normalized);
        return normalized;
    }

    private static void rejectLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path element : path) {
            current = current.resolve(element);
            if (Files.isSymbolicLink(current)) throw error("UNSAFE_STORAGE", "Storage cannot contain symbolic links.");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && !current.toRealPath().equals(current.toAbsolutePath().normalize())) {
                throw error("UNSAFE_STORAGE", "Storage cannot contain redirected directories.");
            }
        }
    }

    private void createDirectory(Path path) throws IOException {
        checked(path);
        Files.createDirectories(path);
        checked(path);
    }

    private static void move(Path source, Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw error("TRANSFER_CONFLICT", "Stored file already exists.");
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(source, target); }
    }

    private static UUID partId(Path path) {
        try { return UUID.fromString(path.getFileName().toString().replaceFirst("\\.part$", "")); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private static void validate(UUID id, long userId, long roomId, String filename,
            String mimeType, long size, String checksum) throws TransferException {
        if (id == null || userId <= 0 || roomId <= 0 || size < 0 || filename == null
                || filename.isBlank() || filename.length() > 255 || filename.equals(".") || filename.equals("..")
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0
                || filename.chars().anyMatch(c -> Character.isISOControl(c))
                || mimeType == null || mimeType.isBlank() || mimeType.length() > 100
                || mimeType.chars().anyMatch(c -> Character.isISOControl(c))
                || checksum == null || !CHECKSUM.matcher(checksum).matches()) {
            throw error("INVALID_METADATA", "Invalid upload metadata.");
        }
    }

    private static void authorize(Session session, long userId) throws TransferException {
        if (session.userId != userId) throw error("PERMISSION_DENIED", "Upload belongs to another user.");
    }

    private void requireActive(Session session) throws TransferException {
        if (closed) throw error("STORAGE_CLOSED", "Storage service is closed.");
        if (session == null || sessions.get(session.id) != session) {
            throw error("TRANSFER_NOT_FOUND", "Upload session is no longer active.");
        }
    }

    private boolean expired(Session session) {
        return System.currentTimeMillis() - session.createdAtMillis >= expiryMillis;
    }

    private Path manifest(Session session) { return temporary.resolve(session.id + ".properties"); }
    private Path finalPath(Session session) { return root.resolve(session.storageKey()); }
    private static TransferException error(String code, String message) { return new TransferException(code, message); }
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Upload verification was interrupted.");
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static final class Session implements UploadSession {
        private String membershipGeneration="";
        public String membershipGeneration() {return membershipGeneration;}
        private final UUID id;
        private final long userId;
        private final long roomId;
        private final String originalName;
        private final String mimeType;
        private final long size;
        private final String checksum;
        private final long createdAtMillis;
        private final Path part;
        private final ReentrantLock lock = new ReentrantLock();

        private Session(UUID id, long userId, long roomId, String originalName, String mimeType,
                long size, String checksum, long createdAtMillis, Path part) {
            this.id = id; this.userId = userId; this.roomId = roomId;
            this.originalName = originalName; this.mimeType = mimeType; this.size = size;
            this.checksum = checksum; this.createdAtMillis = createdAtMillis; this.part = part;
        }
        public UUID id() { return id; }
        public long userId() { return userId; }
        public long roomId() { return roomId; }
        public String originalName() { return originalName; }
        public String mimeType() { return mimeType; }
        public long size() { return size; }
        public String checksum() { return checksum; }
        public long createdAtMillis() { return createdAtMillis; }
        public Path part() { return part; }
        public ReentrantLock lock() { return lock; }
        public String storageKey() { return "rooms/" + roomId + "/files/" + id + ".bin"; }
    }
}
