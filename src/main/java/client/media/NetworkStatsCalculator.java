package client.media;

/** Small UI projection of local media measurements; never sends telemetry or reads a database. */
public final class NetworkStatsCalculator {
    private NetworkStatsCalculator() {}

    public record CalculatedSample(
            Double rttMs,
            Double jitterMs,
            Double lossPct,
            Integer bitrateKbps,
            Integer fps,
            Integer width,
            Integer height,
            Integer receiveBitrateKbps,
            NetworkSample.Quality quality,
            NetworkSample.Limitation limitation) {
        public CalculatedSample(
                Double rtt,
                Double jitter,
                Double loss,
                Integer bitrate,
                Integer fps,
                Integer width,
                Integer height) {
            this(rtt, jitter, loss, bitrate, fps, width, height, null, null, null);
        }

        public boolean isEmpty() {
            return rttMs == null
                    && jitterMs == null
                    && lossPct == null
                    && bitrateKbps == null
                    && fps == null
                    && width == null
                    && height == null
                    && receiveBitrateKbps == null;
        }
    }

    static CalculatedSample display(NetworkSample s, NetworkQualityAnalyzer.Analysis a) {
        var evidence =
                s.uplink()
                        .evidence()
                        .merge(s.downlink().evidence())
                        .merge(s.voiceUp().evidence())
                        .merge(s.voiceDown().evidence())
                        .merge(s.screenUp().evidence())
                        .merge(s.screenDown().evidence());
        Double up =
                sum(s.uplink().bitrateBps(), s.voiceUp().bitrateBps(), s.screenUp().bitrateBps());
        Double down =
                sum(
                        s.downlink().bitrateBps(),
                        s.voiceDown().bitrateBps(),
                        s.screenDown().bitrateBps());
        // The badge combines media evidence; camera bitrate decisions still use only the analyzer's
        // uplink.
        var quality =
                NetworkQualityAnalyzer.classify(
                        new NetworkSample.Metrics(
                                evidence.rttMs(),
                                evidence.jitterMs(),
                                evidence.lossPct(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                NetworkSample.Limitation.UNKNOWN));
        return new CalculatedSample(
                evidence.rttMs(),
                evidence.jitterMs(),
                evidence.lossPct(),
                integer(up, 1000),
                integer(s.uplink().fps(), 1),
                s.uplink().width(),
                s.uplink().height(),
                integer(down, 1000),
                quality,
                a.limitation());
    }

    private static Integer integer(Double n, double scale) {
        return n == null ? null : (int) Math.min(Integer.MAX_VALUE, Math.round(n / scale));
    }

    private static Double sum(Double... ns) {
        Double result = null;
        for (Double n : ns) if (n != null) result = result == null ? n : result + n;
        return result;
    }

    /** Three-bar legacy presentation only; not the adaptive quality decision. */
    public static String calculateQuality(Double rtt, Double jitter, Double loss) {
        var e = new AdaptiveVideoPolicy.Evidence(rtt, jitter, loss);
        if (!e.known()) return null;
        if (e.above(160, 40, 3)) return "KEM";
        if (e.above(80, 20, 1)) return "TRUNG_BINH";
        return "TOT";
    }
}
