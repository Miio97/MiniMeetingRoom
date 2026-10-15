package client.media;

import com.google.gson.JsonObject;
import common.meeting.RoomSnapshot;
import common.meeting.RoomStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
