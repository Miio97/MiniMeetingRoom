package client.media;

import common.meeting.CreateRoomParams;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: opens the Windows microphone; does not save captured audio. */
@EnabledIfSystemProperty(named = "mmr.test.audio.devices", matches = "true")
@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WindowsAudioIntegrationTest {
    @TempDir Path temporary;

    @Test void windowsMicrophoneReachesRemoteDecoderAndStops() throws Exception {
        FxTestRuntime.start();
        var tls = RoomTestFixture.certificates(temporary);
        try (var fixture = new VideoTestFixture(tls, temporary.resolve("windows-audio"))) {
            var host = fixture.session(fixture.database.account("windowsMic", "Microphone"));
            var guest = fixture.session(fixture.database.account("remoteEar", "Receiver"));
            AudioCallController capture = null;
            try {
                var room = host.create(new CreateRoomParams("Windows audio", "1234", 2, false)).get(10, TimeUnit.SECONDS);
                host.join(false, false).get(10, TimeUnit.SECONDS);
                guest.lookup(room.joinCode(), "1234").get(10, TimeUnit.SECONDS);
                guest.join(false, false).get(10, TimeUnit.SECONDS);
                var receiver = guest.video().audio();
                receiver.enter();
                capture = new AudioCallController(host::videoRequest, host::snapshot, host::selfParticipantId,
                        false, new MicrophoneConsentStore(temporary.resolve("mic.properties")));
                var sender = capture;
                sender.access(true).get(5, TimeUnit.SECONDS);
                sender.enter();
                for (int cycle = 0; cycle < 3; cycle++) {
                    long before = receiver.receivedSamples();
                    FxTestRuntime.call(sender::toggle).get(20, TimeUnit.SECONDS);
                    AudioCallIntegrationTest.await(() -> receiver.receivedSamples() > before + 48000);
                    System.out.println("WINDOWS_AUDIO_RECEIVED cycle=" + cycle + " samples=" + receiver.receivedSamples());
                    FxTestRuntime.call(sender::toggle).get(10, TimeUnit.SECONDS);
                    assertFalse(FxTestRuntime.call(() -> sender.requestedProperty().get()));
                    // A barrier verifies native teardown completed and the owner remains responsive.
                    sender.choice().get(2, TimeUnit.SECONDS);
                }
            } finally {
                if (capture != null) { capture.close(); capture.closeComplete().get(12, TimeUnit.SECONDS); }
                var remote = guest.video();
                host.close(); guest.close(); remote.closeComplete().get(12, TimeUnit.SECONDS);
            }
        }
    }
}
