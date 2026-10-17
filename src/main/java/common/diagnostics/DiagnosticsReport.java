package common.diagnostics;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline, bounded reader for mmr.diagnostics.v1; never emits arbitrary log text. */
public final class DiagnosticsReport {
    private static final Set<String> LATENCIES = Set.of("total_ms", "response_ms", "handler_ms",
            "phase_elapsed_ms", "queue_ms", "tls_context_ms", "tcp_connect_ms", "tls_handshake_ms",
            "database_ms", "startup_ms", "native_load_ms", "signaling_round_trip_ms", "join_ms",
            "ice_ms", "dtls_ms", "first_packet_ms", "first_frame_ms", "first_audio_ms",
            "forward_ms", "forward_wait_ms", "queue_age_ms", "rtt_ms", "jitter_ms", "shutdown_ms",
            "peer_setup_ms", "cycle_ms");
    private static final Set<String> MEASUREMENTS = Set.of("interval_ms", "heap_used_bytes",
            "heap_committed_bytes", "thread_count", "process_cpu_pct", "system_cpu_pct",
            "gc_collections_delta", "gc_time_ms_delta", "packets_sent", "packets_received",
            "packets_lost", "bytes_sent", "bytes_received", "frames_encoded", "frames_decoded",
            "frames_dropped", "send_bitrate_bps", "receive_bitrate_bps", "receive_loss_pct",
            "encode_fps", "decode_fps", "render_fps", "queue_depth", "queue_drops", "nack_count",
            "pli_count", "retransmissions", "rss_bytes", "native_memory_bytes",
            "video_frames_cumulative", "audio_chunks_cumulative", "audio_samples_cumulative",
            "rtp_packets_cumulative", "video_bytes_cumulative", "audio_bytes_cumulative",
            "decoded_video_frames_cumulative", "decoded_audio_chunks_cumulative",
            "video_encoded_frames_cumulative", "audio_encoded_frames_cumulative",
            "input_duration_ms", "late_media_callbacks", "active_peer_connections",
            "ingress_packets", "ingress_payload_bytes", "forwarded_packets", "forwarded_payload_bytes",
            "queue_packets", "queue_bytes", "expired_drops", "write_errors", "read_errors", "pli_requests",
            "ingress_codec_bytes", "forwarded_codec_bytes", "room_count", "participant_count",
            "render_frames_cumulative", "render_dropped_frames_cumulative", "go_heap_bytes", "goroutine_count");
    private static final Set<String> EVENTS = Set.of("run.start", "run.end", "log.truncated",
            "runtime.sample", "runtime.sample_error", "media.sample", "client_ready",
            "auth_client_request", "server.started", "server.stopped", "server.connection.accepted",
            "server.connection.completed", "server.connection.rejected", "server.connection.failed",
            "server.request.failed", "server.startup.database_checked", "server.startup.tls_loaded",
            "server.startup.completed", "server.startup.failed", "server.shutdown.failed",
            "media.probe.started", "media.probe.native_loaded", "media.probe.connected",
            "media.probe.sample", "media.probe.completed", "media.probe.failed",
            "media.probe.cleanup", "media.probe.closed", "media.probe.native", "media.probe.start",
            "media.probe.received", "media.probe.stats", "sfu.probe.stats", "sfu.probe.connected",
            "sfu.probe.closed", "sfu.probe.failed", "meeting.state", "signaling.request.completed",
            "signaling.server.started", "signaling.server.failed", "signaling.session.revoked",
            "signaling.client.connected", "signaling.client.request", "signaling.client.failed", "signaling.connection.failed", "signaling.connection.closed",
            "signaling.request.failed", "signaling.cleanup.failed", "meeting.startup.failed", "video.engine.sample",
            "video.integration.received", "video.integration.completed", "video.client.closed", "video.client.close_failed",
            "video.revoked", "video.stop.failed", "video.revoke.failed", "video.client.operation.failed");
    private static final Set<String> PHASES = Set.of("send", "receive", "load_settings", "tls_context",
            "tcp_connect", "tls_handshake", "response", "request_read", "request_handler",
            "response_write", "connection_close", "configuration", "database_preflight",
            "listener_bind", "listener_run", "create", "connect", "generated_media",
            "receive_threshold", "stats", "close", "control", "dispatch", "accept", "startup", "capture", "preview", "publish", "subscribe", "camera_formats", "audio", "screen");
    private static final Set<String> ROLES = Set.of("server", "client", "app", "sfu", "signaling",
            "media-smoke", "media-probe", "client-media-probe");
    static final Limits DEFAULT_LIMITS = new Limits(128, 256L * 1024 * 1024, 64 * 1024, 200_000);

