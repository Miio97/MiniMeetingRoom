package client.media;
import dev.onvoid.webrtc.media.video.VideoCaptureCapability;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CameraCaptureCapabilityTest {
    @Test void picksSupported720p30AndFallsBackWithoutRequestingSixtyFpsOrUpscaling() {
        var mode=CameraVideoInput.select(List.of(new VideoCaptureCapability(1920,1080,60),new VideoCaptureCapability(1280,720,60),new VideoCaptureCapability(1280,720,30),new VideoCaptureCapability(640,480,15)));
        assertEquals(1280,mode.width);assertEquals(720,mode.height);assertEquals(30,mode.frameRate);
        mode=CameraVideoInput.select(List.of(new VideoCaptureCapability(640,480,15)));assertEquals(640,mode.width);assertEquals(15,mode.frameRate);
    }
    @Test void smoothCaptureWinsOverHighResolutionWithEightOrFifteenFramesPerSecond() {
        var mode=CameraVideoInput.select(List.of(new VideoCaptureCapability(1280,720,8),new VideoCaptureCapability(1280,720,15),new VideoCaptureCapability(640,480,30)));
        assertEquals(640,mode.width);assertEquals(480,mode.height);assertEquals(30,mode.frameRate);
    }
}
