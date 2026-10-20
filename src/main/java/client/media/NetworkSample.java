package client.media;

/** Compact immutable RAM sample. No report, JSON, frames or native peer references. */
public record NetworkSample(long atNanos, Metrics uplink, Metrics downlink, Metrics voiceUp, Metrics voiceDown,
        Metrics screenUp, Metrics screenDown, int captureWidth, int captureHeight, int captureFps) {
    public enum Quality { EXCELLENT, GOOD, FAIR, POOR, CRITICAL, UNKNOWN }
    public enum Limitation { NONE, NETWORK_LIMITED, CPU_LIMITED, CAMERA_LIMITED, UNKNOWN }
    public record Metrics(Double rttMs, Double jitterMs, Double lossPct, Double bitrateBps, Double fps,
            Integer width, Integer height, Double availableBitrateBps, Limitation limitation,Double droppedFps,String connectionState) {
        public Metrics(Double rtt,Double jitter,Double loss,Double bitrate,Double fps,Integer width,Integer height,Double available,Limitation reason) {
            this(rtt,jitter,loss,bitrate,fps,width,height,available,reason,null,"unknown");
        }
        public static final Metrics UNKNOWN = new Metrics(null,null,null,null,null,null,null,null,Limitation.UNKNOWN);
        public Metrics {
            rttMs=valid(rttMs,60000); jitterMs=valid(jitterMs,10000); lossPct=valid(lossPct,100);
            bitrateBps=valid(bitrateBps,1_000_000_000); fps=valid(fps,240); availableBitrateBps=valid(availableBitrateBps,1_000_000_000);
            width=dimension(width); height=dimension(height); limitation=limitation==null?Limitation.UNKNOWN:limitation;
            droppedFps=valid(droppedFps,240);
            if(!java.util.Set.of("connected","connecting","failed","closed").contains(connectionState==null?"unknown":connectionState)) connectionState="unknown";
        }
        public AdaptiveVideoPolicy.Evidence evidence() { return new AdaptiveVideoPolicy.Evidence(rttMs,jitterMs,lossPct); }
        public boolean known() { return evidence().known() || availableBitrateBps!=null || bitrateBps!=null || fps!=null; }
        private static Integer dimension(Integer n) { return n!=null && n>0 && n<=7680 ? n : null; }
        private static Double valid(Double n,double max) { return n!=null && Double.isFinite(n) && n>=0 && n<=max ? n:null; }
    }
}