    private DiagnosticsReport() { }

    /** Run IDs and filenames are replaced by local aliases in the shareable report. */
    public static String createReport(Path input, String runId) throws IOException {
        return createReport(input, runId, DEFAULT_LIMITS);
    }

    static String createReport(Path input, String runId, Limits limits) throws IOException {
        if (runId != null && !runId.matches("[A-Za-z0-9_.:-]{1,96}")) {
            throw new IllegalArgumentException("Invalid technical run ID");
        }
        State state = new State(limits);
        List<Path> sources = new ArrayList<>();
        if (Files.isDirectory(input, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.newDirectoryStream(input, "*.txt")) {
                for (Path source : entries) {
                    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (sources.size() == limits.maxFiles()) { state.fileLimit = true; break; }
                    sources.add(source);
                }
            }
            sources.sort(Comparator.comparing(path -> path.getFileName().toString()));
        } else if (Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) {
            sources.add(input);
        } else {
            throw new IOException("Diagnostics input is not a regular file or directory");
        }
        for (int index = 0; index < sources.size(); index++) {
            if (state.bytes >= limits.maxBytes()) { state.byteLimit = true; break; }
            try { state.read(sources.get(index), index + 1, runId); }
            catch (IOException failure) { state.unreadableFiles++; }
        }
        return state.render();
    }

    public static void writeReport(Path input, String runId, Path output) throws IOException {
        Path source = input.toAbsolutePath().normalize();
        Path destination = output.toAbsolutePath().normalize();
        if (source.equals(destination) || Files.isDirectory(source) && destination.getParent().equals(source)) {
            throw new IllegalArgumentException("Save the report outside the input logs");
        }
        String report = createReport(source, runId);
        if (destination.getParent() != null) Files.createDirectories(destination.getParent());
        Files.writeString(destination, report, StandardCharsets.UTF_8);
    }

    /** Properties avoid shell re-parsing of paths; the PowerShell wrapper sets these. */
    public static void main(String[] args) {
        try {
            if (args.length != 0 && args.length != 2 && args.length != 3) {
                throw new IllegalArgumentException("Use input output [run-id]");
            }
            Path input = Path.of(args.length > 0 ? args[0]
                    : System.getProperty("mmr.diagnostics.report.input", "logs/diagnostics"));
            Path output = Path.of(args.length > 0 ? args[1]
                    : System.getProperty("mmr.diagnostics.report.output", "target/diagnostics-report.txt"));
            String runId = args.length == 3 ? args[2] : System.getProperty("mmr.diagnostics.report.runId");
            writeReport(input, runId == null || runId.isBlank() ? null : runId, output);
            System.out.println("Diagnostics report written.");
        } catch (IOException | RuntimeException failure) {
            // Omit the cause/message: paths and malformed JSON can contain secrets.
            throw new IllegalStateException("Diagnostics report failed ("
                    + failure.getClass().getSimpleName() + ").");
        }
    }

    record Limits(int maxFiles, long maxBytes, int maxLineBytes, int maxSamples) {
        Limits {
            if (maxFiles < 1 || maxBytes < 1 || maxLineBytes < 1 || maxSamples < 1) {
                throw new IllegalArgumentException("Invalid report limits");
            }
        }
    }

    private record ProcessKey(int file, String run, long pid, String role) { }
    private record MetricKey(int event, long track, String kind, String action, String phase,
            long cycle, String metric) { }

    private static final class State {
        final Limits limits;
        final Map<ProcessKey, Process> processes = new LinkedHashMap<>();
        final Map<String, Integer> runs = new LinkedHashMap<>();
        long bytes, invalidLines, oversizedLines, filteredRecords, unreadableFiles, rejectedGroups;
        int filesRead, storedSamples, metricSeries;
        boolean fileLimit, byteLimit, seriesLimit;

        State(Limits limits) { this.limits = limits; }

