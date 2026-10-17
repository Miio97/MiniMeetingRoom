package common.diagnostics;

import com.google.gson.Gson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, asynchronous UTF-8 JSON-lines diagnostics in a .txt file per process.
 * Application calls only enqueue bounded scalar fields. Do not pass credentials,
 * identities, host names, chat contents, request/response objects or free text.
 * Exceptions are recorded by class and SQLState only, without messages or stacks.
 */
public final class Diagnostics implements AutoCloseable {
    private static final Diagnostics DISABLED = new Diagnostics();
    private static volatile Diagnostics current = DISABLED;
    private static final Gson JSON = new Gson();
    private static final Set<String> STRING_FIELDS = Set.of("action", "phase", "outcome",
            "response_code", "media_status", "operation", "screen", "kind", "status",
            "reason", "code", "component", "transport", "protocol", "bind_scope",
            "auth_transport", "result", "startup_step", "error_kind", "sql_state");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final long FOOTER_RESERVE = 4096;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean noticePrinted = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong truncatedEvents = new AtomicLong();
    private final Object admission = new Object();
    private final long startedNanos = System.nanoTime();
    private final ArrayBlockingQueue<Map<String, Object>> queue;
    private final Path path;
    private final String role;
    private final String runId;
    private final long pid;
    private final long maxBytes;
    private final Thread writerThread;
    private final ScheduledExecutorService sampler;
    private final BufferedWriter writer;
    private volatile boolean ioFailed;
    private volatile boolean truncated;
    private long bytesWritten;
    private long sequence;
    private long recordsWritten;
    private long lastSampleNanos;
    private long lastProcessCpuNanos = -1;
    private long lastGcCount;
    private long lastGcMillis;

    private Diagnostics() {
        queue = null;
        path = null;
        role = "disabled";
        runId = "disabled";
        pid = 0;
        maxBytes = 0;
        writerThread = null;
        sampler = null;
        writer = null;
        closed.set(true);
    }

