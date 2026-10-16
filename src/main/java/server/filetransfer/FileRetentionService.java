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
