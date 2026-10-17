package client.media;

import java.util.concurrent.TimeUnit;

/** Monotonic hysteresis. Missing measurements are never evidence of recovery. */
public final class AdaptiveVideoPolicy {
    public enum Profile {
        NORMAL(700_000, 15, 1, "Bình thường"),
        MEDIUM(400_000, 12, 1.5, "Video tiết kiệm"),
        LOW(140_000, 8, 2.5, "Video chất lượng thấp"),
        VOICE_PRIORITY(140_000, 8, 2.5, "Tạm dừng video để ưu tiên thoại");
        public final int bitrate, fps;
        public final double scale;
        public final String label;
        Profile(int bitrate, int fps, double scale, String label) {
            this.bitrate = bitrate; this.fps = fps; this.scale = scale; this.label = label;
        }
    }
    public record Evidence(Double rttMs, Double jitterMs, Double lossPct) {
        public static final Evidence UNKNOWN = new Evidence(null, null, null);
        public Evidence { rttMs = valid(rttMs, 60_000); jitterMs = valid(jitterMs, 10_000); lossPct = valid(lossPct, 100); }
        private static Double valid(Double value, double maximum) {
            return value != null && Double.isFinite(value) && value >= 0 && value <= maximum ? value : null;
        }
        public boolean known() { return rttMs != null || jitterMs != null || lossPct != null; }
        public Evidence merge(Evidence other) { return new Evidence(max(rttMs, other.rttMs), max(jitterMs, other.jitterMs), max(lossPct, other.lossPct)); }
        private static Double max(Double a, Double b) {
            if (a == null) return b;
            if (b == null) return a;
            return Math.max(a, b);
        }
        boolean above(double rtt, double jitter, double loss) {
            return rttMs != null && rttMs >= rtt || jitterMs != null && jitterMs >= jitter || lossPct != null && lossPct >= loss;
        }
        boolean healthy() { return known() && !above(200, 35, 2); }
    }
    private Profile profile = Profile.NORMAL;
    private static final long UNSET = Long.MIN_VALUE;
    private long badSince = UNSET, severeSince = UNSET, voiceBadSince = UNSET, goodSince = UNSET, last = UNSET;
    public Profile profile() { return profile; }
    public Profile observe(long now, Evidence video, Evidence voice) {
        if (last != UNSET && now <= last) return profile;
        if (last != UNSET && now - last > TimeUnit.SECONDS.toNanos(4)) resetWindows();
        last = now;
        Evidence combined = video.merge(voice);
        if (!combined.known()) { resetWindows(); return profile; }
        boolean bad = combined.above(250, 40, 3);
        boolean veryBad = combined.above(500, 80, 8);
        boolean voiceBad = voice.known() && voice.above(600, 80, 8);
        badSince = bad ? start(badSince, now) : UNSET;
        severeSince = veryBad ? start(severeSince, now) : UNSET;
        voiceBadSince = profile == Profile.LOW && voiceBad ? start(voiceBadSince, now) : UNSET;
        goodSince = combined.healthy() ? start(goodSince, now) : UNSET;
        Profile next = profile;
        if (profile == Profile.LOW && elapsed(voiceBadSince, now, 5)) next = Profile.VOICE_PRIORITY;
        else if (elapsed(badSince, now, 3)) {
            if (profile == Profile.NORMAL) next = Profile.MEDIUM;
            else if (profile == Profile.MEDIUM && elapsed(severeSince, now, 3)) next = Profile.LOW;
        }
        if (elapsed(goodSince, now, 12) && profile != Profile.NORMAL) next = Profile.values()[profile.ordinal() - 1];
        if (next != profile) { profile = next; resetWindows(); }
        return profile;
    }
    private static long start(long since, long now) { return since == UNSET ? now : since; }
    private static boolean elapsed(long since, long now, int seconds) { return since != UNSET && now - since >= TimeUnit.SECONDS.toNanos(seconds); }
    private void resetWindows() { badSince = severeSince = voiceBadSince = goodSince = UNSET; }
}
