package client.media;

import dev.onvoid.webrtc.RTCStatsReport;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Exactly one bounded batch in flight on one fixed scheduler. Native callbacks only deposit results. */
final class NetworkStatsCollector implements AutoCloseable {
    private final ScheduledThreadPoolExecutor worker=createWorker();
    private static ScheduledThreadPoolExecutor createWorker() {
        var worker=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("media-stats").factory());
        worker.setRemoveOnCancelPolicy(true);worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);return worker;
    }
    private final Supplier<List<MediaStatsSource>> sources;
    private final BiConsumer<Long,NetworkSample> sink;
    private final Supplier<int[]> capture;
    private final NetworkStatsConfig config;
    private final RtcQualityMonitor monitor=new RtcQualityMonitor();
    private final AtomicLong generation=new AtomicLong();
    private final AtomicBoolean closed=new AtomicBoolean(), collecting=new AtomicBoolean();
    private volatile Round current;
    private boolean active;
    private final AtomicBoolean resetQueued=new AtomicBoolean();
    private static final class Round {
        final long generation; final List<MediaStatsSource> sources;
        final AtomicReferenceArray<CompletableFuture<RTCStatsReport>> requests;
        final AtomicReferenceArray<RTCStatsReport> reports; final AtomicInteger remaining; final AtomicBoolean finished=new AtomicBoolean();
        ScheduledFuture<?> timeout;
        Round(long generation,List<MediaStatsSource> sources) {this.generation=generation;this.sources=sources;reports=new AtomicReferenceArray<>(sources.size());requests=new AtomicReferenceArray<>(sources.size());remaining=new AtomicInteger(sources.size());}
        void release() {for(int i=0;i<requests.length();i++) {var request=requests.get(i);if(request!=null) request.complete(null);}}
    }
    NetworkStatsCollector(NetworkStatsConfig config,Supplier<List<MediaStatsSource>> sources,Supplier<int[]> capture,BiConsumer<Long,NetworkSample> sink) {
        this.config=config;this.sources=sources;this.capture=capture;this.sink=sink;
        if(config.monitorEnabled()) worker.scheduleAtFixedRate(this::tick,config.intervalMs(),config.intervalMs(),TimeUnit.MILLISECONDS);
    }
    private void enqueue(Runnable task) { try {worker.execute(task);} catch(RejectedExecutionException ignored) { } }
    private void tick() {
        if(closed.get() || !collecting.compareAndSet(false,true)) return;
        try {
            List<MediaStatsSource> peers=sources.get();
            if(peers.isEmpty()) {
                if(active) {active=false;monitor.reset();sink.accept(generation.get(),null);}
                collecting.set(false);return;
            }
            active=true;
            if(peers.size()>160) peers=List.copyOf(peers.subList(0,160));
            Round round=new Round(generation.get(),peers);current=round;
            round.timeout=worker.schedule(() -> finish(round),config.timeoutMs(),TimeUnit.MILLISECONDS);
            for(int i=0;i<peers.size();i++) {
                if(closed.get() || round.finished.get()) break;
                final int index=i;
                CompletableFuture<RTCStatsReport> request;
                try { request=peers.get(i).read().get(); } catch(Exception failure) {request=CompletableFuture.completedFuture(null);}
                round.requests.set(i,request);
                request.whenComplete((report,failure) -> {
                    if(!round.finished.get()) round.reports.set(index,report);
                    if(round.remaining.decrementAndGet()==0) enqueue(() -> finish(round));
                });
            }
        } catch(Exception failure) { current=null;collecting.set(false); }
    }
    private void finish(Round round) {
        if(!round.finished.compareAndSet(false,true)) return;
        if(round.timeout!=null) round.timeout.cancel(false);
        // Completing timed-out waiters frees their closures; the native gate remains occupied until its callback.
        round.release();
        try {
            if(!closed.get() && round.generation==generation.get()) {
                long now=System.nanoTime(); monitor.begin(now);
                monitor.retainSources(round.sources);
                for(int i=0;i<round.sources.size();i++) monitor.accept(round.sources.get(i),round.reports.get(i));
                int[] mode=capture.get();sink.accept(round.generation,monitor.finish(mode[0],mode[1],mode[2]));
            }
        } finally {if(current==round) {current=null;collecting.set(false);}}
    }
    void reset() {
        generation.incrementAndGet();
        if(resetQueued.compareAndSet(false,true)) enqueue(() -> {try {if(current!=null) finish(current);monitor.reset();} finally {resetQueued.set(false);}});
    }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        generation.incrementAndGet();Round round=current;
        if(round!=null) {round.finished.set(true);if(round.timeout!=null)round.timeout.cancel(false);round.release();}
        enqueue(() -> {monitor.reset();current=null;collecting.set(false);});worker.shutdown();
    }
    boolean awaitStopped(long timeoutMs) throws InterruptedException {return worker.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS);}
}
