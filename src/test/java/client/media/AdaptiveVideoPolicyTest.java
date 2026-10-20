package client.media;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static client.media.NetworkSample.*;
import static client.media.AdaptiveVideoPolicy.Profile.*;
import static client.media.MediaStatsSamples.*;
import static org.junit.jupiter.api.Assertions.*;
class AdaptiveVideoPolicyTest {
    final NetworkStatsConfig config=NetworkStatsConfig.defaults();
    final NetworkStatsBuffer buffer=new NetworkStatsBuffer(40);
    final NetworkQualityAnalyzer analyzer=new NetworkQualityAnalyzer();
    final AdaptiveVideoPolicy policy=new AdaptiveVideoPolicy(config);
    AdaptiveVideoPolicy.Profile at(int second,Metrics up,Metrics down,Metrics voice) {
        buffer.add(sample(TimeUnit.SECONDS.toNanos(second),up,down,voice));return policy.observe(analyzer.analyze(buffer.snapshot(),config));
    }
    @Test void weakUplinkReducesBeforePausingAndGoodVoiceNeverCausesPause() {
        for(int i=0;i<4;i++) assertEquals(LOW,at(i,BAD,GOOD,GOOD));
        assertEquals(SAVING,at(4,BAD,GOOD,GOOD));
        for(int i=5;i<60;i++) at(i,BAD,GOOD,GOOD);assertEquals(SAVING,policy.profile());
    }
    @Test void voicePauseAndRecoveryNeedSustainedEvidenceAndRestoreOneStepAtATime() {
        for(int i=0;i<=10;i++) at(i,BAD,GOOD,BAD);assertEquals(VOICE_PRIORITY,policy.profile());
        for(int i=11;i<=25;i++) assertEquals(VOICE_PRIORITY,at(i,GOOD,GOOD,GOOD));
        assertEquals(SAVING,at(26,GOOD,GOOD,GOOD));
        for(int i=27;i<41;i++) assertEquals(SAVING,at(i,GOOD,GOOD,GOOD));
        assertEquals(LOW,at(41,GOOD,GOOD,GOOD));
        for(int i=42;i<=56;i++) at(i,GOOD,GOOD,GOOD);assertEquals(HD,policy.profile());
    }
    @Test void receivingLossAndLatencyAloneCannotReduceTheLocalSender() {
        for(int i=0;i<40;i++) at(i,GOOD,BAD,GOOD);assertEquals(HD,policy.profile());
        policy.reset();buffer.clear();
        var latency=new Metrics(900.0,null,null,null,null,null,null,null,Limitation.NONE);
        for(int i=0;i<40;i++) at(i,latency,GOOD,GOOD);assertEquals(LOW,policy.profile());
    }
    @Test void spikesMissingDataAndGapsCannotInventCongestionOrRecovery() {
        for(int i=0;i<40;i++) at(i,i%3==0?BAD:GOOD,GOOD,GOOD);assertNotEquals(SAVING,policy.profile());
        policy.reset();buffer.clear();for(int i=0;i<=5;i++) at(i,BAD,GOOD,GOOD);assertEquals(SAVING,policy.profile());
        for(int i=6;i<40;i++) at(i,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN);assertEquals(SAVING,policy.profile());
        at(100,GOOD,GOOD,GOOD);at(200,GOOD,GOOD,GOOD);assertEquals(SAVING,policy.profile());
    }
    @Test void cpuLimitationAndBandwidthHeadroomPreventBlindUpgrades() {
        var cpu=new Metrics(40.0,1.0,0.0,50000.0,5.0,640,360,4000000.0,Limitation.CPU_LIMITED);
        for(int i=0;i<40;i++) at(i,cpu,GOOD,GOOD);assertEquals(SAVING,policy.profile());
        policy.reset();buffer.clear();
        var noHeadroom=new Metrics(40.0,1.0,0.0,10000.0,30.0,640,360,700000.0,Limitation.NONE);
        for(int i=0;i<40;i++) at(i,noHeadroom,GOOD,GOOD);assertEquals(LOW,policy.profile());
    }
    @Test void healthyLowCeilingCanProbeHdWithoutAlreadyHavingHdBandwidthOrThirtyFpsCapture() {
        var headroom=new Metrics(1.0,3.0,0.0,500000.0,25.0,640,360,1500000.0,Limitation.NONE);
        for(int i=0;i<=20;i++) {
            buffer.add(new NetworkSample(TimeUnit.SECONDS.toNanos(i),headroom,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN,Metrics.UNKNOWN,1280,720,25));
            policy.observe(analyzer.analyze(buffer.snapshot(),config));
        }
        assertEquals(HD,policy.profile());
    }
    @Test void diagnosticDisableFlagKeepsOneStaticSafePolicyRatherThanStartingAnotherManager() {
        var values=new java.util.Properties();values.setProperty("video.adaptive.enabled","false");
        var disabled=new AdaptiveVideoPolicy(NetworkStatsConfig.from(values));
        var critical=new NetworkQualityAnalyzer.Analysis(1,Quality.CRITICAL,Quality.CRITICAL,Limitation.CPU_LIMITED,true,true,false,true,false,true);
        assertEquals(LOW,disabled.observe(critical));
    }
    @Test void nativeBandwidthFlagAndLowEstimateWithoutTransportImpairmentDoNotCauseFalseDegradation() {
        var constrained=new Metrics(40.0,5.0,0.0,10000.0,30.0,640,360,100000.0,Limitation.NETWORK_LIMITED);
        for(int i=0;i<40;i++) at(i,constrained,GOOD,GOOD);
        assertEquals(LOW,policy.profile(),"Scene/cap-limited estimates must not be treated as independent network congestion");
    }
}
