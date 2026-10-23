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
    @BeforeAll static void runtime() throws Exception {
        FxTestRuntime.start();tls=RoomTestFixture.certificates(temporary);
        // CustomVideoSource needs the SDK library loaded even when this class runs alone.
        try(var nativeLoader=new NativeVideoSession()) { }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void selectingARealWindowKeepsPickerFocusedAndDoesNotPublish() throws Exception {
        String title="MiniMeeting capture focus test "+UUID.randomUUID();
        var transfers=new java.util.concurrent.atomic.AtomicInteger();
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var windows=FxTestRuntime.call(() -> {
            var target=new javafx.stage.Stage();target.setTitle(title);
            target.setScene(new javafx.scene.Scene(new javafx.scene.control.Label("Screen capture test window"),360,240));
            target.setX(740);target.setY(100);target.show();
            target.focusedProperty().addListener((o,b,a) -> {if(a && armed.get()) transfers.incrementAndGet();});
            var meeting=new javafx.stage.Stage();meeting.setTitle("MiniMeeting test owner");
            meeting.setScene(new javafx.scene.Scene(new javafx.scene.control.Label("Meeting test"),560,440));
            meeting.setX(80);meeting.setY(100);meeting.show();
            return List.of(target,meeting);
        });
        var screen=new ScreenCallController((type,body,id) -> {
            requests.incrementAndGet();return CompletableFuture.failedFuture(new IllegalStateException("NO_NETWORK"));
        },() -> null,() -> "self",false);
        ScreenSourceDialog picker=null;
        try {
            picker=FxTestRuntime.call(() -> {
                var dialog=new ScreenSourceDialog(windows.get(1),screen,"self");
                // Leave the target enabled, like an external app (Zalo) would be.
                dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);dialog.show();return dialog;
            });
            var dialog=picker;
            FxTestRuntime.await(() -> ((javafx.scene.control.ComboBox<?>)dialog.getDialogPane().lookup("#screen-source-choice")).getItems().stream()
                    .anyMatch(c -> c instanceof ScreenVideoInput.Choice s && s.window() && s.title().equals(title)));
            FxTestRuntime.await(() -> dialog.getDialogPane().getScene().getWindow().isFocused());
            FxTestRuntime.call(() -> {
                @SuppressWarnings("unchecked") var combo=(javafx.scene.control.ComboBox<ScreenVideoInput.Choice>)dialog.getDialogPane().lookup("#screen-source-choice");
                armed.set(true);combo.setValue(combo.getItems().stream().filter(s -> s.window() && s.title().equals(title)).findFirst().orElseThrow());return null;
            });
            FxTestRuntime.await(() -> !dialog.getDialogPane().lookup("#screen-source-confirm").isDisabled());
            Thread.sleep(700);
            assertEquals(0,transfers.get(),"Selecting/previewing must never activate the captured window");
            assertEquals(0,requests.get(),"Preview must not publish");
            FxTestRuntime.call(() -> {
                assertTrue(dialog.getDialogPane().getScene().getWindow().isFocused());
                assertNotNull(screen.frames("self").imageProperty().get());
                ((javafx.scene.control.Button)dialog.getDialogPane().lookup("#screen-source-confirm")).fire();return null;
            });
            assertNotNull(dialog.getResult());
            assertEquals(0,transfers.get());
        } finally {
            armed.set(false);
            if(picker!=null) {var dialog=picker;FxTestRuntime.call(() -> {dialog.close();return null;});}
            screen.close();screen.closeComplete().get(10,TimeUnit.SECONDS);
            FxTestRuntime.call(() -> {windows.forEach(javafx.stage.Stage::close);return null;});
        }
    }
    @Test void malformedNativeAnswerBecomesARecoverableConnectionFailure() throws Exception {
        try(var nativeSession=new NativeVideoSession();var mailbox=new VideoFrameMailbox();var peer=nativeSession.subscriber(mailbox)) {
            peer.offer();
            var failure=assertThrows(Exception.class,() -> peer.answer("invalid"));
            assertEquals("SCREEN_CONNECTION_FAILED",CameraErrors.screenReceiveCode(failure));
        }
    }
    @Test void preparedPortraitScreenSurvivesSubscribeFailureVoicePriorityAndStaticCapture() throws Exception {
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("portrait-prepared"))) {
            var host=fixture.session(fixture.database.account("portraitHost","Host"));
            var guest=fixture.session(fixture.database.account("portraitGuest","Guest"));
            ScreenCallController sender=null,receiver=null;ScreenSourceDialog picker=null;
            var source=new GeneratedScreen(600,900);
            var opens=new java.util.concurrent.atomic.AtomicInteger();
            var unsubscribes=new java.util.concurrent.atomic.AtomicInteger();
            var subscribes=new java.util.concurrent.atomic.AtomicInteger();
            var stops=new java.util.concurrent.atomic.AtomicInteger();
            var failState=new java.util.concurrent.atomic.AtomicBoolean();
            var rejectAnswer=new java.util.concurrent.atomic.AtomicBoolean(true);
            try {
                var room=host.create(new CreateRoomParams("Portrait","1234",3,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);
                guest.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);guest.join(false,false).get(10,TimeUnit.SECONDS);
                var choice=new ScreenVideoInput.Choice("Local-only source title",1,false);
                sender=new ScreenCallController((type,body,id) -> {
                    if(type.equals("SCREEN_STOP")) stops.incrementAndGet();
                    if(type.equals("SCREEN_STATE") && failState.compareAndSet(true,false))
                        return CompletableFuture.failedFuture(new java.io.IOException("TEST_NETWORK_FAILURE"));
                    return host.videoRequest(type,body,id);
                },host::snapshot,host::selfParticipantId,() -> List.of(choice),ignored -> {opens.incrementAndGet();return source;});
                receiver=new ScreenCallController((type,body,id) -> {
                    if(type.equals("SCREEN_UNSUBSCRIBE")) unsubscribes.incrementAndGet();
                    if(type.equals("SCREEN_SUBSCRIBE")) {
                        subscribes.incrementAndGet();
                        return guest.videoRequest(type,body,id).thenApply(reply -> {
                            if(rejectAnswer.compareAndSet(true,false)) {var bad=reply.deepCopy();bad.addProperty("sdp","invalid");return bad;}
                            return reply;
                        });
                    }
                    return guest.videoRequest(type,body,id);
                },guest::snapshot,guest::selfParticipantId,true);
                var screen=sender;var viewer=receiver;screen.enter();viewer.enter();
                // Start while voice priority is already selected. SFU must see RTP first.
                screen.prioritizeVoice(true).get(5,TimeUnit.SECONDS);
                picker=FxTestRuntime.call(() -> {var dialog=new ScreenSourceDialog(null,screen,host.selfParticipantId());dialog.show();return dialog;});
                var dialog=picker;
                FxTestRuntime.await(() -> !((javafx.scene.control.ComboBox<?>)dialog.getDialogPane().lookup("#screen-source-choice")).getItems().isEmpty());
                FxTestRuntime.call(() -> {
                    @SuppressWarnings("unchecked") var combo=(javafx.scene.control.ComboBox<ScreenVideoInput.Choice>)dialog.getDialogPane().lookup("#screen-source-choice");
                    combo.setValue(choice);return null;
                });
                FxTestRuntime.await(() -> !dialog.getDialogPane().lookup("#screen-source-confirm").isDisabled());
                FxTestRuntime.call(() -> {((javafx.scene.control.Button)dialog.getDialogPane().lookup("#screen-source-confirm")).fire();return null;});
                assertEquals(choice,dialog.getResult());assertEquals(0,source.closes.get());
                FxTestRuntime.call(screen::publishPreparedScreen).get(20,TimeUnit.SECONDS);
                AudioCallIntegrationTest.await(() -> host.snapshot().participants().stream().anyMatch(p -> p.participantId().equals(host.selfParticipantId()) && p.mediaState().sharePublishing()));
                screen.prioritizeVoice(false).get(5,TimeUnit.SECONDS);
                var mailbox=viewer.frames(host.selfParticipantId());
                try {AudioCallIntegrationTest.await(() -> mailbox.receivedFrames()>0);}
                catch(AssertionError failure) {
                    throw new AssertionError("Portrait receive: subscribes="+subscribes.get()+", unsubscribes="+unsubscribes.get()
                            +", stops="+stops.get()+", localFrames="+screen.frames(host.selfParticipantId()).receivedFrames()
                            +", status="+FxTestRuntime.call(() -> viewer.statusProperty().get()),failure);
                }
                FxTestRuntime.await(() -> mailbox.imageProperty().get()!=null);
                assertEquals(1,opens.get());assertEquals(1,source.starts.get());assertEquals(0,source.closes.get());
                assertTrue(subscribes.get()>=2);assertTrue(unsubscribes.get()>=1,"Failed answer must release SFU transport");
                FxTestRuntime.call(() -> {
                    var image=mailbox.imageProperty().get();assertTrue(image.getHeight()>image.getWidth());
                    assertEquals(600.0/900,image.getWidth()/image.getHeight(),0.02);return null;
                });
                int before=subscribes.get(),cleanups=unsubscribes.get();
                viewer.prioritizeVoice(true).get(5,TimeUnit.SECONDS);
                assertTrue(unsubscribes.get()>cleanups);
                Thread.sleep(1200);long resumed=System.nanoTime();
                viewer.prioritizeVoice(false).get(5,TimeUnit.SECONDS);
                FxTestRuntime.await(() -> mailbox.imageProperty().get()!=null);
                assertEquals(before+1,subscribes.get(),"Resume must succeed on first subscribe attempt");
                assertTrue(System.nanoTime()-resumed<TimeUnit.SECONDS.toNanos(10),"Must not wait for 13-second ICE expiry");
                failState.set(true);AudioCallIntegrationTest.await(() -> !failState.get());
                assertTrue(FxTestRuntime.call(() -> screen.requestedProperty().get()));assertEquals(0,stops.get());
                source.freeze();long frames=mailbox.receivedFrames();
                // No new capture callbacks for longer than the former eight-second timeout.
                Thread.sleep(10000);
                assertTrue(FxTestRuntime.call(() -> screen.requestedProperty().get()));
                FxTestRuntime.call(() -> {assertNotNull(mailbox.imageProperty().get());return null;});
                assertEquals(0,source.closes.get());assertEquals(0,stops.get());
                assertTrue(mailbox.receivedFrames()>=frames);
                var hostStop=host.stopPublish(host.selfParticipantId(),"SCREEN");
                FxTestRuntime.call(() -> {for(int i=0;i<100;i++) screen.stopShare();return screen.stopShare();}).get(10,TimeUnit.SECONDS);
                hostStop.get(10,TimeUnit.SECONDS);
                screen.stopShare().get(5,TimeUnit.SECONDS);
                assertFalse(FxTestRuntime.call(() -> screen.requestedProperty().get()));
                assertEquals(1,opens.get());assertEquals(1,source.closes.get());
                assertFalse(FxTestRuntime.call(() -> screen.statusProperty().get().equals(CameraErrors.screenMessage(new SignalException("SCREEN_CAPTURE_FAILED")))));
            } finally {
                if(picker!=null) {var dialog=picker;FxTestRuntime.call(() -> {dialog.close();return null;});}
                if(sender!=null) {sender.close();sender.closeComplete().get(15,TimeUnit.SECONDS);}
                if(receiver!=null) {receiver.close();receiver.closeComplete().get(15,TimeUnit.SECONDS);}
                source.close();host.close();guest.close();
            }
        }
    }
    @Test void mailboxCountsOnlyUsableFramesAndAcceptsPortrait() throws Exception {
        try(var mailbox=new VideoFrameMailbox()) {
            var invalid=dev.onvoid.webrtc.media.video.NativeI420Buffer.allocate(1922,1080);
            mailbox.onVideoFrame(new dev.onvoid.webrtc.media.video.VideoFrame(invalid,System.nanoTime()));
            assertEquals(0,mailbox.receivedFrames());assertEquals(1,mailbox.droppedFrames());
            var portrait=dev.onvoid.webrtc.media.video.NativeI420Buffer.allocate(600,900);
            Thread.sleep(50);
            mailbox.onVideoFrame(new dev.onvoid.webrtc.media.video.VideoFrame(portrait,System.nanoTime()));
            FxTestRuntime.await(() -> mailbox.imageProperty().get()!=null);
            assertEquals(1,mailbox.receivedFrames());
        }
        var invalidSource=new GeneratedScreen(1922,1080);
        var screen=new ScreenCallController((type,body,id) -> {
            fail("Invalid preview must never signal");return null;
        },() -> null,() -> "self",true);
        try {
            var failure=assertThrows(ExecutionException.class,() -> screen.previewInput(() -> invalidSource).get(8,TimeUnit.SECONDS));
            assertEquals("SCREEN_NO_FRAMES",CameraErrors.screenCode(failure));
            assertEquals(0,screen.frames("self").receivedFrames());assertEquals(1,invalidSource.closes.get());
        } finally {screen.close();screen.closeComplete().get(10,TimeUnit.SECONDS);invalidSource.close();}
    }
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
            if(screen.systemAudioSupported()) {
                FxTestRuntime.call(() -> {((javafx.scene.control.CheckBox)picker.getDialogPane().lookup("#screen-system-audio")).fire();return null;});
                FxTestRuntime.await(() -> screen.systemAudioEnabledProperty().get());
            }
            FxTestRuntime.call(() -> {picker.close();return null;});screen.endPreview().get(10,TimeUnit.SECONDS);
            assertNull(picker.getResult());assertFalse(FxTestRuntime.call(() -> screen.requestedProperty().get()));
            assertFalse(FxTestRuntime.call(() -> screen.systemAudioEnabledProperty().get()));assertEquals(0,requests.get());
        } finally {FxTestRuntime.call(() -> {picker.close();return null;});screen.close();screen.closeComplete().get(10,TimeUnit.SECONDS);}
    }
}
