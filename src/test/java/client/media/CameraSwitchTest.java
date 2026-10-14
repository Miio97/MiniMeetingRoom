package client.media;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraSwitchTest {
    @Test void rapidPressesKeepOneStartAndApplyTheFinalOffChoice() throws Exception {
        var start = new CompletableFuture<Void>(); AtomicInteger starts = new AtomicInteger(), stops = new AtomicInteger();
        try (var camera = new CameraSwitch(() -> { })) {
            java.util.function.Supplier<CompletableFuture<Void>> begin = () -> { starts.incrementAndGet(); return start; };
            java.util.function.Supplier<CompletableFuture<Void>> end = () -> { stops.incrementAndGet(); return CompletableFuture.completedFuture(null); };
            var first = camera.toggle(false, begin, end);
            for (int i = 0; i < 99; i++) assertSame(first, camera.toggle(false, begin, end));
            assertFalse(camera.wanted()); assertTrue(camera.pending()); assertEquals(1, starts.get());
            start.complete(null); first.get(1, TimeUnit.SECONDS);
            assertEquals(1, stops.get()); assertFalse(camera.pending()); assertFalse(camera.wanted());
        }
    }
    @Test void cancelDuringStartDoesNotFailTheLastOffChoiceOrQueueRepeatedStarts() throws Exception {
        var start = new CompletableFuture<Void>(); AtomicInteger stops = new AtomicInteger();
        try (var camera = new CameraSwitch(() -> { })) {
            var first = camera.toggle(false, () -> start, () -> { stops.incrementAndGet(); return CompletableFuture.completedFuture(null); });
            camera.toggle(false, () -> start, () -> CompletableFuture.completedFuture(null));
            start.completeExceptionally(new CancellationException()); first.get(1, TimeUnit.SECONDS);
            assertFalse(camera.pending()); assertFalse(camera.wanted()); assertEquals(0, stops.get());
        }
    }
    @Test void failureResetsChoiceAndAllowsAnExplicitRetry() throws Exception {
        try (var camera = new CameraSwitch(() -> { })) {
            assertThrows(ExecutionException.class, () -> camera.toggle(false, () -> CompletableFuture.failedFuture(new IllegalStateException("CAMERA_NO_FRAMES")),
                    () -> CompletableFuture.completedFuture(null)).get(1, TimeUnit.SECONDS));
            assertFalse(camera.wanted()); assertFalse(camera.pending());
            camera.toggle(false, () -> CompletableFuture.completedFuture(null), () -> CompletableFuture.completedFuture(null)).get(1, TimeUnit.SECONDS);
            assertTrue(camera.wanted());
        }
    }
}
