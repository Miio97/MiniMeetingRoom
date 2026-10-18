package client.media;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static client.media.AdaptiveVideoPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class AdaptiveVideoPolicyTest {
    private final AdaptiveVideoPolicy policy = new AdaptiveVideoPolicy();
    private static final Evidence GOOD = new Evidence(45.0, 5.0, 0.0);
    private static final Evidence BAD = new Evidence(700.0, 95.0, 12.0);
    private Profile at(int second, Evidence video, Evidence voice) { return policy.observe(TimeUnit.SECONDS.toNanos(second), video, voice); }
    @Test void videoCongestionReducesGraduallyButCannotPauseHealthyVoice() {
        for (int i = 0; i < 3; i++) assertEquals(Profile.NORMAL, at(i, BAD, GOOD));
        assertEquals(Profile.MEDIUM, at(3, BAD, GOOD));
        for (int i = 4; i <= 60; i++) at(i, BAD, GOOD);
        assertEquals(Profile.LOW, policy.profile());
    }
    @Test void onlySustainedVoiceImpairmentAtLowVideoPausesAndRecoveryIsGradual() {
        for (int i = 0; i <= 13; i++) at(i, BAD, BAD);
        assertEquals(Profile.VOICE_PRIORITY, policy.profile());
        for (int i = 14; i <= 25; i++) assertEquals(Profile.VOICE_PRIORITY, at(i, GOOD, GOOD));
        assertEquals(Profile.LOW, at(26, GOOD, GOOD));
        for (int i = 27; i <= 52; i++) at(i, GOOD, GOOD);
        assertEquals(Profile.NORMAL, policy.profile());
    }
    @Test void transientSpikesAndAlternatingSignalsDoNotOscillate() {
        for (int i = 0; i < 60; i++) at(i, i % 3 == 0 ? BAD : GOOD, GOOD);
        assertEquals(Profile.NORMAL, policy.profile());
    }
    @Test void missingInvalidAndStaleMeasurementsCannotRestoreOrPauseVideo() {
        for (int i = 0; i <= 9; i++) at(i, BAD, Evidence.UNKNOWN);
        assertEquals(Profile.LOW, policy.profile());
        var invalid = new Evidence(Double.NaN, Double.POSITIVE_INFINITY, -1.0);
        for (int i = 10; i < 40; i++) at(i, invalid, Evidence.UNKNOWN);
        assertEquals(Profile.LOW, policy.profile());
        at(100, GOOD, GOOD); at(200, GOOD, GOOD); assertEquals(Profile.LOW, policy.profile());
        at(199, BAD, BAD); assertEquals(Profile.LOW, policy.profile());
    }
    @Test void moderateCongestionNeverDropsBelowMedium() {
        for (int i = 0; i <= 80; i++) at(i, new Evidence(300.0, 50.0, 4.0), GOOD);
        assertEquals(Profile.MEDIUM, policy.profile());
        assertEquals(Profile.MEDIUM, at(81, BAD, GOOD), "One severe spike must not trigger another downgrade");
    }
}
