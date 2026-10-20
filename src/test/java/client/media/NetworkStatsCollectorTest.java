package client.media;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.onvoid.webrtc.RTCStatsReport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetworkStatsCollectorTest {
    @Test void idleDoesNotReadAndTimeoutResetAndCloseBoundWorkAndIgnoreOldRound() throws Exception {
        var p=new Properties();p.setProperty("network.stats.timeout.ms","500");var config=NetworkStatsConfig.from(p);
        var reads=new AtomicInteger();var samples=new CopyOnWriteArrayList<Long>();var enabled=new AtomicBoolean();
        var gate=new SingleFlightStats();var pending=new AtomicReference<java.util.function.Consumer<RTCStatsReport>>();
        var source=new MediaStatsSource(new Object(),MediaStatsSource.Kind.CAMERA,true,false,() -> gate.request(callback -> {reads.incrementAndGet();pending.set(callback);}));
        var collector=new NetworkStatsCollector(config,() -> enabled.get()?List.of(source):List.of(),() -> new int[]{640,360,30},(generation,sample) -> samples.add(generation));
        try {
            Thread.sleep(600);assertEquals(0,reads.get());enabled.set(true);await(() -> reads.get()==1);
            collector.reset();Thread.sleep(1200);assertEquals(1,reads.get(),"Timed-out native request must not spawn more native work");
            assertTrue(samples.stream().allMatch(g -> g==1));pending.get().accept(null);await(() -> reads.get()==2);
        } finally {collector.close();gate.close();assertTrue(collector.awaitStopped(2000));}
        int count=samples.size();Thread.sleep(650);assertEquals(count,samples.size());
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<end) {if(condition.getAsBoolean()) return;Thread.sleep(20);}fail("timeout");}
}
