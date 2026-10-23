package server.meeting;

import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ImmediateRoomCleanupTest {
    String url;
    Connection connection() throws SQLException {return DriverManager.getConnection(url);}
    void sql(String sql) throws SQLException {try(var c=connection();var s=c.createStatement()) {s.execute(sql);}}
    int count() throws SQLException {try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM Tin_Nhan")) {r.next();return r.getInt(1);}}
    @BeforeEach void setup() throws Exception {
        url="jdbc:h2:mem:cleanup_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        sql("CREATE TABLE Phong_Hop(Ma_Phong BIGINT PRIMARY KEY,Trang_Thai VARCHAR(30),Ngay_Dong TIMESTAMP)");
        sql("CREATE TABLE MMR_Phong_Van_Hanh(Ma_Phong BIGINT PRIMARY KEY,Instance_ID VARCHAR(64),Room_Opaque_ID VARCHAR(24) DEFAULT 'test-generation')");
        sql("CREATE TABLE Tin_Nhan(Ma_Tin_Nhan BIGINT PRIMARY KEY,Ma_Phong BIGINT)");
        sql("CREATE TABLE Tep_Dinh_Kem(Ma_Tep BIGINT PRIMARY KEY,Ma_Tin_Nhan BIGINT REFERENCES Tin_Nhan(Ma_Tin_Nhan),Duong_Dan_Luu_Tru VARCHAR(500))");
        sql("INSERT INTO Phong_Hop VALUES(1,'DA_DONG',CURRENT_TIMESTAMP),(2,'DANG_HOAT_DONG',NULL)");
        sql("INSERT INTO MMR_Phong_Van_Hanh(Ma_Phong,Instance_ID) VALUES(1,'local'),(2,'local')");
        sql("INSERT INTO Tin_Nhan VALUES(1,1),(2,2)");
    }
    @Test void immediatelyCleansOnlyClosedContentAndDoesNotRequireTwentyMinutes() throws Exception {
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {},id -> false,() -> {})) {
            try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
            assertTrue(cleanup.clean(1));assertEquals(1,count());
            try(var c=connection()) {assertDoesNotThrow(() -> cleanup.check(c,1));}
            assertTrue(cleanup.clean(2));assertEquals(1,count());assertTrue(cleanup.clean(1));
        }
    }
    @Test void filesAndPendingUploadsMustBeRemovedBeforeMessagesAndReopen() throws Exception {
        sql("INSERT INTO Tep_Dinh_Kem(Ma_Tep,Ma_Tin_Nhan) VALUES(1,1)");var pending=new AtomicBoolean(true);
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {},id -> pending.get(),() -> {})) {
            assertFalse(cleanup.clean(1));assertEquals(2,count());
            sql("DELETE FROM Tep_Dinh_Kem");assertFalse(cleanup.clean(1));
            pending.set(false);assertTrue(cleanup.clean(1));assertEquals(1,count());
        }
    }
    @Test void restartScanResumesClosedRoomsButDoesNotTouchAnotherInstance() throws Exception {
        sql("INSERT INTO Phong_Hop VALUES(3,'DA_DONG',CURRENT_TIMESTAMP)");sql("INSERT INTO MMR_Phong_Van_Hanh(Ma_Phong,Instance_ID) VALUES(3,'other')");sql("INSERT INTO Tin_Nhan VALUES(3,3)");
        sql("INSERT INTO Phong_Hop VALUES(4,'DA_DONG',CURRENT_TIMESTAMP)");sql("INSERT INTO Tin_Nhan VALUES(4,4)");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var changed=new CountDownLatch(1);
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));},id -> false,changed::countDown)) {
            cleanup.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
            try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
            release.countDown();assertTrue(changed.await(5,TimeUnit.SECONDS));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(cleanup.isPending(1) && System.nanoTime()<until) Thread.sleep(5);
            assertEquals(3,count());
            assertEquals("ROOM_CLEANUP_OWNER_REQUIRED",assertThrows(RoomException.class,() -> cleanup.clean(3)).code());
            try(var c=connection()) {assertEquals("ROOM_CLEANUP_OWNER_REQUIRED",assertThrows(RoomException.class,() -> cleanup.check(c,4)).code());}
            try(var c=connection()) {assertDoesNotThrow(() -> cleanup.check(c,1));}
        }
    }
    @Test void diskFailureNeverReportsReadyAndCanBeRetried() throws Exception {
        var fail=new AtomicBoolean(true);
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {if(fail.get()) throw new java.io.IOException("test disk failure");},id -> false,() -> {})) {
            assertThrows(java.io.IOException.class,() -> cleanup.clean(1));assertEquals(2,count());
            try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
            fail.set(false);assertTrue(cleanup.clean(1));
        }
    }
    @Test void cleanupUsesBatchesAndCannotDeclareReadyBetweenBatches() throws Exception {
        try(var c=connection();var insert=c.prepareStatement("INSERT INTO Tin_Nhan VALUES(?,1)")) {
            for(int id=10;id<1010;id++) {insert.setInt(1,id);insert.addBatch();}insert.executeBatch();
        }
        long started=System.nanoTime();
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {},id -> false,() -> {})) {
            assertFalse(cleanup.clean(1));try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
            assertFalse(cleanup.clean(1));assertTrue(cleanup.clean(1));assertEquals(1,count());
        }
        System.out.println("CLEANUP_BENCHMARK messages=1001 batches=3 elapsedMs="+(System.nanoTime()-started)/1e6);
    }
    @Test void cancellationMustDrainBeforeDiskAndAllReopensRemainFenced() throws Exception {
        var drained=new AtomicBoolean();var calls=new AtomicInteger();
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",false,2,(f,b) -> {calls.incrementAndGet();return b;},id -> false,f -> drained.get(),() -> {})) {
            assertFalse(cleanup.clean(1));assertEquals("CANCELLING",cleanup.phase(1));assertEquals(0,calls.get());
            try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
            drained.set(true);assertTrue(cleanup.clean(1));assertEquals(1,calls.get());
            try(var c=connection()) {assertDoesNotThrow(() -> cleanup.check(c,1));}
        }
    }
    @Test void commitRejectsChangedOwnerGenerationAndOpenState() throws Exception {
        var store=new RoomCleanupStore(this::connection,false);var fence=store.capture(1,"local");
        for(String mutation:java.util.List.of("UPDATE MMR_Phong_Van_Hanh SET Instance_ID='other' WHERE Ma_Phong=1",
                "UPDATE MMR_Phong_Van_Hanh SET Room_Opaque_ID='new-generation' WHERE Ma_Phong=1",
                "UPDATE Phong_Hop SET Trang_Thai='DANG_HOAT_DONG' WHERE Ma_Phong=1")) {
            sql(mutation);assertThrows(RoomException.class,() -> store.commit(fence,java.util.List.of()));assertEquals(2,count());
            sql("UPDATE MMR_Phong_Van_Hanh SET Instance_ID='local',Room_Opaque_ID='test-generation' WHERE Ma_Phong=1");
            sql("UPDATE Phong_Hop SET Trang_Thai='DA_DONG' WHERE Ma_Phong=1");
        }
    }
    @Test void twoRoomsCanProgressAndRetryDoesNotOccupyAWorker() throws Exception {
        sql("UPDATE Phong_Hop SET Trang_Thai='DA_DONG' WHERE Ma_Phong=2");
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        var ref=new AtomicReference<ImmediateRoomCleanup>();
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",false,2,(f,b) -> {
            entered.countDown();assertTrue(entered.await(5,TimeUnit.SECONDS));
            if(f.roomId()==1) {assertTrue(release.await(5,TimeUnit.SECONDS));throw new java.io.IOException("fixture");}return b;
        },id -> false,f -> true,() -> {if(ref.get()!=null && !ref.get().isPending(2)) done.countDown();})) {
            ref.set(cleanup);cleanup.request(1);cleanup.request(2);cleanup.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
            assertTrue(done.await(5,TimeUnit.SECONDS));
            long completedBy=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(count()!=1 && System.nanoTime()<completedBy) Thread.sleep(5);
            assertEquals(1,count());release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!"RETRY".equals(cleanup.phase(1)) && System.nanoTime()<until) Thread.sleep(5);
            assertEquals("RETRY",cleanup.phase(1));assertTrue(cleanup.activeCount()<=2);
        }
        assertArrayEquals(new long[]{2,4,8,16,32,64,64},java.util.stream.IntStream.rangeClosed(1,7).mapToLong(ImmediateRoomCleanup::retrySeconds).toArray());
    }
    @Test void missingProcedureIsNeverReportedAsCleaned() throws Exception {
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",true,1,(f,b) -> b,id -> false,f -> true,() -> {})) {
            assertThrows(SQLException.class,() -> cleanup.clean(1));assertTrue(cleanup.isPending(1));assertEquals(2,count());
            try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
        }
    }
    @Test void overflowIsRediscoveredAndPerRoomTasksAreUniqueAndBounded() throws Exception {
        try(var c=connection();var s=c.createStatement()) {
            for(int i=3;i<=270;i++) {s.addBatch("INSERT INTO Phong_Hop VALUES("+i+",'DA_DONG',CURRENT_TIMESTAMP)");s.addBatch("INSERT INTO MMR_Phong_Van_Hanh(Ma_Phong,Instance_ID) VALUES("+i+",'local')");s.addBatch("INSERT INTO Tin_Nhan VALUES("+i+","+i+")");}s.executeBatch();
        }
        var running=new ConcurrentHashMap<Long,Boolean>();var peak=new AtomicInteger();
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",false,2,(f,b) -> {
            assertNull(running.putIfAbsent(f.roomId(),true));peak.accumulateAndGet(running.size(),Math::max);
            try {Thread.sleep(2);return b;} finally {running.remove(f.roomId());}
        },id -> false,f -> true,() -> {})) {
            for(long id=1;id<=270;id++) {cleanup.request(id);cleanup.request(id);}
            assertTrue(cleanup.pendingCount()<=256);assertTrue(cleanup.queueSize()<=32);cleanup.start();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);while(count()!=1 && System.nanoTime()<until) Thread.sleep(25);
            assertEquals(1,count());assertTrue(peak.get()<=2);
        }
    }
    @Test void shutdownAndRestartKeepUnfinishedDurableWork() throws Exception {
        var entered=new CountDownLatch(1);
        var cleanup=new ImmediateRoomCleanup(this::connection,"local",false,1,(f,b) -> {entered.countDown();new CountDownLatch(1).await();return b;},id -> false,f -> true,() -> {});
        cleanup.request(1);cleanup.start();assertTrue(entered.await(5,TimeUnit.SECONDS));cleanup.close();assertEquals(2,count());
        try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
        try(var restarted=new ImmediateRoomCleanup(this::connection,"local",id -> {},id -> false,() -> {})) {assertTrue(restarted.clean(1));assertEquals(1,count());}
    }
    @Test void diskAndCancellationNeverBorrowOrRetainADatabaseConnection() throws Exception {
        var borrowed=new AtomicInteger();
        server.auth.ConnectionProvider measured=() -> {
            var raw=connection();borrowed.incrementAndGet();var returned=new AtomicBoolean();
            return (Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a) -> {
                try {return m.invoke(raw,a);} catch(java.lang.reflect.InvocationTargetException e) {throw e.getCause();}
                finally {if(m.getName().equals("close") && returned.compareAndSet(false,true)) borrowed.decrementAndGet();}
            });
        };
        try(var cleanup=new ImmediateRoomCleanup(measured,"local",false,2,(f,b) -> {assertEquals(0,borrowed.get());return b;},id -> {assertEquals(0,borrowed.get());return false;},f -> {assertEquals(0,borrowed.get());return true;},() -> {})) {
            assertTrue(cleanup.clean(1));assertEquals(0,borrowed.get());
            try(var c=measured.getConnection()) {assertDoesNotThrow(() -> cleanup.check(c,1));}assertEquals(0,borrowed.get());
        }
    }
}
