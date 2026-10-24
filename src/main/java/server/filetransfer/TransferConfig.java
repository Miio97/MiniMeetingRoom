package server.filetransfer;

import common.filetransfer.TransferProtocol;

import server.config.ServerConfig;

import java.nio.file.Path;
import java.time.Duration;

public record TransferConfig(
        boolean enabled,
        String bind,
        int port,
        int chunkSize,
        int maxConcurrent,
        int maxPerUser,
        int timeoutMillis,
        Path storagePath,
        long maxFileBytes,
        long quotaBytes,
        int maxPendingPerUser,
        Duration expiry,
        boolean resumeEnabled) {
    public TransferConfig {
        if (bind == null
                || bind.isBlank()
                || port < 0
                || port > 65535
                || chunkSize < 4096
                || chunkSize > TransferProtocol.MAX_PAYLOAD
                || maxConcurrent < 1
                || maxConcurrent > 64
                || maxPerUser < 1
                || maxPerUser > maxConcurrent
                || timeoutMillis < 1000
                || timeoutMillis > 300000
                || maxFileBytes < 0
                || quotaBytes < maxFileBytes
                || maxPendingPerUser < 1
                || maxPendingPerUser > 100
                || expiry.isNegative()
                || expiry.isZero())
            throw new IllegalArgumentException("Invalid file transfer configuration");
    }

    public static TransferConfig load() {
        if (!Boolean.parseBoolean(ServerConfig.get("file.transfer.tls-enabled", "true")))
            throw new IllegalArgumentException("File transfer requires TLS");
        if (!"SHA-256".equalsIgnoreCase(ServerConfig.get("file.transfer.checksum", "SHA-256")))
            throw new IllegalArgumentException("File transfer requires SHA-256");
        return new TransferConfig(
                Boolean.parseBoolean(ServerConfig.get("file.transfer.enabled", "true")),
                ServerConfig.get("file.transfer.bind", ServerConfig.get("auth.bind", "127.0.0.1")),
                ServerConfig.getInt("file.transfer.port", 9001),
                ServerConfig.getInt("file.transfer.chunk-size", 65536),
                ServerConfig.getInt("file.transfer.max-concurrent-transfers", 8),
                ServerConfig.getInt("file.transfer.max-transfers-per-user", 3),
                ServerConfig.getInt("file.transfer.socket-timeout-ms", 30000),
                Path.of(ServerConfig.get("file.transfer.storage-path", "storage")),
                mb("file.transfer.max-file-size-mb", 1024),
                mb("file.transfer.storage-quota-mb", 10240),
                ServerConfig.getInt("file.transfer.max-pending-per-user", 4),
                Duration.ofHours(ServerConfig.getInt("file.transfer.expiry-hours", 24)),
                Boolean.parseBoolean(ServerConfig.get("file.transfer.resume-enabled", "true")));
    }

    private static long mb(String key, long fallback) {
        return Math.multiplyExact(
                Long.parseLong(ServerConfig.get(key, Long.toString(fallback))), 1024L * 1024);
    }
}