        void read(Path source, int file, String filter) throws IOException {
            filesRead++;
            long available = Math.min(100L * 1024 * 1024, limits.maxBytes() - bytes);
            long length = Files.size(source);
            long consumed = 0;
            try (var stream = new BufferedInputStream(Files.newInputStream(source))) {
                while (consumed < available) {
                    var line = new ByteArrayOutputStream(Math.min(limits.maxLineBytes(), 1024));
                    boolean oversized = false, newline = false;
                    int next = -1;
                    while (consumed < available && (next = stream.read()) != -1) {
                        consumed++;
                        if (next == '\n') { newline = true; break; }
                        if (line.size() < limits.maxLineBytes()) line.write(next);
                        else oversized = true;
                    }
                    if (oversized) oversizedLines++;
                    else if (line.size() > 0 && (newline || next == -1 || consumed >= length)) {
                        accept(line.toString(StandardCharsets.UTF_8), file, filter);
                    }
                    if (next == -1) break;
                }
            } finally { bytes += consumed; }
            if (length > consumed) byteLimit = true;
        }

        void accept(String line, int file, String filter) {
            try {
                // JsonParser has recursive internals; reject extreme nesting before parsing.
                if (!boundedDepth(line)) { invalidLines++; return; }
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                String run = tag(record, "run_id");
                String role = tag(record, "role");
                String event = tag(record, "event");
                Long pid = integer(record, "pid");
                if (!"mmr.diagnostics.v1".equals(tag(record, "schema")) || run == null
                        || role == null || event == null || pid == null || !record.has("fields")
                        || !record.get("fields").isJsonObject()) { invalidLines++; return; }
                if (filter != null && !filter.equals(run)) { filteredRecords++; return; }
                ProcessKey key = new ProcessKey(file, run, pid, role);
                Process process = processes.get(key);
                if (process == null) {
                    if (processes.size() >= 256) { rejectedGroups++; return; }
                    runs.computeIfAbsent(run, ignored -> runs.size() + 1);
                    process = new Process(key);
                    processes.put(key, process);
                }
                process.accept(record, event, this);
            } catch (RuntimeException failure) { invalidLines++; }
        }

        String render() {
            var text = new StringBuilder("MiniMeetingRoom diagnostics report (mmr.diagnostics.v1)\n");
            text.append("Input: files_read=").append(filesRead).append(" bytes_read=").append(bytes)
                    .append(" process_groups=").append(processes.size()).append('\n');
            text.append("Reader: invalid_lines=").append(invalidLines).append(" oversized_lines=")
                    .append(oversizedLines).append(" unreadable_files=").append(unreadableFiles)
                    .append(" filtered_records=").append(filteredRecords).append(" rejected_groups=")
                    .append(rejectedGroups).append(" file_limit_hit=").append(fileLimit)
                    .append(" byte_limit_hit=").append(byteLimit).append(" metric_series_limit_hit=")
                    .append(seriesLimit).append('\n');
            text.append("Limits: files=").append(limits.maxFiles()).append(" total_bytes=")
                    .append(limits.maxBytes()).append(" per_file_bytes=104857600 line_bytes=")
                    .append(limits.maxLineBytes()).append(" percentile_samples=").append(limits.maxSamples())
                    .append(" groups=256 metric_series=4096 events_per_group=256\n");
            text.append("Source files and run IDs use local aliases; no paths, identities or free text are echoed.\n")
                    .append("Percentiles use nearest-rank raw timing samples only, separately per event/action/phase/kind/track/probe cycle.\n")
                    .append("Cumulative counters describe observed lifetime totals within one probe cycle; they are not interval rates.\n")
                    .append("sfu.probe.stats counters originate in the external media process; runtime.sample still describes this JVM.\n")
                    .append("Missing values stay unavailable. JVM heap is not process RSS; timings do not imply one-way media latency.\n");
            for (Process process : processes.values()) process.render(text, runs.get(process.key.run()));
            if (processes.isEmpty()) text.append("No matching valid records; no performance conclusion is supported.\n");
            return text.toString();
        }
    }

    private static final class Process {
        final ProcessKey key;
        final Map<String, Integer> events = new LinkedHashMap<>();
        final Map<Integer, Long> eventCounts = new LinkedHashMap<>();
        final Map<MetricKey, Statistics> metrics = new LinkedHashMap<>();
        final Map<String, Double> conditions = new LinkedHashMap<>();
        long records, runtimeSamples, mediaSamples, failures, starts, ends, drops, truncations;
        double first = Double.POSITIVE_INFINITY, last = 0;
        boolean fileTruncated, clean;

