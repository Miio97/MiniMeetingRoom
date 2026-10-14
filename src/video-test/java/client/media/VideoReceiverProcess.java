package client.media;

import client.meeting.MeetingSession;
import client.signalingapi.SignalingClient;
import common.auth.TlsSupport;
import common.signaling.SignalWire;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import support.FxTestRuntime;

/** Separate native JVM, receive only. All session values stay in the test child's environment. */
public final class VideoReceiverProcess {
    public static void main(String[] args) {
        VideoCallController video = null; MeetingSession session = null;
        CountDownLatch releaseCatalog = new CountDownLatch(1);
        Path output = Path.of(System.getenv("MMR_TEST_RESULT"));
        try {
            FxTestRuntime.start();
            var tls = TlsSupport.client(Path.of(System.getenv("MMR_TEST_CERTIFICATE")));
            int port = Integer.parseInt(System.getenv("MMR_TEST_SIGNAL_PORT"));
            session = new MeetingSession(System.getenv("MMR_TEST_TOKEN"), Instant.parse(System.getenv("MMR_TEST_EXPIRY")),
                    listener -> new SignalingClient(new SignalingClient.Settings("localhost", port, null, SignalWire.DEFAULT_MAX_BYTES), tls, listener));
            session.lookup(System.getenv("MMR_TEST_ROOM_CODE"), "1234").get(10, TimeUnit.SECONDS); session.join(false, false).get(10, TimeUnit.SECONDS);
            CameraConsentStore consent = new CameraConsentStore(output.resolveSibling("child-camera.properties")); consent.access(true);
            MeetingSession owner = session;
            CountDownLatch entered = new CountDownLatch(1);
            video = new VideoCallController(owner::videoRequest, owner::snapshot, owner::selfParticipantId, consent, () -> {
                entered.countDown(); try { releaseCatalog.await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
                return List.of();
            });
            video.cameras(); if (!entered.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("CATALOG_TEST_NOT_STARTED");
            video.cameraChoice().get(2, TimeUnit.SECONDS); // Must not queue behind stalled discovery.
            String source = System.getenv("MMR_TEST_SOURCE"); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (video.frames(source).receivedFrames() < 5 && System.nanoTime() < deadline) Thread.sleep(50);
            if (video.frames(source).receivedFrames() < 5) throw new IllegalStateException("REMOTE_VIDEO_NOT_RECEIVED");
            VideoCallController receiver = video;
            FxTestRuntime.await(() -> receiver.frames(source).imageProperty().get() != null);
            Files.writeString(output, "received=" + video.frames(source).receivedFrames() + "\nrendered=" + video.frames(source).renderedFrames() + "\n");
        } catch (Exception | LinkageError failure) {
            try { Files.writeString(output, "error=" + failure.getClass().getSimpleName() + "\n"); } catch (Exception ignored) { }
        } finally {
            releaseCatalog.countDown(); if (video != null) video.close(); if (session != null) session.close();
            try { if (video != null) video.closeComplete().get(8, TimeUnit.SECONDS); } catch (Exception ignored) { }
            javafx.application.Platform.exit();
        }
    }
}
