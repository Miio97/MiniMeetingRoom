package common.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsTest {
    @TempDir
    Path directory;
    private final Map<String, String> previous = new HashMap<>();

    @BeforeEach
    void configureIsolatedOutput() {
        Diagnostics.current().close();
        set("enabled", "true");
        set("dir", directory.toString());
        set("runId", "test-run");
        set("sampleSeconds", "1");
        set("maxBytes", "20971520");
        set("queueCapacity", "1024");
    }

    @AfterEach
    void restoreConfiguration() {
        Diagnostics.current().close();
        previous.forEach((key, value) -> {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
        });
    }

    @Test
    void drainsEventsAndEndsCleanlyWithoutChangingRepeatedClose() throws Exception {
        Diagnostics diagnostics = Diagnostics.start("client");
        assertSame(diagnostics, Diagnostics.current());
        assertSame(diagnostics, Diagnostics.start("client"));
        diagnostics.event("client_ready", Map.of("media_status", "not_implemented"));
        for (int i = 0; i < 100; i++) {
            diagnostics.event("auth_client_request", Map.of("action", "LOGIN",
                    "response_code", "INVALID_CREDENTIALS", "total_ms", i * 0.5));
        }
        diagnostics.close();
        diagnostics.close();
        List<JsonObject> records = records(diagnostics.logPath());
        assertEquals("run.start", records.getFirst().get("event").getAsString());
        assertEquals("run.end", records.getLast().get("event").getAsString());
        assertEquals(100, records.stream().filter(record ->
                "auth_client_request".equals(record.get("event").getAsString())).count());
        long previousSequence = 0;
        for (JsonObject record : records) {
            assertEquals("mmr.diagnostics.v1", record.get("schema").getAsString());
            assertEquals("test-run", record.get("run_id").getAsString());
            assertTrue(record.get("time_utc").getAsString().endsWith("Z"));
            assertTrue(record.get("seq").getAsLong() > previousSequence);
            previousSequence = record.get("seq").getAsLong();
        }
        assertTrue(records.getLast().getAsJsonObject("fields").get("clean_shutdown").getAsBoolean());
        assertEquals(0, records.getLast().get("dropped_events").getAsLong());
        assertNull(Diagnostics.current().logPath());
    }

    @Test
    void omitsSecretsFreeTextAndObjectSerializationButKeepsUsefulFailureClasses() throws Exception {
        Diagnostics diagnostics = Diagnostics.start("client");
        AtomicBoolean serialized = new AtomicBoolean();
        Map<String, Object> fields = new HashMap<>();
        fields.put("password", "private-password");
        fields.put("token", "private-token");
        fields.put("username", "private-user");
        fields.put("email", "private@example.com");
        fields.put("message", "private-server-message");
        fields.put("phase", "tls_handshake");
        fields.put("outcome", "error");
        fields.put("total_ms", 12.5);
        fields.put("nan_value", Double.NaN);
        fields.put("infinite_value", Double.POSITIVE_INFINITY);
        fields.put("payload", new Object() {
            @Override
            public String toString() {
                serialized.set(true);
                return "private-payload";
            }
        });
        diagnostics.failure("auth_client_request", new IllegalStateException("private-error",
                new SQLException("private-database-url", "28000")), fields);
        diagnostics.close();
        String text = Files.readString(diagnostics.logPath(), StandardCharsets.UTF_8);
        assertFalse(text.contains("private-"));
        assertFalse(text.contains("private@example.com"));
        assertFalse(text.contains("NaN"));
        assertFalse(text.contains("Infinity"));
        assertFalse(serialized.get());
        JsonObject error = records(diagnostics.logPath()).stream().filter(record ->
                "auth_client_request".equals(record.get("event").getAsString())).findFirst().orElseThrow();
        JsonObject recorded = error.getAsJsonObject("fields");
        assertEquals("tls_handshake", recorded.get("phase").getAsString());
        assertEquals(12.5, recorded.get("total_ms").getAsDouble());
        assertEquals("28000", recorded.get("sql_state").getAsString());
        assertEquals("java.lang.IllegalStateException", recorded.get("error_type").getAsString());
        assertEquals("java.sql.SQLException", recorded.get("cause_type").getAsString());
        assertEquals(2, recorded.getAsJsonArray("error_chain").size());
    }

    @Test
    void recordsMeasuredRuntimeWindowWithDefinedCpuRange() throws Exception {
        Diagnostics diagnostics = Diagnostics.start("server");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean sampleObserved = false;
        while (System.nanoTime() < deadline) {
            if (Files.readString(diagnostics.logPath(), StandardCharsets.UTF_8)
                    .contains("\"event\":\"runtime.sample\"")) {
                sampleObserved = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(sampleObserved, "A real runtime sample must be flushed within five seconds");
        diagnostics.close();
        JsonObject sample = records(diagnostics.logPath()).stream().filter(record ->
                "runtime.sample".equals(record.get("event").getAsString())).findFirst().orElseThrow();
        JsonObject fields = sample.getAsJsonObject("fields");
        assertTrue(fields.get("interval_ms").getAsDouble() >= 900);
        assertTrue(fields.get("heap_used_bytes").getAsLong() >= 0);
        assertTrue(fields.get("thread_count").getAsInt() > 0);
        for (String key : List.of("process_cpu_pct", "system_cpu_pct")) {
            if (fields.has(key)) {
                assertTrue(fields.get(key).getAsDouble() >= 0);
                assertTrue(fields.get(key).getAsDouble() <= 100);
            }
        }
    }

    @Test
    void marksBoundedFileAndPreservesFooterDuringConcurrentBurst() throws Exception {
        set("maxBytes", "8192");
        set("queueCapacity", "8");
        Diagnostics diagnostics = Diagnostics.start("server");
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
            for (int worker = 0; worker < 8; worker++) {
                workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < 1000; i++) {
                        diagnostics.event("burst", Map.of("completed_total", i,
                                "action", "LOGIN", "total_ms", i * 0.1));
                    }
                    return null;
                });
            }
            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();
        }
        diagnostics.close();
        assertTrue(Files.size(diagnostics.logPath()) <= 8192);
        List<JsonObject> records = records(diagnostics.logPath());
        assertTrue(records.stream().anyMatch(record ->
                "log.truncated".equals(record.get("event").getAsString())));
        JsonObject footer = records.getLast();
        assertEquals("run.end", footer.get("event").getAsString());
        assertTrue(footer.getAsJsonObject("fields").get("file_truncated").getAsBoolean());
        assertTrue(footer.getAsJsonObject("fields").get("truncated_events").getAsLong() > 0);
        assertTrue(footer.get("dropped_events").getAsLong() > 0);
    }

    @Test
    void mediaWindowsDeriveRatesWithoutInventingUnavailableStats() throws Exception {
        Diagnostics diagnostics = Diagnostics.start("server");
        diagnostics.mediaSample(new MediaSample(1, MediaSample.Kind.VIDEO, 2000,
                null, 90L, 10L, 1000L, null, 5.5, null, 25L, null, null));
        diagnostics.close();
        JsonObject fields = records(diagnostics.logPath()).stream().filter(record ->
                "media.sample".equals(record.get("event").getAsString()))
                .findFirst().orElseThrow().getAsJsonObject("fields");
        assertEquals(4000, fields.get("send_bitrate_bps").getAsDouble());
        assertEquals(12.5, fields.get("encode_fps").getAsDouble());
        assertEquals(10, fields.get("receive_loss_pct").getAsDouble());
        assertEquals(5.5, fields.get("rtt_ms").getAsDouble());
        assertFalse(fields.has("receive_bitrate_bps"));
        assertFalse(fields.has("decode_fps"));
        assertFalse(fields.has("jitter_ms"));
    }

    @Test
    void rejectsInvalidMediaCountersAndNonfiniteMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> new MediaSample(0,
                MediaSample.Kind.AUDIO, 0, null, null, null, null, null,
                null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaSample(0,
                MediaSample.Kind.AUDIO, 1000, -1L, null, null, null, null,
                null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaSample(0,
                MediaSample.Kind.AUDIO, 1000, null, null, null, null, null,
                Double.NaN, null, null, null, null));
    }

    @Test
    void disabledAndUnwritableOutputDoNotInterfereWithApplication() throws Exception {
        set("enabled", "false");
        Diagnostics disabled = Diagnostics.start("client");
        assertNull(disabled.logPath());
        assertDoesNotThrow(() -> disabled.event("event", Map.of()));
        assertDoesNotThrow(disabled::close);
        set("enabled", "true");
        Path regularFile = Files.writeString(directory.resolve("not-a-directory"), "test");
        set("dir", regularFile.toString());
        assertNull(Diagnostics.start("client").logPath());
    }

    @Test
    void invalidNumericConfigurationFallsBackAndFilenameRoleCannotContainColon() throws Exception {
        set("maxBytes", "invalid");
        set("sampleSeconds", "0");
        set("queueCapacity", "-1");
        Diagnostics diagnostics = Diagnostics.start("client:test");
        assertNotNull(diagnostics.logPath());
        assertTrue(diagnostics.logPath().getFileName().toString().startsWith("app-"));
        diagnostics.close();
        JsonObject fields = records(diagnostics.logPath()).getFirst().getAsJsonObject("fields");
        assertEquals(20L * 1024 * 1024, fields.get("max_file_bytes").getAsLong());
        assertEquals(5000, fields.get("sample_interval_ms").getAsLong());
        assertEquals(1024, fields.get("queue_capacity").getAsLong());
    }

    private void set(String name, String value) {
        String key = "mmr.diagnostics." + name;
        if (!previous.containsKey(key)) {
            previous.put(key, System.getProperty(key));
        }
        System.setProperty(key, value);
    }

    private static List<JsonObject> records(Path path) throws Exception {
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(line -> JsonParser.parseString(line).getAsJsonObject()).toList();
    }
}
