package support;

import client.meeting.MeetingSession;
import client.signalingapi.SignalingClient;
import com.google.gson.Gson;
import common.auth.AuthResponse;
import common.signaling.SignalWire;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import server.auth.SessionValidator;
import server.media.*;
import server.meeting.*;
import server.signalingapi.*;

/** Isolated H2 + TLS + actual Go video process; never opens devices or writes Railway. */
public final class VideoTestFixture implements AutoCloseable {
    public final RoomTestFixture database;
    public final RoomService rooms;
    public final server.AuthServer collaboration;
    private final javax.net.ssl.SSLContext clientTls;
    public final SignalingServer signaling;
    public final VideoEngineClient engine;
    public final VideoCoordinator coordinator;
    private final Process process;
    private final RoomTestFixture.Tls tls;
    private final Path diagnosticDirectory;
    public VideoTestFixture(RoomTestFixture.Tls tls, Path temporary) throws Exception {
        Files.createDirectories(temporary);
        this.tls = tls; clientTls = tls.client(); database = new RoomTestFixture(tls);
        String migration = Files.readString(Path.of("database/migrations/001-meeting-runtime.sql"))
                .replaceAll("(?m)^--[^\\r\\n]*", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
        for (String sql : migration.split(";")) if (!sql.isBlank()) database.execute(sql);
        Path binary = Path.of(System.getProperty("mmr.video.test.binary", ".tools/bin/mmr-video.exe")).toAbsolutePath();
        if (!Files.isRegularFile(binary)) throw new IllegalStateException("BUILD_VIDEO_ENGINE_BEFORE_TEST");
        int port; try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        byte[] key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        ProcessBuilder builder = new ProcessBuilder(binary.toString()).directory(Path.of("").toAbsolutePath().toFile());
        builder.environment().put("MMR_MEDIA_CONTROL_SECRET", secret); builder.environment().put("MMR_MEDIA_CONTROL_PORT", Integer.toString(port));
        diagnosticDirectory = temporary.resolve("go-diagnostics");
        builder.environment().put("MMR_DIAGNOSTICS_DIR", diagnosticDirectory.toString());
        builder.environment().remove("MMR_DB_URL"); builder.environment().remove("MMR_DB_USER"); builder.environment().remove("MMR_DB_PASSWORD");
        // Match production and avoid transient Windows locks on redirected files during JUnit cleanup.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        process = builder.start();
        VideoEngineClient ready = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (process.isAlive() && ready == null && System.nanoTime() < deadline) {
            try { ready = new VideoEngineClient(port, secret); } catch (Exception pending) { Thread.sleep(50); }
        }
        if (ready == null) { process.destroyForcibly(); database.close(); throw new IllegalStateException("VIDEO_TEST_PROCESS_FAILED"); }
        engine = ready;
        AtomicReference<SignalingServer> signalReference = new AtomicReference<>();
        AtomicReference<VideoCoordinator> mediaReference = new AtomicReference<>(); Gson json = new Gson();
        rooms = new RoomService(new RoomRepository(database::connection), Clock.systemUTC(), "video-test", new byte[32], 10, event -> {
            VideoCoordinator media = mediaReference.get(); if (media != null) media.roomEvent(event);
            SignalingServer server = signalReference.get(); if (server != null) server.event(event.recipientConnectionId(), event.type(), event.roomId(),
                    event.revision(), json.toJsonTree(event.body()).getAsJsonObject());
        }); rooms.start();
        coordinator = new VideoCoordinator(rooms, engine); mediaReference.set(coordinator);
        signaling = new SignalingServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new SessionValidator(database::connection, Clock.systemUTC()), new MeetingHandler(rooms, coordinator),
                rooms.serverEpoch(), SignalingServer.Options.defaults()); signalReference.set(signaling); signaling.start();
        var auth = new server.auth.AuthService(database::connection, new server.auth.PasswordHasher(), Clock.systemUTC(), Duration.ofHours(24), hash -> { signaling.revokeSession(hash); rooms.revokeSession(hash); });
        collaboration = new server.AuthServer(tls.server(), new InetSocketAddress("127.0.0.1", 0),
                new server.ApplicationService(auth, new server.rooms.RoomService(database::connection, new server.auth.PasswordHasher(), Clock.systemUTC(), true))::handle, rooms);
        collaboration.start();
    }
    public client.auth.AuthClient client() { return new client.auth.AuthClient("localhost", collaboration.port(), clientTls, 5000); }
    public MeetingSession session(AuthResponse account) {
        return session(account, ignored -> { });
    }
    public MeetingSession session(AuthResponse account, java.util.function.Consumer<SignalingClient> created) {
        return new MeetingSession(account.token(), Instant.parse(account.expiresAt()), listener -> {
            var transport = new SignalingClient(new SignalingClient.Settings("localhost", signaling.port(), null, SignalWire.DEFAULT_MAX_BYTES), tls.client(), listener);
            created.accept(transport); return transport;
        },
                session -> new client.media.VideoCallController(session::videoRequest, session::snapshot, session::selfParticipantId,
                        new client.media.CameraConsentStore(diagnosticDirectory.getParent().resolve("camera-" + UUID.randomUUID() + ".properties")), List::of, true, new client.media.MicrophoneConsentStore(diagnosticDirectory.getParent().resolve("mic-" + UUID.randomUUID() + ".properties"))));
    }
    @Override public void close() throws Exception {
        signaling.close(); collaboration.close(); rooms.close(); coordinator.close(); engine.shutdown(); engine.close();
        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("VIDEO_TEST_PROCESS_LEAK"); }
        database.close();
        // Retain bounded, sanitized numeric evidence after JUnit removes its temporary directory.
        if (Files.isDirectory(diagnosticDirectory)) {
            Path evidence = Path.of("target/video-diagnostics"); Files.createDirectories(evidence);
            try (var logs = Files.newDirectoryStream(diagnosticDirectory, "*.txt")) {
                for (Path log : logs) Files.copy(log, evidence.resolve(log.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
