package client.media;

import client.UI.dialogs.ScreenSourceDialog;
import com.google.gson.JsonObject;
import common.meeting.CreateRoomParams;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(100)
public class SystemAudioIntegrationTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception {FxTestRuntime.start();tls=RoomTestFixture.certificates(temporary);try(var nativeLoader=new NativeVideoSession()) {}}

    @Test @EnabledOnOs(OS.WINDOWS)
    void nativeLoopbackStartsWithoutMicrophoneAndDisposesItsHelper() throws Exception {
        Set<Long> before=ProcessHandle.current().descendants().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
        var blocks=new AtomicInteger();var audible=new AtomicInteger();List<ProcessHandle> helpers;
        var input=new WindowsSystemAudioInput();
        try {
            input.start(pcm -> {if(pcm.length==1920) {blocks.incrementAndGet();for(byte b:pcm) if(b!=0) {audible.incrementAndGet();break;}}});
            AudioCallIntegrationTest.await(() -> blocks.get()>5);
            if(Boolean.getBoolean("mmr.test.systemAudioProbe")) {
                java.nio.file.Files.writeString(Path.of("target/system-audio-probe.ready"),"ready");
                AudioCallIntegrationTest.await(() -> audible.get()>3);
                java.nio.file.Files.writeString(Path.of("target/system-audio-native-evidence.txt"),"pcmBlocks="+blocks.get()+", nonSilentBlocks="+audible.get());
            }
            assertFalse(input.failed());
            helpers=ProcessHandle.current().descendants().filter(p -> !before.contains(p.pid())).toList();
            assertFalse(helpers.isEmpty());
        } finally {input.close();}
        AudioCallIntegrationTest.await(() -> helpers.stream().noneMatch(ProcessHandle::isAlive));
        // Captured PCM is discarded; this test never records or forwards system sound.
    }

    @Test void pickerAudioToggleAndHostStopKeepMicrophoneAndCameraIndependent() throws Exception {
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("system-sound"))) {
            var host=fixture.session(fixture.database.account("soundHost","Host"));
            var guest=fixture.session(fixture.database.account("soundGuest","Guest"));
            ScreenCallController screen=null;ScreenSourceDialog picker=null;
            var starts=new AtomicInteger();var closes=new AtomicInteger();
            var loseStop=new AtomicBoolean();
            try {
                var room=host.create(new CreateRoomParams("Sound","1234",3,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);guest.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);guest.join(false,false).get(10,TimeUnit.SECONDS);
                var camera=host.video();var viewer=guest.video();camera.publishPreparedCamera().get(10,TimeUnit.SECONDS);viewer.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                camera.startInput(() -> new VideoCallIntegrationTest.GeneratedCamera(0)).get(20,TimeUnit.SECONDS);
                camera.audio().access(true).get(5,TimeUnit.SECONDS);FxTestRuntime.call(camera.audio()::toggle).get(20,TimeUnit.SECONDS);
                var choice=new ScreenVideoInput.Choice("Test screen",1,false);
                screen=new ScreenCallController((type,body,request) -> {
                    if(type.equals("SCREEN_AUDIO_STOP") && loseStop.compareAndSet(true,false)) return CompletableFuture.failedFuture(new java.io.IOException("TEST_CONTROL_FAILURE"));
                    return host.videoRequest(type,body,request);
                },host::snapshot,host::selfParticipantId,() -> List.of(choice),ignored -> new GeneratedScreen(),
                        true,() -> new GeneratedSystemAudio(starts,closes));
                var sender=screen;sender.enter();
                picker=FxTestRuntime.call(() -> {var dialog=new ScreenSourceDialog(null,sender,host.selfParticipantId());dialog.show();return dialog;});
                var dialog=picker;
                FxTestRuntime.await(() -> !((javafx.scene.control.ComboBox<?>)dialog.getDialogPane().lookup("#screen-source-choice")).getItems().isEmpty());
                FxTestRuntime.call(() -> {
                    @SuppressWarnings("unchecked") var sources=(javafx.scene.control.ComboBox<ScreenVideoInput.Choice>)dialog.getDialogPane().lookup("#screen-source-choice");
                    sources.setValue(choice);((javafx.scene.control.CheckBox)dialog.getDialogPane().lookup("#screen-system-audio")).fire();return null;
                });
                FxTestRuntime.await(() -> !dialog.getDialogPane().lookup("#screen-source-confirm").isDisabled());
                assertEquals(0,starts.get(),"Preview must not open loopback");
                FxTestRuntime.call(() -> {((javafx.scene.control.Button)dialog.getDialogPane().lookup("#screen-source-confirm")).fire();return null;});
                FxTestRuntime.call(sender::publishPreparedScreen).get(20,TimeUnit.SECONDS);
                String id=host.selfParticipantId();
                AudioCallIntegrationTest.await(() -> viewer.screen().receivedSystemAudioSamples(id)>4800 && viewer.audio().receivedSamples(id)>4800 && viewer.frames(id).receivedFrames()>3);
                assertTrue(FxTestRuntime.call(() -> camera.audio().requestedProperty().get()));
                sender.setSystemAudioEnabled(false).get(20,TimeUnit.SECONDS);
                assertEquals(1,closes.get());assertTrue(state(guest,"SCREEN_AUDIO").isEmpty());
                assertTrue(state(guest,"AUDIO").has(id));assertTrue(state(guest,"VIDEO").has(id));assertTrue(state(guest,"SCREEN").has(id));
                sender.setSystemAudioEnabled(true).get(20,TimeUnit.SECONDS);
                assertEquals(2,starts.get());
                loseStop.set(true);
                assertThrows(ExecutionException.class,() -> sender.setSystemAudioEnabled(false).get(20,TimeUnit.SECONDS));
                sender.setSystemAudioEnabled(true).get(20,TimeUnit.SECONDS);
                assertEquals(3,starts.get(),"Retry must clean the old SFU publisher before publishing again");
                long before=viewer.screen().receivedSystemAudioSamples(id);
                AudioCallIntegrationTest.await(() -> viewer.screen().receivedSystemAudioSamples(id)>before+4800);
                viewer.screen().prioritizeVoice(true).get(10,TimeUnit.SECONDS);Thread.sleep(1200);
                long paused=viewer.screen().receivedSystemAudioSamples(id),resumed=System.nanoTime();
                viewer.screen().prioritizeVoice(false).get(10,TimeUnit.SECONDS);
                AudioCallIntegrationTest.await(() -> viewer.screen().receivedSystemAudioSamples(id)>paused+4800);
                assertTrue(System.nanoTime()-resumed<TimeUnit.SECONDS.toNanos(10));
                // Muting the microphone must leave system audio and SCREEN running.
                FxTestRuntime.call(camera.audio()::toggle).get(10,TimeUnit.SECONDS);
                assertFalse(FxTestRuntime.call(() -> camera.audio().requestedProperty().get()));
                assertTrue(state(guest,"SCREEN_AUDIO").has(id));assertTrue(state(guest,"SCREEN").has(id));
                FxTestRuntime.call(camera.audio()::toggle).get(20,TimeUnit.SECONDS);
                host.stopPublish(id,"SCREEN").get(10,TimeUnit.SECONDS);
                FxTestRuntime.await(() -> !sender.requestedProperty().get() && !sender.systemAudioEnabledProperty().get());
                AudioCallIntegrationTest.await(() -> closes.get()==3);
                assertTrue(state(guest,"SCREEN_AUDIO").isEmpty());assertTrue(state(guest,"SCREEN").isEmpty());
                assertTrue(state(guest,"AUDIO").has(id));assertTrue(state(guest,"VIDEO").has(id));
            } finally {
                if(picker!=null) {var dialog=picker;FxTestRuntime.call(() -> {dialog.close();return null;});}
                if(screen!=null) {screen.close();screen.closeComplete().get(24,TimeUnit.SECONDS);}
                var h=host.video();var g=guest.video();host.close();guest.close();h.closeComplete().get(24,TimeUnit.SECONDS);g.closeComplete().get(24,TimeUnit.SECONDS);
            }
        }
    }

    @Test void loopbackFailureLeavesScreenAndMicRunning() throws Exception {
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("sound-failure"))) {
            var host=fixture.session(fixture.database.account("soundFailure","Host"));ScreenCallController screen=null;
            try {
                host.create(new CreateRoomParams("Sound failure","1234",3,false)).get(10,TimeUnit.SECONDS);host.join(false,false).get(10,TimeUnit.SECONDS);
                var camera=host.video();camera.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                camera.audio().access(true).get(5,TimeUnit.SECONDS);FxTestRuntime.call(camera.audio()::toggle).get(20,TimeUnit.SECONDS);
                screen=new ScreenCallController(host::videoRequest,host::snapshot,host::selfParticipantId,List::of,ignored -> new GeneratedScreen(),true,
                        () -> new SystemAudioInput() {public void start(java.util.function.Consumer<byte[]> pcm) {throw new IllegalStateException("SYSTEM_AUDIO_UNAVAILABLE");}public void close() {}});
                screen.enter();var sender=screen;FxTestRuntime.call(() -> sender.toggleInput(GeneratedScreen::new)).get(20,TimeUnit.SECONDS);
                assertThrows(ExecutionException.class,() -> sender.setSystemAudioEnabled(true).get(20,TimeUnit.SECONDS));
                FxTestRuntime.await(() -> !sender.systemAudioEnabledProperty().get());
                assertTrue(FxTestRuntime.call(() -> sender.requestedProperty().get() && camera.audio().requestedProperty().get()));
                assertTrue(state(host,"SCREEN_AUDIO").isEmpty());
                assertTrue(host.snapshot().participants().getFirst().mediaState().micRequested());
            } finally {
                if(screen!=null) {screen.close();screen.closeComplete().get(24,TimeUnit.SECONDS);}
                var video=host.video();host.close();video.closeComplete().get(24,TimeUnit.SECONDS);
            }
        }
    }
    private static JsonObject state(client.meeting.MeetingSession session,String kind) throws Exception {
        return session.videoRequest(kind+"_STATE",new JsonObject(),UUID.randomUUID().toString()).get(10,TimeUnit.SECONDS).getAsJsonObject("sources");
    }
}
