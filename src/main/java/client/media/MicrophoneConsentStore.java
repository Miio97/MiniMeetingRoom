package client.media;

import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/** Per Windows user, local only. This consent does not override Windows microphone privacy. */
public final class MicrophoneConsentStore {
    public enum Access {
        ASK,
        ALLOWED,
        DENIED
    }

    public record Choice(Access access, String preferredDevice, String preferredSpeaker) {
        public Choice(Access access, String preferredDevice) {
            this(access, preferredDevice, "");
        }
    }

    private final Path path;

    public MicrophoneConsentStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public static MicrophoneConsentStore local() {
        String override = System.getProperty("mmr.microphone.preferences");
        if (override != null && !override.isBlank())
            return new MicrophoneConsentStore(Path.of(override));
        String local = System.getenv("LOCALAPPDATA");
        Path directory =
                local == null || local.isBlank()
                        ? Path.of(System.getProperty("user.home"), ".minimeetingroom")
                        : Path.of(local, "MiniMeetingRoom");
        return new MicrophoneConsentStore(directory.resolve("microphone-preferences.properties"));
    }

    public synchronized Choice read() throws IOException {
        if (!Files.exists(path)) return new Choice(Access.ASK, "", "");
        if (Files.size(path) > 16384) throw new IOException("CAMERA_PREFERENCES_LIMIT");
        Properties values = new Properties();
        try (var reader = Files.newBufferedReader(path)) {
            values.load(reader);
        }
        Access access;
        try {
            access = Access.valueOf(values.getProperty("microphone.access", "ASK"));
        } catch (IllegalArgumentException invalid) {
            access = Access.ASK;
        }
        return new Choice(
                access,
                values.getProperty("microphone.device", ""),
                values.getProperty("speaker.device", ""));
    }

    public synchronized void access(boolean allowed) throws IOException {
        Choice current = read();
        write(
                new Choice(
                        allowed ? Access.ALLOWED : Access.DENIED,
                        current.preferredDevice(),
                        current.preferredSpeaker()));
    }

    public synchronized void device(String descriptor) throws IOException {
        if (descriptor == null || descriptor.length() > 4096)
            throw new IOException("CAMERA_DEVICE_INVALID");
        Choice current = read();
        write(new Choice(current.access(), descriptor, current.preferredSpeaker()));
    }

    public synchronized void speaker(String descriptor) throws IOException {
        if (descriptor == null || descriptor.length() > 4096)
            throw new IOException("AUDIO_DEVICE_INVALID");
        Choice current = read();
        write(new Choice(current.access(), current.preferredDevice(), descriptor));
    }

    private void write(Choice choice) throws IOException {
        Files.createDirectories(path.getParent());
        Properties values = new Properties();
        values.setProperty("microphone.access", choice.access().name());
        values.setProperty("microphone.device", choice.preferredDevice());
        values.setProperty("speaker.device", choice.preferredSpeaker());
        Path temporary = Files.createTempFile(path.getParent(), "microphone-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) {
                values.store(writer, "Local microphone choice; no account or server data.");
            }
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
