package server.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

/** Stable instance ownership and request HMAC key, stored only in ignored server config. */
public record MeetingSecrets(String instanceId, byte[] receiptKey) {
    public MeetingSecrets {
        receiptKey = receiptKey.clone();
    }

    @Override
    public byte[] receiptKey() {
        return receiptKey.clone();
    }

    @Override
    public String toString() {
        return "MeetingSecrets[REDACTED]";
    }

    public static MeetingSecrets load() throws IOException {
        Path file =
                Path.of(
                        System.getProperty(
                                "mmr.meeting.secrets", "config/meeting-server.private.properties"));
        Properties values = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            }
        } else {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            values.setProperty("instance.id", UUID.randomUUID().toString());
            values.setProperty(
                    "receipt.key", Base64.getUrlEncoder().withoutPadding().encodeToString(key));
            if (file.toAbsolutePath().getParent() != null)
                Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer writer =
                    Files.newBufferedWriter(
                            file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                values.store(
                        writer,
                        "Server-only meeting ownership. Preserve across restarts; never copy to a"
                                + " client.");
            }
        }
        try {
            String instance = values.getProperty("instance.id");
            byte[] key = Base64.getUrlDecoder().decode(values.getProperty("receipt.key", ""));
            if (instance == null || !instance.matches("[A-Za-z0-9_-]{1,64}") || key.length != 32)
                throw new IllegalArgumentException();
            return new MeetingSecrets(instance, key);
        } catch (RuntimeException invalid) {
            throw new IOException("MEETING_PRIVATE_CONFIG_INVALID");
        }
    }
}
