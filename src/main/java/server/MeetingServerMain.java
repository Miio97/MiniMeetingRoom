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
                AtomicReference<server.filetransfer.FileTransferServer> filesReference = new AtomicReference<>();
                AtomicReference<MeetingHandler> handlerReference = new AtomicReference<>();
                Gson json = new Gson();
                int maxParticipants = ServerConfig.getInt("meeting.maxParticipants",20);
                if(maxParticipants<2 || maxParticipants>50) throw new IllegalArgumentException("MEETING_CAPACITY_LIMIT");
                RoomRepository roomRepository=new RoomRepository();
                try (RoomService rooms = new RoomService(roomRepository, Clock.systemUTC(), secrets.instanceId(),
                        secrets.receiptKey(), maxParticipants, event -> {
                            SignalingServer server = signalingReference.get();
                            var video = videoReference.get(); if (video != null) video.roomEvent(event);
                            var handler=handlerReference.get();if(handler!=null) handler.catalogChanged();
                            if (server != null) server.event(event.recipientConnectionId(), event.type(), event.roomId(),
                                    event.revision(), json.toJsonTree(event.body()).getAsJsonObject());
                        })) {
                    rooms.start(); // Preflight/reconcile only; never creates tables.
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
                            var meetingHandler=new MeetingHandler(rooms,video);
                            SignalingServer signaling = new SignalingServer(tls,
                            new InetSocketAddress(ServerConfig.get("signaling.bind", "127.0.0.1"), ServerConfig.getInt("signaling.port", 8444)),
                            new SessionValidator(), meetingHandler, rooms.serverEpoch(),
                            new SignalingServer.Options(ServerConfig.getInt("signaling.maxConnections", 256),
                                    ServerConfig.getInt("signaling.maxBytes", common.signaling.SignalWire.DEFAULT_MAX_BYTES),
                                    ServerConfig.getInt("signaling.requestsPerSecond", 50),
                                    maxParticipants))) {
                        signalingReference.set(signaling);
                        videoReference.set(video);
                        handlerReference.set(meetingHandler);
                        AuthService auth = new AuthService(hash -> { signaling.revokeSession(hash); rooms.revokeSession(hash); });
                        var authRooms=new server.rooms.RoomService(true);
                        authRooms.legacyOwner(secrets.instanceId());
                        try (var files = AuthServer.fileServer(tls, transferConfig,secrets.instanceId());
                                var contentCleanup=new server.meeting.ImmediateRoomCleanup(rooms.connectionProvider(),secrets.instanceId(),
                                id -> {if(filesReference.get()!=null) filesReference.get().cleanupRoom(id);},
                                id -> filesReference.get()!=null && filesReference.get().hasPendingRoom(id),meetingHandler::catalogChanged);
                                AuthServer authServer = new AuthServer(tls,
                                new InetSocketAddress(ServerConfig.get("auth.bind", "127.0.0.1"), ServerConfig.getInt("auth.port", 8443)),
                                new ApplicationService(auth, authRooms)::handle, rooms)) {
                            filesReference.set(files);
                            if (files != null) files.roomSessionGate(rooms::isCurrentRoomSession);
                            roomRepository.setReopenGuard(contentCleanup);authRooms.setReopenGuard(contentCleanup);
                            meetingHandler.cleanupStatus(contentCleanup::isPending);
                            rooms.onRoomClosed(contentCleanup::request);authRooms.onChanged(() -> {contentCleanup.request(0);meetingHandler.catalogChanged();});contentCleanup.start();
                            Thread shutdown = new Thread(() -> {
                                try {
                                    signaling.close();
                                } finally {
                                    try { authServer.close(); }
                                    catch (java.io.IOException failure) {
                                        Diagnostics.current().failure("meeting.auth.close_failed", failure, Map.of("phase", "close"));
                                    } finally {
                                        contentCleanup.close();
                                        try { if (files != null) files.close(); }
                                        catch (java.io.IOException failure) {
                                            Diagnostics.current().failure("meeting.files.close_failed", failure, Map.of("phase", "close"));
                                        } finally {
                                            contentCleanup.close();
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
