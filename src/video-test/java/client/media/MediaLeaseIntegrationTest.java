package client.media;

import common.meeting.CreateRoomParams;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Removing client polling must not make an authenticated SFU lease expire. */
@Timeout(75)
public class MediaLeaseIntegrationTest {
    @TempDir static Path temporary;
    static RoomTestFixture.Tls tls;
    @BeforeAll static void runtime() throws Exception {FxTestRuntime.start();tls=RoomTestFixture.certificates(temporary);}

    @Test void frequentRoomValidationStillRenewsLeaseWithoutClientMediaPolling() throws Exception {
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("renewal"));
            var host=fixture.session(fixture.database.account("leaseHost","Host"));
            var guest=fixture.session(fixture.database.account("leaseGuest","Guest"))) {
            host.create(new CreateRoomParams("Lease renewal","1234",2,false)).get(8,TimeUnit.SECONDS);
            host.join(false,false).get(8,TimeUnit.SECONDS);
            guest.lookup(host.snapshot().joinCode(),"1234").get(8,TimeUnit.SECONDS);
            guest.join(false,false).get(8,TimeUnit.SECONDS);
            var camera=host.video();var receiver=guest.video();
            camera.publishPreparedCamera().get(8,TimeUnit.SECONDS);receiver.publishPreparedCamera().get(8,TimeUnit.SECONDS);
            FxTestRuntime.call(() -> camera.toggleInput(NativeMediaReuseTest.SyntheticInput::new,false)).get(12,TimeUnit.SECONDS);
            FxTestRuntime.await(() -> receiver.frames(host.selfParticipantId()).receivedFrames()>3);
            long before=receiver.frames(host.selfParticipantId()).receivedFrames();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(18);
            while(System.nanoTime()<until) {
                host.refresh().get(3,TimeUnit.SECONDS);guest.refresh().get(3,TimeUnit.SECONDS);
                Thread.sleep(250);
            }
            assertTrue(receiver.frames(host.selfParticipantId()).receivedFrames()>before+30,
                    "Media must survive beyond the SFU 15-second lease while GET_ROOM keeps validation fresh");
            var sources=fixture.engine.sources(0);
            assertTrue(sources.getAsJsonObject("rooms").getAsJsonObject(host.snapshot().roomId())
                    .getAsJsonObject("sources").getAsJsonObject("VIDEO").has(host.selfParticipantId()));
        }
    }
}
