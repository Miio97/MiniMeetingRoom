package client.media;

import java.util.concurrent.*;
import java.util.function.*;

/** One operation in flight; repeated presses replace the target rather than queueing native starts. */
final class CameraSwitch implements AutoCloseable {
    private final Runnable changed;
    private boolean wanted, applied, pending, closed;
    private Supplier<CompletableFuture<Void>> start, stop;
    private CompletableFuture<Void> settled = CompletableFuture.completedFuture(null);
    CameraSwitch(Runnable changed) { this.changed = changed; }
    synchronized boolean wanted() { return wanted; }
    synchronized boolean pending() { return pending; }
    /** Finish any preview operation, then promote the prepared source as one tracked start. */
    synchronized CompletableFuture<Void> publishPrepared(BooleanSupplier prepared,
            Supplier<CompletableFuture<Void>> publish, Supplier<CompletableFuture<Void>> stop) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("CONNECTION_CLOSED"));
        if (pending) return settled.thenCompose(ignored -> publishPrepared(prepared, publish, stop));
        if (!prepared.getAsBoolean()) return CompletableFuture.completedFuture(null);
        return toggle(false, publish, stop);
    }
    synchronized CompletableFuture<Void> toggle(boolean actual, Supplier<CompletableFuture<Void>> start,
                                                Supplier<CompletableFuture<Void>> stop) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("CONNECTION_CLOSED"));
        if (!pending) { wanted = actual; applied = actual; settled = new CompletableFuture<>(); }
        this.start = start; this.stop = stop; wanted = !wanted;
        boolean launch = !pending; pending = true; changed.run();
        CompletableFuture<Void> result = settled;
        if (launch) advance();
        return result;
    }
    private synchronized void advance() {
        if (closed) return;
        if (applied == wanted) { pending = false; changed.run(); settled.complete(null); return; }
        boolean target = wanted;
        CompletableFuture<Void> operation;
        try { operation = (target ? start : stop).get(); }
        catch (Exception failure) { operation = CompletableFuture.failedFuture(failure); }
        operation.whenComplete((value, failure) -> completed(target, failure));
    }
    private synchronized void completed(boolean target, Throwable failure) {
        if (closed) return;
        Throwable cause = failure;
        while (cause instanceof CompletionException || cause instanceof ExecutionException) cause = cause.getCause();
        applied = failure == null && target;
        if (failure != null && !(cause instanceof CancellationException) && wanted == target) {
            wanted = false; pending = false; changed.run(); settled.completeExceptionally(failure); return;
        }
        advance();
    }
    synchronized void resetOff() { wanted = false; applied = false; changed.run(); }
    @Override public synchronized void close() {
        closed = true; wanted = false; pending = false; changed.run();
        settled.completeExceptionally(new IllegalStateException("CONNECTION_CLOSED"));
    }
}
