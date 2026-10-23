package server.filetransfer;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.logging.Logger;

/** Room-close policy; each deletion is fenced against reopening and active transfers. */
public final class FileRetentionService {
    private static final Logger LOG = Logger.getLogger(FileRetentionService.class.getName());
    private final FileMetadataRepository repository;
    private final TransferStorage storage;
    public FileRetentionService(FileMetadataRepository repository, TransferStorage storage) {
        this.repository = repository; this.storage = storage;
    }
    public boolean pending(long roomId) throws IOException {
        return storage.pendingSessions().stream().anyMatch(session -> session.roomId()==roomId) || !storage.roomObjects(roomId,1).isEmpty();
    }
    /** Coordinator holds the reopen fence, never a DB transaction, throughout this bounded disk pass. */
    public java.util.List<FileMetadataRepository.RetiredFile> sweepBatch(server.meeting.RoomCleanupStore.Fence fence,
            java.util.List<FileMetadataRepository.RetiredFile> batch) throws SQLException,IOException {
        long roomId=fence.roomId();int processed=0;
        for(var session:storage.pendingSessions()) {
            if(session.roomId()!=roomId) continue;
            if(++processed>100) break;
            if(!session.lock().tryLock()) throw new IOException("UPLOAD_STILL_ACTIVE");
            try {
                if(!session.storageKey().startsWith("rooms/"+roomId+"/files/")) throw new IOException("INVALID_ROOM_OBJECT");
                // This is room retirement, not user cancellation. Both published and unpublished
                // objects must go; any committed metadata remains durable until batch acknowledgement.
                storage.discardExpiredRoomUpload(session);
            } finally {session.lock().unlock();}
        }
        var deleted=new java.util.ArrayList<FileMetadataRepository.RetiredFile>();
        for(var file:batch) {
            if(file.roomId()!=roomId || file.storageKey()==null || !file.storageKey().startsWith("rooms/"+roomId+"/files/")) throw new IOException("INVALID_ROOM_OBJECT");
            storage.deleteStoredFile(file.storageKey());deleted.add(file);
        }
        // Immutable generated objects of this closed room, including promote-before-DB failures.
        // Metadata remains until the following guarded commit, even if this pass fails part way.
        for(String key:storage.roomObjects(roomId,100)) storage.deleteStoredFile(key);
        return java.util.List.copyOf(deleted);
    }
    /** One bounded pass. Errors preserve metadata; the coordinator retries before allowing reopen. */
    public void sweepRoom(long roomId) throws SQLException,IOException {
        Instant cutoff=Instant.now();
        for(var session:storage.pendingSessions()) {
            if(session.roomId()!=roomId) continue;
            if(!session.lock().tryLock()) throw new IOException("UPLOAD_STILL_ACTIVE");
            try {
                repository.inExpiredRoom(roomId,cutoff,connection -> {
                    if(repository.findByTransfer(session.id())!=null) storage.completed(session);
                    else storage.discardExpiredRoomUpload(session);
                });
            } finally {session.lock().unlock();}
        }
        for(var file:repository.closedRoomFiles(roomId))
            repository.retireFile(file,cutoff,connection -> storage.deleteStoredFile(file.storageKey()));
        for(String key:storage.roomObjects(roomId,100))
            repository.retireOrphan(roomId,key,cutoff,connection -> storage.deleteStoredFile(key));
    }
    public void sweep(Instant closedBefore) throws SQLException {
        // Match the upload worker's lock order: session, then room. Never wait for a live upload.
        try {
            for (var session : storage.pendingSessions()) {
                if (!session.lock().tryLock()) continue;
                try {
                    repository.inExpiredRoom(session.roomId(), closedBefore, connection -> {
                        if (repository.findByTransfer(session.id()) != null) storage.completed(session);
                        else storage.discardExpiredRoomUpload(session);
                    });
                } catch (IOException error) {
                    LOG.warning("STORAGE_ERROR retention transferId=" + session.id());
                } finally { session.lock().unlock(); }
            }
        } catch (IOException error) {
            LOG.warning("STORAGE_ERROR retention pending uploads");
        }
        long afterId = 0;
        while (true) {
            var files = repository.retirementCandidates(closedBefore, afterId);
            if (files.isEmpty()) break;
            for (var file : files) {
                afterId = file.fileId();
                try {
                    if (repository.retireFile(file, closedBefore, connection -> storage.deleteStoredFile(file.storageKey())))
                        LOG.info("FILE_RETIRED fileId=" + file.fileId());
                } catch (IOException error) {
                    // Keep metadata for retries; a reopened room cancels the old deadline.
                    LOG.warning("STORAGE_ERROR retention fileId=" + file.fileId() + " error=" + error.getClass().getSimpleName());
                }
            }
        }
    }
}
