package client.media;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static client.media.MediaStatsSamples.*;
class NetworkStatsBufferTest {
    @Test void wrapsAtFortyAndDoesNotAcceptOldSamplesOrOldGeneration() {
        var buffer=new NetworkStatsBuffer(40);for(int i=0;i<10000;i++) buffer.add(sample(i,GOOD,GOOD,GOOD));
        assertEquals(40,buffer.size());var history=buffer.snapshot();assertEquals(9960,history[0].atNanos());assertEquals(9999,history[39].atNanos());
        assertFalse(buffer.add(sample(1,GOOD,GOOD,GOOD)));buffer.reset(1);assertEquals(0,buffer.size());
        assertFalse(buffer.add(sample(20000,GOOD,GOOD,GOOD),0));assertTrue(buffer.add(sample(20001,GOOD,GOOD,GOOD),1));
    }
    @Test void snapshotsAreConsistentUnderConcurrentReadersAndLeaveReleasesHistory() throws Exception {
        var buffer=new NetworkStatsBuffer(40);var writer=new Thread(() -> {for(int i=0;i<20000;i++) buffer.add(sample(i,GOOD,GOOD,GOOD));});
        writer.start();while(writer.isAlive()) {var h=buffer.snapshot();assertTrue(h.length<=40);for(int i=1;i<h.length;i++) assertTrue(h[i].atNanos()>h[i-1].atNanos());}
        writer.join();buffer.clear();assertEquals(0,buffer.snapshot().length);
    }
}