        Process(ProcessKey key) { this.key = key; }

        void accept(JsonObject record, String event, State state) {
            records++;
            JsonObject fields = record.getAsJsonObject("fields");
            Double elapsed = number(record, "elapsed_ms");
            if (elapsed != null) { first = Math.min(first, elapsed); last = Math.max(last, elapsed); }
            drops = Math.max(drops, zero(integer(record, "dropped_events")));
            if (fields.has("error_type") || tagged(fields, "outcome", Set.of("error", "failed", "rejected"))) failures++;
            if ("runtime.sample".equals(event)) runtimeSamples++;
            if ("media.sample".equals(event)) mediaSamples++;
            if ("run.start".equals(event)) {
                starts++;
                for (String name : List.of("cpu_count", "max_heap_bytes", "sample_interval_ms", "queue_capacity", "max_file_bytes")) {
                    Double value = number(fields, name);
                    if (value != null) conditions.put(name, value);
                }
            }
            if ("log.truncated".equals(event)) fileTruncated = true;
            if ("run.end".equals(event)) {
                ends++;
                clean |= bool(fields, "clean_shutdown");
                fileTruncated |= bool(fields, "file_truncated");
                drops = Math.max(drops, zero(integer(fields, "dropped_events")));
                truncations = Math.max(truncations, zero(integer(fields, "truncated_events")));
            }
            if (!events.containsKey(event) && events.size() >= 256) { state.seriesLimit = true; return; }
            int eventId = events.computeIfAbsent(event, ignored -> events.size() + 1);
            eventCounts.merge(eventId, 1L, Long::sum);
            Long trackId = integer(fields, "track_id");
            long track = trackId == null ? -1 : trackId;
            String kind = tagged(fields, "kind", Set.of("AUDIO", "VIDEO", "SCREEN"))
                    ? tag(fields, "kind") : "unavailable";
            String action = tagged(fields, "action", Set.of("LOGIN", "REGISTER", "LOGOUT", "AUTHENTICATE",
                    "CREATE_ROOM", "REOPEN_ROOM", "LOOKUP_ROOM", "JOIN_ROOM", "GET_ROOM", "LEAVE_ROOM", "SET_MEDIA_STATE",
                    "SET_LOCKED", "STOP_PUBLISH", "KICK", "VIDEO_PUBLISH", "VIDEO_SUBSCRIBE", "VIDEO_STATE", "VIDEO_STOP",
                    "AUDIO_PUBLISH", "AUDIO_SUBSCRIBE", "AUDIO_STATE", "AUDIO_STOP", "SCREEN_PUBLISH", "SCREEN_SUBSCRIBE",
                    "SCREEN_STATE", "SCREEN_STOP", "PUBLISH", "UNPUBLISH", "SUBSCRIBE", "UNSUBSCRIBE",
                    "SDP_OFFER", "SDP_ANSWER", "ICE_CANDIDATE", "CHAT_SEND", "CHAT_HISTORY", "NETWORK_STATS", "OTHER"))
                    ? tag(fields, "action") : "unavailable";
            String phase = tagged(fields, "phase", PHASES) ? tag(fields, "phase") : "unavailable";
            Long cycleId = event.startsWith("media.probe.") || event.startsWith("sfu.probe.")
                    ? integer(fields, "cycle") : null;
            long cycle = cycleId == null ? -1 : cycleId;
            for (var entry : fields.entrySet()) {
                String name = entry.getKey();
                boolean latency = LATENCIES.contains(name);
                if (!latency && !MEASUREMENTS.contains(name)) continue;
                Double value = number(fields, name);
                if (value == null) continue;
                MetricKey metricKey = new MetricKey(eventId, track, kind, action, phase, cycle, name);
                Statistics statistics = metrics.get(metricKey);
                if (statistics == null) {
                    if (state.metricSeries >= 4096) { state.seriesLimit = true; continue; }
                    statistics = new Statistics(latency);
                    metrics.put(metricKey, statistics);
                    state.metricSeries++;
                }
                statistics.add(value, state);
            }
        }

