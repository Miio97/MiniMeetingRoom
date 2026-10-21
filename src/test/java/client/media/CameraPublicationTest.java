package client.media;

import common.signaling.SignalException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraPublicationTest {
    @Test void staleInitialSnapshotDoesNotStopANewPublicationButConfirmedHostStopDoes() {
        long second = TimeUnit.SECONDS.toNanos(1);
        assertFalse(VideoCallController.shouldStopPublication(false, false, 2 * second));
        assertFalse(VideoCallController.shouldStopPublication(false, false, 5 * second - 1));
        assertTrue(VideoCallController.shouldStopPublication(false, false, 5 * second));
        assertFalse(VideoCallController.shouldStopPublication(true, false, 10 * second));
        assertTrue(VideoCallController.shouldStopPublication(false, true, second));
    }

    @Test void busySignalingErrorsRetainTheirCodesInsteadOfBlamingWindowsCameraAccess() {
        for (String code : new String[] {"ALREADY_PUBLISHING", "VIDEO_BUSY", "MEDIA_BUSY"}) {
            var cause = new SignalException(code); var error = new CompletionException(cause);
            assertEquals(code, CameraErrors.code(error)); assertSame(cause, CameraErrors.unwrap(error));
            assertFalse(CameraErrors.message(error).contains("Windows"));
            assertTrue(CameraErrors.message(error).contains("sau giây lát"));
        }
    }
}
