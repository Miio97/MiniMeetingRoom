package client.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkStatsCalculatorTest {
    @Test
    void testDeltaCalculationAndConversion() {
        NetworkStatsCalculator calc = new NetworkStatsCalculator();

        // Sample 1 at t = 1,000,000,000 ns (1.0s)
        NetworkStatsCalculator.RawCounters s1 = new NetworkStatsCalculator.RawCounters(
                1_000_000_000L,
                0.050, // 50 ms RTT
                0.015, // 15 ms jitter
                10L,   // packets lost
                990L,  // packets received
                125_000L, // bytes sent
                30L,      // frames sent
                640,
                480
        );
        NetworkStatsCalculator.CalculatedSample r1 = calc.process(s1);
        assertEquals(50.0, r1.rttMs(), 0.001);
        assertEquals(15.0, r1.jitterMs(), 0.001);
        // First sample has no baseline, so delta loss/bitrate/fps are null
        assertNull(r1.lossPct());
        assertNull(r1.bitrateKbps());
        assertNull(r1.fps());
        assertEquals(640, r1.width());
        assertEquals(480, r1.height());

        // Sample 2 at t = 2,000,000,000 ns (elapsed = 1.0s)
        // deltaBytes = 100,000 bytes -> 800,000 bits / 1s = 800 kbps
        // deltaFrames = 25 frames / 1s = 25 fps
        // deltaLost = 5, deltaRecv = 495 -> total = 500 packets -> loss = 5 / 500 = 1.0%
        NetworkStatsCalculator.RawCounters s2 = new NetworkStatsCalculator.RawCounters(
                2_000_000_000L,
                0.060, // 60 ms
                0.020, // 20 ms
                15L,   // +5 lost
                1485L, // +495 received
                225_000L, // +100,000 bytes
                55L,      // +25 frames
                640,
                480
        );
        NetworkStatsCalculator.CalculatedSample r2 = calc.process(s2);
        assertEquals(60.0, r2.rttMs(), 0.001);
        assertEquals(20.0, r2.jitterMs(), 0.001);
        assertNotNull(r2.lossPct());
        assertEquals(1.0, r2.lossPct(), 0.01);
        assertEquals(800, r2.bitrateKbps());
        assertEquals(25, r2.fps());
        assertEquals(640, r2.width());
        assertEquals(480, r2.height());
    }

    @Test
    void testCounterResetDropsWindow() {
        NetworkStatsCalculator calc = new NetworkStatsCalculator();

        // Baseline sample
        calc.process(new NetworkStatsCalculator.RawCounters(
                1_000_000_000L, 0.04, 0.01, 100L, 5000L, 1_000_000L, 100L, 640, 480
        ));

        // Sample with counter reset (e.g. peer reconnected or hardware reset, counters decreased)
        NetworkStatsCalculator.CalculatedSample resetSample = calc.process(new NetworkStatsCalculator.RawCounters(
                2_000_000_000L, 0.04, 0.01, 5L, 100L, 50_000L, 10L, 640, 480
        ));

        // When counter resets, delta is negative so window is dropped: metrics are null
        assertNull(resetSample.lossPct(), "Loss should be null on counter reset");
        assertNull(resetSample.bitrateKbps(), "Bitrate should be null on counter reset");
        assertNull(resetSample.fps(), "FPS should be null on counter reset");

        // Next sample after reset builds a new valid delta
        NetworkStatsCalculator.CalculatedSample nextSample = calc.process(new NetworkStatsCalculator.RawCounters(
                3_000_000_000L, 0.04, 0.01, 7L, 298L, 150_000L, 30L, 640, 480
        ));
        // deltaLost = 2, deltaRecv = 198 -> total = 200 -> loss = 1.0%
        // deltaBytes = 100,000 -> 800 kbps
        // deltaFrames = 20 -> 20 fps
        assertNotNull(nextSample.lossPct());
        assertEquals(1.0, nextSample.lossPct(), 0.01);
        assertEquals(800, nextSample.bitrateKbps());
        assertEquals(20, nextSample.fps());
    }

    @Test
    void testNullMetricsAndIsEmpty() {
        NetworkStatsCalculator calc = new NetworkStatsCalculator();

        // All null counters
        NetworkStatsCalculator.RawCounters emptyCounters = new NetworkStatsCalculator.RawCounters(
                1_000_000_000L, null, null, null, null, null, null, null, null
        );
        NetworkStatsCalculator.CalculatedSample sample = calc.process(emptyCounters);
        assertTrue(sample.isEmpty(), "Sample with all null raw counters must be empty");
        assertNull(sample.rttMs());
        assertNull(sample.jitterMs());
        assertNull(sample.lossPct());
        assertNull(sample.bitrateKbps());
        assertNull(sample.fps());
        assertNull(sample.width());
        assertNull(sample.height());
    }
}
