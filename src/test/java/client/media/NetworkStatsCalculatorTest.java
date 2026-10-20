package client.media;

import org.junit.jupiter.api.Test;
import static client.media.MediaStatsSamples.*;
import static client.media.NetworkSample.*;
import static org.junit.jupiter.api.Assertions.*;
class NetworkStatsCalculatorTest {
    @Test void displayIncludesSeparateRatesAndDoesNotUseLowActualBitrateAsBadNetwork() {
        var s=sample(1,GOOD,BAD,GOOD);var a=new NetworkQualityAnalyzer().analyze(new NetworkSample[]{s},NetworkStatsConfig.defaults());
        var view=NetworkStatsCalculator.display(s,a);assertEquals(200,view.bitrateKbps());assertEquals(100,view.receiveBitrateKbps());assertEquals(Quality.POOR,view.quality());
        assertEquals(Quality.EXCELLENT,a.uplink());assertFalse(a.degrade());
    }
    @Test void emptyAndUnknownRemainUnavailable() {
        var s=sample(1,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN);var a=new NetworkQualityAnalyzer().analyze(new NetworkSample[]{s},NetworkStatsConfig.defaults());
        var view=NetworkStatsCalculator.display(s,a);assertTrue(view.isEmpty());assertEquals(Quality.UNKNOWN,view.quality());
    }
    @Test void configRejectsUnsafeSettingsAndHasNoManualOrPersistenceMode() {
        var p=new java.util.Properties();p.setProperty("network.stats.interval.ms","1");p.setProperty("network.stats.buffer.capacity","100000");p.setProperty("video.profile.hd.max-bitrate","-1");p.setProperty("network.stats.persist-raw","true");
        var c=NetworkStatsConfig.from(p);assertEquals(500,c.intervalMs());assertEquals(40,c.capacity());assertEquals(2500000,c.hdBitrate());
    }
    @Test void voiceAndScreenOnlyMeetingsStillHaveRealMediaQualityWithoutInventingVideoFps() {
        var voice=sample(1,Metrics.UNKNOWN,Metrics.UNKNOWN,GOOD);
        var analyzer=new NetworkQualityAnalyzer();var config=NetworkStatsConfig.defaults();
        var view=NetworkStatsCalculator.display(voice,analyzer.analyze(new NetworkSample[]{voice},config));
        assertEquals(Quality.EXCELLENT,view.quality());assertEquals(40.0,view.rttMs());assertNull(view.fps());
        var screen=new NetworkSample(2,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN,GOOD,Metrics.UNKNOWN,0,0,0);
        view=NetworkStatsCalculator.display(screen,analyzer.analyze(new NetworkSample[]{screen},config));assertEquals(40.0,view.rttMs());
    }
}