    private Diagnostics(String requestedRole) throws IOException {
        role = requestedRole != null && requestedRole.matches("[A-Za-z0-9_-]{1,48}")
                ? requestedRole : "app";
        runId = safeTag(setting("runId", "RUN_ID", UUID.randomUUID().toString()),
                UUID.randomUUID().toString());
        pid = ProcessHandle.current().pid();
        maxBytes = integerSetting("maxBytes", "MAX_BYTES", 20L * 1024 * 1024,
                8192, 100L * 1024 * 1024);
        int capacity = (int) integerSetting("queueCapacity", "QUEUE_CAPACITY", 1024, 8, 8192);
        long sampleSeconds = integerSetting("sampleSeconds", "SAMPLE_SECONDS", 5, 1, 60);
        queue = new ArrayBlockingQueue<>(capacity);
        Path directory = Path.of(setting("dir", "DIR", "logs/diagnostics")).toAbsolutePath();
        Files.createDirectories(directory);
        path = directory.resolve(role + "-" + FILE_TIME.format(Instant.now()) + "-" + pid
                + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt");
        writerThread = new Thread(this::writeLoop, "mmr-diagnostics-writer");
        writerThread.setDaemon(true);
        sampler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "mmr-diagnostics-sampler");
            thread.setDaemon(true);
            return thread;
        });
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("format", "utf8_json_lines");
            metadata.put("java_version", System.getProperty("java.version"));
            metadata.put("vm_name", System.getProperty("java.vm.name"));
            metadata.put("os_name", System.getProperty("os.name"));
            metadata.put("os_arch", System.getProperty("os.arch"));
            metadata.put("cpu_count", Runtime.getRuntime().availableProcessors());
            metadata.put("max_heap_bytes", Runtime.getRuntime().maxMemory());
            metadata.put("sample_interval_ms", sampleSeconds * 1000);
            metadata.put("queue_capacity", capacity);
            metadata.put("max_file_bytes", maxBytes);
            queue.add(record("run.start", metadata));
            primeRuntimeCounters();
            writerThread.start();
            sampler.scheduleAtFixedRate(this::sampleRuntime, sampleSeconds,
                    sampleSeconds, TimeUnit.SECONDS);
        } catch (RuntimeException failure) {
            close();
            if (writerThread.getState() == Thread.State.NEW) {
                writer.close();
            }
            throw failure;
        }
    }

    /**
     * Starts at most one active recorder per JVM. Configuration uses properties or env.
     * The application owns close and must stop its workers before closing diagnostics.
     * No implicit JVM shutdown hook competes with the application's cleanup order.
     */
    public static synchronized Diagnostics start(String role) {
        if (current.isActive()) {
            return current;
        }
        if ("false".equalsIgnoreCase(setting("enabled", "ENABLED", "true"))) {
            current = DISABLED;
            return DISABLED;
        }
        try {
            current = new Diagnostics(role);
            System.out.println("Diagnostics log: " + current.logPath());
        } catch (IOException | RuntimeException failure) {
            // Never print the exception message: it can contain private filesystem paths.
            System.err.println("Diagnostics disabled: output could not be initialized ("
                    + failure.getClass().getSimpleName() + ").");
            current = DISABLED;
        }
        return current;
    }

    /** Returns a harmless no-op before start, after close, or following an I/O failure. */
    public static Diagnostics current() {
        Diagnostics result = current;
        return result.isActive() ? result : DISABLED;
    }

    /** Null means diagnostics are disabled or output could not be created. */
    public Path logPath() {
        return path;
    }

    public void event(String name, Map<String, ?> fields) {
        if (!isActive()) {
            return;
        }
        enqueue(record(safeTag(name, "event"), safeFields(fields)));
    }

    public void failure(String name, Throwable failure, Map<String, ?> fields) {
        if (!isActive()) {
            return;
        }
        Map<String, Object> details = safeFields(fields);
        if (failure != null) {
            details.put("error_type", failure.getClass().getName());
            Throwable cause = failure.getCause();
            if (cause != null && cause != failure) {
                details.put("cause_type", cause.getClass().getName());
            }
            Throwable candidate = failure;
            var errorChain = new ArrayList<String>();
            for (int depth = 0; candidate != null && depth < 8; depth++) {
                errorChain.add(candidate.getClass().getName());
                if (candidate instanceof SQLException sql && sql.getSQLState() != null
                        && sql.getSQLState().matches("[A-Za-z0-9]{5}")) {
                    details.put("sql_state", sql.getSQLState());
                }
                Throwable next = candidate.getCause();
                if (next == candidate) {
                    break;
                }
                candidate = next;
            }
            details.put("error_chain", errorChain);
        }
        enqueue(record(safeTag(name, "failure"), details));
    }

    /** Future WebRTC/SFU adapters call this with measured interval deltas only. */
    public void mediaSample(MediaSample sample) {
        if (sample == null) {
            throw new IllegalArgumentException("A measured media sample is required");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("track_id", sample.trackId());
        fields.put("kind", sample.kind().name());
        fields.put("interval_ms", sample.intervalMillis());
        put(fields, "packets_sent", sample.packetsSent());
        put(fields, "packets_received", sample.packetsReceived());
        put(fields, "packets_lost", sample.packetsLost());
        put(fields, "bytes_sent", sample.bytesSent());
        put(fields, "bytes_received", sample.bytesReceived());
        put(fields, "rtt_ms", sample.rttMillis());
        put(fields, "jitter_ms", sample.jitterMillis());
        put(fields, "frames_encoded", sample.framesEncoded());
        put(fields, "frames_decoded", sample.framesDecoded());
        put(fields, "frames_dropped", sample.framesDropped());
        if (sample.bytesSent() != null) {
            fields.put("send_bitrate_bps", sample.bytesSent() * 8000.0 / sample.intervalMillis());
        }
        if (sample.bytesReceived() != null) {
            fields.put("receive_bitrate_bps", sample.bytesReceived() * 8000.0 / sample.intervalMillis());
        }
        if (sample.framesEncoded() != null) {
            fields.put("encode_fps", sample.framesEncoded() * 1000.0 / sample.intervalMillis());
        }
        if (sample.framesDecoded() != null) {
            fields.put("decode_fps", sample.framesDecoded() * 1000.0 / sample.intervalMillis());
        }
        if (sample.packetsReceived() != null && sample.packetsLost() != null) {
            double total = (double) sample.packetsReceived() + sample.packetsLost();
            if (total > 0) {
                fields.put("receive_loss_pct", sample.packetsLost() * 100.0 / total);
            }
        }
        event("media.sample", fields);
    }

    private void enqueue(Map<String, Object> record) {
        synchronized (admission) {
            if (!isActive()) {
                return;
            }
            if (truncated) {
                truncatedEvents.incrementAndGet();
            } else if (!queue.offer(record)) {
                dropped.incrementAndGet();
            }
        }
    }

    private Map<String, Object> record(String name, Map<String, ?> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "mmr.diagnostics.v1");
        result.put("time_utc", Instant.now().toString());
        result.put("run_id", runId);
        result.put("pid", pid);
        result.put("role", role);
        result.put("elapsed_ms", (System.nanoTime() - startedNanos) / 1_000_000.0);
        result.put("event", name);
        result.put("fields", fields);
        return result;
    }

    private void writeLoop() {
        long lastFlush = System.nanoTime();
        try {
            while (!closed.get() || !queue.isEmpty()) {
                Map<String, Object> record = queue.poll(200, TimeUnit.MILLISECONDS);
                if (record != null) {
                    if (truncated) {
                        truncatedEvents.incrementAndGet();
                    } else {
                        writeBounded(record);
                    }
                }
                if (System.nanoTime() - lastFlush >= 1_000_000_000L) {
                    writer.flush();
                    lastFlush = System.nanoTime();
                }
            }
            writeLine(record("run.end", Map.of("clean_shutdown", true,
                    "dropped_events", dropped.get(), "truncated_events", truncatedEvents.get(),
                    "recorded_events", recordsWritten, "file_truncated", truncated)));
            writer.flush();
        } catch (IOException | RuntimeException failure) {
            ioFailed = true;
            queue.clear();
            sampler.shutdownNow();
            if (noticePrinted.compareAndSet(false, true)) {
                System.err.println("Diagnostics disabled: output write failed ("
                        + failure.getClass().getSimpleName() + ").");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Diagnostics must not affect application shutdown.
            }
        }
    }

    private void writeBounded(Map<String, Object> record) throws IOException {
        String line = line(record);
        long size = line.getBytes(StandardCharsets.UTF_8).length;
        if (bytesWritten + size > maxBytes - FOOTER_RESERVE) {
            truncated = true;
            truncatedEvents.incrementAndGet();
            writeLine(record("log.truncated", Map.of("reason", "file_size_limit",
                    "max_file_bytes", maxBytes)));
        } else {
            writer.write(line);
            bytesWritten += size;
            recordsWritten++;
        }
    }

    private void writeLine(Map<String, Object> record) throws IOException {
        String line = line(record);
        long size = line.getBytes(StandardCharsets.UTF_8).length;
        if (bytesWritten + size <= maxBytes) {
            writer.write(line);
            bytesWritten += size;
            recordsWritten++;
        }
    }

    private String line(Map<String, Object> record) {
        record.put("seq", ++sequence);
        record.put("dropped_events", dropped.get());
        return JSON.toJson(record) + "\n";
    }

    private void primeRuntimeCounters() {
        lastSampleNanos = System.nanoTime();
        long[] gc = gcTotals();
        lastGcCount = gc[0];
        lastGcMillis = gc[1];
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            lastProcessCpuNanos = os.getProcessCpuTime();
        }
    }

    private void sampleRuntime() {
        if (!isActive()) {
            return;
        }
        try {
            long now = System.nanoTime();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("interval_ms", (now - lastSampleNanos) / 1_000_000.0);
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            fields.put("heap_used_bytes", heap.getUsed());
            fields.put("heap_committed_bytes", heap.getCommitted());
            fields.put("thread_count", ManagementFactory.getThreadMXBean().getThreadCount());
            long[] gc = gcTotals();
            long gcCount = gc[0];
            long gcMillis = gc[1];
            if (gcCount >= 0 && lastGcCount >= 0) {
                fields.put("gc_collections_delta", Math.max(0, gcCount - lastGcCount));
            }
            if (gcMillis >= 0 && lastGcMillis >= 0) {
                fields.put("gc_time_ms_delta", Math.max(0, gcMillis - lastGcMillis));
            }
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean os) {
                long processCpu = os.getProcessCpuTime();
                if (lastProcessCpuNanos >= 0 && processCpu >= lastProcessCpuNanos
                        && now > lastSampleNanos) {
                    double pct = 100.0 * (processCpu - lastProcessCpuNanos)
                            / (now - lastSampleNanos) / Runtime.getRuntime().availableProcessors();
                    fields.put("process_cpu_pct", Math.min(100.0, Math.max(0, pct)));
                }
                double systemLoad = os.getCpuLoad();
                if (Double.isFinite(systemLoad) && systemLoad >= 0 && systemLoad <= 1) {
                    fields.put("system_cpu_pct", systemLoad * 100);
                }
                lastProcessCpuNanos = processCpu;
            }
            lastSampleNanos = now;
            lastGcCount = gcCount;
            lastGcMillis = gcMillis;
            event("runtime.sample", fields);
        } catch (RuntimeException failure) {
            failure("runtime.sample_error", failure, Map.of());
        }
    }

    @Override
    public void close() {
        synchronized (admission) {
            if (!closed.compareAndSet(false, true) || writerThread == null) {
                return;
            }
        }
        sampler.shutdownNow();
        if (Thread.currentThread() != writerThread) {
            try {
                writerThread.join(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (current == this) {
            current = DISABLED;
        }
    }

    private boolean isActive() {
        return queue != null && !closed.get() && !ioFailed;
    }

    private static Map<String, Object> safeFields(Map<String, ?> fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (fields == null) {
            return result;
        }
        int examined = 0;
        for (var entry : fields.entrySet()) {
            if (++examined > 32) {
                break;
            }
            String key = entry.getKey();
            if (key == null || !key.matches("[a-z][a-z0-9_]{0,63}") || sensitive(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                result.put(key, value);
            } else if (value instanceof Float number && Float.isFinite(number)) {
                result.put(key, number);
            } else if (value instanceof Double number && Double.isFinite(number)) {
                result.put(key, number);
            } else if (value instanceof String tag && STRING_FIELDS.contains(key)
                    && tag.matches("[A-Za-z0-9_.:-]{1,96}")) {
                result.put(key, tag);
            }
        }
        return result;
    }

    private static boolean sensitive(String key) {
        return key.contains("password") || key.contains("secret") || key.contains("token") || key.contains("otp") || key.contains("challenge")
                || key.contains("credential") || key.contains("email") || key.contains("username")
                || key.contains("user_id") || key.contains("session_id") || key.contains("address")
                || key.contains("host") || key.contains("path") || key.contains("message")
                || key.contains("stack") || key.contains("payload") || key.contains("content");
    }

    private static void put(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    private static long[] gcTotals() {
        var beans = ManagementFactory.getGarbageCollectorMXBeans();
        long count = beans.isEmpty() ? -1 : 0;
        long millis = count;
        for (GarbageCollectorMXBean gc : beans) {
            long beanCount = gc.getCollectionCount();
            long beanMillis = gc.getCollectionTime();
            count = beanCount < 0 || count < 0 ? -1 : count + beanCount;
            millis = beanMillis < 0 || millis < 0 ? -1 : millis + beanMillis;
        }
        return new long[]{count, millis};
    }

    private static String safeTag(String value, String fallback) {
        return value != null && value.matches("[A-Za-z0-9_.:-]{1,96}") ? value : fallback;
    }

    private static String setting(String property, String environment, String fallback) {
        String result = System.getProperty("mmr.diagnostics." + property);
        if (result == null || result.isBlank()) {
            result = System.getenv("MMR_DIAGNOSTICS_" + environment);
        }
        return result == null || result.isBlank() ? fallback : result.trim();
    }

    private static long integerSetting(String property, String environment, long fallback,
            long min, long max) {
        try {
            long result = Long.parseLong(setting(property, environment, Long.toString(fallback)));
            return result >= min && result <= max ? result : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
