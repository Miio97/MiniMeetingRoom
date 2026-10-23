package server.filetransfer;

import common.filetransfer.TransferException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Leases live until the worker has closed its disk streams, not merely its socket. */
public final class RoomTransfers {
    private record Key(long room, String generation) { }
    private final Set<Lease> active = ConcurrentHashMap.newKeySet();

    public Lease register(long room, String generation, Runnable abort) {
        Lease lease = new Lease(new Key(room,generation),abort,Thread.currentThread());
        active.add(lease);
        return lease;
    }
    /** Admission holds the DB room lock; this runs after close commits. No waiting or DB I/O. */
    public void cancel(long room, String generation) {
        for (Lease lease : active) if (lease.key.equals(new Key(room,generation))) lease.cancel();
    }
    public boolean pending(long room) {return active.stream().anyMatch(lease -> lease.key.room==room);}
    public int size() {return active.size();}
    /** Called under RAM room authority: only mark/enqueue; never close a socket under that lock. */
    public void closeGeneration(String generation,java.util.function.Consumer<Runnable> enqueue) {
        for(Lease lease:active) if(lease.key.generation.equals(generation) && lease.closing.compareAndSet(false,true)) {
            lease.cancelled=true;enqueue.accept(lease::abort);
        }
    }

    public final class Lease implements AutoCloseable {
        private final Key key;
        private final Runnable abort;
        private final Thread worker;
        private volatile boolean cancelled;
        private boolean released;
        private final java.util.concurrent.atomic.AtomicBoolean closing=new java.util.concurrent.atomic.AtomicBoolean();
        private Lease(Key key,Runnable abort,Thread worker) {this.key=key;this.abort=abort;this.worker=worker;}
        public void check() throws TransferException {
            if(cancelled) throw new TransferException("ROOM_CLOSED","Phòng đã đóng, tác vụ truyền tệp đã dừng.");
        }
        private synchronized void cancel() {
            if(released) return;
            cancelled=true;
            if(closing.compareAndSet(false,true)) abort();
        }
        private synchronized void abort() {
            if(released) return;
            // Socket close releases blocked TLS writes/reads. Disk streams close in worker finally.
            // Interrupt also stops checksum/prefix loops. The lease remains until finally finishes.
            worker.interrupt();abort.run();
        }
        @Override public synchronized void close() {released=true;active.remove(this);}
    }
}
