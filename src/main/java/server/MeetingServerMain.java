package server;

import com.google.gson.Gson;
import common.auth.TlsSupport;
import common.diagnostics.Diagnostics;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import server.auth.AuthService;
import server.auth.SessionValidator;
import server.config.MeetingSecrets;
import server.config.ServerConfig;
import server.meeting.RoomRepository;
import server.meeting.RoomService;
import server.signalingapi.MeetingHandler;
import server.signalingapi.SignalingServer;

/** Combined auth + meeting control JVM, with one diagnostics owner. No implicit migrations. */
public final class MeetingServerMain {
    private MeetingServerMain() { }

    public static void main(String[] args) {
        try (Diagnostics diagnostics = Diagnostics.start("server")) {
            try {
                MeetingSecrets secrets = MeetingSecrets.load();
                AtomicReference<SignalingServer> signalingReference = new AtomicReference<>();
                Gson json = new Gson();
                try (RoomService rooms = new RoomService(new RoomRepository(), Clock.systemUTC(), secrets.instanceId(),
                        secrets.receiptKey(), ServerConfig.getInt("meeting.maxParticipants", 10), event -> {
                            SignalingServer server = signalingReference.get();
                            if (server != null) server.event(event.recipientConnectionId(), event.type(), event.roomId(),
                                    event.revision(), json.toJsonTree(event.body()).getAsJsonObject());
                        })) {
                    rooms.start(); // Preflight/reconcile only; never creates tables.
                    char[] password = ServerConfig.get("auth.keystorePassword", "changeit").toCharArray();
                    javax.net.ssl.SSLContext tls;
                    try { tls = TlsSupport.server(Path.of(ServerConfig.get("auth.keystore", "config/auth-dev.p12")), password); }
                    finally { java.util.Arrays.fill(password, '\0'); }
                    try (SignalingServer signaling = new SignalingServer(tls,
                            new InetSocketAddress(ServerConfig.get("signaling.bind", "127.0.0.1"), ServerConfig.getInt("signaling.port", 8444)),
                            new SessionValidator(), new MeetingHandler(rooms), rooms.serverEpoch(),
                            new SignalingServer.Options(ServerConfig.getInt("signaling.maxConnections", 64),
                                    ServerConfig.getInt("signaling.maxBytes", common.signaling.SignalWire.DEFAULT_MAX_BYTES),
                                    ServerConfig.getInt("signaling.requestsPerSecond", 20)))) {
                        signalingReference.set(signaling);
                        AuthService auth = new AuthService(hash -> { signaling.revokeSession(hash); rooms.revokeSession(hash); });
                        try (AuthServer authServer = new AuthServer(tls,
                                new InetSocketAddress(ServerConfig.get("auth.bind", "127.0.0.1"), ServerConfig.getInt("auth.port", 8443)), auth::handle)) {
                            Thread shutdown = new Thread(() -> {
                                try {
                                    signaling.close();
                                } finally {
                                    try { authServer.close(); }
                                    catch (java.io.IOException failure) {
                                        Diagnostics.current().failure("meeting.auth.close_failed", failure, Map.of("phase", "close"));
                                    } finally { rooms.close(); }
                                }
                            }, "meeting-shutdown");
                            Runtime.getRuntime().addShutdownHook(shutdown);
                            try {
                                authServer.start(); signaling.start();
                                System.out.println("MMR_MEETING_SERVER_READY");
                                signaling.awaitTermination();
                            } finally {
                                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                                catch (IllegalStateException stopping) { }
                            }
                        }
                    }
                }
            } catch (Exception failure) {
                Diagnostics.current().failure("meeting.startup.failed", failure, Map.of("phase", "startup"));
                System.err.println("MMR_MEETING_STARTUP_FAILED: check reviewed schema, server configuration and ports.");
                throw new IllegalStateException("MEETING_STARTUP_FAILED");
            }
        }
    }
}
