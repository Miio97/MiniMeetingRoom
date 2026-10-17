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
                AtomicReference<server.media.VideoCoordinator> videoReference = new AtomicReference<>();
                Gson json = new Gson();
                int maxParticipants = Math.min(50, Math.max(2, ServerConfig.getInt("meeting.maxParticipants", 20)));
                try (RoomService rooms = new RoomService(new RoomRepository(), Clock.systemUTC(), secrets.instanceId(),
                        secrets.receiptKey(), maxParticipants, event -> {
                            SignalingServer server = signalingReference.get();
                            var video = videoReference.get(); if (video != null) video.roomEvent(event);
                            if (server != null) server.event(event.recipientConnectionId(), event.type(), event.roomId(),
                                    event.revision(), json.toJsonTree(event.body()).getAsJsonObject());
                        })) {
                    rooms.start(); // Preflight/reconcile only; never creates tables.
                    var chatRetention = new server.meeting.ChatRetentionService(rooms.connectionProvider(), rooms.clock());
                    chatRetention.start();
                    var networkStats = new server.meeting.NetworkStatsService(rooms.connectionProvider(), rooms.clock());
                    networkStats.start();
                    var transferConfig = server.filetransfer.TransferConfig.load();
                    if (transferConfig.enabled()) {
                        try (var connection = server.database.DatabaseConnection.getConnection(); var statement = connection.createStatement()) {
                            statement.executeQuery("SELECT Transfer_Id, Ma_Tep, Ma_Tin_Nhan, Duong_Dan_Luu_Tru FROM Tep_Dinh_Kem WHERE 1=0").close();
                            statement.executeQuery("SELECT Ma_Tin_Nhan, Ma_Phong, Ma_Nguoi_Gui, Loai_Tin_Nhan, Ngay_Xoa FROM Tin_Nhan WHERE 1=0").close();
                        }
                    }
                    char[] password = ServerConfig.get("auth.keystorePassword", "changeit-local-only").toCharArray();
                    javax.net.ssl.SSLContext tls;
                    try { tls = TlsSupport.server(Path.of(ServerConfig.get("auth.keystore", "config/auth-dev.p12")), password); }
                    finally { java.util.Arrays.fill(password, '\0'); }
                    try (var media = server.media.VideoServiceProcess.start();
                            var video = new server.media.VideoCoordinator(rooms, media.client());
                            SignalingServer signaling = new SignalingServer(tls,
                            new InetSocketAddress(ServerConfig.get("signaling.bind", "127.0.0.1"), ServerConfig.getInt("signaling.port", 8444)),
                            new SessionValidator(), new MeetingHandler(rooms, video, networkStats), rooms.serverEpoch(),
                            new SignalingServer.Options(ServerConfig.getInt("signaling.maxConnections", 256),
                                    ServerConfig.getInt("signaling.maxBytes", common.signaling.SignalWire.DEFAULT_MAX_BYTES),
                                    ServerConfig.getInt("signaling.requestsPerSecond", 50),
                                    maxParticipants))) {
                        signalingReference.set(signaling);
                        videoReference.set(video);
                        AuthService auth = new AuthService(hash -> { signaling.revokeSession(hash); rooms.revokeSession(hash); });
                        try (AuthServer authServer = new AuthServer(tls,
                                new InetSocketAddress(ServerConfig.get("auth.bind", "127.0.0.1"), ServerConfig.getInt("auth.port", 8443)),
                                new ApplicationService(auth, new server.rooms.RoomService(true))::handle, rooms);
                                var files = AuthServer.fileServer(tls, transferConfig)) {
                            Thread shutdown = new Thread(() -> {
                                try {
                                    signaling.close();
                                } finally {
                                    try { authServer.close(); }
                                    catch (java.io.IOException failure) {
                                        Diagnostics.current().failure("meeting.auth.close_failed", failure, Map.of("phase", "close"));
                                    } finally {
                                        try { if (files != null) files.close(); }
                                        catch (java.io.IOException failure) {
                                            Diagnostics.current().failure("meeting.files.close_failed", failure, Map.of("phase", "close"));
                                        } finally {
                                            try { networkStats.close(); } catch (Exception ignored) { }
                                            try { chatRetention.close(); } catch (Exception ignored) { }
                                            rooms.close(); video.close(); media.close(); server.database.DatabaseConnection.close();
                                        }
                                    }
                                }
                            }, "meeting-shutdown");
                            Runtime.getRuntime().addShutdownHook(shutdown);
                            try {
                                authServer.start(); signaling.start();
                                if (files != null) files.start();
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
            } finally { server.database.DatabaseConnection.close(); }
        }
    }
}
