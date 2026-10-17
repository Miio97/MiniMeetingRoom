package client.media;

import dev.onvoid.webrtc.*;
import java.util.*;

/** Per-stream deltas; a peer/SSRC replacement cannot manufacture packet loss. One sampler owns this. */
final class RtcQualityMonitor {
    private record Stream(Object peer, String id) { }
    private record Counter(long lost, long received, long round) { }
    private final Map<Stream, Counter> counters = new HashMap<>();
    private long round;
    private AdaptiveVideoPolicy.Evidence evidence;
    void begin() { evidence = AdaptiveVideoPolicy.Evidence.UNKNOWN; round++; }
    void accept(Object peer, RTCStatsReport report) {
        accept(peer, report, false);
    }
    void accept(Object peer, RTCStatsReport report, boolean transportOnly) {
        if (report == null) return;
        for (RTCStats stat : report.getStats().values()) {
            var values = stat.getAttributes(); Double rtt = null, jitter = null, loss = null;
            if (stat.getType() == RTCStatsType.CANDIDATE_PAIR) {
                if (Boolean.TRUE.equals(values.get("nominated")) || Boolean.TRUE.equals(values.get("selected")))
                    rtt = millis(values.get("currentRoundTripTime"));
            } else if (!transportOnly && (stat.getType() == RTCStatsType.INBOUND_RTP || stat.getType() == RTCStatsType.REMOTE_INBOUND_RTP)) {
                rtt = millis(values.get("roundTripTime")); jitter = millis(values.get("jitter"));
                Stream key = new Stream(peer, stat.getId());
                if (values.get("packetsLost") instanceof Number lost && values.get("packetsReceived") instanceof Number received) {
                    Counter current = new Counter(lost.longValue(), received.longValue(), round); Counter previous = counters.put(key, current);
                    if (previous != null) {
                        long dl = current.lost - previous.lost, dr = current.received - previous.received;
                        if (dl >= 0 && dr >= 0 && dl + dr > 0) loss = dl * 100.0 / (dl + dr);
                    }
                } else if (values.get("fractionLost") instanceof Number fraction) {
                    double value = fraction.doubleValue(); if (Double.isFinite(value) && value >= 0 && value <= 1) loss = value * 100;
                }
            }
            evidence = evidence.merge(new AdaptiveVideoPolicy.Evidence(rtt, jitter, loss));
        }
    }
    AdaptiveVideoPolicy.Evidence finish() {
        counters.values().removeIf(counter -> counter.round < round - 20);
        return evidence;
    }
    private static Double millis(Object value) { return value instanceof Number n ? n.doubleValue() * 1000 : null; }
}
