package server.media;

import client.signalingapi.SignalingClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import common.meeting.CreateRoomParams;
import common.meeting.MemberAuthorization;
import common.meeting.RoomEvent;
import common.meeting.RoomSnapshot;
import common.signaling.SignalMessage;
import common.signaling.SignalWire;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import server.auth.SessionValidator;
import server.meeting.RoomRepository;
import server.meeting.RoomService;
import server.signalingapi.SignalingServer;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real authenticated connection and H2 rooms; a loopback control server records media grants. */
@Timeout(35)
class VideoCoordinatorTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    private RoomTestFixture database;
    private RoomService rooms;
    private SignalingServer signaling;
    private SignalingClient client;
    private SignalingServer.Connection connection;
    private VideoEngineClient engine;
    private FakeEngine fake;
    private VideoCoordinator coordinator;
    private ThreadPoolExecutor controls;
    private final List<CountDownLatch> gates = new ArrayList<>();

    @BeforeAll @Timeout(60)
    static void certificates() throws Exception { tls = RoomTestFixture.certificates(temporary); }

    @BeforeEach
    void start() throws Exception {
        database = new RoomTestFixture(tls);
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "")
                .replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String statement : migration.split(";")) if (!statement.isBlank()) database.execute(statement);
        AtomicReference<VideoCoordinator> current = new AtomicReference<>();
        rooms = new RoomService(new RoomRepository(database::connection), Clock.systemUTC(), "media-reopen-test",
                new byte[32], 10, event -> {
                    VideoCoordinator target = current.get();
                    if (target != null) target.roomEvent(event);
                });
        rooms.start();
        fake = new FakeEngine();
        engine = new VideoEngineClient(fake.port(), "test-control-only");
        controls = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
                Thread.ofPlatform().daemon().name("media-reopen-test-control").factory());
        coordinator = new VideoCoordinator(rooms, engine, controls);
        current.set(coordinator);
        AtomicReference<SignalingServer.Connection> authenticated = new AtomicReference<>();
        signaling = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(database::connection, Clock.systemUTC()), new SignalingServer.Handler() {
                    public SignalMessage handle(SignalingServer.Connection source, SignalMessage request) {
                        authenticated.set(source);
                        return source.message("CAPTURED", request.requestId(), null, null, new JsonObject());
                    }
                    public void disconnected(SignalingServer.Connection source, String reason) {
                        coordinator.revoke(source.id()); rooms.leave(source.id(), "MAT_KET_NOI");
                    }
                }, rooms.serverEpoch(), SignalingServer.Options.defaults());
        signaling.start();
        var account = database.account("MediaHost", "Chủ phòng");
        client = new SignalingClient(new SignalingClient.Settings("localhost", signaling.port(), null,
                SignalWire.DEFAULT_MAX_BYTES), tls.client(), ignored -> { });
        client.connect(account.token());
        client.request("CAPTURE", null, new JsonObject()).get(6, TimeUnit.SECONDS);
        connection = authenticated.get();
        assertNotNull(connection);
    }

    @AfterEach
    void close() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        if (client != null) client.close();
        if (signaling != null) signaling.close();
        if (rooms != null) rooms.close();
        if (coordinator != null) coordinator.close();
        if (controls != null) assertTrue(controls.awaitTermination(5, TimeUnit.SECONDS));
        if (engine != null) engine.close();
        if (fake != null) fake.close();
        if (database != null) database.close();
    }

    @Test
    void queuedRoomCloseRevokesOldGrantAndPreservesReopenedRoomOwner() throws Exception {
        RoomSnapshot original = create();
        MemberAuthorization old = joinAndGrant(original);
        CountDownLatch release = blockControls();
        rooms.leave(connection.id(), "TU_ROI");
        MemberAuthorization fresh = joinAndGrant(reopen(original));
        assertNotEquals(old.roomId(), fresh.roomId());
        assertTrue(fake.has(old)); assertTrue(fake.has(fresh));

        release.countDown(); drainControls();
        assertFalse(fake.has(old), "The ended membership's lease must be revoked");
        assertTrue(fake.has(fresh), "Queued room closure must preserve the new membership's lease");
        assertOwnerStillRenews(fresh);
    }

    @Test
    void queuedValidationFailureCannotRemoveNewOwnerOnSameConnection() throws Exception {
        RoomSnapshot original = create();
        MemberAuthorization old = joinAndGrant(original);
        CountDownLatch release = blockControls();
        coordinator.validated(connection);
        rooms.leave(connection.id(), "TU_ROI");
        MemberAuthorization fresh = joinAndGrant(reopen(original));

        release.countDown(); drainControls();
        assertFalse(fake.has(old));
        assertTrue(fake.has(fresh), "Validation captured before reopening must not revoke the new owner");
        assertOwnerStillRenews(fresh);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROOM_CLOSED", "ROOM_LEFT"})
    void lateTerminalEventForOldRoomCannotRevokeReopenedRoom(String eventType) throws Exception {
        RoomSnapshot original = create();
        MemberAuthorization old = joinAndGrant(original);
        rooms.leave(connection.id(), "TU_ROI"); drainControls();
        MemberAuthorization fresh = joinAndGrant(reopen(original));

        coordinator.roomEvent(new RoomEvent(connection.id(), eventType, old.roomId(), 99, Map.of()));
        drainControls();
        assertTrue(fake.has(fresh), "Terminal events must match the owner's room generation");
        assertOwnerStillRenews(fresh);
    }

    private RoomSnapshot create() throws Exception {
        return rooms.create(connection.principal(), UUID.randomUUID().toString(),
                new CreateRoomParams("Cuộc họp tiếp tục", "1234", 3, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDIO", "VIDEO", "SCREEN"})
    void hostStopReachesEngineForEachMediaKind(String kind) throws Exception {
        MemberAuthorization member = joinAndGrant(create());
        rooms.setMediaRequested(connection.id(), kind, true);
        rooms.stopPublish(connection.id(), member.participantId(), kind);
        drainControls();
        assertEquals(1, fake.stops(member, kind));
        for (String other : List.of("AUDIO", "VIDEO", "SCREEN"))
            if (!other.equals(kind)) assertEquals(0, fake.stops(member, other));
        assertTrue(fake.has(member), "Stopping one media kind must preserve membership");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDIO", "VIDEO", "SCREEN"})
    void queuedStopDoesNotStopAnExplicitlyRestartedPublication(String kind) throws Exception {
        MemberAuthorization member = joinAndGrant(create());
        rooms.setMediaRequested(connection.id(), kind, true);
        CountDownLatch release = blockControls();
        rooms.stopPublish(connection.id(), member.participantId(), kind);
        rooms.setMediaRequested(connection.id(), kind, true);
        release.countDown(); drainControls();
        assertEquals(0, fake.stops(member, kind));
        assertTrue(fake.has(member));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDIO", "VIDEO", "SCREEN"})
    void queuedMediaStopCannotRevokeReopenedRoomOwner(String kind) throws Exception {
        RoomSnapshot original = create();
        MemberAuthorization old = joinAndGrant(original);
        rooms.setMediaRequested(connection.id(), kind, true);
        CountDownLatch release = blockControls();
        rooms.stopPublish(connection.id(), old.participantId(), kind);
        rooms.leave(connection.id(), "TU_ROI");
        MemberAuthorization fresh = joinAndGrant(reopen(original));
        rooms.setMediaRequested(connection.id(), kind, true);
        release.countDown(); drainControls();
        assertEquals(0, fake.stops(fresh, kind));
        assertTrue(fake.has(fresh));
        assertOwnerStillRenews(fresh);
    }

    private RoomSnapshot reopen(RoomSnapshot original) throws Exception {
        return rooms.reopen(connection.principal(), UUID.randomUUID().toString(), original.joinCode());
    }

    private MemberAuthorization joinAndGrant(RoomSnapshot room) throws Exception {
        rooms.join(connection.principal(), connection.id(), connection.generation(), UUID.randomUUID().toString(),
                room.roomId(), room.joinCode(), "", false, false);
        coordinator.handle(connection, connection.message("VIDEO_STATE", UUID.randomUUID().toString(),
                room.roomId(), null, new JsonObject()));
        return rooms.assertMember(connection.id(), room.roomId());
    }

    private CountDownLatch blockControls() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        gates.add(release);
        controls.execute(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        return release;
    }

    private void drainControls() throws Exception { controls.submit(() -> { }).get(6, TimeUnit.SECONDS); }

    private void assertOwnerStillRenews(MemberAuthorization fresh) throws Exception {
        int renewals = fake.renewals(fresh);
        coordinator.validated(connection); drainControls();
        assertTrue(fake.has(fresh));
        assertEquals(renewals + 1, fake.renewals(fresh), "The new owner must remain registered for renewal");
    }

    private static final class FakeEngine implements AutoCloseable {
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<String> leases = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, AtomicInteger> renewals = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> stops = new ConcurrentHashMap<>();

        FakeEngine() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
            server.setExecutor(requests);
            server.createContext("/health", exchange -> {
                JsonObject body = new JsonObject(); body.addProperty("instance", "a".repeat(32));
                body.addProperty("pid", ProcessHandle.current().pid());
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.createContext("/v1/control", exchange -> {
                JsonObject body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                String operation = body.get("operation").getAsString();
                String key = body.get("room").getAsString() + "/" + body.get("participant").getAsString()
                        + "/" + body.get("generation").getAsLong();
                JsonObject response = new JsonObject();
                switch (operation) {
                    case "renew" -> { leases.add(key); renewals.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet(); }
                    case "revoke" -> leases.remove(key);
                    case "state" -> response.add("sources", new JsonObject());
                    case "stop" -> stops.computeIfAbsent(key + "/" + body.get("kind").getAsString(), ignored -> new AtomicInteger()).incrementAndGet();
                    default -> throw new IllegalStateException("Unexpected test control operation: " + operation);
                }
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
            });
            server.start();
        }

        int port() { return server.getAddress().getPort(); }
        boolean has(MemberAuthorization member) { return leases.contains(key(member)); }
        int renewals(MemberAuthorization member) { return renewals.getOrDefault(key(member), new AtomicInteger()).get(); }
        int stops(MemberAuthorization member, String kind) { return stops.getOrDefault(key(member) + "/" + kind, new AtomicInteger()).get(); }
        private static String key(MemberAuthorization member) {
            return member.roomId() + "/" + member.participantId() + "/" + member.generation();
        }
        @Override public void close() { server.stop(0); requests.close(); }
    }
}
