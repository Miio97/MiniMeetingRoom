package server.meeting;

import com.google.gson.*;

import common.room.RoomInfo;

import server.auth.ConnectionProvider;
import server.signalingapi.SignalingServer;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Permission-filtered, coalesced delta feed. No database polling while unchanged. */
public final class RoomCatalog implements AutoCloseable {
    private static final Gson JSON = new Gson();

    private static final class Watch {
        final SignalingServer.Connection connection;
        final long user;
        final int limit;
        String watchId;
        Set<Long> focus = Set.of();
        Map<Long, RoomInfo> last = Map.of();
        Set<Long> cleaning = Set.of();
        long revision;

        Watch(SignalingServer.Connection connection, long user, int limit) {
            this.connection = connection;
            this.user = user;
            this.limit = limit;
        }
    }

    private final ConnectionProvider connections;
    private final ConcurrentHashMap<String, Watch> watchers = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor worker =
            new ScheduledThreadPoolExecutor(
                    1, Thread.ofPlatform().daemon().name("room-catalog").factory());
    private final AtomicBoolean queued = new AtomicBoolean(),
            dirty = new AtomicBoolean(),
            closed = new AtomicBoolean();
    private volatile LongPredicate cleanup = id -> false;
    private volatile SignalingServer server;
    private int failures;

