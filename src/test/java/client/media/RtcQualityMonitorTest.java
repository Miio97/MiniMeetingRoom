package client.media;

import dev.onvoid.webrtc.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtcQualityMonitorTest {
    private final RtcQualityMonitor monitor = new RtcQualityMonitor();
    private final Object peer = new Object();
    private static class Stat extends RTCStats {
        Stat(RTCStatsType type, String id, Map<String, Object> values) { super(1, type, id, values); }
    }
    private static class Report extends RTCStatsReport {
        Report(RTCStats... stats) { super(map(stats), 1); }
        static Map<String, RTCStats> map(RTCStats[] stats) { var map = new HashMap<String, RTCStats>(); for (var stat : stats) map.put(stat.getId(), stat); return map; }
    }
    private Report counters(long lost, long received) { return new Report(new Stat(RTCStatsType.INBOUND_RTP, "audio", Map.of("packetsLost", lost, "packetsReceived", received, "jitter", .03))); }
    @Test void lossUsesDeltasAndPeerReplacementDoesNotFabricateLoss() {
        monitor.begin(); monitor.accept(peer, counters(100, 900)); assertNull(monitor.finish().lossPct());
        monitor.begin(); monitor.accept(peer, counters(102, 998)); assertEquals(2.0, monitor.finish().lossPct());
        monitor.begin(); monitor.accept(peer, counters(0, 5)); assertNull(monitor.finish().lossPct());
        monitor.begin(); monitor.accept(new Object(), counters(200, 200)); assertNull(monitor.finish().lossPct());
    }
    @Test void outboundAudioReportsAndOnlySelectedIcePathAreUsed() {
        monitor.begin(); monitor.accept(peer, new Report(
                new Stat(RTCStatsType.CANDIDATE_PAIR, "unused", Map.of("currentRoundTripTime", 5.0, "state", "succeeded", "nominated", false)),
                new Stat(RTCStatsType.CANDIDATE_PAIR, "active", Map.of("currentRoundTripTime", .07, "nominated", true)),
                new Stat(RTCStatsType.REMOTE_INBOUND_RTP, "voice", Map.of("fractionLost", .1, "jitter", .08))));
        var sample = monitor.finish(); assertEquals(70.0, sample.rttMs()); assertEquals(80.0, sample.jitterMs()); assertEquals(10.0, sample.lossPct());
    }
    @Test void pausedVideoIgnoresStaleRtpSoIceAndVoiceCanTriggerRecovery() {
        monitor.begin(); monitor.accept(peer, new Report(
                new Stat(RTCStatsType.CANDIDATE_PAIR, "active", Map.of("currentRoundTripTime", .04, "nominated", true)),
                new Stat(RTCStatsType.REMOTE_INBOUND_RTP, "old", Map.of("fractionLost", .9, "jitter", .9))), true);
        var sample = monitor.finish(); assertEquals(40.0, sample.rttMs()); assertNull(sample.lossPct()); assertNull(sample.jitterMs());
    }
}
