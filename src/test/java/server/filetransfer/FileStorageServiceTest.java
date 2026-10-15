package server.filetransfer;

import common.filetransfer.TransferException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileStorageServiceTest {
    @TempDir Path directory;

    @Test
    void arbitraryExtensionsUnicodeAndEmptyObjectsRemainByteExact() throws Exception {
        try (var storage = storage(1024, 1024, 20)) {
            for (String filename : new String[]{"notes.txt", "document.pdf", "picture.png", "video.mp4",
                    "archive.zip", "application.exe", "Báo cáo tháng 10.sql", "empty"}) {
                byte[] bytes = filename.equals("empty") ? new byte[0] : filename.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var session = begin(storage, filename, bytes);
                try (var output = storage.append(session)) { output.write(bytes); }
                assertEquals(bytes.length, storage.offset(session));
                String key = storage.verifyAndPromote(session);
                assertEquals("rooms/11/files/" + session.id() + ".bin", key);
                assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(key)));
                storage.completed(session);
                assertThrows(TransferException.class, () -> storage.get(session.id(), 1));
            }
        }
    }

    @Test
    void restartResumesStoredOffsetAndReconcilesPromotedObjectIdempotently() throws Exception {
        byte[] bytes = "restart-safe upload".getBytes();
        UUID id;
        String checksum = hash(bytes);
        try (var storage = storage(100, 100, 4)) {
            var session = begin(storage, "file.txt", bytes);
            id = session.id();
            try (var output = storage.append(session)) { output.write(bytes, 0, 5); }
        }
        String key;
        try (var storage = storage(100, 100, 4)) {
            var session = storage.begin(id, 1, 11, "file.txt", "application/octet-stream", bytes.length, checksum);
            assertEquals(5, storage.offset(session));
            assertEquals(bytes.length, storage.accountedBytes());
            try (var output = storage.append(session)) { output.write(bytes, 5, bytes.length - 5); }
            key = storage.verifyAndPromote(session);
            // Simulate a crash/ambiguous DB commit before completed().
        }
        try (var storage = storage(100, 100, 4)) {
            var session = storage.get(id, 1);
            assertEquals(bytes.length, storage.offset(session));
            assertEquals(bytes.length, storage.accountedBytes());
            assertEquals(key, storage.verifyAndPromote(session));
            assertEquals("COMMIT_PENDING", assertThrows(TransferException.class,
                    () -> storage.cancel(session)).code());
            storage.completed(session);
            storage.completed(session);
            assertEquals(bytes.length, storage.accountedBytes());
        }
        try (var storage = storage(100, 100, 4)) {
            assertArrayEquals(bytes, Files.readAllBytes(storage.resolve(key)));
            assertEquals(bytes.length, storage.accountedBytes());
            storage.deleteStoredFile(key);
            assertEquals(0, storage.accountedBytes());
        }
    }

    @Test
    void declaredBytesReserveQuotaBeforeAnyChunksAndCancellationReleasesIt() throws Exception {
        try (var storage = storage(10, 10, 4)) {
            var first = begin(storage, "first", new byte[7]);
            assertEquals(7, storage.accountedBytes());
            assertEquals("STORAGE_FULL", assertThrows(TransferException.class,
                    () -> begin(storage, "second", new byte[4])).code());
            assertEquals("FILE_TOO_LARGE", assertThrows(TransferException.class,
                    () -> begin(storage, "large", new byte[11])).code());
            storage.cancel(first);
            assertEquals(0, storage.accountedBytes());
            begin(storage, "second", new byte[10]);
            assertEquals(10, storage.accountedBytes());
        }
        try (var storage = storage(10, 10, 4)) {
            assertEquals(10, storage.accountedBytes());
            assertEquals("STORAGE_FULL", assertThrows(TransferException.class,
                    () -> begin(storage, "third", new byte[1])).code());
        }
    }

    @Test
    void validatesOwnerImmutableMetadataAndPendingLimit() throws Exception {
        try (var storage = storage(100, 100, 1)) {
            var session = begin(storage, "one.txt", new byte[3]);
            assertEquals("PERMISSION_DENIED", assertThrows(TransferException.class,
                    () -> storage.get(session.id(), 2)).code());
            assertEquals("PERMISSION_DENIED", assertThrows(TransferException.class,
                    () -> storage.begin(session.id(), 2, 11, "one.txt", "application/octet-stream",
                            3, hash(new byte[3]))).code());
            assertEquals("TRANSFER_CONFLICT", assertThrows(TransferException.class,
                    () -> storage.begin(session.id(), 1, 12, "one.txt", "application/octet-stream",
                            3, hash(new byte[3]))).code());
            assertEquals("TOO_MANY_TRANSFERS", assertThrows(TransferException.class,
                    () -> begin(storage, "two.txt", new byte[1])).code());
            assertSame(session, storage.begin(session.id(), 1, 11, "one.txt", "application/octet-stream",
                    3, hash(new byte[3])));
        }
    }

    @Test
    void blocksTraversalAndOversizedChunksWithoutWritingThem() throws Exception {
        try (var storage = storage(100, 100, 4)) {
            for (String name : new String[]{"../outside", "..\\outside", "bad\nname", ".."}) {
                assertThrows(TransferException.class, () -> begin(storage, name, new byte[0]));
            }
            for (String key : new String[]{"../outside", "C:/outside", "rooms/1/files/../outside",
                    "rooms/1/files/00000000-0000-0000-0000-000000000000.bin/else"}) {
                assertEquals("INVALID_STORAGE_KEY", assertThrows(TransferException.class,
                        () -> storage.resolve(key)).code());
            }
            var session = begin(storage, "short.bin", new byte[3]);
            try (var output = storage.append(session)) {
                assertEquals("SIZE_MISMATCH", assertThrows(TransferException.class,
                        () -> output.write(new byte[4])).code());
                output.write(new byte[3]);
                assertThrows(TransferException.class, () -> output.write(4));
            }
            assertEquals(3, storage.offset(session));
        }
    }

    @Test
    void mismatchedChecksumNeverPublishesFile() throws Exception {
        try (var storage = storage(100, 100, 4)) {
            var session = begin(storage, "file.bin", new byte[]{1, 2, 3});
            try (var output = storage.append(session)) { output.write(new byte[]{1, 2, 4}); }
            assertEquals("CHECKSUM_MISMATCH", assertThrows(TransferException.class,
                    () -> storage.verifyAndPromote(session)).code());
            assertFalse(Files.exists(storage.resolve(session.storageKey())));
            assertTrue(Files.exists(session.part()));
            storage.cancel(session);
            assertEquals(0, storage.accountedBytes());
        }
    }

    @Test
    void checksumVerificationReportsActivityAndCooperatesWithShutdownInterrupt() throws Exception {
        byte[] bytes = new byte[150_000];
        try (var storage = storage(200_000, 200_000, 4)) {
            var session = begin(storage, "large.bin", bytes);
            try (var output = storage.append(session)) { output.write(bytes); }
            AtomicInteger progress = new AtomicInteger();
            try {
                assertThrows(InterruptedIOException.class, () -> storage.verifyAndPromote(session, () -> {
                    progress.incrementAndGet();
                    Thread.currentThread().interrupt();
                }));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted(); // Do not leak the intentional test interruption into JUnit.
            }
            assertEquals(1, progress.get());
            assertTrue(Files.exists(session.part()));
            assertFalse(Files.exists(storage.resolve(session.storageKey())));
            progress.set(0);
            storage.verifyAndPromote(session, progress::incrementAndGet);
            assertEquals(3, progress.get());
        }
    }

    @Test
    void expiryDeletesPartialButRetainsPromotedCommitPendingData() throws Exception {
        UUID partialId;
        UUID promotedId;
        String key;
        try (var storage = storage(100, 100, 4)) {
            var partial = begin(storage, "partial.bin", new byte[5]);
            partialId = partial.id();
            var promoted = begin(storage, "complete.bin", new byte[3]);
            promotedId = promoted.id();
            try (var output = storage.append(promoted)) { output.write(new byte[3]); }
            key = storage.verifyAndPromote(promoted);
        }
        ageManifest(partialId);
        ageManifest(promotedId);
        try (var storage = storage(100, 100, 4)) {
            assertThrows(TransferException.class, () -> storage.get(partialId, 1));
            assertEquals(3, storage.offset(storage.get(promotedId, 1)));
            storage.cleanup();
            assertTrue(Files.exists(storage.resolve(key)));
            assertEquals(3, storage.accountedBytes());
        }
    }

    @Test
    void symlinkedStorageIsRejectedWhenPlatformAllowsCreatingLinks() throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path root = directory.resolve("root");
        try { Files.createSymbolicLink(root, outside); }
        catch (IOException | UnsupportedOperationException unsupported) {
            assumeTrue(false, "Platform does not permit symbolic link creation: " + unsupported.getMessage());
        }
        assertEquals("UNSAFE_STORAGE", assertThrows(TransferException.class,
                () -> new FileStorageService(root, 100, 100, 4, Duration.ofDays(1), true)).code());
    }

    @Test
    void rootLockPreventsTwoServersBypassingQuotaAndClosesCleanly() throws Exception {
        try (var first = storage(100, 100, 4)) {
            assertEquals("STORAGE_IN_USE", assertThrows(TransferException.class,
                    () -> storage(100, 100, 4)).code());
        }
        try (var reopened = storage(100, 100, 4)) {
            begin(reopened, "file", new byte[0]);
        }
    }

    @Test
    void globalPendingLimitAlsoBoundsZeroByteUploadsAcrossDifferentUsers() throws Exception {
        try (var storage = new FileStorageService(directory.resolve("storage"), 100, 100, 4,
                1, Duration.ofDays(1), true)) {
            begin(storage, "first", new byte[0]);
            assertEquals("TOO_MANY_TRANSFERS", assertThrows(TransferException.class,
                    () -> storage.begin(UUID.randomUUID(), 2, 11, "second", "application/octet-stream",
                            0, hash(new byte[0]))).code());
        }
    }

    @Test
    void physicalCapacityIncludesUnwrittenReservationsAndHeadroomWithoutOverflow() throws Exception {
        assertDoesNotThrow(() -> FileStorageService.validatePhysicalCapacity(100, 20, 64, 16));
        assertEquals("STORAGE_FULL", assertThrows(TransferException.class,
                () -> FileStorageService.validatePhysicalCapacity(100, 20, 65, 16)).code());
        assertThrows(TransferException.class,
                () -> FileStorageService.validatePhysicalCapacity(100, 0, 0, 101));
        assertThrows(TransferException.class,
                () -> FileStorageService.validatePhysicalCapacity(Long.MAX_VALUE, Long.MAX_VALUE, 1, 0));
        assertDoesNotThrow(() -> FileStorageService.validatePhysicalCapacity(Long.MAX_VALUE, 0, Long.MAX_VALUE, 0));
        try (var storage = new FileStorageService(directory.resolve("storage"), 100, 100, 4,
                4, Duration.ofDays(1), true, Long.MAX_VALUE)) {
            assertEquals("STORAGE_FULL", assertThrows(TransferException.class,
                    () -> begin(storage, "empty", new byte[0])).code());
            assertEquals(0, storage.accountedBytes());
            assertTrue(storage.pendingSessions().isEmpty());
        }
    }

    @Test
    void pendingSnapshotIsImmutableAndDoesNotChangeWhenTransfersComplete() throws Exception {
        try (var storage = storage(100, 100, 4)) {
            var session = begin(storage, "empty", new byte[0]);
            var pending = storage.pendingSessions();
            assertEquals(1, pending.size());
            assertSame(session, pending.getFirst());
            assertThrows(UnsupportedOperationException.class, pending::clear);
            storage.cancel(session);
            assertEquals(1, pending.size());
            assertTrue(storage.pendingSessions().isEmpty());
        }
    }

    @Test
    void recoveryFailsSafelyWhenPendingLimitIsLoweredBelowDurableSessionCount() throws Exception {
        try (var storage = storage(100, 100, 4)) {
            begin(storage, "one", new byte[0]);
            begin(storage, "two", new byte[0]);
        }
        assertThrows(IllegalStateException.class, () -> new FileStorageService(directory.resolve("storage"),
                100, 100, 4, 1, Duration.ofDays(1), true));
        try (var storage = storage(100, 100, 4)) {
            assertEquals(2, storage.pendingSessions().size());
        }
    }

    private FileStorageService storage(long quota, long maxFile, int maxPending) throws IOException {
        return new FileStorageService(directory.resolve("storage"), quota, maxFile, maxPending, Duration.ofHours(24), true);
    }

    private static FileStorageService.Session begin(FileStorageService storage, String filename, byte[] bytes) throws Exception {
        return storage.begin(UUID.randomUUID(), 1, 11, filename, "application/octet-stream", bytes.length, hash(bytes));
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void ageManifest(UUID id) throws IOException {
        Path manifest = directory.resolve("storage/temporary/" + id + ".properties");
        Properties metadata = new Properties();
        try (var input = Files.newInputStream(manifest)) { metadata.load(input); }
        metadata.setProperty("createdAtMillis", "0");
        try (var output = Files.newOutputStream(manifest)) { metadata.store(output, "expired test session"); }
    }
}
