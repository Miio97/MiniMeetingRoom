package client.media;

import client.meeting.MeetingSession;
import client.UI.dialogs.ScreenSourceDialog;
import common.meeting.CreateRoomParams;
import common.signaling.SignalException;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(100)
public class ScreenCallIntegrationTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception {FxTestRuntime.start();tls=RoomTestFixture.certificates(temporary);}
    @Test void screenAndCameraRunTogetherLateJoinReceivesAndHostStopKeepsCamera() throws Exception {
        try(VideoTestFixture fixture=new VideoTestFixture(tls,temporary.resolve("screen"))) {
            List<MeetingSession> clients=new ArrayList<>();List<VideoCallController> videos=new ArrayList<>();
            try {
                for(int i=0;i<3;i++) clients.add(fixture.session(fixture.database.account("screen"+i,"Screen "+i)));
                MeetingSession host=clients.getFirst(),publisher=clients.get(1),late=clients.get(2);
                var room=host.create(new CreateRoomParams("Screen", "1234",3,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);videos.add(host.video());host.video().publishPreparedCamera().get(10,TimeUnit.SECONDS);
                publisher.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);publisher.join(false,false).get(10,TimeUnit.SECONDS);
                var video=publisher.video();videos.add(video);video.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                video.startInput(() -> new VideoCallIntegrationTest.GeneratedCamera(1)).get(20,TimeUnit.SECONDS);
                FxTestRuntime.call(() -> video.screen().toggleInput(GeneratedScreen::new)).get(20,TimeUnit.SECONDS);
                AudioCallIntegrationTest.await(() -> host.video().screen().frames(publisher.selfParticipantId()).receivedFrames()>3);
                late.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);late.join(false,false).get(10,TimeUnit.SECONDS);
                videos.add(late.video());late.video().publishPreparedCamera().get(10,TimeUnit.SECONDS);
                AudioCallIntegrationTest.await(() -> late.video().screen().frames(publisher.selfParticipantId()).receivedFrames()>3);
                AudioCallIntegrationTest.await(() -> host.video().frames(publisher.selfParticipantId()).receivedFrames()>3);
                var rejected=assertThrows(ExecutionException.class,() -> FxTestRuntime.call(() -> host.video().screen().toggleInput(GeneratedScreen::new)).get(20,TimeUnit.SECONDS));
                assertEquals("SHARE_IN_USE",((SignalException)rejected.getCause()).code());
                host.stopPublish(publisher.selfParticipantId(),"SCREEN").get(10,TimeUnit.SECONDS);
                AudioCallIntegrationTest.await(() -> !video.screen().requestedProperty().get());
                JsonObject camera=host.videoRequest("VIDEO_STATE",new JsonObject(),UUID.randomUUID().toString()).get(10,TimeUnit.SECONDS);
                assertTrue(camera.getAsJsonObject("sources").has(publisher.selfParticipantId()));
                FxTestRuntime.call(() -> {
                    CompletableFuture<Void> last=null;for(int i=0;i<100;i++) last=video.screen().toggleInput(GeneratedScreen::new);return last;
                }).get(25,TimeUnit.SECONDS);
                assertFalse(FxTestRuntime.call(() -> video.screen().requestedProperty().get()));
                assertTrue(host.videoRequest("SCREEN_STATE",new JsonObject(),UUID.randomUUID().toString()).get(10,TimeUnit.SECONDS).getAsJsonObject("sources").isEmpty());
                var endingSource=new GeneratedScreen();
                FxTestRuntime.call(() -> video.screen().toggleInput(() -> endingSource)).get(20,TimeUnit.SECONDS);
                endingSource.close();
                AudioCallIntegrationTest.await(() -> !video.screen().requestedProperty().get());
                assertTrue(host.videoRequest("SCREEN_STATE",new JsonObject(),UUID.randomUUID().toString()).get(10,TimeUnit.SECONDS).getAsJsonObject("sources").isEmpty());
                String publisherId=publisher.selfParticipantId();var oldFrames=host.video().screen().frames(publisherId);
                publisher.close();video.closeComplete().get(20,TimeUnit.SECONDS);
                FxTestRuntime.await(() -> oldFrames.imageProperty().get()==null);
            } finally {clients.forEach(MeetingSession::close);for(var video:videos) video.closeComplete().get(20,TimeUnit.SECONDS);}
        }
    }
    @Test void pickerPreviewAndCancelUseGeneratedFramesAndPublishNothing() throws Exception {
        var choice=new ScreenVideoInput.Choice("Test screen",1,false);
        java.util.concurrent.atomic.AtomicInteger requests=new java.util.concurrent.atomic.AtomicInteger();
        var screen=new ScreenCallController((type,body,id) -> {requests.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("NO_NETWORK"));},
                () -> null,() -> "self",() -> List.of(choice),ignored -> new GeneratedScreen());
        ScreenSourceDialog picker=FxTestRuntime.call(() -> {var dialog=new ScreenSourceDialog(null,screen,"self");dialog.show();return dialog;});
        try {
            FxTestRuntime.await(() -> !((javafx.scene.control.ComboBox<?>)picker.getDialogPane().lookup("#screen-source-choice")).getItems().isEmpty());
            FxTestRuntime.call(() -> {
                @SuppressWarnings("unchecked") var combo=(javafx.scene.control.ComboBox<ScreenVideoInput.Choice>)picker.getDialogPane().lookup("#screen-source-choice");combo.setValue(choice);return null;
            });
            FxTestRuntime.await(() -> !picker.getDialogPane().lookup("#screen-source-confirm").isDisabled());
            assertTrue(screen.frames("self").receivedFrames()>0);assertEquals(0,requests.get());
            FxTestRuntime.call(() -> {picker.close();return null;});screen.endPreview().get(10,TimeUnit.SECONDS);
            assertNull(picker.getResult());assertFalse(FxTestRuntime.call(() -> screen.requestedProperty().get()));
        } finally {FxTestRuntime.call(() -> {picker.close();return null;});screen.close();screen.closeComplete().get(10,TimeUnit.SECONDS);}
    }
}
