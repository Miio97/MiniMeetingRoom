package client.media;

import common.meeting.CreateRoomParams;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sends generated audio to the real selected Windows speaker; never captures a microphone. */
@EnabledIfSystemProperty(named = "mmr.test.audio.playback", matches = "true")
@Timeout(70)
class WindowsAudioPlaybackTest {
    @TempDir Path temporary;
    @Test void generatedRemoteAudioPlaysThroughWindowsOutputAndCanReopen() throws Exception {
        FxTestRuntime.start();
        var tls = RoomTestFixture.certificates(temporary);
        try (var fixture = new VideoTestFixture(tls, temporary.resolve("playback"))) {
            var host = fixture.session(fixture.database.account("toneSource", "Tone"));
            var guest = fixture.session(fixture.database.account("speakerReceiver", "Speaker"));
            AudioCallController receiver = null;
            var speaker = dev.onvoid.webrtc.media.MediaDevices.getDefaultAudioRenderDevice();
            assertNotNull(speaker);
            try {
                var room = host.create(new CreateRoomParams("Playback", "1234", 2, false)).get(10, TimeUnit.SECONDS);
                host.join(false, false).get(10, TimeUnit.SECONDS);
                guest.lookup(room.joinCode(), "1234").get(10, TimeUnit.SECONDS);
                guest.join(false, false).get(10, TimeUnit.SECONDS);
                var sender = host.video().audio(); sender.enter(); sender.access(true).get(3, TimeUnit.SECONDS);
                FxTestRuntime.call(sender::toggle).get(15, TimeUnit.SECONDS);
                receiver = new AudioCallController(guest::videoRequest, guest::snapshot, guest::selfParticipantId,
                        false, new MicrophoneConsentStore(temporary.resolve("receive.properties")));
                var output = receiver; output.selectSpeaker(speaker).get(5, TimeUnit.SECONDS); output.enter();
                AudioCallIntegrationTest.await(() -> output.receivedSamples() > 48000);
                verifyWindowsRenderSession(speaker.getDescriptor());
                output.selectSpeaker(speaker).get(5, TimeUnit.SECONDS);
                long before = output.receivedSamples();
                AudioCallIntegrationTest.await(() -> output.receivedSamples() > before + 48000);
                verifyWindowsRenderSession(speaker.getDescriptor());
                assertFalse(FxTestRuntime.call(() -> output.requestedProperty().get()), "Listening must not open the mic");
            } finally {
                if (receiver != null) { receiver.close(); receiver.closeComplete().get(12, TimeUnit.SECONDS); }
                var video = host.video(); host.close(); guest.close(); video.closeComplete().get(12, TimeUnit.SECONDS);
            }
        }
    }
    private void verifyWindowsRenderSession(String endpoint) throws Exception {
        String probe = Path.of("src/video-test/resources/WindowsAudioSessionProbe.cs").toAbsolutePath().toString();
        var process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command",
                "Add-Type -Path '" + probe.replace("'", "''") + "'; [AudioMixerRead]::Run(" + ProcessHandle.current().pid() + ", '" + endpoint.replace("'", "''") + "')")
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(8, TimeUnit.SECONDS));
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("WINDOWS_RENDER_SESSION " + result.trim());
        assertEquals(0, process.exitValue(), result);
        assertTrue(result.contains("state=1"), "Windows render session must be active: " + result);
    }
}