    public RoomCatalog(ConnectionProvider connections) {
        this.connections = connections;
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void bind(SignalingServer server) {
        this.server = server;
    }

    public void cleanupStatus(LongPredicate cleanup) {
        this.cleanup = cleanup;
    }

    public JsonObject subscribe(SignalingServer.Connection connection, int limit) throws Exception {
        return subscribe(connection, limit, java.util.UUID.randomUUID().toString());
    }

    public JsonObject subscribe(SignalingServer.Connection connection, int limit, String watchId)
            throws Exception {
        return subscribe(connection, limit, watchId, Set.of());
    }

    public JsonObject subscribe(
            SignalingServer.Connection connection, int limit, String watchId, Set<Long> focus)
            throws Exception {
        if (focus.size() > 100 || focus.stream().anyMatch(id -> id < 1))
            throw new common.signaling.SignalException("INVALID_REQUEST");
        if (watchers.size() >= 256 && !watchers.containsKey(connection.id()))
            throw new common.signaling.SignalException("SERVER_BUSY");
        var watch =
                new Watch(
                        connection,
                        connection.principal().userId(),
                        Math.max(focus.size(), Math.max(3, Math.min(200, limit))));
        watch.focus = Set.copyOf(focus);
        watch.watchId = watchId;
        synchronized (watch) {
            var body = read(watch, true);
            watchers.put(connection.id(), watch);
            invalidate();
            return body;
        }
    }

    public void unsubscribe(String connection) {
        watchers.remove(connection);
    }

    public void invalidate() {
        if (closed.get()) return;
        dirty.set(true);
        if (queued.compareAndSet(false, true))
            try {
                worker.schedule(this::drain, 50, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                queued.set(false);
            }
    }

    private void drain() {
        dirty.set(false);
        boolean failed = false;
        for (var watch : watchers.values()) {
            if (closed.get()) break;
            if (watch.connection.isClosed()) {
                unsubscribe(watch.connection.id());
                continue;
            }
            try {
                synchronized (watch) {
                    var delta = read(watch, false);
                    if (delta != null && server != null)
                        server.event(
                                watch.connection.id(), "ROOM_CATALOG_CHANGED", null, null, delta);
                }
            } catch (Exception failure) {
                failed = true;
            }
        }
        queued.set(false);
        if (failed && !closed.get()) {
            int backoff = Math.min(30, 1 << Math.min(5, failures++));
            if (queued.compareAndSet(false, true))
                try {
                    worker.schedule(this::drain, backoff, TimeUnit.SECONDS);
                } catch (RejectedExecutionException stopped) {
                    queued.set(false);
                }
        } else {
            failures = 0;
            if (dirty.get()) invalidate();
        }
    }

    private JsonObject read(Watch watch, boolean snapshot) throws Exception {
        var next = new LinkedHashMap<Long, RoomInfo>();
        var cleaning = new HashSet<Long>();
        String priority =
                watch.focus.isEmpty()
                        ? ""
                        : "CASE WHEN p.Ma_Phong IN ("
                                + String.join(",", Collections.nCopies(watch.focus.size(), "?"))
                                + ") THEN 0 ELSE 1 END,";
        try (var connection = connections.getConnection();
                var query =
                        connection.prepareStatement(
                                "SELECT"
                                    + " p.Ma_Phong,p.Ten_Phong,p.Ma_Phong_Tham_Gia,p.Ma_Chu_Phong,p.Ngay_Tao,p.Trang_Thai,p.Da_Khoa,p.So_Nguoi_Toi_Da,(SELECT"
                                    + " COUNT(DISTINCT t.Ma_Nguoi_Dung) FROM Thanh_Vien_Phong t"
                                    + " WHERE t.Ma_Phong=p.Ma_Phong AND t.Roi_Phong_Luc IS"
                                    + " NULL),EXISTS(SELECT 1 FROM Tin_Nhan m WHERE"
                                    + " m.Ma_Phong=p.Ma_Phong),EXISTS(SELECT 1 FROM"
                                    + " MMR_Phong_Van_Hanh x WHERE x.Ma_Phong=p.Ma_Phong) FROM"
                                    + " Phong_Hop p WHERE p.Ma_Chu_Phong=? OR EXISTS(SELECT 1 FROM"
                                    + " Thanh_Vien_Phong h WHERE h.Ma_Phong=p.Ma_Phong AND"
                                    + " h.Ma_Nguoi_Dung=?) ORDER BY "
                                        + priority
                                        + "p.Ngay_Tao DESC,p.Ma_Phong DESC LIMIT ?")) {
            query.setLong(1, watch.user);
            query.setLong(2, watch.user);
            int index = 3;
            for (long id : watch.focus) query.setLong(index++, id);
            query.setInt(index, watch.limit);
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    long id = rows.getLong(1);
                    var created =
                            rows.getTimestamp(5, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    var room =
                            new RoomInfo(
                                    id,
                                    rows.getString(2),
                                    rows.getString(3),
                                    rows.getLong(4),
                                    created.toInstant().toString(),
                                    rows.getString(6),
                                    rows.getBoolean(7),
                                    rows.getInt(8),
                                    rows.getInt(9),
                                    List.of());
                    next.put(id, room);
                    if ("DA_DONG".equals(room.status())
                            && (rows.getBoolean(11) && rows.getBoolean(10) || cleanup.test(id)))
                        cleaning.add(id);
                }
            }
        }
        var missing = new HashSet<>(watch.last.keySet());
        missing.addAll(watch.focus);
        missing.removeAll(next.keySet());
        var stillAllowed = new HashSet<Long>();
        if (!missing.isEmpty())
            try (var connection = connections.getConnection();
                    var query =
                            connection.prepareStatement(
                                    "SELECT p.Ma_Phong FROM Phong_Hop p WHERE p.Ma_Phong IN ("
                                            + String.join(
                                                    ",", Collections.nCopies(missing.size(), "?"))
                                            + ") AND (p.Ma_Chu_Phong=? OR EXISTS(SELECT 1 FROM"
                                            + " Thanh_Vien_Phong h WHERE h.Ma_Phong=p.Ma_Phong AND"
                                            + " h.Ma_Nguoi_Dung=?))")) {
                int index = 1;
                for (long id : missing) query.setLong(index++, id);
                query.setLong(index++, watch.user);
                query.setLong(index, watch.user);
                try (var rows = query.executeQuery()) {
                    while (rows.next()) stillAllowed.add(rows.getLong(1));
                }
            }
        if (!snapshot
                && next.equals(watch.last)
                && cleaning.equals(watch.cleaning)
                && missing.isEmpty()) return null;
        var changed = new JsonArray();
        var removed = new JsonArray();
        var evicted = new JsonArray();
        for (var entry : next.entrySet())
            if (snapshot
                    || !entry.getValue().equals(watch.last.get(entry.getKey()))
                    || cleaning.contains(entry.getKey()) != watch.cleaning.contains(entry.getKey()))
                changed.add(JSON.toJsonTree(entry.getValue()));
        for (long id : missing) {
            if (stillAllowed.contains(id)) evicted.add(id);
            else removed.add(id);
        }
        var body = new JsonObject();
        body.addProperty("watchId", watch.watchId);
        body.addProperty("snapshot", snapshot);
        body.addProperty("revision", ++watch.revision);
        body.add("rooms", changed);
        body.add("removed", removed);
        body.add("cleaning", JSON.toJsonTree(cleaning));
        body.add("evicted", evicted);
        watch.last = Map.copyOf(next);
        watch.cleaning = Set.copyOf(cleaning);
        return body;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            worker.shutdownNow();
            watchers.clear();
        }
    }
}
