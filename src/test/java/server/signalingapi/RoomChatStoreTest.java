package server.signalingapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.signaling.SignalException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomChatStoreTest {
    private JsonObject send(RoomChatStore store,String room,String nonce) throws Exception {
        return store.send(room,1,nonce,"text-"+nonce,1,"Host",Instant.EPOCH,() -> true,ignored -> {});
    }
    @Test void boundedHistoryIsAscendingAndBeforeIdPagesWithoutCrossRoomContent() throws Exception {
        try(var store=new RoomChatStore()) {
            for(int i=1;i<=510;i++) send(store,"a","n"+i);
            send(store,"b","private");
            var history=store.history("a",null,500);assertEquals(500,history.size());
            assertEquals(11,history.get(0).getAsJsonObject().get("id").getAsLong());
            assertEquals(510,history.get(499).getAsJsonObject().get("id").getAsLong());
            var page=store.history("a",500L,50);assertEquals(50,page.size());
            assertEquals(450,page.get(0).getAsJsonObject().get("id").getAsLong());
            assertEquals(499,page.get(49).getAsJsonObject().get("id").getAsLong());
            assertEquals(0,store.history("a",11L,50).size());assertEquals(1,store.history("b",null,50).size());
        }
    }
    @Test void retryAndStatusAreSessionScopedAndReceiptsRemainAfterHistoryEviction() throws Exception {
        try(var store=new RoomChatStore()) {
            var pushes=new AtomicInteger();
            var first=store.send("a",1,"nonce","text",1,"Host",Instant.EPOCH,() -> true,ignored -> pushes.incrementAndGet());
            var repeated=store.send("a",1,"nonce","text",1,"Host",Instant.EPOCH,() -> {fail("Retry must not consume rate tokens");return false;},ignored -> fail("No duplicate push"));
            assertEquals(first,repeated);assertEquals(1,pushes.get());
            var failure=assertThrows(SignalException.class,() -> store.send("a",1,"nonce","changed",1,"Host",Instant.EPOCH,() -> true,ignored -> {}));
            assertEquals("REQUEST_ID_REUSED",failure.code());
            for(int i=0;i<500;i++) send(store,"a","new"+i);
            var ids=new JsonArray();ids.add("nonce");assertEquals(1,store.status("a",1,ids).size());assertEquals(0,store.status("a",2,ids).size());
            assertEquals(first,store.send("a",1,"nonce","text",1,"Host",Instant.EPOCH,() -> true,ignored -> fail("Eviction must not permit duplicate resend")));
        }
    }
    @Test void receiptCapRateRejectionExpirationAndRoomRemovalReleaseMemory() throws Exception {
        var nanos=new AtomicLong();
        try(var store=new RoomChatStore(nanos::get)) {
            assertEquals("RATE_LIMIT",assertThrows(SignalException.class,() -> store.send("a",1,"limited","text",1,"Host",Instant.EPOCH,() -> false,ignored -> {})).code());
            assertEquals(0,store.receiptCount());
            for(int i=0;i<1024;i++) send(store,i%2==0?"a":"b","n"+i);
            assertEquals(1024,store.receiptCount());assertEquals("CHAT_BUSY",assertThrows(SignalException.class,() -> send(store,"b","full")).code());
            store.removeRoom("a");assertEquals(1,store.roomCount());assertEquals(512,store.receiptCount());
            assertEquals(0,store.history("fresh-opaque-id",null,50).size());
            nanos.set(TimeUnit.MINUTES.toNanos(10)+1);send(store,"b","after-expiry");assertEquals(1,store.receiptCount());
            store.close();assertEquals(0,store.roomCount());assertEquals(0,store.receiptCount());
            assertEquals("CONNECTION_CLOSED",assertThrows(SignalException.class,() -> send(store,"a","closed")).code());
        }
    }
    @Test void concurrentSameRoomSendsAllocateAscendingIdsAndBroadcastExactlyOnce() throws Exception {
        try(var store=new RoomChatStore();var pool=Executors.newFixedThreadPool(8)) {
            var pushed=new ArrayList<Long>();var futures=new ArrayList<Future<?>>();
            for(int i=0;i<80;i++) {String nonce="n"+i;futures.add(pool.submit(() -> {
                try {store.send("a",1,nonce,"text",1,"Host",Instant.EPOCH,() -> true,body -> pushed.add(body.get("id").getAsLong()));}
                catch(Exception failure) {throw new CompletionException(failure);}
            }));}
            for(var future:futures) future.get(5,TimeUnit.SECONDS);
            assertEquals(80,pushed.size());assertEquals(List.copyOf(pushed).stream().sorted().toList(),pushed);
            assertEquals(80,pushed.stream().distinct().count());
            assertEquals(80,store.history("a",null,100).size());
        }
    }
}
