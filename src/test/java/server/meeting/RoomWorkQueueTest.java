package server.meeting;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RoomWorkQueueTest {
    @Test void sameRoomIsOrderedOtherRoomCanProgressAndOverflowIsRejected() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var second=new CountDownLatch(1);var independent=new CountDownLatch(1);
        try(var queue=new RoomWorkQueue("bounded-room-test",2,1)) {
            try {
                queue.execute("a",() -> {entered.countDown();try {release.await(5,TimeUnit.SECONDS);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}});
                assertTrue(entered.await(2,TimeUnit.SECONDS));queue.execute("a",second::countDown);
                assertThrows(RejectedExecutionException.class,() -> queue.execute("a",() -> {}));
                queue.execute("b",independent::countDown);assertTrue(independent.await(2,TimeUnit.SECONDS));assertEquals(1,second.getCount());
            } finally {release.countDown();}
            assertTrue(second.await(2,TimeUnit.SECONDS));
        }
    }
}
