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
        sql("CREATE TABLE MMR_Phong_Van_Hanh(Ma_Phong BIGINT PRIMARY KEY,Instance_ID VARCHAR(64))");
        sql("CREATE TABLE Tin_Nhan(Ma_Tin_Nhan BIGINT PRIMARY KEY,Ma_Phong BIGINT)");
        sql("CREATE TABLE Tep_Dinh_Kem(Ma_Tep BIGINT PRIMARY KEY,Ma_Tin_Nhan BIGINT REFERENCES Tin_Nhan(Ma_Tin_Nhan))");
        sql("INSERT INTO Phong_Hop VALUES(1,'DA_DONG',CURRENT_TIMESTAMP),(2,'DANG_HOAT_DONG',NULL)");
        sql("INSERT INTO MMR_Phong_Van_Hanh VALUES(1,'local'),(2,'local')");
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
        sql("INSERT INTO Tep_Dinh_Kem VALUES(1,1)");var pending=new AtomicBoolean(true);
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {},id -> pending.get(),() -> {})) {
            assertFalse(cleanup.clean(1));assertEquals(2,count());
            sql("DELETE FROM Tep_Dinh_Kem");assertFalse(cleanup.clean(1));
            pending.set(false);assertTrue(cleanup.clean(1));assertEquals(1,count());
        }
    }
    @Test void restartScanResumesClosedRoomsButDoesNotTouchAnotherInstance() throws Exception {
        sql("INSERT INTO Phong_Hop VALUES(3,'DA_DONG',CURRENT_TIMESTAMP)");sql("INSERT INTO MMR_Phong_Van_Hanh VALUES(3,'other')");sql("INSERT INTO Tin_Nhan VALUES(3,3)");
        sql("INSERT INTO Phong_Hop VALUES(4,'DA_DONG',CURRENT_TIMESTAMP)");sql("INSERT INTO Tin_Nhan VALUES(4,4)");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var changed=new CountDownLatch(1);
        try(var cleanup=new ImmediateRoomCleanup(this::connection,"local",id -> {entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));},id -> false,changed::countDown)) {
            cleanup.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
            try(var c=connection()) {assertThrows(RoomException.class,() -> cleanup.check(c,1));}
            release.countDown();assertTrue(changed.await(5,TimeUnit.SECONDS));assertEquals(3,count());
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
}
