package client.media;

import common.meeting.CreateRoomParams;
import dev.onvoid.webrtc.RTCStatsType;
import dev.onvoid.webrtc.media.video.*;
import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in comparable H2/TLS/native-Pion benchmark. No cameras, microphones or Railway. */
@Timeout(1800)
public class MediaToggleBenchmarkTest {
    @TempDir static Path temporary;
    private static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception { FxTestRuntime.start(); tls = RoomTestFixture.certificates(temporary); }

    @Test void measureColdAndRepeatedActivation() throws Exception {
        int rounds = Integer.getInteger("mmr.benchmark.rounds", 3);
        int samples = Integer.getInteger("mmr.benchmark.samples", 50);
        assertTrue(rounds > 0 && rounds <= 10 && samples > 0 && samples <= 200);
        String label = System.getProperty("mmr.benchmark.label", "current");
        assertTrue(label.matches("[A-Za-z0-9_-]{1,48}"));
        Path output = Path.of("target", "media-toggle-" + label + ".csv");
        Files.createDirectories(output.getParent());
        System.setProperty("mmr.diagnostics.dir", Path.of("target", "media-toggle-diagnostics-" + label).toString());
        try (var diagnostics = common.diagnostics.Diagnostics.start("media-benchmark");
                var observer = Executors.newSingleThreadExecutor();
                BufferedWriter out = Files.newBufferedWriter(output)) {
            out.write("kind,round,sample,activation,fx_dispatch_ms,publish_complete_ms,remote_data_ms,process_cpu_ms,heap_bytes,threads,gc_count,gc_ms,receiving_peers\n");
            for (int round = 0; round < rounds; round++) {
                try (VideoTestFixture fixture = new VideoTestFixture(tls, temporary.resolve("round-" + round))) {
                    var host = fixture.session(fixture.database.account("benchHost" + round, "Host"));
                    var guest = fixture.session(fixture.database.account("benchGuest" + round, "Guest"));
                    VideoCallController camera = null, receiver = null;
                    try {
                        var room = host.create(new CreateRoomParams("Media benchmark", "1234", 2, false)).get(10, TimeUnit.SECONDS);
                        host.join(false, false).get(10, TimeUnit.SECONDS);
                        guest.lookup(room.joinCode(), "1234").get(10, TimeUnit.SECONDS); guest.join(false, false).get(10, TimeUnit.SECONDS);
                        camera = host.video(); receiver = guest.video();
                        camera.publishPreparedCamera().get(10, TimeUnit.SECONDS); receiver.publishPreparedCamera().get(10, TimeUnit.SECONDS);
                        camera.audio().access(true).get(5, TimeUnit.SECONDS);
                        String participant = host.selfParticipantId();
                        for (String kind : List.of("VIDEO", "AUDIO")) {
                            for (int sample = 0; sample <= samples; sample++) {
                                VideoCallController sender = camera, target = receiver;
                                long frames = target.frames(participant).receivedFrames();
                                long pcm = target.audio().receivedSamples(participant);
                                Map<Object, Long> packets = audioPackets(target.audio());
                                long cpu = processCpu();
                                long started = System.nanoTime();
                                var firstRemote = observer.submit(() -> {
                                    long deadline = started + TimeUnit.SECONDS.toNanos(20);
                                    while (System.nanoTime() < deadline) {
                                        boolean received = kind.equals("VIDEO") ? target.frames(participant).receivedFrames() > frames
                                                : target.audio().receivedSamples(participant) > pcm && advancedAudioPackets(target.audio(), packets);
                                        if (received) return System.nanoTime();
                                        Thread.sleep(10);
                                    }
                                    throw new AssertionError("No remote " + kind + " data");
                                });
                                CompletableFuture<Void> enabled = FxTestRuntime.call(() -> kind.equals("VIDEO")
                                        ? sender.toggleInput(GeneratedCamera::new, false) : sender.audio().toggle());
                                long dispatched = System.nanoTime();
                                enabled.get(20, TimeUnit.SECONDS);
                                long completed = System.nanoTime();
                                long delivered = firstRemote.get(22, TimeUnit.SECONDS);
                                long gcCount = 0, gcMs = 0;
                                for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) { gcCount += Math.max(0, gc.getCollectionCount()); gcMs += Math.max(0, gc.getCollectionTime()); }
                                List<MediaStatsSource> routes = new ArrayList<>(); target.audio().statsSources(routes);
                                out.write(String.format(Locale.ROOT, "%s,%d,%d,%s,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%d%n",
                                        kind, round, sample, sample == 0 ? "cold" : "warm", ms(dispatched-started), ms(completed-started), ms(delivered-started),
                                        ms(Math.max(0,processCpu()-cpu)), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                                        ManagementFactory.getThreadMXBean().getThreadCount(), gcCount, gcMs, routes.size()));
                                out.flush();
                                if (kind.equals("VIDEO")) FxTestRuntime.call(() -> sender.toggleInput(GeneratedCamera::new, false)).get(15, TimeUnit.SECONDS);
                                else FxTestRuntime.call(sender.audio()::toggle).get(15, TimeUnit.SECONDS);
                                // Drain already decoded frames/packets; this is outside activation measurements.
                                Thread.sleep(300);
                            }
                        }
                    } finally {
                        host.close(); guest.close();
                        if (camera != null) camera.closeComplete().get(15, TimeUnit.SECONDS);
                        if (receiver != null) receiver.closeComplete().get(15, TimeUnit.SECONDS);
                    }
                }
            }
        }
        System.out.println("MEDIA_TOGGLE_BENCHMARK=" + output.toAbsolutePath());
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static long processCpu() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended.getProcessCpuTime() : 0;
    }
    private static Map<Object,Long> audioPackets(AudioCallController audio) throws Exception {
        List<MediaStatsSource> routes = new ArrayList<>(); audio.statsSources(routes);
        Map<Object,Long> values = new IdentityHashMap<>();
        for (var route : routes) if (!route.sending()) {
            var report = route.read().get().get(3, TimeUnit.SECONDS); long count = 0;
            if (report != null) for (var stat : report.getStats().values()) if (stat.getType() == RTCStatsType.INBOUND_RTP) {
                if (stat.getAttributes().get("packetsReceived") instanceof Number n) count += n.longValue();
            }
            values.put(route.identity(), count);
        }
        return values;
    }
    private static boolean advancedAudioPackets(AudioCallController audio, Map<Object,Long> before) throws Exception {
        for (var entry : audioPackets(audio).entrySet()) if (entry.getValue() > before.getOrDefault(entry.getKey(), 0L)) return true;
        return false;
    }

    static final class GeneratedCamera implements VideoInput {
        private final CustomVideoSource source = new CustomVideoSource();
        private final ScheduledExecutorService frames = Executors.newSingleThreadScheduledExecutor();
        private final AtomicBoolean closed = new AtomicBoolean();
        public int width() { return 320; } public int height() { return 240; } public int fps() { return 15; }
        public VideoTrackSource source() { return source; }
        public void start() { frames.scheduleAtFixedRate(this::frame, 0, 67, TimeUnit.MILLISECONDS); }
        private synchronized void frame() {
            if (closed.get()) return;
            var buffer = NativeI420Buffer.allocate(width(), height());
            try {
                fill(buffer.getDataY(), 110); fill(buffer.getDataU(), 60); fill(buffer.getDataV(), 200);
                source.pushFrame(new VideoFrame(buffer, System.nanoTime()));
            } finally { buffer.release(); }
        }
        private static void fill(ByteBuffer bytes, int value) { for (int i=0;i<bytes.capacity();i++) bytes.put(i,(byte)value); }
        public synchronized void close() { if (closed.compareAndSet(false,true)) { frames.shutdownNow(); source.dispose(); } }
    }
}
