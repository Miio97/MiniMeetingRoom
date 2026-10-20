package client.media;

import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import dev.onvoid.webrtc.media.video.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Native 720p input, sender scaling and RTP decode; no real camera or database. */
@Timeout(90)
class AutoVideoHdIntegrationTest {
    @TempDir static Path temporary;
    static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception {FxTestRuntime.start();tls=RoomTestFixture.certificates(temporary);}
    @Test void nativePixelConversionPreservesColorsRotationAndBindingLifetime() throws Exception {
        try(var runtime=new NativeVideoSession();var mailbox=new VideoFrameMailbox()) {
            for(int rotation:new int[]{0,90,180,270}) {
                var binding=mailbox.bind();FxTestRuntime.call(() -> null);
                pushPixels(binding,rotation,false);
                FxTestRuntime.await(() -> mailbox.imageProperty().get()!=null);
                FxTestRuntime.call(() -> {
                    var image=mailbox.imageProperty().get();
                    assertEquals(rotation==90 || rotation==270?2:4,image.getWidth());
                    assertEquals(rotation==90 || rotation==270?4:2,image.getHeight());
                    int x=rotation==90?1:rotation==180?3:0,y=rotation==180?1:rotation==270?3:0;
                    assertEquals(0xff000000,image.getPixelReader().getArgb(x,y));return null;
                });
            }
            var oldBinding=mailbox.bind();var binding=mailbox.bind();FxTestRuntime.call(() -> null);
            long received=mailbox.receivedFrames();pushPixels(oldBinding,0,false);assertEquals(received,mailbox.receivedFrames());
            pushPixels(binding,0,true);FxTestRuntime.await(() -> mailbox.imageProperty().get()!=null);
            int color=FxTestRuntime.call(() -> mailbox.imageProperty().get().getPixelReader().getArgb(0,0));
            assertEquals(255,(color>>>24)&255);assertTrue(((color>>>16)&255)>240);assertTrue(((color>>>8)&255)<20);assertTrue((color&255)<20);
            mailbox.pause();FxTestRuntime.await(() -> mailbox.imageProperty().get()==null);
        }
    }
    static void pushPixels(VideoTrackSink sink,int rotation,boolean red) {
        var buffer=NativeI420Buffer.allocate(4,2);
        for(int row=0;row<2;row++) for(int column=0;column<4;column++) buffer.getDataY().put(row*buffer.getStrideY()+column,(byte)(red?81:column<2?16:235));
        for(int column=0;column<2;column++) {buffer.getDataU().put(column,(byte)(red?90:128));buffer.getDataV().put(column,(byte)(red?240:128));}
        // A sink callback owns and releases this reference, just like the native track callbacks.
        sink.onVideoFrame(new VideoFrame(buffer,rotation,System.nanoTime()));
    }
    @Test void healthyNativePathCanRaiseQualityAndRenderAboveTenFramesPerSecond() throws Exception {
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("native-quality"))) {
            var host=fixture.session(fixture.database.account("nativeHost","Host"));
            var guest=fixture.session(fixture.database.account("nativeGuest","Guest"));
            VideoCallController h=null,g=null;
            try {
                var room=host.create(new common.meeting.CreateRoomParams("Native quality","1234",2,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);guest.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);guest.join(false,false).get(10,TimeUnit.SECONDS);
                h=host.video();g=guest.video();h.publishPreparedCamera().get(10,TimeUnit.SECONDS);g.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                var input=new java.util.concurrent.atomic.AtomicReference<HdFrames>();
                h.startInput(() -> {var source=new HdFrames();input.set(source);return source;}).get(15,TimeUnit.SECONDS);
                Thread.sleep(21000);
                var source=input.get();var sent=h.frames(host.selfParticipantId());var received=g.frames(host.selfParticipantId());
                long capturedBefore=sent.receivedFrames(),renderedBefore=received.renderedFrames(),decodedBefore=received.receivedFrames();
                Thread.sleep(3000);
                double captureFps=(sent.receivedFrames()-capturedBefore)/3.0,renderFps=(received.renderedFrames()-renderedBefore)/3.0,decodeFps=(received.receivedFrames()-decodedBefore)/3.0;
                var history=VideoCallController.class.getDeclaredField("statsBuffer");history.setAccessible(true);
                var samples=((NetworkStatsBuffer)history.get(h)).snapshot();
                var uplink=samples.length==0?NetworkSample.Metrics.UNKNOWN:samples[samples.length-1].uplink();
                String detail="profile="+h.qualityProperty().get()+", captureFps="+captureFps+", decodeFps="+decodeFps+", renderFps="+renderFps+", uplink="+uplink;
                System.out.println("NATIVE_VIDEO_QUALITY "+detail);
                assertEquals(AdaptiveVideoPolicy.Profile.HD,h.qualityProperty().get(),detail);
                assertTrue(captureFps>=20,detail);assertTrue(decodeFps>=20,detail);assertTrue(renderFps>=18,detail);
                assertEquals(1,source.starts.get());assertEquals(0,fixture.telemetryMessages.get());
            } finally {host.close();guest.close();if(h!=null) h.closeComplete().get(20,TimeUnit.SECONDS);if(g!=null) g.closeComplete().get(20,TimeUnit.SECONDS);}
        }
    }
    @Test void autoScalesActual720pInputWithoutRestartingCaptureOrPeer() throws Exception {
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("hd"))) {
            var host=fixture.session(fixture.database.account("hdHost","Host"));var guest=fixture.session(fixture.database.account("hdGuest","Guest"));
            VideoCallController h=null,g=null;
            try {
                var room=host.create(new common.meeting.CreateRoomParams("HD","1234",2,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);guest.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);guest.join(false,false).get(10,TimeUnit.SECONDS);
                h=host.video();g=guest.video();var camera=h;var receiver=g;var input=new java.util.concurrent.atomic.AtomicReference<HdFrames>();
                h.publishPreparedCamera().get(10,TimeUnit.SECONDS);g.publishPreparedCamera().get(10,TimeUnit.SECONDS);
                long seed=System.nanoTime()+TimeUnit.MINUTES.toNanos(1);var good=new AdaptiveVideoPolicy.Evidence(40.0,5.0,0.0);var bad=new AdaptiveVideoPolicy.Evidence(700.0,70.0,8.0);
                camera.networkSample(MediaStatsSamples.evidence(seed,good,good)).get(5,TimeUnit.SECONDS);
                h.startInput(() -> {var source=new HdFrames();input.set(source);return source;}).get(15,TimeUnit.SECONDS);var source=input.get();
                var received=receiver.frames(host.selfParticipantId());
                try {FxTestRuntime.await(() -> received.imageProperty().get()!=null && received.imageProperty().get().getWidth()<=640 && received.imageProperty().get().getHeight()<=360);}
                catch(AssertionError failure) {
                    String detail=FxTestRuntime.call(() -> "received="+received.receivedFrames()+", size="+(received.imageProperty().get()==null?"none":received.imageProperty().get().getWidth()+"x"+received.imageProperty().get().getHeight())
                            +", profile="+camera.qualityProperty().get()+", status="+camera.statusProperty().get()+", stats="+camera.latestStats()+", inputFrames="+source.frame);
                    throw new AssertionError(detail,failure);
                }
                long beforeHd=received.receivedFrames();
                for(int second=1;second<=20;second++) camera.networkSample(MediaStatsSamples.evidence(seed+TimeUnit.SECONDS.toNanos(second),good,good)).get(5,TimeUnit.SECONDS);
                FxTestRuntime.await(() -> camera.qualityProperty().get()==AdaptiveVideoPolicy.Profile.HD && received.receivedFrames()>beforeHd+3 && received.imageProperty().get().getWidth()<=1280 && received.imageProperty().get().getHeight()<=720);
                long frames=received.receivedFrames();Thread.sleep(1500);assertTrue(received.receivedFrames()-frames<=55,"30 FPS capture must not turn into a 45/60 FPS stream");
                long beforeSaving=received.receivedFrames();
                for(int second=21;second<=45;second++) camera.networkSample(MediaStatsSamples.evidence(seed+TimeUnit.SECONDS.toNanos(second),bad,good)).get(5,TimeUnit.SECONDS);
                FxTestRuntime.await(() -> camera.qualityProperty().get()==AdaptiveVideoPolicy.Profile.SAVING && received.receivedFrames()>beforeSaving+3 && received.imageProperty().get().getWidth()<=320 && received.imageProperty().get().getHeight()<=180);
                assertEquals(1,source.starts.get());assertFalse(source.closed);assertEquals(0,fixture.telemetryMessages.get());
            } finally {host.close();guest.close();if(h!=null) h.closeComplete().get(20,TimeUnit.SECONDS);if(g!=null) g.closeComplete().get(20,TimeUnit.SECONDS);}
        }
    }
    static final class HdFrames implements VideoInput {
        final CustomVideoSource source=new CustomVideoSource();final ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();
        final AtomicInteger starts=new AtomicInteger();volatile boolean closed;int frame;
        final byte[] y=new byte[1280*720],uv=new byte[1280*720/4];
        HdFrames(){java.util.Arrays.fill(y,(byte)90);java.util.Arrays.fill(uv,(byte)128);}
        public int width(){return 1280;}public int height(){return 720;}public int fps(){return 30;}
        public VideoTrackSource source(){return source;}
        public void start(){starts.incrementAndGet();producer.scheduleAtFixedRate(this::frame,0,1000000000L/30,TimeUnit.NANOSECONDS);}
        synchronized void frame(){if(closed)return;var buffer=NativeI420Buffer.allocate(1280,720);try {buffer.getDataY().put(y);buffer.getDataU().put(uv);buffer.getDataV().put(uv);int column=(frame++*7)%1280;for(int row=0;row<720;row++) buffer.getDataY().put(row*buffer.getStrideY()+column,(byte)235);source.pushFrame(new VideoFrame(buffer,System.nanoTime()));}finally {buffer.release();}}
        public synchronized void close(){if(closed)return;closed=true;producer.shutdownNow();source.dispose();}
    }
}
