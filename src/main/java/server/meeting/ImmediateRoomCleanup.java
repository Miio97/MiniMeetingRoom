package server.meeting;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import server.auth.ConnectionProvider;
import server.filetransfer.FileMetadataRepository.RetiredFile;

/** Bounded per-room turns. Closed owned rows are the durable backlog after overflow/restart. */
public final class ImmediateRoomCleanup implements AutoCloseable,RoomReopenGuard {
    @FunctionalInterface public interface FilesCleanup {void run(long roomId) throws Exception;}
    @FunctionalInterface public interface PendingFiles {boolean test(long roomId) throws Exception;}
    @FunctionalInterface public interface BatchFilesCleanup {List<RetiredFile> run(RoomCleanupStore.Fence fence,List<RetiredFile> files) throws Exception;}
    @FunctionalInterface public interface CancelTransfers {boolean cancelAndDrained(RoomCleanupStore.Fence fence) throws Exception;}
    public enum Phase {CANCELLING,CLEANING,RETRY}
    private static final int CAPACITY=256;
    private static final class Work {
        final long enqueued=System.nanoTime();final AtomicBoolean running=new AtomicBoolean();
        volatile long after,cancellingSince;volatile int failures;volatile Phase phase=Phase.CANCELLING;
        boolean transfersDrained;
    }
    private final ConnectionProvider connections;
    private final RoomCleanupStore store;
    private final String instance;
    private final BatchFilesCleanup files;
    private final PendingFiles pendingFiles;
    private final CancelTransfers transfers;
    private final Runnable changed;
    private final ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1,Thread.ofPlatform().daemon().name("room-cleanup-dispatch").factory());
    private final ThreadPoolExecutor workers;
    private final Semaphore dbBudget;
    private final ConcurrentHashMap<Long,Work> pending=new ConcurrentHashMap<>();
    private final Map<Long,String> verified=Collections.synchronizedMap(new LinkedHashMap<>());
    private final AtomicBoolean queued=new AtomicBoolean(),closed=new AtomicBoolean();
    private long cursor,nextScan;
    private boolean recovering=true;
    private boolean legacyFilesCallback;
    /** Explicit JDBC reference mode for existing isolated tests. */
    public ImmediateRoomCleanup(ConnectionProvider connections,String instance,FilesCleanup files,PendingFiles pendingFiles,Runnable changed) {
        this(connections,instance,false,2,(f,b) -> {files.run(f.roomId());return List.of();},pendingFiles,f -> true,changed);
        legacyFilesCallback=true;
    }
    public ImmediateRoomCleanup(ConnectionProvider connections,String instance,boolean routines,int concurrency,BatchFilesCleanup files,
            PendingFiles pendingFiles,CancelTransfers transfers,Runnable changed) {
        if(concurrency<1 || concurrency>2) throw new IllegalArgumentException("CLEANUP_CONCURRENCY");
        this.connections=connections;this.instance=instance;this.files=files;this.pendingFiles=pendingFiles;this.transfers=transfers;this.changed=changed;
        store=new RoomCleanupStore(connections,routines);dbBudget=new Semaphore(concurrency);
        workers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),Thread.ofPlatform().daemon().name("room-content-cleanup-",0).factory());
        timer.setRemoveOnCancelPolicy(true);timer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }
    public void start() {timer.scheduleWithFixedDelay(this::tick,0,250,TimeUnit.MILLISECONDS);}
    public boolean isPending(long roomId) {return pending.containsKey(roomId);}
    /** Catalog invokes this only for closed rooms. Reverify an evicted certificate on demand. */
    public boolean isVerified(long roomId) {
        if(!verified.containsKey(roomId) && !pending.containsKey(roomId)) request(roomId);
        return verified.containsKey(roomId) && !pending.containsKey(roomId);
    }
    public boolean isVerified(long roomId,String generation) {
        if(!Objects.equals(generation,verified.get(roomId)) && !pending.containsKey(roomId)) request(roomId);
        return generation!=null && generation.equals(verified.get(roomId)) && !pending.containsKey(roomId);
    }
    public String phase(long roomId) {var work=pending.get(roomId);return work==null?"":work.phase.name();}
    public int activeCount() {return workers.getActiveCount();}
    public int queueSize() {return workers.getQueue().size();}
    public int pendingCount() {return pending.size();}
    public boolean isTerminated() {return workers.isTerminated() && timer.isTerminated();}
    public void request(long roomId) {
        if(closed.get()) return;
        if(roomId>0) {verified.remove(roomId);add(roomId);changed.run();}
        if(queued.compareAndSet(false,true)) try {timer.execute(() -> {queued.set(false);tick();});} catch(RejectedExecutionException e) {queued.set(false);}
    }
    private void add(long roomId) {
        synchronized(pending) {if(pending.size()<CAPACITY) pending.putIfAbsent(roomId,new Work());}
    }
    @Override public void check(Connection connection,long roomId) throws SQLException {
        if(pending.containsKey(roomId)) throw new RoomException("ROOM_CLEANING");
        if(!owned(connection,roomId)) throw new RoomException("ROOM_CLEANUP_OWNER_REQUIRED");
        RoomReopenGuard.requireEmpty(connection,roomId);
        try(var s=connection.prepareStatement("SELECT Room_Opaque_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=?")) {
            s.setLong(1,roomId);try(var r=s.executeQuery()) {
                if(!r.next() || !Objects.equals(r.getString(1),verified.get(roomId))) {request(roomId);throw new RoomException("ROOM_CLEANING");}
            }
        }
    }
    private void tick() {
        if(closed.get()) return;
        long now=System.nanoTime();
        if(now>=nextScan && dbBudget.tryAcquire()) {
            try {scan();nextScan=now+TimeUnit.SECONDS.toNanos(2);}
            catch(Exception failure) {nextScan=now+TimeUnit.SECONDS.toNanos(4);common.diagnostics.Diagnostics.current().failure("room.cleanup.scan_failed",failure,Map.of("phase","scan"));}
            finally {dbBudget.release();}
        }
        var eligible=new ArrayList<>(pending.entrySet());eligible.sort(Comparator.comparingLong(e -> e.getValue().after));
        for(var entry:eligible) {
            Work work=entry.getValue();if(now<work.after || !work.running.compareAndSet(false,true)) continue;
            try {workers.execute(() -> turn(entry.getKey(),work));}
            catch(RejectedExecutionException full) {work.running.set(false);break;}
        }
    }
    private void scan() throws Exception {
        var rooms=new ArrayList<Long>();var needsWork=new HashSet<Long>();var messages=new HashSet<Long>();
        try(var c=connections.getConnection();var s=c.prepareStatement("SELECT p.Ma_Phong,x.Room_Opaque_ID,EXISTS(SELECT 1 FROM Tin_Nhan t WHERE t.Ma_Phong=p.Ma_Phong) FROM Phong_Hop p JOIN MMR_Phong_Van_Hanh x ON x.Ma_Phong=p.Ma_Phong WHERE p.Trang_Thai='DA_DONG' AND x.Instance_ID=? AND p.Ma_Phong>? ORDER BY p.Ma_Phong LIMIT 32")) {
            s.setString(1,instance);s.setLong(2,cursor);try(var r=s.executeQuery()) {while(r.next()) {cursor=r.getLong(1);rooms.add(cursor);if(r.getBoolean(3)) messages.add(cursor);if(r.getBoolean(3) || !Objects.equals(r.getString(2),verified.get(cursor))) needsWork.add(cursor);}}
        }
        boolean firstPass=recovering;
        if(rooms.size()<32) {cursor=0;recovering=false;}
        // Verify even an empty closed room after restart. Never inspect disk under the reopen row lock.
        for(long room:needsWork) {
            if(firstPass) {add(room);continue;}
            // Certificate eviction alone must not continuously reclean empty history rooms.
            try {if(messages.contains(room) || pendingFiles.test(room)) add(room);} catch(Exception failure) {add(room);}
        }
    }
    private void turn(long room,Work work) {
        if(closed.get() || !dbBudget.tryAcquire()) {work.running.set(false);return;}
        try {
            common.diagnostics.Diagnostics.current().event("room.cleanup.queue",Map.of("room_id",room,"queue_ms",(System.nanoTime()-work.enqueued)/1e6,"worker_count",workers.getActiveCount(),"queue_count",workers.getQueue().size()));
            if(cleanTurn(room,work)) {
                pending.remove(room,work);changed.run();
                common.diagnostics.Diagnostics.current().event("room.cleanup.completed",Map.of("room_id",room,"duration_ms",(System.nanoTime()-work.enqueued)/1e6));
            } else {work.failures=0;work.after=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(10);}
        } catch(Exception failure) {
            String failedPhase=work.phase.name();
            if(work.failures<Integer.MAX_VALUE) work.failures++;long delay=retrySeconds(work.failures);work.after=System.nanoTime()+TimeUnit.SECONDS.toNanos(delay);work.phase=Phase.RETRY;changed.run();
            String code=failure instanceof SQLException s && s.getErrorCode()==1305?"CLEANUP_PROCEDURE_MISSING":failure instanceof RoomException r?r.code():failure instanceof common.filetransfer.TransferException t?t.code():failure.getClass().getSimpleName();
            if(failure instanceof java.io.IOException && java.util.Set.of("TRANSFER_DRAIN_TIMEOUT","FILE_STORAGE_UNAVAILABLE","INVALID_ROOM_OBJECT","UPLOAD_STILL_ACTIVE").contains(failure.getMessage())) code=failure.getMessage();
            common.diagnostics.Diagnostics.current().failure("room.cleanup.failed",failure,Map.of("room_id",room,"phase",failedPhase,"code",code,"attempt",work.failures,"retry_seconds",delay));
        } finally {dbBudget.release();work.running.set(false);}
    }
    public static long retrySeconds(int failures) {return 1L<<Math.min(6,Math.max(1,failures));}
    public boolean clean(long roomId) throws Exception {
        add(roomId);Work work=pending.get(roomId);if(work==null) return false;
        if(!work.running.compareAndSet(false,true)) return false;
        if(!dbBudget.tryAcquire()) {work.running.set(false);return false;}
        try {boolean done=cleanTurn(roomId,work);if(done) pending.remove(roomId,work);return done;}
        finally {dbBudget.release();work.running.set(false);}
    }
    private boolean cleanTurn(long roomId,Work work) throws Exception {
        var fence=store.capture(roomId,instance);if(fence==null) return true;
        long at=System.nanoTime();
        if(!work.transfersDrained) {
            if(work.phase!=Phase.CANCELLING) {work.phase=Phase.CANCELLING;changed.run();}
            if(work.cancellingSince==0) {work.cancellingSince=at;changed.run();}
            if(!transfers.cancelAndDrained(fence)) {
                if(at-work.cancellingSince>TimeUnit.SECONDS.toNanos(5)) throw new java.io.IOException("TRANSFER_DRAIN_TIMEOUT");
                return false;
            }
            work.transfersDrained=true;
            RoomCleanupStore.metric("room.cleanup.cancel",fence,Map.of("duration_ms",(System.nanoTime()-work.cancellingSince)/1e6));
        }
        if(work.phase!=Phase.CLEANING) {work.phase=Phase.CLEANING;changed.run();}
        var batch=legacyFilesCallback?List.<RetiredFile>of():store.batch(fence);at=System.nanoTime();var deleted=files.run(fence,batch);
        RoomCleanupStore.metric("room.cleanup.disk",fence,Map.of("duration_ms",(System.nanoTime()-at)/1e6,"file_count",deleted.size()));
        if(closed.get()) return false;
        boolean empty=store.commit(fence,deleted);
        boolean complete=empty && !pendingFiles.test(roomId);
        if(complete) synchronized(verified) {verified.put(roomId,fence.generation());while(verified.size()>CAPACITY) verified.remove(verified.keySet().iterator().next());}
        return complete;
    }
    private boolean owned(Connection c,long room) throws SQLException {
        try(var s=c.prepareStatement("SELECT Instance_ID FROM MMR_Phong_Van_Hanh WHERE Ma_Phong=?")) {s.setLong(1,room);try(var r=s.executeQuery()) {return r.next() && instance.equals(r.getString(1));}}
    }
    @Override public void close() {if(closed.compareAndSet(false,true)) {
        timer.shutdownNow();workers.shutdownNow();
        try {workers.awaitTermination(5,TimeUnit.SECONDS);} catch(InterruptedException e) {Thread.currentThread().interrupt();}
        // Retain fences if a caller still references the guard during shutdown.
    }}
}