        void render(StringBuilder text, int run) {
            text.append("\nProcess: source_file=").append(key.file()).append(" run_group=").append(run)
                    .append(" pid=").append(key.pid()).append(" role=")
                    .append(ROLES.contains(key.role()) ? key.role() : "OTHER").append('\n');
            text.append("Records: total=").append(records).append(" runtime_samples=").append(runtimeSamples)
                    .append(" media_samples=").append(mediaSamples).append(" failures=").append(failures)
                    .append(" observed_span_ms=").append(Double.isFinite(first) ? format(last - first) : "unavailable").append('\n');
            text.append("Logger: run_start_count=").append(starts).append(" run_end_count=").append(ends)
                    .append(" missing_run_end=").append(ends == 0).append(" clean_shutdown=")
                    .append(ends == 0 ? "unavailable" : clean).append(" dropped_events_max=").append(drops)
                    .append(" truncated_events_max=").append(truncations).append(" file_truncated=").append(fileTruncated).append('\n');
            text.append("Conditions:");
            conditions.forEach((name, value) -> text.append(' ').append(name).append('=').append(format(value)));
            if (conditions.isEmpty()) text.append(" unavailable");
            text.append('\n');
            Map<Integer, String> labels = new LinkedHashMap<>();
            events.forEach((event, id) -> labels.put(id, EVENTS.contains(event) ? event : "other.event#" + id));
            eventCounts.forEach((id, count) -> text.append("Event: name=").append(labels.get(id))
                    .append(" n=").append(count).append('\n'));
            for (var entry : metrics.entrySet()) {
                MetricKey metric = entry.getKey();
                text.append("Metric: event=").append(labels.get(metric.event())).append(" action=")
                        .append(metric.action()).append(" phase=").append(metric.phase()).append(" cycle=")
                        .append(metric.cycle() < 0 ? "unavailable" : metric.cycle()).append(" track=")
                        .append(metric.track() < 0 ? "unavailable" : metric.track())
                        .append(" kind=").append(metric.kind()).append(" name=").append(metric.metric()).append(' ');
                entry.getValue().render(text);
                text.append('\n');
            }
            if (metrics.isEmpty()) text.append("Measured metrics: unavailable\n");
        }
    }

    private static final class Statistics {
        final boolean latency;
        final List<Double> values = new ArrayList<>();
        long count;
        double min = Double.POSITIVE_INFINITY, max, mean;

        Statistics(boolean latency) { this.latency = latency; }

        void add(double value, State state) {
            count++;
            min = Math.min(min, value); max = Math.max(max, value);
            mean += (value - mean) / count;
            if (latency && state.storedSamples < state.limits.maxSamples()) {
                values.add(value); state.storedSamples++;
            }
        }

        void render(StringBuilder text) {
            text.append("n=").append(count).append(" min=").append(format(min)).append(" max=")
                    .append(format(max)).append(" mean=").append(format(mean));
            if (!latency) return;
            if (values.size() != count) { text.append(" p50/p95/p99=unavailable(sample_limit)"); return; }
            double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(sorted);
            for (int percentile : new int[] {50, 95, 99}) {
                int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
                text.append(" p").append(percentile).append('=').append(format(sorted[index]));
            }
        }
    }

    private static boolean boundedDepth(String text) {
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (value == '\\') escaped = true;
                else if (value == '"') quoted = false;
            } else if (value == '"') quoted = true;
            else if ((value == '{' || value == '[') && ++depth > 32) return false;
            else if (value == '}' || value == ']') depth--;
        }
        return true;
    }

    private static String tag(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) return null;
        String tag = value.getAsString();
        return tag.matches("[A-Za-z0-9_.:-]{1,96}") ? tag : null;
    }

    private static boolean tagged(JsonObject object, String name, Set<String> accepted) {
        String value = tag(object, name);
        return value != null && accepted.contains(value);
    }

    private static Double number(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            double number = value.getAsDouble();
            return Double.isFinite(number) && number >= 0 ? number : null;
        } catch (NumberFormatException ignored) { return null; }
    }

    private static Long integer(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
        try {
            long number = value.getAsBigDecimal().longValueExact();
            return number >= 0 ? number : null;
        } catch (ArithmeticException | NumberFormatException ignored) { return null; }
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private static long zero(Long value) { return value == null ? 0 : value; }
    private static String format(double value) { return String.format(Locale.ROOT, "%.3f", value); }
}
