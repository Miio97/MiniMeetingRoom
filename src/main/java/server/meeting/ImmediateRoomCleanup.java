package server.meeting;

import server.auth.ConnectionProvider;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Bounded, restartable cleanup using existing closed-room rows; no schema or retention deadline.
 */
public final class ImmediateRoomCleanup implements AutoCloseable, RoomReopenGuard {
    @FunctionalInterface
    public interface FilesCleanup {
        void run(long roomId) throws Exception;
    }

    @FunctionalInterface
    public interface PendingFiles {
        boolean test(long roomId) throws Exception;
    }

    private record Retry(int failures, long after) {}

    private final ConnectionProvider connections;
    private final String instance;
    private final FilesCleanup files;
    private final PendingFiles pendingFiles;
    private final Runnable changed;
    private final ScheduledThreadPoolExecutor worker =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("room-content-cleanup").factory());
    private final ConcurrentHashMap<Long, Retry> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean queued = new AtomicBoolean(), closed = new AtomicBoolean();
    private long cursor;

    public ImmediateRoomCleanup(
            ConnectionProvider connections,
            String instance,
            FilesCleanup files,
            PendingFiles pendingFiles,
            Runnable changed) {
        this.connections = connections;
        this.instance = instance;
        this.files = files;
        this.pendingFiles = pendingFiles;
        this.changed = changed;
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void start() {
        worker.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    public boolean isPending(long roomId) {
        return pending.containsKey(roomId);
    }

    public void request(long roomId) {
        if (closed.get()) return;
        if (roomId > 0 && pending.size() < 256) pending.putIfAbsent(roomId, new Retry(0, 0));
        if (queued.compareAndSet(false, true))
            try {
                worker.execute(
                        () -> {
                            queued.set(false);
                            tick();
                        });
            } catch (RejectedExecutionException stopped) {
                queued.set(false);
            }
    }

    @Override
    public void check(Connection connection, long roomId) throws SQLException {
        if (pending.containsKey(roomId)) throw new RoomException("ROOM_CLEANING");
        try {
            RoomReopenGuard.requireEmpty(connection, roomId);
        } catch (RoomException cleaning) {
            if (!owned(connection, roomId)) throw new RoomException("ROOM_CLEANUP_OWNER_REQUIRED");
            throw cleaning;
        }
        try {
            if (pendingFiles.test(roomId))
                throw new RoomException(
                        owned(connection, roomId)
                                ? "ROOM_CLEANING"
                                : "ROOM_CLEANUP_OWNER_REQUIRED");
        } catch (RoomException cleaning) {
            throw cleaning;
        } catch (Exception failure) {
            throw new SQLException("ROOM_CLEANUP_UNAVAILABLE", failure);
        }
    }

    private void tick() {
        if (closed.get()) return;
        try {
            // Never infer ownership for old unmarked legacy rows in a shared database.
            try (var connection = connections.getConnection();
                    var query =
                            connection.prepareStatement(
                                    "SELECT p.Ma_Phong,EXISTS(SELECT 1 FROM Tin_Nhan t WHERE"
                                            + " t.Ma_Phong=p.Ma_Phong) FROM Phong_Hop p JOIN"
                                            + " MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong WHERE"
                                            + " p.Trang_Thai='DA_DONG' AND x.Instance_ID=? AND"
                                            + " p.Ma_Phong>? ORDER BY p.Ma_Phong LIMIT 32")) {
                query.setString(1, instance);
                query.setLong(2, cursor);
                int count = 0;
                try (var rows = query.executeQuery()) {
                    while (rows.next()) {
                        cursor = rows.getLong(1);
                        if (pending.size() < 256
                                && (rows.getBoolean(2) || pendingFiles.test(cursor)))
                            pending.putIfAbsent(cursor, new Retry(0, 0));
                        count++;
                    }
                }
                if (count < 32) cursor = 0;
            }
            for (var entry : List.copyOf(pending.entrySet())) {
                if (closed.get()) break;
                if (System.nanoTime() < entry.getValue().after()) continue;
                try {
                    if (clean(entry.getKey())) {
                        pending.remove(entry.getKey(), entry.getValue());
                        changed.run();
                    }
                } catch (Exception failure) {
                    int attempts = Math.min(6, entry.getValue().failures() + 1);
                    pending.replace(
                            entry.getKey(),
                            entry.getValue(),
                            new Retry(
                                    attempts,
                                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1L << attempts)));
                    System.err.println("ROOM_CLEANUP_RETRY: " + failure.getClass().getSimpleName());
                }
            }
        } catch (Exception failure) {
            System.err.println("ROOM_CLEANUP_SCAN_FAILED: " + failure.getClass().getSimpleName());
        }
    }

    /** Row locks match reopen; files are removed before their rows, in bounded passes. */
    public boolean clean(long roomId) throws Exception {
        try (var connection = connections.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT p.Trang_Thai FROM Phong_Hop p WHERE p.Ma_Phong=?")) {
            query.setLong(1, roomId);
            try (var rows = query.executeQuery()) {
                if (!rows.next() || !"DA_DONG".equals(rows.getString(1))) return true;
            }
            if (!owned(connection, roomId)) throw new RoomException("ROOM_CLEANUP_OWNER_REQUIRED");
        }
        files.run(roomId);
        try (var connection = connections.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                try (var query =
                        connection.prepareStatement(
                                "SELECT Trang_Thai FROM Phong_Hop WHERE Ma_Phong=? FOR UPDATE")) {
                    query.setLong(1, roomId);
                    try (var rows = query.executeQuery()) {
                        if (!rows.next() || !"DA_DONG".equals(rows.getString(1))) {
                            connection.rollback();
                            return true;
                        }
                    }
                }
                try (var query =
                        connection.prepareStatement(
                                "SELECT 1 FROM Tep_Dinh_Kem f JOIN Tin_Nhan t ON"
                                    + " t.Ma_Tin_Nhan=f.Ma_Tin_Nhan WHERE t.Ma_Phong=? LIMIT 1")) {
                    query.setLong(1, roomId);
                    try (var rows = query.executeQuery()) {
                        if (rows.next()) {
                            connection.rollback();
                            return false;
                        }
                    }
                }
                if (pendingFiles.test(roomId)) {
                    connection.rollback();
                    return false;
                }
                var ids = new ArrayList<Long>();
                try (var query =
                        connection.prepareStatement(
                                "SELECT Ma_Tin_Nhan FROM Tin_Nhan WHERE Ma_Phong=? ORDER BY"
                                        + " Ma_Tin_Nhan LIMIT 500")) {
                    query.setLong(1, roomId);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) ids.add(rows.getLong(1));
                    }
                }
                try (var delete =
                        connection.prepareStatement(
                                "DELETE FROM Tin_Nhan WHERE Ma_Tin_Nhan=? AND Ma_Phong=?")) {
                    for (long id : ids) {
                        delete.setLong(1, id);
                        delete.setLong(2, roomId);
                        delete.addBatch();
                    }
                    if (!ids.isEmpty()) delete.executeBatch();
                }
                boolean empty;
                try (var query =
                        connection.prepareStatement(
                                "SELECT 1 FROM Tin_Nhan WHERE Ma_Phong=? LIMIT 1")) {
                    query.setLong(1, roomId);
                    try (var rows = query.executeQuery()) {
                        empty = !rows.next();
                    }
                }
                connection.commit();
                return empty;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private boolean owned(Connection connection, long roomId) throws SQLException {
        try (var query =
                connection.prepareStatement(
                        "SELECT Instance_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=?")) {
            query.setLong(1, roomId);
            try (var rows = query.executeQuery()) {
                return rows.next() && instance.equals(rows.getString(1));
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            pending.clear();
        }
    }
}
