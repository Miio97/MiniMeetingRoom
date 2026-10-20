package client.media;

import dev.onvoid.webrtc.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RtcQualityMonitorTest {
    static long timestamp;
    static class Stat extends RTCStats {Stat(RTCStatsType type,String id,Map<String,Object> values) {super(++timestamp,type,id,values);}}
    static class Report extends RTCStatsReport {Report(RTCStats... stats) {super(map(stats),++timestamp);}static Map<String,RTCStats> map(RTCStats[] stats) {var m=new HashMap<String,RTCStats>();for(var s:stats)m.put(s.getId(),s);return m;}}
    final RtcQualityMonitor monitor=new RtcQualityMonitor();final Object peer=new Object();
    MediaStatsSource source(Object peer,boolean sending,boolean paused) {return new MediaStatsSource(peer,MediaStatsSource.Kind.CAMERA,sending,paused,() -> null);}
    Report inbound(long lost,long received,long bytes,Object ssrc) {return new Report(new Stat(RTCStatsType.INBOUND_RTP,"stream",Map.of("ssrc",ssrc,"packetsLost",lost,"packetsReceived",received,"bytesReceived",bytes,"jitter",.03)));}
    @Test void perStreamDeltasResetOnCountersPeerSsrcAndDoNotMixDirections() {
        monitor.begin(0);monitor.accept(source(peer,false,false),inbound(100,900,1000,1));assertNull(monitor.finish(0,0,0).downlink().lossPct());
        monitor.begin(500000000);monitor.accept(source(peer,false,false),inbound(102,998,2000,1));var s=monitor.finish(0,0,0);
        assertEquals(2.0,s.downlink().lossPct());assertEquals(16000.0,s.downlink().bitrateBps());assertNull(s.uplink().lossPct());
        monitor.begin(1000000000);monitor.accept(source(peer,false,false),inbound(0,5,50,1));assertNull(monitor.finish(0,0,0).downlink().lossPct());
        monitor.begin(1500000000);monitor.accept(source(peer,false,false),inbound(20,90,500,2));assertNull(monitor.finish(0,0,0).downlink().lossPct());
        monitor.begin(2000000000);monitor.accept(source(new Object(),false,false),inbound(50,100,900,2));assertNull(monitor.finish(0,0,0).downlink().lossPct());
    }
    @Test void selectedIceOnlyAndRemoteOutboundFeedbackAreUsedButPausedRtpIsIgnored() {
        var report=new Report(new Stat(RTCStatsType.CANDIDATE_PAIR,"unused",Map.of("currentRoundTripTime",5.0,"nominated",false)),
                new Stat(RTCStatsType.CANDIDATE_PAIR,"selected",Map.of("currentRoundTripTime",.04,"nominated",true,"availableOutgoingBitrate",4000000.0)),
                new Stat(RTCStatsType.REMOTE_INBOUND_RTP,"remote",Map.of("fractionLost",.1,"jitter",.08)));
        monitor.begin(0);monitor.accept(source(peer,true,false),report);var first=monitor.finish(0,0,0).uplink();
        assertEquals(40.0,first.rttMs());assertEquals(10.0,first.lossPct());assertEquals(80.0,first.jitterMs());
        monitor.begin(500000000);monitor.accept(source(peer,true,true),report);var paused=monitor.finish(0,0,0).uplink();
        assertEquals(40.0,paused.rttMs());assertNull(paused.lossPct());assertNull(paused.jitterMs());
    }
    @Test void staleReportsInvalidNumbersAndSameTimestampCannotManufactureZeroLoss() {
        var report=inbound(1,100,1000,1);monitor.begin(0);monitor.accept(source(peer,false,false),report);monitor.finish(0,0,0);
        monitor.begin(500000000);monitor.accept(source(peer,false,false),report);var s=monitor.finish(0,0,0);
        assertNull(s.downlink().lossPct());assertNull(s.downlink().bitrateBps());
        var invalid=new NetworkSample.Metrics(Double.NaN,Double.POSITIVE_INFINITY,-1.0,null,null,null,null,null,null);
        assertFalse(invalid.known());
    }
    @Test void transportSelectedPairWinsOverOldNominationAndOutgoingBandwidthIsNotDownlinkCapacity() {
        var report=new Report(new Stat(RTCStatsType.TRANSPORT,"transport",Map.of("selectedCandidatePairId","active","dtlsState","connected")),
                new Stat(RTCStatsType.CANDIDATE_PAIR,"old",Map.of("nominated",true,"currentRoundTripTime",3.0)),
                new Stat(RTCStatsType.CANDIDATE_PAIR,"active",Map.of("nominated",true,"currentRoundTripTime",.03,"availableOutgoingBitrate",100000.0)));
        monitor.begin(1);monitor.accept(source(peer,false,false),report);var sample=monitor.finish(640,360,30);
        assertEquals(30.0,sample.downlink().rttMs());assertNull(sample.downlink().availableBitrateBps());assertEquals("connected",sample.downlink().connectionState());
    }
    @Test void retiredPeersReleaseTheirCounterBaselineBeforeATrackCanBeReused() {
        monitor.begin(1);monitor.accept(source(peer,false,false),inbound(1,100,1000,1));monitor.finish(0,0,0);
        monitor.retainSources(List.of());
        monitor.begin(500000001);monitor.accept(source(peer,false,false),inbound(2,200,2000,1));assertNull(monitor.finish(0,0,0).downlink().lossPct());
    }
}
