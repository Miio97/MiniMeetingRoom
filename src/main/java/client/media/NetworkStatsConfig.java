package client.media;

import java.nio.file.*;
import java.util.Properties;

/** Client properties only. Raw persistence and manual quality are deliberately unsupported. */
public record NetworkStatsConfig(
        boolean monitorEnabled,
        boolean adaptiveEnabled,
        int intervalMs,
        int uiMs,
        int analysisMs,
        int capacity,
        int timeoutMs,
        int degradeMs,
        int upgradeMs,
        int cooldownMs,
        int savingBitrate,
        int lowBitrate,
        int hdBitrate) {
    public static NetworkStatsConfig defaults() {
        return from(new Properties());
    }

    public static NetworkStatsConfig load() {
        Properties values = new Properties();
        try (var reader =
                Files.newBufferedReader(
                        Path.of(
                                System.getProperty(
                                        "mmr.client.config", "config/auth-client.properties")))) {
            values.load(reader);
        } catch (Exception ignored) {
        }
        return from(values);
    }

    public static NetworkStatsConfig from(Properties p) {
        return new NetworkStatsConfig(
                bool(p, "network.monitor.enabled"),
                bool(p, "video.adaptive.enabled"),
                number(p, "network.stats.interval.ms", 500, 500, 2000),
                number(p, "network.stats.ui-update.ms", 1000, 1000, 10000),
                number(p, "network.stats.analysis.interval.ms", 1000, 1000, 5000),
                40,
                number(p, "network.stats.timeout.ms", 3000, 500, 3000),
                number(p, "video.adaptive.degrade-window.ms", 4000, 2000, 8000),
                number(p, "video.adaptive.upgrade-window.ms", 15000, 10000, 18000),
                number(p, "video.adaptive.cooldown.ms", 10000, 5000, 30000),
                number(p, "video.profile.saving.max-bitrate", 200000, 100000, 250000),
                number(p, "video.profile.low.max-bitrate", 600000, 300000, 1000000),
                number(p, "video.profile.hd.max-bitrate", 2500000, 1200000, 3000000));
    }

    private static boolean bool(Properties p, String key) {
        return !"false".equalsIgnoreCase(p.getProperty(key));
    }

    private static int number(Properties p, String key, int fallback, int min, int max) {
        try {
            int n = Integer.parseInt(p.getProperty(key));
            return n >= min && n <= max ? n : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
