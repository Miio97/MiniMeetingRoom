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
class AudioFailureTest {
    @TempDir Path temporary;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); }

    private RoomSnapshot room() {
        return new RoomSnapshot("room", "code", "Audio", RoomStatus.ACTIVE, false, 10, "self", 1, List.of());
    }

    @Test void nativeErrorCompletesToggleAndAllowsAnotherExplicitAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        var controller = new AudioCallController((type, body, id) -> CompletableFuture.completedFuture(new JsonObject()),
                this::room, () -> "self", true, new MicrophoneConsentStore(temporary.resolve("mic.properties")),
                (headless, capture, mic, speaker) -> { attempts.incrementAndGet(); throw new Error("Init recording failed"); });
        try {
            controller.access(true).get(2, TimeUnit.SECONDS);
            for (int i = 0; i < 2; i++) {
                var operation = FxTestRuntime.call(controller::toggle);
                var failure = assertThrows(ExecutionException.class, () -> operation.get(2, TimeUnit.SECONDS));
                assertInstanceOf(Error.class, failure.getCause());
                assertFalse(FxTestRuntime.call(() -> controller.requestedProperty().get()));
                controller.choice().get(2, TimeUnit.SECONDS);
            }
            assertEquals(2, attempts.get());
        } finally { controller.close(); controller.closeComplete().get(3, TimeUnit.SECONDS); }
    }

    @Test void nativeReceiveErrorDoesNotSilentlyCancelPeriodicPolling() throws Exception {
        CountDownLatch attempted = new CountDownLatch(2);
        JsonObject state = new JsonObject(), sources = new JsonObject();
        sources.addProperty("other", "publication"); state.add("sources", sources);
        var controller = new AudioCallController((type, body, id) -> CompletableFuture.completedFuture(state),
                this::room, () -> "self", true, new MicrophoneConsentStore(temporary.resolve("mic.properties")),
                (headless, capture, mic, speaker) -> { attempted.countDown(); throw new Error("Init playout failed"); });
        try {
            controller.enter();
            assertTrue(attempted.await(5, TimeUnit.SECONDS), "Native Error must not cancel the scheduled poll");
            controller.choice().get(2, TimeUnit.SECONDS);
        } finally { controller.close(); controller.closeComplete().get(3, TimeUnit.SECONDS); }
    }

    @Test void closingDoesNotWaitForeverForAnUninterruptibleNativeCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var controller = new AudioCallController((type, body, id) -> CompletableFuture.completedFuture(new JsonObject()),
                this::room, () -> "self", true, new MicrophoneConsentStore(temporary.resolve("mic.properties")),
                (headless, capture, mic, speaker) -> {
                    entered.countDown();
                    boolean done = false;
                    while (!done) { try { release.await(); done = true; } catch (InterruptedException ignored) { } }
                    throw new Error("Device unavailable");
                });
        try {
            controller.access(true).get(2, TimeUnit.SECONDS);
            var operation = FxTestRuntime.call(controller::toggle);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            controller.close();
            assertThrows(ExecutionException.class, () -> operation.get(2, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> controller.closeComplete().get(8, TimeUnit.SECONDS));
            assertEquals("AUDIO_CLEANUP_FAILED", failure.getCause().getMessage());
        } finally { release.countDown(); controller.close(); }
    }

    @Test void interruptionAndConnectionTimeoutAreNotReportedAsMicrophoneFailures() {
        assertEquals("CONNECTION_CLOSED", CameraErrors.audioCode(new InterruptedException()));
        assertEquals("AUDIO_CONNECTION_FAILED", CameraErrors.audioCode(new ExecutionException(new TimeoutException())));
        assertEquals("MICROPHONE_OPEN_FAILED", CameraErrors.audioCode(new Error("Init recording failed")));
    }
}
