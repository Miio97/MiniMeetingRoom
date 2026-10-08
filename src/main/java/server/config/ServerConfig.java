package server.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/** File configuration with environment variables taking precedence. */
public final class ServerConfig {
    private static final Map<String, String> ENVIRONMENT_KEYS = Map.of(
            "db.url", "MMR_DB_URL",
            "db.user", "MMR_DB_USER",
            "db.password", "MMR_DB_PASSWORD",
            "auth.port", "MMR_AUTH_PORT",
            "auth.bind", "MMR_AUTH_BIND",
            "auth.keystore", "MMR_AUTH_KEYSTORE",
            "auth.keystorePassword", "MMR_AUTH_KEYSTORE_PASSWORD",
            "auth.sessionHours", "MMR_AUTH_SESSION_HOURS");
    private static final Properties FILE_PROPERTIES = load();

    private ServerConfig() {
    }

    public static String get(String key, String fallback) {
        String environmentName = ENVIRONMENT_KEYS.getOrDefault(key,
                "MMR_" + key.replace('.', '_').toUpperCase(Locale.ROOT));
        String environmentValue = System.getenv(environmentName);
        return environmentValue != null ? environmentValue : FILE_PROPERTIES.getProperty(key, fallback);
    }

    public static int getInt(String key, int fallback) {
        String value = get(key, Integer.toString(fallback));
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid integer server setting: " + key);
        }
    }

    private static Properties load() {
        Properties properties = new Properties();
        Path path = Path.of(System.getProperty("mmr.server.config", "config/auth-server.properties"));
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read server configuration file: " + path, exception);
            }
        }
        return properties;
    }
}
