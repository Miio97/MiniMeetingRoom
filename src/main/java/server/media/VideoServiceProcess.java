package server.media;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import server.config.ServerConfig;

/** One Java-owned child process; local build works with Run File and survives Maven clean. */
public final class VideoServiceProcess implements AutoCloseable {
    private final Process process;
    private final VideoEngineClient client;
    private VideoServiceProcess(Process process, VideoEngineClient client) { this.process = process; this.client = client; }
    public VideoEngineClient client() { return client; }
    public static VideoServiceProcess start() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path binary = root.resolve(".tools/bin/mmr-video.exe");
        Path go = root.resolve(".tools/go1.27.2/go/bin/go.exe");
        Path sources = root.resolve("media/video");
        Files.createDirectories(binary.getParent());
        long binaryTime = Files.exists(binary) ? Files.getLastModifiedTime(binary).toMillis() : 0;
        boolean rebuild;
        try (var files = Files.list(sources)) { rebuild = files.filter(p -> p.toString().endsWith(".go") || Set.of("go.mod", "go.sum").contains(p.getFileName().toString()))
                .anyMatch(p -> { try { return Files.getLastModifiedTime(p).toMillis() > binaryTime; } catch (Exception error) { return true; } }); }
        if (rebuild) {
            if (!Files.isRegularFile(go)) throw new IllegalStateException("RUN_INIT_GO_TOOLCHAIN_SCRIPT");
            ProcessBuilder builder = new ProcessBuilder(go.toString(), "build", "-ldflags=-H=windowsgui", "-o", binary.toString(), ".").directory(sources.toFile());
            builder.environment().put("GOTOOLCHAIN", "local"); builder.environment().put("GOCACHE", root.resolve(".tools/go-cache").toString());
            builder.environment().put("GOMODCACHE", root.resolve(".tools/go-modules").toString());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process build = builder.start();
            if (!build.waitFor(90, TimeUnit.SECONDS)) { build.destroyForcibly(); throw new IllegalStateException("VIDEO_BUILD_TIMEOUT"); }
            if (build.exitValue() != 0) throw new IllegalStateException("VIDEO_BUILD_FAILED");
        }
        Path keyFile = root.resolve("config/video-server.private.properties");
        Properties keys = new Properties();
        if (Files.exists(keyFile)) { try (var reader = Files.newBufferedReader(keyFile)) { keys.load(reader); } }
        else {
            byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
            keys.setProperty("control.key", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            try (var writer = Files.newBufferedWriter(keyFile, StandardOpenOption.CREATE_NEW)) { keys.store(writer, "Server-only video control; never copy to clients."); }
        }
        String secret = keys.getProperty("control.key", "");
        if (!secret.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException("VIDEO_PRIVATE_CONFIG_INVALID");
        int port = ServerConfig.getInt("media.control.port", 8766);
        int maxParticipants = Math.min(50, Math.max(2, ServerConfig.getInt("meeting.maxParticipants", 20)));
        ProcessBuilder builder = new ProcessBuilder(binary.toString()).directory(root.toFile());
        builder.environment().put("MMR_MEDIA_CONTROL_SECRET", secret);
        builder.environment().put("MMR_MEDIA_CONTROL_PORT", Integer.toString(port));
        builder.environment().put("MINIMEETING_MAX_PEERS", Integer.toString(maxParticipants));
        builder.environment().put("MMR_MAX_PEERS", Integer.toString(maxParticipants));
        builder.environment().put("MMR_MAX_PARTICIPANTS", Integer.toString(maxParticipants));
        for (String key : List.of("MMR_DB_URL", "MMR_DB_USER", "MMR_DB_PASSWORD", "MMR_AUTH_KEYSTORE_PASSWORD")) builder.environment().remove(key);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (process.isAlive() && System.nanoTime() < deadline) {
            try {
                VideoEngineClient candidate = new VideoEngineClient(port, secret);
                if (candidate.processId() == process.pid() && process.isAlive()) return new VideoServiceProcess(process, candidate);
                candidate.close();
            }
            catch (Exception notReady) { Thread.sleep(100); }
        }
        process.destroyForcibly(); throw new IllegalStateException("VIDEO_SERVICE_UNAVAILABLE");
    }
    @Override public void close() {
        client.shutdown(); client.close();
        try { if (!process.waitFor(6, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
    }
}
