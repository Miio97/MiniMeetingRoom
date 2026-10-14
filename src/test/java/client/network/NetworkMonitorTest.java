package client.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static client.network.NetworkMonitor.Quality.*;

class NetworkMonitorTest {
    private final AtomicLong time = new AtomicLong(12345);
    private final NetworkMonitor monitor = new NetworkMonitor(time::get);
    private void advance(long millis) { time.addAndGet(Duration.ofMillis(millis).toNanos()); }

    @Test void startsWithoutInventedSamplesAndDetectsNoFirstResponse() {
        var initial = monitor.snapshot();
        assertEquals(WAITING, initial.quality()); assertNull(initial.rttMillis()); assertNull(initial.jitterMillis());
        assertTrue(initial.history().isEmpty()); assertEquals(0, initial.requests());
        assertEquals(0, initial.sendBitsPerSecond()); assertEquals(0, initial.receiveBitsPerSecond());
        advance(5001); assertEquals(DISCONNECTED, monitor.snapshot().quality());
    }
    @Test void derivesJitterFromAdjacentMeasuredResponsesAndPrunesThirtySecondHistory() {
        monitor.exchange(true, 40.0); advance(1000);
        monitor.exchange(true, 50.0); advance(1000); monitor.exchange(true, 45.0);
        var sample = monitor.snapshot();
        assertEquals(45, sample.rttMillis()); assertEquals(7.5, sample.jitterMillis());
        assertEquals(GOOD, sample.quality()); assertEquals(3, sample.history().size());
        assertEquals(2, sample.history().getLast().second());
        advance(29001); sample = monitor.snapshot();
        assertEquals(1, sample.history().size()); assertNull(sample.rttMillis());
        advance(1000); assertTrue(monitor.snapshot().history().isEmpty());
    }
    @Test void staleMeasurementsCannotKeepGreenBadgeAndFailuresDoNotBecomeZeroLatency() {
        monitor.exchange(true, 10.0); advance(3001); assertEquals(POOR, monitor.snapshot().quality());
        advance(2000); assertEquals(DISCONNECTED, monitor.snapshot().quality()); assertNull(monitor.snapshot().rttMillis());
        monitor.exchange(true, null);
        var failure = monitor.snapshot();
        assertNull(failure.history().getLast().rttMillis()); assertNull(failure.jitterMillis());
        assertEquals(50, failure.failurePercent()); assertEquals(DISCONNECTED, failure.quality());
        advance(1000); monitor.exchange(true, 20.0);
        assertEquals(20, monitor.snapshot().rttMillis()); assertNull(monitor.snapshot().jitterMillis());
        advance(30001); monitor.exchange(true, 15.0);
        assertEquals(GOOD, monitor.snapshot().quality()); assertEquals(0, monitor.snapshot().failures());
    }
    @Test void qualityUsesMeasuredDelayVariationAndFailures() {
        monitor.exchange(true, 80.0); assertEquals(FAIR, monitor.snapshot().quality());
        monitor.exchange(true, 160.0); assertEquals(POOR, monitor.snapshot().quality());
        advance(31000); monitor.exchange(true, 1.0); assertEquals(GOOD, monitor.snapshot().quality());
        advance(1000); monitor.exchange(true, 50.0); assertEquals(POOR, monitor.snapshot().quality());
        assertEquals(49, monitor.snapshot().jitterMillis());
    }
    @Test void countsActualStreamBytesOnceIncludingLengthPrefixAndHandlesEof() throws Exception {
        ByteArrayOutputStream target = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(monitor.output(target));
        output.writeInt(3); output.write(new byte[] {1, 2, 3}); output.flush();
        DataInputStream input = new DataInputStream(monitor.input(new ByteArrayInputStream(target.toByteArray())));
        assertEquals(3, input.readInt()); assertArrayEquals(new byte[] {1, 2, 3}, input.readNBytes(3));
        assertEquals(-1, input.read());
        advance(1000); var sample = monitor.snapshot();
        assertEquals(7, sample.bytesSent()); assertEquals(7, sample.bytesReceived());
        assertEquals(56, sample.sendBitsPerSecond()); assertEquals(56, sample.receiveBitsPerSecond());
        advance(6000); sample = monitor.snapshot();
        assertEquals(0, sample.sendBitsPerSecond()); assertEquals(0, sample.receiveBitsPerSecond());
        assertEquals(7, sample.bytesSent());
    }
    @Test void rateUsesRealElapsedTimeForPartialWindowsAndIsIndependentOfSnapshotFrequency() throws Exception {
        monitor.output(new ByteArrayOutputStream()).write(new byte[1000]); advance(5500);
        assertEquals(8000 / 5.5, monitor.snapshot().sendBitsPerSecond(), .0001);
        assertEquals(monitor.snapshot(), monitor.snapshot());
        advance(500); assertEquals(0, monitor.snapshot().sendBitsPerSecond());
        monitor.output(new ByteArrayOutputStream()).write(new byte[500]); advance(1000);
        assertEquals(800, monitor.snapshot().sendBitsPerSecond());
    }
    @Test void ignoresFailedWritesAndFreezesCountersOnClose() throws Exception {
        OutputStream failing = monitor.output(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("test failure"); }
        });
        assertThrows(IOException.class, () -> failing.write(1)); assertEquals(0, monitor.snapshot().bytesSent());
        monitor.exchange(true, 9.0); monitor.close(); var stopped = monitor.snapshot();
        assertEquals(STOPPED, stopped.quality());
        advance(60000); monitor.exchange(true, null); monitor.output(new ByteArrayOutputStream()).write(42);
        assertEquals(stopped, monitor.snapshot());
    }
    @Test void concurrentChatAndRoomCountersAreIsolatedBetweenClients() throws Exception {
        NetworkMonitor other = new NetworkMonitor(time::get);
        try (var workers = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 100; i++) workers.submit(() -> {
                monitor.output(new ByteArrayOutputStream()).write(new byte[50]);
                monitor.input(new ByteArrayInputStream(new byte[25])).readNBytes(25);
                monitor.exchange(false, 1.0); return null;
            });
            workers.shutdown(); assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(5000, monitor.snapshot().bytesSent()); assertEquals(2500, monitor.snapshot().bytesReceived());
        assertEquals(100, monitor.snapshot().requests()); assertTrue(monitor.snapshot().history().isEmpty());
        assertEquals(0, other.snapshot().bytesSent()); assertEquals(0, other.snapshot().requests());
    }
    @Test void rejectsInvalidTimingWithoutRecordingFabricatedMeasurements() {
        for (double invalid : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> monitor.exchange(true, invalid));
        }
        assertTrue(monitor.snapshot().history().isEmpty()); assertEquals(0, monitor.snapshot().requests());
    }
}
