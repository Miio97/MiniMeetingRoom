package client.media;

import client.meeting.MeetingSession;
import client.signalingapi.SignalingClient;
import common.auth.TlsSupport;
import common.signaling.SignalWire;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import support.FxTestRuntime;

/** Isolated Opus sender/receiver for integration tests; never opens microphone or speaker. */
public final class AudioPeerProcess {
    public static void main(String[] args) {
        Path output = Path.of(System.getenv("MMR_TEST_RESULT"));
        MeetingSession session = null;
        AudioCallController audio = null;
        String result;
        try {
            FxTestRuntime.start();
            var tls = TlsSupport.client(Path.of(System.getenv("MMR_TEST_CERTIFICATE")));
            int port = Integer.parseInt(System.getenv("MMR_TEST_SIGNAL_PORT"));
            session = new MeetingSession(System.getenv("MMR_TEST_TOKEN"), Instant.parse(System.getenv("MMR_TEST_EXPIRY")),
                    listener -> new SignalingClient(new SignalingClient.Settings("localhost", port, null, SignalWire.DEFAULT_MAX_BYTES), tls, listener));
            session.lookup(System.getenv("MMR_TEST_ROOM_CODE"), "1234").get(10, TimeUnit.SECONDS);
            session.join(false, false).get(10, TimeUnit.SECONDS);
            var owner = session;
            audio = new AudioCallController(owner::videoRequest, owner::snapshot, owner::selfParticipantId, true,
                    new MicrophoneConsentStore(output.resolveSibling("child-mic.properties")));
            audio.access(true).get(3, TimeUnit.SECONDS); audio.enter();
            var peer = audio;
            FxTestRuntime.call(peer::toggle).get(15, TimeUnit.SECONDS);
            AudioCallIntegrationTest.await(() -> peer.receivedSamples() > 48000);
            // Continue forwarding until the parent has decoded a full second as well.
            Path done = output.resolveSibling("parent-audio-received");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
            while (!Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(50);
            if (!Files.exists(done)) throw new IllegalStateException("PARENT_AUDIO_NOT_RECEIVED");
            result = "received=" + peer.receivedSamples() + "\n";
        } catch (Exception | Error failure) {
            result = "error=" + failure.getClass().getSimpleName() + "\n";
        } finally {
            if (audio != null) audio.close();
            if (session != null) session.close();
        }
        try {
            if (audio != null) audio.closeComplete().get(12, TimeUnit.SECONDS);
        } catch (Exception | Error failure) { result = "error=AudioCleanupFailed\n"; }
        try { Files.writeString(output, result); } catch (Exception ignored) { }
        javafx.application.Platform.exit();
    }
}
