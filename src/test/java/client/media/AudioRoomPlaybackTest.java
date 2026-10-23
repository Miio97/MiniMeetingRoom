package client.media;

import com.google.gson.JsonObject;
import common.meeting.MediaState;
import common.meeting.ParticipantView;
import common.meeting.RoomRole;
import common.meeting.RoomSnapshot;
import common.meeting.RoomStatus;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.FxTestRuntime;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AudioRoomPlaybackTest {
    @TempDir Path temporary;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    @Test void enteringRoomAloneDoesNotOpenAnAudioDevice() throws Exception {
        verifyNoDevices(false);
    }

    @Test void ownPublicationNeverCreatesALocalPlaybackLoop() throws Exception {
        verifyNoDevices(true);
    }

    @Test void preJoinMicrophonePublishesOnEnterAndSurvivesUnconfirmedRoomSnapshot() throws Exception {
        var unconfirmed = new RoomSnapshot("room", "code", "Solo", RoomStatus.ACTIVE, false, 10, "self", 1,
                List.of(new ParticipantView("self", "Self", RoomRole.HOST, new MediaState(false, false, false, false, false, false))));
        var confirmed = new RoomSnapshot("room", "code", "Solo", RoomStatus.ACTIVE, false, 10, "self", 2,
                List.of(new ParticipantView("self", "Self", RoomRole.HOST, new MediaState(true, false, false, true, false, false))));
        var hostMuted = new RoomSnapshot("room", "code", "Solo", RoomStatus.ACTIVE, false, 10, "self", 3,
                List.of(new ParticipantView("self", "Self", RoomRole.HOST, new MediaState(false, false, false, false, false, false))));
        var currentRoom = new AtomicReference<>(unconfirmed);
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch pollsAfterPublish = new CountDownLatch(2);
        AtomicInteger stopCount = new AtomicInteger();
        var answerer = new AudioAnswerer();

        var controller = new AudioCallController((type, body, id) -> {
            if ("AUDIO_PUBLISH".equals(type)) {
                published.countDown();
                JsonObject reply = new JsonObject();
                reply.addProperty("transport", "t-audio");
                return answerer.answer(body.get("sdp").getAsString()).thenApply(sdp -> {
                    reply.addProperty("sdp", sdp);
                    return reply;
                });
            }
            if ("AUDIO_STOP".equals(type)) {
                stopCount.incrementAndGet();
                return CompletableFuture.completedFuture(new JsonObject());
            }
            if ("AUDIO_STATE".equals(type)) {
                if (published.getCount() == 0) pollsAfterPublish.countDown();
                JsonObject state = new JsonObject();
                state.add("sources", new JsonObject());
                return CompletableFuture.completedFuture(state);
            }
            return CompletableFuture.completedFuture(new JsonObject());
        }, currentRoom::get, () -> "self", true, new MicrophoneConsentStore(temporary.resolve("mic-prejoin.properties")));

        try {
            controller.access(true).get(3, TimeUnit.SECONDS);
            // Toggle microphone ON in Pre-Join (before enter() is called)
            FxTestRuntime.call(controller::toggle).get(5, TimeUnit.SECONDS);
            assertTrue(FxTestRuntime.call(() -> controller.requestedProperty().get()), "Mic must be active in pre-join");
            assertEquals(1, published.getCount(), "Pre-join must not publish before entering meeting");

            // Enter meeting room while RoomSnapshot still has micRequested = false
            controller.enter();
            assertTrue(published.await(5, TimeUnit.SECONDS), "Entering meeting must publish prepared microphone");
            assertTrue(pollsAfterPublish.await(5, TimeUnit.SECONDS), "Periodic synchronize must run while snapshot lags");
            controller.choice().get(3, TimeUnit.SECONDS);

            assertEquals(0, stopCount.get(), "Lagging room snapshot must not auto-stop microphone after entering meeting");
            assertTrue(FxTestRuntime.call(() -> controller.requestedProperty().get()), "Microphone must remain ON in meeting");

            // Once server confirms micRequested=true and later host mutes (micRequested=false), it must stop immediately
            currentRoom.set(confirmed);
            Thread.sleep(1200);
            currentRoom.set(hostMuted);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (FxTestRuntime.call(() -> controller.requestedProperty().get()) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertFalse(FxTestRuntime.call(() -> controller.requestedProperty().get()), "Confirmed host mute must stop microphone");
        } finally {
            controller.close();
            controller.closeComplete().get(5, TimeUnit.SECONDS);
            answerer.close();
        }
    }

    /** A real remote peer is needed: an offer cannot be echoed back as an answer. */
    private static final class AudioAnswerer implements AutoCloseable {
        private final HeadlessAudioDeviceModule audio = new HeadlessAudioDeviceModule();
        private final PeerConnectionFactory factory = new PeerConnectionFactory(audio);
        private final CompletableFuture<Void> gathered = new CompletableFuture<>();
        private final RTCPeerConnection peer = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
            @Override public void onIceCandidate(RTCIceCandidate candidate) { }
            @Override public void onIceGatheringChange(RTCIceGatheringState state) {
                if (state == RTCIceGatheringState.COMPLETE) gathered.complete(null);
            }
        });

        CompletableFuture<String> answer(String offer) {
            return setDescription(new RTCSessionDescription(RTCSdpType.OFFER, offer), true)
                    .thenCompose(ignored -> {
                        var result = new CompletableFuture<RTCSessionDescription>();
                        peer.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                            @Override public void onSuccess(RTCSessionDescription answer) { result.complete(answer); }
                            @Override public void onFailure(String error) { result.completeExceptionally(new IllegalStateException(error)); }
                        });
                        return result;
                    }).thenCompose(answer -> setDescription(answer, false))
                    .thenCompose(ignored -> gathered)
                    .thenApply(ignored -> peer.getLocalDescription().sdp);
        }

        private CompletableFuture<Void> setDescription(RTCSessionDescription description, boolean remote) {
            var result = new CompletableFuture<Void>();
            var observer = new SetSessionDescriptionObserver() {
                @Override public void onSuccess() { result.complete(null); }
                @Override public void onFailure(String error) { result.completeExceptionally(new IllegalStateException(error)); }
            };
            if (remote) peer.setRemoteDescription(description, observer);
            else peer.setLocalDescription(description, observer);
            return result;
        }

        @Override public void close() { peer.close(); factory.dispose(); audio.dispose(); }
    }

    private void verifyNoDevices(boolean ownPublication) throws Exception {
        var room = new RoomSnapshot("room", "code", "Solo", RoomStatus.ACTIVE, false, 10, "self", 1, List.of());
        AtomicInteger opened = new AtomicInteger();
        CountDownLatch polls = new CountDownLatch(2);
        JsonObject state = new JsonObject(), sources = new JsonObject();
        if (ownPublication) sources.addProperty("self", "own-publication");
        state.add("sources", sources);
        var controller = new AudioCallController((type, body, id) -> {
            polls.countDown();
            return CompletableFuture.completedFuture(state);
        }, () -> room, () -> "self", true, new MicrophoneConsentStore(temporary.resolve("mic.properties")),
                (headless, capture, mic, speaker) -> {
                    opened.incrementAndGet();
                    throw new IllegalStateException("UNEXPECTED_DEVICE_OPEN");
                });
        try {
            controller.enter();
            assertTrue(polls.await(5, TimeUnit.SECONDS), "Room state must continue polling");
            // A barrier on the controller executor ensures the last poll finished processing.
            controller.choice().get(3, TimeUnit.SECONDS);
            assertEquals(0, opened.get(), "A solo room must not open capture or playout");
        } finally {
            controller.close();
            controller.closeComplete().get(5, TimeUnit.SECONDS);
        }
    }
}
