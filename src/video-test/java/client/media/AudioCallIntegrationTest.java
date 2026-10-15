package client.media;

import client.meeting.MeetingSession;
import common.meeting.CreateRoomParams;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real native Opus encode/decode + Go forwarding + H2/TLS; no physical device access. */
@Timeout(100)
public class AudioCallIntegrationTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); tls=RoomTestFixture.certificates(temporary); }
    @Test void twoSeparateJvmsExchangeOpusInBothDirections() throws Exception {
        try (var fixture = new VideoTestFixture(tls, temporary.resolve("audio-two-jvms"))) {
            var host = fixture.session(fixture.database.account("processHost", "Host"));
            var guest = fixture.database.account("processGuest", "Guest");
            Process child = null;
            var audio = host.video().audio();
            try {
                var room = host.create(new CreateRoomParams("Two JVM audio", "1234", 2, false)).get(10, TimeUnit.SECONDS);
                host.join(false, false).get(10, TimeUnit.SECONDS);
                audio.access(true).get(3, TimeUnit.SECONDS); audio.enter();
                FxTestRuntime.call(audio::toggle).get(15, TimeUnit.SECONDS);
                Path resultFile = temporary.resolve("audio-child.properties");
                String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
                var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                        "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classpath, AudioPeerProcess.class.getName());
                var env = builder.environment();
                env.put("MMR_TEST_TOKEN", guest.token()); env.put("MMR_TEST_EXPIRY", guest.expiresAt());
                env.put("MMR_TEST_CERTIFICATE", temporary.resolve("room-test.cer").toString());
                env.put("MMR_TEST_SIGNAL_PORT", Integer.toString(fixture.signaling.port()));
                env.put("MMR_TEST_ROOM_CODE", room.joinCode()); env.put("MMR_TEST_RESULT", resultFile.toString());
                env.remove("MMR_DB_URL"); env.remove("MMR_DB_USER"); env.remove("MMR_DB_PASSWORD");
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                child = builder.start();
                await(() -> audio.receivedSamples() > 48000);
                java.nio.file.Files.writeString(resultFile.resolveSibling("parent-audio-received"), "ok");
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Separate audio JVM must shut down without hanging");
                assertEquals(0, child.exitValue());
                Properties result = new Properties();
                try (var reader = java.nio.file.Files.newBufferedReader(resultFile)) { result.load(reader); }
                assertNull(result.getProperty("error"));
                assertTrue(Long.parseLong(result.getProperty("received", "0")) > 48000);
            } finally {
                if (child != null && child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                var video = host.video(); host.close(); video.closeComplete().get(12, TimeUnit.SECONDS);
            }
        }
    }
    @Test void logoutAndLeaveRevokeAudioAndScreenWhileOtherSourcesContinue() throws Exception {
        try(VideoTestFixture fixture=new VideoTestFixture(tls,temporary.resolve("media-logout"))) {
            var hostAccount=fixture.database.account("mediaHost","Media Host");
            var guestAccount=fixture.database.account("mediaGuest","Media Guest");
            MeetingSession host=fixture.session(hostAccount),guest=fixture.session(guestAccount);
            VideoCallController hostVideo=null,guestVideo=null;
            try {
                var room=host.create(new CreateRoomParams("Lifecycle","1234",2,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);guest.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);guest.join(false,false).get(10,TimeUnit.SECONDS);
                hostVideo=host.video();guestVideo=guest.video();var h=hostVideo;var g=guestVideo;
                h.publishPreparedCamera().get(10,TimeUnit.SECONDS);g.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                h.audio().access(true).get(5,TimeUnit.SECONDS);g.audio().access(true).get(5,TimeUnit.SECONDS);
                FxTestRuntime.call(() -> h.audio().toggle()).get(20,TimeUnit.SECONDS);FxTestRuntime.call(() -> g.audio().toggle()).get(20,TimeUnit.SECONDS);
                FxTestRuntime.call(() -> g.screen().toggleInput(GeneratedScreen::new)).get(20,TimeUnit.SECONDS);
                String guestId=guest.selfParticipantId();var share=h.screen().frames(guestId);
                await(() -> h.audio().receivedSamples(guestId)>4800 && share.receivedFrames()>3);
                var denied=assertThrows(ExecutionException.class,() -> guest.stopPublish(host.selfParticipantId(),"AUDIO").get(10,TimeUnit.SECONDS));
                assertEquals("HOST_REQUIRED",((common.signaling.SignalException)denied.getCause()).code());
                assertTrue(fixture.database.client().logout(guestAccount.token()).success());g.closeComplete().get(20,TimeUnit.SECONDS);
                FxTestRuntime.await(() -> host.participants().size()==1 && share.imageProperty().get()==null);
                assertTrue(FxTestRuntime.call(() -> h.audio().requestedProperty().get()));
                assertTrue(host.videoRequest("SCREEN_STATE",new JsonObject(),UUID.randomUUID().toString()).get(10,TimeUnit.SECONDS).getAsJsonObject("sources").isEmpty());
                host.leave().get(10,TimeUnit.SECONDS);h.closeComplete().get(20,TimeUnit.SECONDS);
                assertEquals(0,fixture.rooms.participantCount());assertEquals(0,fixture.rooms.roomCount());
            } finally {host.close();guest.close();if(hostVideo!=null) hostVideo.closeComplete().get(20,TimeUnit.SECONDS);if(guestVideo!=null) guestVideo.closeComplete().get(20,TimeUnit.SECONDS);}
        }
    }
    @Test void threeClientsExchangeAudioAndHostStopsOnlyAudio() throws Exception {
        try(VideoTestFixture fixture=new VideoTestFixture(tls,temporary.resolve("audio"))) {
            List<MeetingSession> clients=new ArrayList<>(); List<VideoCallController> videos=new ArrayList<>();
            try {
                for(int i=0;i<3;i++) clients.add(fixture.session(fixture.database.account("audio"+i,"Audio "+i)));
                MeetingSession host=clients.getFirst(); var preview=host.create(new CreateRoomParams("Audio", "1234",3,false)).get(10,TimeUnit.SECONDS);
                for(int i=0;i<3;i++) {
                    MeetingSession client=clients.get(i); if(i>0) client.lookup(preview.joinCode(),"1234").get(10,TimeUnit.SECONDS);
                    client.join(false,false).get(10,TimeUnit.SECONDS); var video=client.video();videos.add(video);
                    video.audio().access(true).get(5,TimeUnit.SECONDS); video.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                    FxTestRuntime.call(() -> video.audio().toggle()).get(20,TimeUnit.SECONDS);
                }
                for(int receiver=0;receiver<3;receiver++) for(int sender=0;sender<3;sender++) if(receiver!=sender) {
                    var audio=videos.get(receiver).audio(); String source=clients.get(sender).selfParticipantId();
                    await(() -> audio.receivedSamples(source)>4800);
                }
                var guest=clients.get(1); var guestVideo=videos.get(1);
                guestVideo.startInput(() -> new VideoCallIntegrationTest.GeneratedCamera(1)).get(20,TimeUnit.SECONDS);
                await(() -> guest.snapshot().participants().stream().filter(p -> p.participantId().equals(guest.selfParticipantId())).anyMatch(p -> p.mediaState().audioPublishing()));
                host.stopPublish(guest.selfParticipantId(),"AUDIO").get(10,TimeUnit.SECONDS);
                await(() -> !guestVideo.audio().requestedProperty().get());
                JsonObject camera=host.videoRequest("VIDEO_STATE",new JsonObject(),UUID.randomUUID().toString()).get(10,TimeUnit.SECONDS);
                assertTrue(camera.getAsJsonObject("sources").has(guest.selfParticipantId()),"Host mic stop must preserve the camera");
                FxTestRuntime.call(() -> {
                    CompletableFuture<Void> last=null;for(int i=0;i<100;i++) last=guestVideo.audio().toggle();return last;
                }).get(25,TimeUnit.SECONDS);
                assertFalse(FxTestRuntime.call(() -> guestVideo.audio().requestedProperty().get()));
                host.kick(guest.selfParticipantId()).get(10,TimeUnit.SECONDS);
                long before=videos.get(2).audio().receivedSamples(host.selfParticipantId());
                await(() -> videos.get(2).audio().receivedSamples(host.selfParticipantId())>before+4800);
                clients.get(2).close(); videos.get(2).closeComplete().get(20,TimeUnit.SECONDS);
            } finally {
                clients.forEach(MeetingSession::close);for(var video:videos) video.closeComplete().get(20,TimeUnit.SECONDS);
            }
        }
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);
        while(!condition.getAsBoolean()) {if(System.nanoTime()>end) fail("Media condition timed out");Thread.sleep(50);}
    }
}
