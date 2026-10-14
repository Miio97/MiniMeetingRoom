package server.filetransfer;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.logging.Logger;

/** Optional server-admin policy. Never tied to a sender leaving a room. */
public final class FileRetentionService {
    private static final Logger LOG = Logger.getLogger(FileRetentionService.class.getName());
    private final FileMetadataRepository repository;
    private final TransferStorage storage;
    public FileRetentionService(FileMetadataRepository repository, TransferStorage storage) {
        this.repository = repository; this.storage = storage;
    }
    public void sweep(Instant closedBefore) throws SQLException {
        for (var file : repository.retireClosedRoomFiles(closedBefore)) {
            try {
                storage.deleteStoredFile(file.storageKey());
                repository.finishRetirement(file.fileId());
                LOG.info("FILE_RETIRED fileId=" + file.fileId());
            } catch (IOException error) {
                // Hidden metadata keeps its storage key until deletion can be retried.
                LOG.warning("STORAGE_ERROR retention fileId=" + file.fileId() + " error=" + error.getClass().getSimpleName());
            }
        }
    }
}
