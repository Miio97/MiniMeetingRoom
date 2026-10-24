package client.media;

import dev.onvoid.webrtc.RTCStatsReport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** A timeout ends the caller's wait, NOT the native request. Only its callback frees the gate. */
final class SingleFlightStats implements AutoCloseable {
    private final AtomicReference<CompletableFuture<RTCStatsReport>> active =
            new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    CompletableFuture<RTCStatsReport> request(Consumer<Consumer<RTCStatsReport>> start) {
        var result = new CompletableFuture<RTCStatsReport>();
        if (closed.get() || !active.compareAndSet(null, result))
            return CompletableFuture.completedFuture(null);
        try {
            if (closed.get()) {
                active.compareAndSet(result, null);
                result.complete(null);
                return result;
            }
            start.accept(
                    report -> {
                        result.complete(closed.get() ? null : report);
                        active.compareAndSet(result, null);
                    });
        } catch (Exception | LinkageError failure) {
            active.compareAndSet(result, null);
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override
    public void close() {
        closed.set(true);
        var result = active.getAndSet(null);
        if (result != null) result.complete(null);
    }
}
