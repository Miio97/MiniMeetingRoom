package client.media;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioDeviceTesterTest {
    @Test void readsNativeStereoFramesInsteadOfDroppingFourByteFrames() {
        byte[] frame = {0, 64, 0, -64, 0, 64, 0, -64};
        assertEquals(0.5, AudioDeviceTester.recordedLevel(frame, 2, 4, 2), 0.001);
        assertEquals(0.5, AudioDeviceTester.recordedLevel(frame, 4, 2, 1), 0.001);
    }

    @Test void ignoresBufferTailOutsideTheReportedFrameCount() {
        byte[] frame = {0, 0, 0, 0, -1, 127, -1, 127};
        assertEquals(0, AudioDeviceTester.recordedLevel(frame, 1, 4, 2));
        assertEquals(0, AudioDeviceTester.recordedLevel(frame, 1, 8, 2));
    }
    @Test void microphoneLevelUsesNormalizedPcmRms() {
        byte[] samples = new byte[8];
        for (int i = 0; i < samples.length; i += 2) {
            short value = 16384;
            samples[i] = (byte) value;
            samples[i + 1] = (byte) (value >> 8);
        }
        assertEquals(0.5, AudioDeviceTester.rms(samples, 16), 0.001);
        assertEquals(0, AudioDeviceTester.rms(new byte[8], 16));
        assertEquals(0, AudioDeviceTester.rms(samples, 8));
    }
}
