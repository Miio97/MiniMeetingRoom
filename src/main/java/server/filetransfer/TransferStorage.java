package server.filetransfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

/**
 * Streaming storage boundary for local disk or a future object-storage adapter.
 * Keys are opaque generated identifiers, never user filenames or absolute paths.
 * Authentication and room authorization remain the caller's responsibility.
 */
public interface TransferStorage extends AutoCloseable {
    /** Only recognized object keys in the managed room directory, never personal downloads. */
    default java.util.List<String> roomObjects(long roomId,int limit) throws IOException {return java.util.List.of();}
    /** Durable metadata and the per-upload lock; no backend-specific path escapes this interface. */
    interface UploadSession {
        UUID id();
        long userId();
        long roomId();
        String originalName();
        String mimeType();
        long size();
        String checksum();
        long createdAtMillis();
        Lock lock();
        String storageKey();
        default String membershipGeneration() {return "";}
    }

    /** Reserve capacity or return the same user's matching unfinished upload for resume. */
    UploadSession begin(UUID id, long userId, long roomId, String originalName,
            String mimeType, long size, String checksum) throws IOException;
    default UploadSession begin(UUID id,long userId,long roomId,String name,String mime,long size,String checksum,String generation) throws IOException {
        return begin(id,userId,roomId,name,mime,size,checksum);
    }

    UploadSession get(UUID id, long userId) throws IOException;

    /** Immutable bounded snapshot for startup/periodic metadata reconciliation. */
    List<? extends UploadSession> pendingSessions() throws IOException;

    /** Authoritative durable offset. The caller holds the session lock for these operations. */
    long offset(UploadSession session) throws IOException;

    /** Caller closes the returned stream before releasing the session lock. */
    OutputStream append(UploadSession session) throws IOException;

    /** Verify size and SHA-256, publish immutable bytes, and return their storage key. */
    String verifyAndPromote(UploadSession session, Runnable activity) throws IOException;

    /** Discard upload bookkeeping only after its metadata transaction is committed. */
    void completed(UploadSession session) throws IOException;

    /** Cancel an unfinished upload; never delete an object awaiting metadata reconciliation. */
    void cancel(UploadSession session) throws IOException;

    /** Admin only: caller holds the session lock and the expired closed room's DB lock.
     * Must establish that no committed metadata references this upload before calling. */
    void discardExpiredRoomUpload(UploadSession session) throws IOException;

    /** Stream an existing immutable object. Caller owns and closes the stream. */
    InputStream open(String storageKey) throws IOException;

    long size(String storageKey) throws IOException;

    /** Authorized room-retention hook. Refuse deletion while an object stream is open. */
    void deleteStoredFile(String storageKey) throws IOException;

    void cleanup() throws IOException;

    @Override void close() throws IOException;
}
