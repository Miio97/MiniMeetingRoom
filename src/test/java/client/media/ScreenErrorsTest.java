package client.media;
import common.signaling.SignalException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ScreenErrorsTest {
    @Test void receiverFailuresNeverBlameLocalCapture() {
        assertEquals("ALREADY_SUBSCRIBED",CameraErrors.code(new SignalException("ALREADY_SUBSCRIBED")));
        for(Throwable error:new Throwable[]{new SignalException("ALREADY_SUBSCRIBED"),new SignalException("UNKNOWN_REMOTE_CODE"),new TimeoutException(),new IllegalStateException("native receive failed"),new SignalException("SCREEN_NO_FRAMES")}) {
            assertEquals("SCREEN_CONNECTION_FAILED",CameraErrors.screenReceiveCode(new CompletionException(error)));
        }
        assertEquals("SCREEN_CAPTURE_FAILED",CameraErrors.screenCode(new IllegalStateException("local capture failed")));
    }
}
