package common.diagnostics;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticsReportTest {
    private static final Gson JSON = new Gson();
    @TempDir Path directory;
    @Test void systemAudioCountersAndActionsStaySeparateFromMicrophone() throws Exception {
        Path input=write("system-audio.txt",List.of(
                record("sound",22,"sfu","video.engine.sample",1,0,Map.of("kind","AUDIO","ingress_packets",3)),
                record("sound",22,"sfu","video.engine.sample",2,0,Map.of("kind","SCREEN_AUDIO","ingress_packets",9)),
                record("sound",22,"client","video.client.operation.failed",3,0,Map.of("kind","SCREEN_AUDIO","action","SCREEN_AUDIO_PUBLISH","phase","audio","total_ms",4,"device","PRIVATE_DEVICE"))));
        String report=DiagnosticsReport.createReport(input,null);
        assertTrue(report.contains("kind=AUDIO name=ingress_packets n=1 min=3.000"));
        assertTrue(report.contains("kind=SCREEN_AUDIO name=ingress_packets n=1 min=9.000"));
        assertTrue(report.contains("action=SCREEN_AUDIO_PUBLISH"));assertFalse(report.contains("PRIVATE_DEVICE"));
    }

    @Test void screenUnsubscribeActionSurvivesReportWithoutCaptureMetadata() throws Exception {
        Path input=write("screen-cleanup.txt",List.of(record("screen",22,"client","video.client.operation.failed",1,0,
                Map.of("action","SCREEN_UNSUBSCRIBE","kind","SCREEN","phase","subscribe","total_ms",2,
                        "window_title","PRIVATE_WINDOW_TITLE","sdp","PRIVATE_SDP"))));
        String report=DiagnosticsReport.createReport(input,null);
        assertTrue(report.contains("action=SCREEN_UNSUBSCRIBE"));
        assertFalse(report.contains("PRIVATE_WINDOW_TITLE"));assertFalse(report.contains("PRIVATE_SDP"));
    }

    @Test void audioAndScreenCountersAreSeparatedAndUnknownValuesStayAbsent() throws Exception {
        Path input=write("kinds.txt",List.of(
            record("kinds",22,"sfu","video.engine.sample",1,0,Map.of("kind","AUDIO","ingress_packets",100,"queue_drops",2,"pli_requests",0)),
            record("kinds",22,"sfu","video.engine.sample",2,0,Map.of("kind","SCREEN","ingress_packets",15,"queue_drops",1,"pli_requests",1)),
            record("kinds",22,"sfu","video.client.operation.failed",3,0,Map.of("kind","AUDIO","phase","audio","total_ms",4)),
            record("kinds",22,"sfu","video.client.operation.failed",4,0,Map.of("kind","SCREEN","phase","screen","total_ms",5))));
        String report=DiagnosticsReport.createReport(input,null);
        assertTrue(report.contains("kind=AUDIO name=ingress_packets n=1 min=100.000"));
        assertTrue(report.contains("kind=SCREEN name=ingress_packets n=1 min=15.000"));
        assertTrue(report.contains("phase=audio"));assertTrue(report.contains("phase=screen"));
        assertFalse(report.contains("name=jitter_ms"));assertFalse(report.contains("other.event"));
    }
    @Test
    void separatesProcessesFilesAndTracksAndUsesRawNearestRankPercentiles() throws Exception {
        List<String> client = new ArrayList<>();
        client.add(record("shared", 1, "client", "run.start", 0, 0, Map.of("sample_interval_ms", 5000)));
        for (int i = 1; i <= 100; i++) {
            client.add(record("shared", 1, "client", "auth_client_request", i, 2,
                    Map.of("action", "LOGIN", "total_ms", i, "total_ms_p95", 999999)));
        }
        client.add(record("shared", 1, "client", "run.end", 110, 3,
                Map.of("clean_shutdown", true, "dropped_events", 3, "truncated_events", 7, "file_truncated", true)));
        write("a.txt", client);
        write("b.txt", List.of(record("shared", 2, "server", "media.sample", 1000, 0,
                Map.of("track_id", 1, "kind", "VIDEO", "rtt_ms", 400, "frames_encoded", 2)),
                record("shared", 2, "server", "media.sample", 2000, 0,
                Map.of("track_id", 2, "kind", "VIDEO", "rtt_ms", 900, "frames_encoded", 3))));
        // Same pid/run/role in another file still belongs to a different process group.
        write("c.txt", List.of(record("shared", 1, "client", "auth_client_request", 1, 0,
                Map.of("total_ms", 8000))));
        String report = DiagnosticsReport.createReport(directory, "shared");
        assertTrue(report.contains("process_groups=3"));
        assertTrue(report.contains("invalid_lines=0"));
        assertTrue(report.contains("pid=1 role=client"));
        assertTrue(report.contains("name=total_ms n=100 min=1.000 max=100.000 mean=50.500 p50=50.000 p95=95.000 p99=99.000"));
        assertTrue(report.contains("track=1 kind=VIDEO name=rtt_ms n=1 min=400.000"));
        assertTrue(report.contains("track=2 kind=VIDEO name=rtt_ms n=1 min=900.000"));
        assertTrue(report.contains("dropped_events_max=3 truncated_events_max=7 file_truncated=true"));
        assertTrue(report.contains("missing_run_end=true clean_shutdown=unavailable"));
        assertFalse(report.contains("total_ms_p95"));
        assertFalse(report.contains("999999"));
        assertFalse(report.contains("a.txt"));
    }

    @Test
    void filtersRunsAndRedactsFreeTextErrorDetailsAndUnsupportedMetrics() throws Exception {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("error_type", "private-secret-exception");
        fields.put("message", "private-password-and-token");
        fields.put("username", "private-username");
        fields.put("sdp", "private-sdp-content");
        fields.put("pin", 765432);
        fields.put("p95_ms", 888888);
        fields.put("total_ms", 12.5);
        write("private-source.txt", List.of(record("selected", 10, "client", "private-event-secret", 10, 0, fields),
                record("other", 20, "server", "run.start", 0, 0, Map.of())));
        String report = DiagnosticsReport.createReport(directory, "selected");
        assertTrue(report.contains("filtered_records=1"));
        assertTrue(report.contains("failures=1"));
        assertTrue(report.contains("event=other.event#1"));
        assertTrue(report.contains("p50=12.500 p95=12.500 p99=12.500"));
        assertFalse(report.contains("private-"));
        assertFalse(report.contains("765432"));
        assertFalse(report.contains("888888"));
        assertFalse(report.contains("selected"));
    }

    @Test
    void reportsProbeEvidenceWithoutInventingUnknownCounters() throws Exception {
        Path input = write("probe.txt", List.of(record("probe", 25, "client-media-probe", "run.start", 0, 0, Map.of()),
                record("probe", 25, "client-media-probe", "media.probe.native_loaded", 1, 0, Map.of()),
                record("probe", 25, "client-media-probe", "media.probe.sample", 1000, 0,
                        Map.of("peer_setup_ms", 12, "video_frames_cumulative", 30, "rtp_packets_cumulative", 100)),
                record("probe", 25, "client-media-probe", "run.end", 1100, 0, Map.of("clean_shutdown", true))));
        String report = DiagnosticsReport.createReport(input, null);
        assertTrue(report.contains("invalid_lines=0"));
        assertTrue(report.contains("role=client-media-probe"));
        assertTrue(report.contains("Event: name=media.probe.native_loaded n=1"));
        assertTrue(report.contains("name=peer_setup_ms n=1"));
        assertTrue(report.contains("name=video_frames_cumulative n=1 min=30.000"));
        assertTrue(report.contains("name=rtp_packets_cumulative n=1 min=100.000"));
        assertFalse(report.contains("name=audio_chunks_cumulative"));
    }

    @Test
    void keepsSendReceiveAndResetProbeCyclesSeparate() throws Exception {
        Path input = write("probe.txt", List.of(
                record("probe", 25, "client-media-probe", "media.probe.stats", 1, 0,
                        Map.of("cycle", 1, "phase", "send", "rtp_packets_cumulative", 100)),
                record("probe", 25, "client-media-probe", "media.probe.stats", 2, 0,
                        Map.of("cycle", 1, "phase", "receive", "rtp_packets_cumulative", 90)),
                record("probe", 25, "client-media-probe", "media.probe.stats", 3, 0,
                        Map.of("cycle", 2, "phase", "send", "rtp_packets_cumulative", 5))));
        String report = DiagnosticsReport.createReport(input, null);
        assertTrue(report.contains("phase=send cycle=1 track=unavailable kind=unavailable name=rtp_packets_cumulative n=1 min=100.000"));
        assertTrue(report.contains("phase=receive cycle=1 track=unavailable kind=unavailable name=rtp_packets_cumulative n=1 min=90.000"));
        assertTrue(report.contains("phase=send cycle=2 track=unavailable kind=unavailable name=rtp_packets_cumulative n=1 min=5.000"));
    }

    @Test
    void keepsExternalSfuAudioVideoAndCyclesSeparateWithoutExposingPayloadHashes() throws Exception {
        Path input = write("sfu.txt", List.of(
                record("probe", 25, "client-media-probe", "sfu.probe.stats", 1, 0,
                        Map.of("cycle", 1, "kind", "AUDIO", "ingress_packets", 100, "forwarded_codec_bytes", 400,
                                "ingress_payload_sha256", "private-payload-hash")),
                record("probe", 25, "client-media-probe", "sfu.probe.stats", 2, 0,
                        Map.of("cycle", 1, "kind", "VIDEO", "ingress_packets", 60)),
                record("probe", 25, "client-media-probe", "sfu.probe.stats", 3, 0,
                        Map.of("cycle", 2, "kind", "AUDIO", "ingress_packets", 10)),
                record("probe", 25, "client-media-probe", "media.probe.stats", 4, 0,
                        Map.of("cycle", 1, "phase", "send", "rtp_packets_cumulative", 999))));
        String report = DiagnosticsReport.createReport(input, null);
        assertTrue(report.contains("event=sfu.probe.stats action=unavailable phase=unavailable cycle=1 track=unavailable kind=AUDIO name=ingress_packets n=1 min=100.000"));
        assertTrue(report.contains("cycle=1 track=unavailable kind=VIDEO name=ingress_packets n=1 min=60.000"));
        assertTrue(report.contains("cycle=2 track=unavailable kind=AUDIO name=ingress_packets n=1 min=10.000"));
        assertTrue(report.contains("name=forwarded_codec_bytes n=1 min=400.000"));
        assertFalse(report.contains("private-payload-hash"));
        assertFalse(report.contains("ingress_payload_sha256"));
    }

    @Test
    void countsMalformedOversizedAndDeeplyNestedLinesWithoutEchoingThem() throws Exception {
        String normal = record("safe", 1, "app", "runtime.sample", 1000, 0,
                Map.of("heap_used_bytes", 1234, "interval_ms", 1000));
        Path input = directory.resolve("source.txt");
        String deep = "[".repeat(40) + "0" + "]".repeat(40);
        Files.writeString(input, "malformed private-password\n" + "x".repeat(1025) + "\n" + deep + "\n" + normal,
                StandardCharsets.UTF_8);
        String report = DiagnosticsReport.createReport(input, null, new DiagnosticsReport.Limits(1, 10000, 1024, 10));
        assertTrue(report.contains("invalid_lines=2 oversized_lines=1"));
        assertTrue(report.contains("runtime_samples=1"));
        assertTrue(report.contains("name=heap_used_bytes n=1 min=1234.000"));
        assertFalse(report.contains("private-password"));
        assertFalse(report.contains("p50="));
    }

    @Test
    void reportsBoundedInputAndNeverPresentsIncompletePercentiles() throws Exception {
        List<String> records = new ArrayList<>();
        for (int i = 1; i <= 3; i++) records.add(record("safe", 1, "client", "auth_client_request", i, 0, Map.of("total_ms", i)));
        Path input = write("a.txt", records);
        write("b.txt", records);
        String sampleLimited = DiagnosticsReport.createReport(input, null, new DiagnosticsReport.Limits(2, 10000, 1024, 2));
        assertTrue(sampleLimited.contains("name=total_ms n=3"));
        assertTrue(sampleLimited.contains("p50/p95/p99=unavailable(sample_limit)"));
        String fileLimited = DiagnosticsReport.createReport(directory, null, new DiagnosticsReport.Limits(1, 10000, 1024, 10));
        assertTrue(fileLimited.contains("file_limit_hit=true"));
        String byteLimited = DiagnosticsReport.createReport(input, null, new DiagnosticsReport.Limits(1, 10, 1024, 10));
        assertTrue(byteLimited.contains("bytes_read=10"));
        assertTrue(byteLimited.contains("byte_limit_hit=true"));
        assertTrue(byteLimited.contains("No matching valid records"));
    }

    @Test
    void rejectsNonfiniteNegativeAndStringMeasurementsAndProtectsSourceFiles() throws Exception {
        String record = record("safe", 1, "server", "media.sample", 100, 0,
                Map.of("track_id", 1, "kind", "AUDIO", "rtt_ms", -2, "jitter_ms", "999", "bytes_sent", 100,
                        "packets_lost", -1));
        // Gson's parser can represent this nonfinite exponent; the reader must omit it.
        record = record.replace("\"rtt_ms\":-2", "\"rtt_ms\":1e999");
        Path input = write("source.txt", List.of(record));
        String report = DiagnosticsReport.createReport(input, null);
        assertTrue(report.contains("name=bytes_sent n=1"));
        assertFalse(report.contains("name=rtt_ms"));
        assertFalse(report.contains("name=jitter_ms"));
        assertFalse(report.contains("name=packets_lost"));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticsReport.writeReport(input, null, input));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticsReport.writeReport(directory, null, directory.resolve("report.txt")));
        Path output = directory.resolve("reports").resolve("report.txt");
        DiagnosticsReport.writeReport(input, null, output);
        assertTrue(Files.readString(output).startsWith("MiniMeetingRoom diagnostics report"));
        assertThrows(IllegalArgumentException.class, () -> DiagnosticsReport.createReport(input, "contains spaces"));
    }

    private Path write(String filename, List<String> records) throws Exception {
        Path source = directory.resolve(filename);
        Files.writeString(source, String.join("\n", records) + "\n", StandardCharsets.UTF_8);
        return source;
    }

    private static String record(String run, long pid, String role, String event, double elapsed,
            long dropped, Map<String, ?> fields) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schema", "mmr.diagnostics.v1");
        record.put("run_id", run);
        record.put("pid", pid);
        record.put("role", role);
        record.put("elapsed_ms", elapsed);
        record.put("event", event);
        record.put("dropped_events", dropped);
        record.put("fields", fields);
        return JSON.toJson(record);
    }
}
