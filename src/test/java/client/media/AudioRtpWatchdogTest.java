package client.media;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioRtpWatchdogTest {
    private long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }
    @Test void connectedDecoderProducingConcealmentDoesNotCountAsRtpProgress() {
        var watch = new AudioRtpWatchdog(0);
        watch.observe(1465, seconds(1));
        watch.observe(1465, seconds(15));
        assertFalse(watch.stalled(seconds(15)));
        assertTrue(watch.stalled(seconds(16)));
        watch.observe(1466, seconds(17));
        assertFalse(watch.stalled(seconds(17)));
    }
    @Test void quietStreamsWithSparsePacketsRemainHealthyAndMissingRtpTimesOut() {
        var watch = new AudioRtpWatchdog(0);
        assertFalse(watch.stalled(seconds(14)));
        assertTrue(watch.stalled(seconds(15)));
        for (int i = 1; i < 10; i++) {
            watch.observe(i, seconds(i * 10));
            assertFalse(watch.stalled(seconds(i * 10 + 9)));
        }
    }
}
