package server.signalingapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.signaling.SignalException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Ephemeral content only. Call under RoomService.withChatMember to fence room removal. */
final class RoomChatStore implements AutoCloseable {
    static final int MAX_ROOM_MESSAGES = 500;
    private record Key(long session, String room, String nonce) { }
    private record Message(long id, long userId, String displayName, String content, Instant sentAt) {
        JsonObject json() {
            var value=new JsonObject();value.addProperty("id",id);value.addProperty("userId",userId);
            value.addProperty("displayName",displayName);value.addProperty("content",content);value.addProperty("sentAt",sentAt.toString());return value;
        }
    }
    private record Receipt(String content, JsonObject ack, long at) { }
    private static final class Buffer { final ArrayDeque<Message> messages=new ArrayDeque<>(); }
    private final ConcurrentHashMap<String,Buffer> buffers=new ConcurrentHashMap<>();
    private final HashMap<Key,Receipt> receipts=new HashMap<>();
    private final AtomicLong nextMessageId=new AtomicLong(1);
    private final java.util.function.LongSupplier timer;
    private final ReentrantReadWriteLock lifecycle=new ReentrantReadWriteLock();
    private boolean closed;
    RoomChatStore() {this(System::nanoTime);}
    RoomChatStore(java.util.function.LongSupplier timer) {this.timer=timer;}

    @FunctionalInterface private interface Operation<T> { T run(Buffer buffer) throws SignalException; }
    private <T> T inRoom(String room, Operation<T> operation) throws SignalException {
        lifecycle.readLock().lock();
        try {
            if(closed) throw new SignalException("CONNECTION_CLOSED");
            Buffer buffer=buffers.computeIfAbsent(room,id -> new Buffer());
            synchronized(buffer) { return operation.run(buffer); }
        } finally { lifecycle.readLock().unlock(); }
    }

    JsonObject send(String room,long session,String nonce,String content,long userId,String displayName,
            Instant sentAt,BooleanSupplier allowed,Consumer<JsonObject> broadcast) throws SignalException {
        return inRoom(room,buffer -> {
            Key key=new Key(session,room,nonce);long now=timer.getAsLong();JsonObject ack;
            synchronized(receipts) {
                receipts.entrySet().removeIf(entry -> now-entry.getValue().at()>TimeUnit.MINUTES.toNanos(10));
                Receipt previous=receipts.get(key);
                if(previous!=null) {
                    if(!previous.content().equals(content)) throw new SignalException("REQUEST_ID_REUSED");
                    return previous.ack().deepCopy();
                }
                if(receipts.size()>=1024) throw new SignalException("CHAT_BUSY");
                if(!allowed.getAsBoolean()) throw new SignalException("RATE_LIMIT");
                long id=nextMessageId.getAndIncrement();
                var message=new Message(id,userId,displayName,content,sentAt);
                buffer.messages.addLast(message);
                if(buffer.messages.size()>MAX_ROOM_MESSAGES) buffer.messages.removeFirst();
                ack=new JsonObject();ack.addProperty("ok",true);ack.addProperty("id",id);
                ack.addProperty("clientMessageId",nonce);ack.addProperty("sentAt",sentAt.toString());
                receipts.put(key,new Receipt(content,ack,now));
                // Admission is global and bounded; socket enqueue happens after releasing its monitor.
            }
            var message=buffer.messages.getLast().json();message.addProperty("clientMessageId",nonce);
            broadcast.accept(message);
            return ack.deepCopy();
        });
    }

    JsonArray history(String room,Long before,int limit) throws SignalException {
        return inRoom(room,buffer -> {
            var latest=new ArrayList<Message>();var descending=buffer.messages.descendingIterator();
            while(descending.hasNext() && latest.size()<limit) {
                var message=descending.next();if(before==null || message.id()<before) latest.add(message);
            }
            Collections.reverse(latest);var result=new JsonArray();latest.forEach(message -> result.add(message.json()));return result;
        });
    }
    JsonArray status(String room,long session,JsonArray ids) throws SignalException {
        return inRoom(room,buffer -> {
            var result=new JsonArray();long now=timer.getAsLong();
            synchronized(receipts) {
                receipts.entrySet().removeIf(entry -> now-entry.getValue().at()>TimeUnit.MINUTES.toNanos(10));
                for(var id:ids) {var receipt=receipts.get(new Key(session,room,id.getAsString()));if(receipt!=null) result.add(receipt.ack().deepCopy());}
            }
            return result;
        });
    }
    void removeRoom(String room) {
        lifecycle.readLock().lock();
        try {
            Buffer buffer=buffers.remove(room);
            if(buffer!=null) synchronized(buffer) {
                buffer.messages.clear();synchronized(receipts) {receipts.keySet().removeIf(key -> key.room().equals(room));}
            }
        } finally { lifecycle.readLock().unlock(); }
    }
    int roomCount() { return buffers.size(); }
    int receiptCount() { synchronized(receipts) { return receipts.size(); } }
    @Override public void close() {
        lifecycle.writeLock().lock();
        try {closed=true;buffers.clear();synchronized(receipts) {receipts.clear();}}
        finally {lifecycle.writeLock().unlock();}
    }
}
