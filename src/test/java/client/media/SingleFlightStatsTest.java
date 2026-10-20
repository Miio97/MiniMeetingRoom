package client.media;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import dev.onvoid.webrtc.RTCStatsReport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SingleFlightStatsTest {
    @Test void timeoutDoesNotFreeNativeSlotAndLateCallbackCannotReplaceACompletedResult() {
        var gate=new SingleFlightStats();var calls=new AtomicInteger();var callback=new AtomicReference<Consumer<RTCStatsReport>>();
        var first=gate.request(done -> {calls.incrementAndGet();callback.set(done);});first.complete(null);
        for(int i=0;i<1000;i++) assertNull(gate.request(done -> calls.incrementAndGet()).join());assertEquals(1,calls.get());
        callback.get().accept(null);var second=gate.request(done -> {calls.incrementAndGet();done.accept(null);});assertNull(second.join());assertEquals(2,calls.get());
        gate.close();assertNull(gate.request(done -> calls.incrementAndGet()).join());assertEquals(2,calls.get());
    }
}
