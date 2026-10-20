package client.media;

import java.util.concurrent.TimeUnit;

/** Monotonic hysteresis. Missing measurements are never evidence of recovery. */
public final class AdaptiveVideoPolicy {
    public enum Profile {
        SAVING(200_000, 15, 320,180,"Tiết kiệm"),
        LOW(600_000, 30, 640,360,"Tiêu chuẩn"),
        HD(2_500_000, 30, 1280,720,"HD"),
        VOICE_PRIORITY(200_000,15,320,180,"Tạm dừng video để ưu tiên thoại");
        public final int bitrate, fps, width, height;
        public final String label;
        Profile(int bitrate, int fps, int width,int height,String label) {
            this.bitrate = bitrate; this.fps = fps; this.width=width;this.height=height;this.label = label;
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
    private Profile profile = Profile.LOW;
    private static final long UNSET = Long.MIN_VALUE;
    private long changed=UNSET,last=UNSET;
    private final NetworkStatsConfig config;
    public AdaptiveVideoPolicy() {this(NetworkStatsConfig.defaults());}
    public AdaptiveVideoPolicy(NetworkStatsConfig config) {this.config=config;}
    public Profile profile() { return profile; }
    /** A new transport has new paths and counters; old pause evidence must not block fresh subscriptions. */
    public void reset() {profile=Profile.LOW;last=changed=UNSET;}
    public Profile observe(NetworkQualityAnalyzer.Analysis a) {
        long now=a.at();if(!config.adaptiveEnabled() || last!=UNSET && now<=last) return profile;last=now;
        Profile next=profile;
        boolean cooldown=changed!=UNSET && now-changed<TimeUnit.MILLISECONDS.toNanos(config.cooldownMs());
        if(a.voiceThreat()) next=Profile.VOICE_PRIORITY;
        else if(profile==Profile.VOICE_PRIORITY) {
            if((a.voiceHealthy() || a.recover()) && a.uplink()!=NetworkSample.Quality.POOR && a.uplink()!=NetworkSample.Quality.CRITICAL) next=Profile.SAVING;
        } else if((a.degrade() && !cooldown) || a.critical()) {
            if(profile==Profile.HD) next=Profile.LOW;
            else if(profile==Profile.LOW) next=Profile.SAVING;
        } else if(a.recover() && !cooldown && (changed==UNSET || now-changed>=TimeUnit.MILLISECONDS.toNanos(config.upgradeMs()))) {
            if(profile==Profile.SAVING) next=Profile.LOW;
            else if(profile==Profile.LOW && a.hdCapable()) next=Profile.HD;
        }
        if(next!=profile) {profile=next;changed=now;}return profile;
    }
}
