package client.media;

import static client.media.NetworkSample.*;

import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Windowed local evidence. Actual bitrate is informational, never a bandwidth estimate. */
public final class NetworkQualityAnalyzer {
    public record Analysis(
            long at,
            Quality uplink,
            Quality downlink,
            Limitation limitation,
            boolean degrade,
            boolean critical,
            boolean recover,
            boolean voiceThreat,
            boolean voiceHealthy,
            boolean hdCapable) {}

    public Analysis analyze(NetworkSample[] history, NetworkStatsConfig config) {
        if (history.length == 0)
            return new Analysis(
                    0,
                    Quality.UNKNOWN,
                    Quality.UNKNOWN,
                    Limitation.UNKNOWN,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false);
        NetworkSample last = history[history.length - 1];
        long now = last.atNanos();
        Quality up = classify(last.uplink()), down = classify(last.downlink());
        Limitation reason = last.uplink().limitation();
        if (reason != Limitation.CPU_LIMITED && bad(last.uplink()))
            reason = Limitation.NETWORK_LIMITED;
        else if (reason != Limitation.CPU_LIMITED
                && last.captureFps() > 0
                && last.captureFps() < 30) reason = Limitation.CAMERA_LIMITED;
        boolean poor =
                duration(
                                history,
                                s ->
                                        bad(s.uplink())
                                                || s.uplink().limitation()
                                                        == Limitation.CPU_LIMITED)
                        >= TimeUnit.MILLISECONDS.toNanos(config.degradeMs());
        boolean severe =
                duration(history, s -> critical(s.uplink())) >= TimeUnit.SECONDS.toNanos(2);
        boolean voice =
                duration(history, NetworkQualityAnalyzer::voiceBad) >= TimeUnit.SECONDS.toNanos(5);
        boolean voiceGood =
                duration(history, NetworkQualityAnalyzer::voiceGood)
                        >= TimeUnit.MILLISECONDS.toNanos(config.upgradeMs());
        boolean healthy =
                duration(
                                history,
                                s ->
                                        healthy(s.uplink())
                                                && !voiceBad(s)
                                                && s.uplink().limitation()
                                                        != Limitation.CPU_LIMITED)
                        >= TimeUnit.MILLISECONDS.toNanos(config.upgradeMs());
        return new Analysis(
                now,
                up,
                down,
                reason,
                poor,
                severe,
                healthy,
                voice,
                voiceGood,
                (last.captureWidth() > AdaptiveVideoPolicy.Profile.LOW.width
                                || last.captureHeight() > AdaptiveVideoPolicy.Profile.LOW.height)
                        && last.captureFps() > 0
                        && last.uplink().availableBitrateBps() != null
                        // Probe the next ceiling with headroom above the current ceiling. Requiring
                        // 130% of HD while still capped at LOW can prevent congestion control
                        // probing it.
                        // maxBitrate remains a ceiling; native congestion control chooses the
                        // actual rate.
                        && last.uplink().availableBitrateBps() >= config.lowBitrate() * 1.25);
    }

    private static long duration(NetworkSample[] history, Predicate<NetworkSample> condition) {
        long end = history[history.length - 1].atNanos(), start = end, last = end;
        for (int i = history.length - 1; i >= 0; i--) {
            NetworkSample sample = history[i];
            if (last - sample.atNanos() > TimeUnit.SECONDS.toNanos(3) || !condition.test(sample))
                break;
            start = sample.atNanos();
            last = start;
        }
        return end - start;
    }

    static boolean bad(Metrics m) {
        // RTT alone is latency, not proof that the local sender is congested.
        return m.evidence().above(Double.MAX_VALUE, 60, 5)
                || m.availableBitrateBps() != null
                        && m.availableBitrateBps() < 350000
                        && m.evidence().above(200, 20, 1);
    }

    static boolean critical(Metrics m) {
        return m.evidence().above(Double.MAX_VALUE, 100, 12)
                || m.availableBitrateBps() != null
                        && m.availableBitrateBps() < 140000
                        && m.evidence().above(250, 30, 2);
    }

    static boolean healthy(Metrics m) {
        return m.evidence().healthy();
    }

    static boolean voiceBad(NetworkSample s) {
        return s.voiceUp().evidence().above(600, 80, 8)
                || s.voiceDown().evidence().above(600, 80, 8);
    }

    static boolean voiceGood(NetworkSample s) {
        var a = s.voiceUp().evidence();
        var b = s.voiceDown().evidence();
        return (a.known() || b.known())
                && (!a.known() || a.healthy())
                && (!b.known() || b.healthy());
    }

    public static Quality classify(Metrics m) {
        if (!m.evidence().known() && m.availableBitrateBps() == null) return Quality.UNKNOWN;
        if (critical(m)) return Quality.CRITICAL;
        if (bad(m) || m.evidence().above(500, 60, 5)) return Quality.POOR;
        if (m.evidence().above(200, 30, 2)) return Quality.FAIR;
        if (m.evidence().above(120, 15, 1)) return Quality.GOOD;
        return Quality.EXCELLENT;
    }
}
