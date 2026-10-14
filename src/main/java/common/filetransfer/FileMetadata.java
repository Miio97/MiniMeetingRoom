package common.filetransfer;

/** Public metadata only; storage keys and tokens never appear here. */
public record FileMetadata(long id, long roomId, long userId, String senderName,
        String originalName, String mimeType, long sizeBytes, String checksum, String createdAt) { }
