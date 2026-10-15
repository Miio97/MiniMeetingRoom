package client.media;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/** Per Windows user, local only. This consent does not override Windows camera privacy. */
public final class CameraConsentStore {
    public enum Access { ASK, ALLOWED, DENIED }
    public record Choice(Access access, String preferredDevice) { }
    private final Path path;
    public CameraConsentStore(Path path) { this.path = path.toAbsolutePath().normalize(); }
    public static CameraConsentStore local() {
        String override = System.getProperty("mmr.camera.preferences");
        if (override != null && !override.isBlank()) return new CameraConsentStore(Path.of(override));
        String local = System.getenv("LOCALAPPDATA");
        Path directory = local == null || local.isBlank() ? Path.of(System.getProperty("user.home"), ".minimeetingroom")
                : Path.of(local, "MiniMeetingRoom");
        return new CameraConsentStore(directory.resolve("camera-preferences.properties"));
    }
    public synchronized Choice read() throws IOException {
        if (!Files.exists(path)) return new Choice(Access.ASK, "");
        if (Files.size(path) > 16384) throw new IOException("CAMERA_PREFERENCES_LIMIT");
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path)) { values.load(reader); }
        Access access;
        try { access = Access.valueOf(values.getProperty("camera.access", "ASK")); }
        catch (IllegalArgumentException invalid) { access = Access.ASK; }
        return new Choice(access, values.getProperty("camera.device", ""));
    }
    public synchronized void access(boolean allowed) throws IOException {
        Choice current = read(); write(new Choice(allowed ? Access.ALLOWED : Access.DENIED, current.preferredDevice()));
    }
    public synchronized void device(String descriptor) throws IOException {
        if (descriptor == null || descriptor.length() > 4096) throw new IOException("CAMERA_DEVICE_INVALID");
        Choice current = read(); write(new Choice(current.access(), descriptor));
    }
    private void write(Choice choice) throws IOException {
        Files.createDirectories(path.getParent());
        Properties values = new Properties(); values.setProperty("camera.access", choice.access().name()); values.setProperty("camera.device", choice.preferredDevice());
        Path temporary = Files.createTempFile(path.getParent(), "camera-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { values.store(writer, "Local camera choice; no account or server data."); }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
