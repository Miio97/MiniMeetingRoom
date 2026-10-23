package server.signalingapi;

import client.signalingapi.SignalingClient;
import com.google.gson.JsonObject;
import common.signaling.SignalMessage;
import common.signaling.SignalWire;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.auth.SessionValidator;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Real TLS dispatch: a slow camera command must not block audio or room control. */
@Timeout(30)
class MediaDispatchTest {
    @TempDir static Path temporary;
    static RoomTestFixture.Tls tls;
    @BeforeAll static void tls() throws Exception {tls=RoomTestFixture.certificates(temporary);}

    @Test void independentKindsProceedButSameKindKeepsOrder() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        var stops=new AtomicInteger();
        try(var fixture=new RoomTestFixture(tls);
            var server=new SignalingServer(tls.server(),new InetSocketAddress("127.0.0.1",0),
                new SessionValidator(fixture::connection,Clock.systemUTC()),new SignalingServer.Handler() {
                    public SignalMessage handle(SignalingServer.Connection connection,SignalMessage request) throws Exception {
                        if(request.type().equals("VIDEO_PUBLISH")) {started.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));}
                        if(request.type().equals("VIDEO_STOP")) stops.incrementAndGet();
                        return connection.message("REPLY",request.requestId(),request.roomId(),null,new JsonObject());
                    }
                    public void disconnected(SignalingServer.Connection connection,String reason) { }
                },"media-dispatch-test",SignalingServer.Options.defaults())) {
            server.start();
            try(var client=new SignalingClient(new SignalingClient.Settings("localhost",server.port(),null,SignalWire.DEFAULT_MAX_BYTES),tls.client(),ignored -> {})) {
                client.connect(fixture.account("mediaorder","Media order").token());
                var camera=client.request("VIDEO_PUBLISH","room",new JsonObject());
                assertTrue(started.await(3,TimeUnit.SECONDS));
                var stop=client.request("VIDEO_STOP","room",new JsonObject());
                try {
                    assertNotNull(client.request("AUDIO_STATE","room",new JsonObject()).get(2,TimeUnit.SECONDS));
                    assertNotNull(client.request("VIDEO_STATE","room",new JsonObject()).get(2,TimeUnit.SECONDS));
                    assertNotNull(client.request("GET_ROOM","room",new JsonObject()).get(2,TimeUnit.SECONDS));
                    assertFalse(stop.isDone());assertEquals(0,stops.get());
                } finally {release.countDown();}
                camera.get(3,TimeUnit.SECONDS);stop.get(3,TimeUnit.SECONDS);assertEquals(1,stops.get());
            }
        } finally {release.countDown();}
    }

    @Test void queuedMediaIsCancelledOnDisconnectInsteadOfExecutingStaleWork() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var stops=new AtomicInteger();
        try(var fixture=new RoomTestFixture(tls);
            var server=new SignalingServer(tls.server(),new InetSocketAddress("127.0.0.1",0),
                new SessionValidator(fixture::connection,Clock.systemUTC()),new SignalingServer.Handler() {
                    public SignalMessage handle(SignalingServer.Connection connection,SignalMessage request) throws Exception {
                        if(request.type().equals("VIDEO_PUBLISH")) {started.countDown();release.await(5,TimeUnit.SECONDS);}
                        if(request.type().equals("VIDEO_STOP")) stops.incrementAndGet();
                        return connection.message("REPLY",request.requestId(),request.roomId(),null,new JsonObject());
                    }
                    public void disconnected(SignalingServer.Connection connection,String reason) { }
                },"media-cancel-test",SignalingServer.Options.defaults())) {
            server.start();
            var client=new SignalingClient(new SignalingClient.Settings("localhost",server.port(),null,SignalWire.DEFAULT_MAX_BYTES),tls.client(),ignored -> {});
            try {
                client.connect(fixture.account("mediacancel","Media cancel").token());
                client.request("VIDEO_PUBLISH","room",new JsonObject());assertTrue(started.await(3,TimeUnit.SECONDS));
                var stop=client.request("VIDEO_STOP","room",new JsonObject());
                client.request("AUDIO_STATE","room",new JsonObject()).get(2,TimeUnit.SECONDS);
                client.close();assertThrows(ExecutionException.class,() -> stop.get(2,TimeUnit.SECONDS));
                Thread.sleep(100);release.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(!server.lifecycleState().startsWith("registered=0 ") && System.nanoTime()<deadline) Thread.sleep(20);
                assertTrue(server.lifecycleState().startsWith("registered=0 "),server.lifecycleState());assertEquals(0,stops.get());
            } finally {client.close();release.countDown();}
        } finally {release.countDown();}
    }
}
