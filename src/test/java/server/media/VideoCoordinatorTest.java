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
    private final AtomicReference<SignalingServer.Connection> authenticated = new AtomicReference<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final java.util.concurrent.BlockingQueue<SignalMessage> events = new java.util.concurrent.LinkedBlockingQueue<>();

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
                SignalWire.DEFAULT_MAX_BYTES), tls.client(), events::offer);
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

    @Test
    void deviceReplacementRevokesOldMediaBeforeQueuedControlsRun() throws Exception {
        MemberAuthorization old = joinAndGrant(create());
        CountDownLatch release = blockControls();
        coordinator.roomEvent(new RoomEvent(connection.id(), "ROOM_LEFT", old.roomId(), 99,
                Map.of("reason", "DEVICE_REPLACED")));
        assertFalse(fake.has(old), "Replacement must retire the old generation before the new device joins");
        release.countDown(); drainControls();
        assertFalse(fake.has(old));
    }

    @Test
    void deviceTakeoverGrantsFreshGenerationAndDelayedOldCleanupPreservesIt() throws Exception {
        var room = create();
        var oldConnection = connection;
        MemberAuthorization old = joinAndGrant(room);
        CountDownLatch release = blockControls();
        coordinator.validated(oldConnection);
        var login = database.client().login("MediaHost", RoomTestFixture.PASSWORD);
        assertTrue(login.success());
        try (var replacement = new SignalingClient(new SignalingClient.Settings("localhost", signaling.port(), null,
                SignalWire.DEFAULT_MAX_BYTES), tls.client(), ignored -> { })) {
            replacement.connect(login.token());
            replacement.request("CAPTURE", null, new JsonObject()).get(6, TimeUnit.SECONDS);
            var freshConnection = authenticated.get();
            assertNotEquals(oldConnection.id(), freshConnection.id());
            rooms.join(freshConnection.principal(), freshConnection.id(), freshConnection.generation(), UUID.randomUUID().toString(),
                    room.roomId(), room.joinCode(), "1234", false, false);
            assertFalse(fake.has(old), "Old media must be gone when the replacement joins");
            coordinator.handle(freshConnection, freshConnection.message("VIDEO_STATE", UUID.randomUUID().toString(),
                    room.roomId(), null, new JsonObject()));
            var fresh = rooms.assertMember(freshConnection.id(), room.roomId());
            assertEquals(old.participantId(), fresh.participantId());
            assertNotEquals(old.generation(), fresh.generation());
            release.countDown(); drainControls();
            coordinator.revoke(oldConnection.id());
            assertTrue(fake.has(fresh), "Old connection cleanup must not revoke the new device's media");
        }
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

    @ParameterizedTest
    @ValueSource(strings={"VIDEO","AUDIO","SCREEN","SCREEN_AUDIO"})
    void unsubscribeForwardsBoundedTransportsWithMembership(String kind) throws Exception {
        var member=joinAndGrant(create());
        var body=new JsonObject();var ids=new com.google.gson.JsonArray();ids.add("receiver-1");body.add("transports",ids);
        coordinator.handle(connection,connection.message(kind+"_UNSUBSCRIBE",UUID.randomUUID().toString(),member.roomId(),null,body));
        var forwarded=fake.unsubscribe.get();
        assertEquals("unsubscribe",forwarded.get("operation").getAsString());
        assertEquals(kind,forwarded.get("kind").getAsString());
        assertEquals(member.participantId(),forwarded.get("participant").getAsString());
        assertEquals(ids,forwarded.getAsJsonArray("transports"));
        if(!kind.equals("AUDIO")) {
            for(int i=0;i<6;i++) ids.add("extra-"+i);
            assertEquals("INVALID_REQUEST",assertThrows(common.signaling.SignalException.class,() -> coordinator.handle(connection,
                    connection.message(kind+"_UNSUBSCRIBE",UUID.randomUUID().toString(),member.roomId(),null,body))).code());
        }
        for(int i=0;i<51;i++) ids.add("receiver-"+i);
        assertEquals("INVALID_REQUEST",assertThrows(common.signaling.SignalException.class,() -> coordinator.handle(connection,
                connection.message(kind+"_UNSUBSCRIBE",UUID.randomUUID().toString(),member.roomId(),null,body))).code());
    }
    @Test void screenStateConfirmsExactOwnPublicationWithoutSubscribingToSelf() throws Exception {
        var member=joinAndGrant(create());rooms.setMediaRequested(connection.id(),"SCREEN",true);
        var sources=new JsonObject();sources.addProperty(member.participantId(),"new-screen-transport");
        fake.activeSources.set(sources);
        var reply=coordinator.handle(connection,connection.message("SCREEN_STATE",UUID.randomUUID().toString(),member.roomId(),null,new JsonObject()));
        assertEquals("new-screen-transport",reply.get("publication").getAsString());
        assertFalse(reply.getAsJsonObject("sources").has(member.participantId()));
        assertTrue(rooms.assertMember(connection.id(),member.roomId()).mediaState().sharePublishing());
    }
    @Test void systemAudioRequiresShareAndDoesNotChangeMicOrShareFlags() throws Exception {
        var member=joinAndGrant(create());var body=new JsonObject();body.addProperty("sdp","v=0");body.addProperty("publication","screen-parent");
        var publish=connection.message("SCREEN_AUDIO_PUBLISH",UUID.randomUUID().toString(),member.roomId(),null,body);
        assertEquals("MEDIA_CLOSED",assertThrows(common.signaling.SignalException.class,() -> coordinator.handle(connection,publish)).code());
        rooms.setMediaRequested(connection.id(),"AUDIO",true);rooms.setMediaRequested(connection.id(),"SCREEN",true);
        coordinator.handle(connection,publish);
        assertEquals("SCREEN_AUDIO",fake.publication.get().get("kind").getAsString());
        assertEquals("screen-parent",fake.publication.get().get("publication").getAsString());
        coordinator.handle(connection,connection.message("SCREEN_AUDIO_STATE",UUID.randomUUID().toString(),member.roomId(),null,new JsonObject()));
        coordinator.handle(connection,connection.message("SCREEN_AUDIO_STOP",UUID.randomUUID().toString(),member.roomId(),null,new JsonObject()));
        var state=rooms.assertMember(connection.id(),member.roomId()).mediaState();
        assertTrue(state.micRequested());assertTrue(state.shareRequested());assertEquals(0,fake.stops(member,"AUDIO"));
    }
    private RoomSnapshot create() throws Exception {
        return rooms.create(connection.principal(), UUID.randomUUID().toString(),
                new CreateRoomParams("Cuộc họp tiếp tục", "1234", 3, false));
    }

    @Test
    void mediaWatchPushIsRecipientScopedAndPreservesPausedPublication() throws Exception {
        MemberAuthorization host = joinAndGrant(create());
        var preview = rooms.getRoom(connection.id(), host.roomId());
        var account = database.account("MediaGuest", "Người tham gia");
        var guestEvents = new java.util.concurrent.LinkedBlockingQueue<SignalMessage>();
        try (var guest = new SignalingClient(new SignalingClient.Settings("localhost", signaling.port(), null,
                SignalWire.DEFAULT_MAX_BYTES), tls.client(), guestEvents::offer)) {
            guest.connect(account.token()); guest.request("CAPTURE", null, new JsonObject()).get(6, TimeUnit.SECONDS);
            var guestConnection = authenticated.get();
            rooms.join(guestConnection.principal(), guestConnection.id(), guestConnection.generation(), UUID.randomUUID().toString(),
                    host.roomId(), preview.joinCode(), "1234", false, false);
            var member = rooms.assertMember(guestConnection.id(), host.roomId());
            JsonObject initial = coordinator.handle(guestConnection, guestConnection.message("MEDIA_WATCH", UUID.randomUUID().toString(), host.roomId(), null, new JsonObject()));
            assertTrue(initial.get("snapshot").getAsBoolean()); assertEquals(member.generation(), initial.get("generation").getAsLong());
            assertTrue(initial.get("revision").getAsLong() >= 1);
            rooms.setMediaRequested(connection.id(), "VIDEO", true);
            fake.source(host, "VIDEO", "host-video", false);
            SignalMessage published = awaitSources(guestEvents, "sources", "VIDEO", host.participantId(), "host-video");
            assertEquals(host.roomId(), published.roomId());
            assertEquals(member.generation(), published.body().get("generation").getAsLong());
            JsonObject own = coordinator.handle(connection, connection.message("MEDIA_WATCH", UUID.randomUUID().toString(), host.roomId(), null, new JsonObject()));
            assertFalse(own.getAsJsonObject("sources").getAsJsonObject("VIDEO").has(host.participantId()), "Own source must never loop back");
            rooms.setMediaRequested(connection.id(), "VIDEO", false);
            fake.source(host, "VIDEO", "host-video", true);
            SignalMessage paused = awaitSources(guestEvents, "pausedSources", "VIDEO", host.participantId(), "host-video");
            assertFalse(paused.body().getAsJsonObject("sources").getAsJsonObject("VIDEO").has(host.participantId()));
            assertTrue(paused.body().get("revision").getAsLong() > published.body().get("revision").getAsLong());
            JsonObject resynchronized = coordinator.handle(guestConnection, guestConnection.message("MEDIA_WATCH", UUID.randomUUID().toString(), host.roomId(), null, new JsonObject()));
            assertEquals("host-video", resynchronized.getAsJsonObject("pausedSources").getAsJsonObject("VIDEO").get(host.participantId()).getAsString(), "A repeat watch must return the authoritative cache even if no new push is due");
            assertTrue(resynchronized.get("revision").getAsLong() >= paused.body().get("revision").getAsLong());
            // Wrong membership generation cannot be resurrected by a cached engine event.
            fake.source(new MemberAuthorization(host.roomId(), host.participantId(), host.generation() + 1, host.role(), host.mediaState()), "VIDEO", "stale-video", false);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                SignalMessage event = guestEvents.poll(200, TimeUnit.MILLISECONDS);
                if (event != null && event.type().equals("MEDIA_SOURCES") && event.body().get("revision").getAsLong() > paused.body().get("revision").getAsLong()) {
                    assertFalse(event.body().getAsJsonObject("sources").getAsJsonObject("VIDEO").has(host.participantId())); observed = true; break;
                }
            }
            assertTrue(observed, "Generation change must produce a filtered authoritative snapshot");
        }
    }

    private SignalMessage awaitSources(java.util.concurrent.BlockingQueue<SignalMessage> queue, String field, String kind, String participant, String publication) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            SignalMessage event = queue.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && event.type().equals("MEDIA_SOURCES")) {
                JsonObject sources = event.body().getAsJsonObject(field).getAsJsonObject(kind);
                if (sources.has(participant) && sources.get(participant).getAsString().equals(publication)) return event;
            }
        }
        throw new AssertionError("Media source snapshot was not pushed");
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
        private final AtomicReference<JsonObject> unsubscribe=new AtomicReference<>();
        private final AtomicReference<JsonObject> publication=new AtomicReference<>();
        private final AtomicReference<JsonObject> activeSources=new AtomicReference<>(new JsonObject());
        private final HttpServer server;
        private final java.util.concurrent.ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
        private final Set<String> leases = ConcurrentHashMap.newKeySet();
        private final ConcurrentHashMap<String, AtomicInteger> renewals = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicInteger> stops = new ConcurrentHashMap<>();
        private final Object sourceLock = new Object();
        private final JsonObject sourceRooms = new JsonObject();
        private long sourceRevision = 1;

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
            server.createContext("/v1/sources", exchange -> {
                long after = Long.parseLong(exchange.getRequestURI().getQuery().substring("after=".length()));
                JsonObject body = new JsonObject();
                synchronized (sourceLock) {
                    if (after == sourceRevision) try { sourceLock.wait(500); } catch (InterruptedException ended) { Thread.currentThread().interrupt(); }
                    body.addProperty("instance", "a".repeat(32)); body.addProperty("revision", sourceRevision); body.add("rooms", sourceRooms.deepCopy());
                }
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
                    case "unsubscribe" -> unsubscribe.set(body);
                    case "publish" -> {publication.set(body);response.addProperty("sdp","v=0");response.addProperty("transport",body.get("transport").getAsString());}
                    case "renew" -> { leases.add(key); renewals.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet(); }
                    case "revoke" -> leases.remove(key);
                    case "state" -> response.add("sources", activeSources.get().deepCopy());
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
        void source(MemberAuthorization member, String kind, String publication, boolean paused) {
            synchronized (sourceLock) {
                JsonObject room = sourceRooms.getAsJsonObject(member.roomId());
                if (room == null) {
                    room = new JsonObject(); room.add("generations", new JsonObject());
                    for (String field : List.of("sources", "pausedSources")) {
                        JsonObject kinds = new JsonObject(); for (String media : List.of("VIDEO", "AUDIO", "SCREEN")) kinds.add(media, new JsonObject()); room.add(field, kinds);
                    }
                    sourceRooms.add(member.roomId(), room);
                }
                room.getAsJsonObject("generations").addProperty(member.participantId(), member.generation());
                room.getAsJsonObject(paused ? "sources" : "pausedSources").getAsJsonObject(kind).remove(member.participantId());
                room.getAsJsonObject(paused ? "pausedSources" : "sources").getAsJsonObject(kind).addProperty(member.participantId(), publication);
                sourceRevision++; sourceLock.notifyAll();
            }
        }
        boolean has(MemberAuthorization member) { return leases.contains(key(member)); }
        int renewals(MemberAuthorization member) { return renewals.getOrDefault(key(member), new AtomicInteger()).get(); }
        int stops(MemberAuthorization member, String kind) { return stops.getOrDefault(key(member) + "/" + kind, new AtomicInteger()).get(); }
        private static String key(MemberAuthorization member) {
            return member.roomId() + "/" + member.participantId() + "/" + member.generation();
        }
        @Override public void close() { server.stop(0); requests.close(); }
    }
}
