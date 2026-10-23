package client.media;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraSwitchTest {
    @Test void explicitStopCoalescesDuringStartAndRemainsOffAfterHostStop() throws Exception {
        var starting=new CompletableFuture<Void>();var stopping=new CompletableFuture<Void>();
        var starts=new AtomicInteger();var stops=new AtomicInteger();
        try(var sharing=new CameraSwitch(() -> {})) {
            var result=sharing.toggle(false,() -> {starts.incrementAndGet();return starting;},() -> stopping);
            for(int i=0;i<100;i++) assertSame(result,sharing.stop(false,() -> {stops.incrementAndGet();return stopping;}));
            starting.complete(null);assertEquals(1,stops.get());
            sharing.resetOff(); // Host stopped while explicit off is still in flight.
            for(int i=0;i<100;i++) sharing.stop(false,() -> {fail("Already stopped");return null;});
            stopping.complete(null);result.get(1,TimeUnit.SECONDS);
            sharing.stop(false,() -> {fail("Already stopped");return null;}).get(1,TimeUnit.SECONDS);
            assertEquals(1,starts.get());assertFalse(sharing.wanted());
        }
    }

    @Test void preparedPreviewPublicationIsPendingAndRapidPressesKeepTheLastChoice() throws Exception {
        var publication = new CompletableFuture<Void>();
        AtomicBoolean prepared = new AtomicBoolean(true);
        AtomicInteger publishes = new AtomicInteger(), stops = new AtomicInteger();
        try (var camera = new CameraSwitch(() -> { })) {
            java.util.function.Supplier<CompletableFuture<Void>> publish = () -> { publishes.incrementAndGet(); return publication; };
            java.util.function.Supplier<CompletableFuture<Void>> stop = () -> { stops.incrementAndGet(); return CompletableFuture.completedFuture(null); };
            var first = camera.publishPrepared(prepared::get, publish, stop);
            assertTrue(camera.pending()); assertTrue(camera.wanted()); assertEquals(1, publishes.get());
            var duplicate = camera.publishPrepared(prepared::get, publish, stop);
            for (int i = 0; i < 100; i++) assertSame(first, camera.toggle(true, publish, stop));
            prepared.set(false); publication.complete(null);
            first.get(1, TimeUnit.SECONDS); duplicate.get(1, TimeUnit.SECONDS);
            assertEquals(1, publishes.get()); assertEquals(0, stops.get()); assertFalse(camera.pending());
            assertTrue(camera.wanted());
        }
    }

    @Test void enteringDuringPreviewWaitsThenPublishesWithoutReopeningCapture() throws Exception {
        var preview = new CompletableFuture<Void>(); var publication = new CompletableFuture<Void>();
        AtomicBoolean prepared = new AtomicBoolean(); AtomicInteger publishes = new AtomicInteger();
        try (var camera = new CameraSwitch(() -> { })) {
            camera.toggle(false, () -> preview, () -> CompletableFuture.completedFuture(null));
            var entered = camera.publishPrepared(prepared::get, () -> { publishes.incrementAndGet(); return publication; },
                    () -> CompletableFuture.completedFuture(null));
            assertEquals(0, publishes.get()); assertTrue(camera.pending());
            prepared.set(true); preview.complete(null);
            assertTrue(camera.pending()); assertTrue(camera.wanted()); assertFalse(entered.isDone());
            assertEquals(1, publishes.get()); publication.complete(null); entered.get(1, TimeUnit.SECONDS);
            assertFalse(camera.pending()); assertTrue(camera.wanted());
        }
    }

    @Test void finalOffDuringPreparedPublicationCancelsWithoutAnErrorOrAnotherStart() throws Exception {
        var publication = new CompletableFuture<Void>(); AtomicInteger stops = new AtomicInteger();
        try (var camera = new CameraSwitch(() -> { })) {
            var entered = camera.publishPrepared(() -> true, () -> publication,
                    () -> { stops.incrementAndGet(); return CompletableFuture.completedFuture(null); });
            camera.toggle(true, () -> { fail("Must not reopen the camera"); return null; }, () -> CompletableFuture.completedFuture(null));
            publication.completeExceptionally(new CancellationException()); entered.get(1, TimeUnit.SECONDS);
            assertFalse(camera.pending()); assertFalse(camera.wanted()); assertEquals(0, stops.get());
        }
    }
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
