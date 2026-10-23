package server.meeting;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in MySQL 8 fixture, loopback only; never reads private configuration. */
@EnabledIfSystemProperty(named="mmr.test.cleanup.mysqlPort",matches="[0-9]+")
class RoomCleanupRoutineIntegrationTest {
    String base,database,url;
    Connection connection() throws SQLException {return DriverManager.getConnection(url,"root","");}
    @BeforeEach void setup() throws Exception {
        int port=Integer.parseInt(System.getProperty("mmr.test.cleanup.mysqlPort"));assertTrue(port>1024 && port<65536);
        base="jdbc:mysql://127.0.0.1:"+port+"/?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=10000";
        try(var c=DriverManager.getConnection(base,"root","");var s=c.createStatement();var r=s.executeQuery("SELECT VERSION()")) {
            r.next();assertTrue(r.getString(1).matches("8\\..*"),"Requires isolated MySQL 8, not MariaDB");
        }
        database="mmr_cleanup_test_"+UUID.randomUUID().toString().replace("-","");url=base.replace("/?","/"+database+"?");
        try(var c=DriverManager.getConnection(base,"root","");var s=c.createStatement()) {s.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");}
        try(var c=connection();var s=c.createStatement()) {
            String schema=Files.readString(Path.of("database/schema.sql"));schema=schema.substring(schema.indexOf("CREATE TABLE Nguoi_Dung"));
            for(String q:schema.replaceAll("(?m)^[ \\t]*--[^\\r\\n]*","").split(";")) if(!q.isBlank()) s.execute(q);
            for(String q:Files.readString(Path.of("database/migrations/001-meeting-runtime.sql")).replaceAll("(?m)^[ \\t]*--[^\\r\\n]*","").split(";")) if(!q.isBlank()) s.execute(q);
            String procedures=Files.readString(Path.of("database/procedures/006-room-cleanup-v1.sql")).replaceAll("(?m)^DELIMITER[^\\r\\n]*","");
            // Ignore the preflight SELECT; send complete CREATE bodies, not DELIMITER directives.
            for(String q:procedures.split("\\$\\$")) if(q.contains("CREATE PROCEDURE")) s.execute(q.substring(q.indexOf("CREATE PROCEDURE")));
            s.execute("INSERT INTO Nguoi_Dung(Ten_Dang_Nhap,Mat_Khau_Bam,Ten_Hien_Thi) VALUES('cleanup-fixture','unused','Fixture')");
            s.execute("INSERT INTO Phong_Hop(Ma_Phong_Tham_Gia,Ten_Phong,Ma_Chu_Phong,Trang_Thai,So_Nguoi_Toi_Da) VALUES('CLEANUP1','Fixture',1,'DA_DONG',2)");
            s.execute("INSERT INTO MMR_Phong_Van_Hanh VALUES(1,'generation-one','fixture','epoch',CURRENT_TIMESTAMP)");
            s.execute("INSERT INTO Tin_Nhan(Ma_Phong,Ma_Nguoi_Gui,Loai_Tin_Nhan,Noi_Dung) VALUES(1,1,'TEP','fixture')");
            s.execute("INSERT INTO Tep_Dinh_Kem(Ma_Tin_Nhan,Ten_Tep_Goc,Duong_Dan_Luu_Tru,Loai_MIME,Kich_Thuoc_Byte,Ma_Kiem_Tra,Transfer_Id) VALUES(1,'fixture','rooms/1/files/00000000-0000-0000-0000-000000000001.bin','application/octet-stream',0,REPEAT('a',64),'00000000-0000-0000-0000-000000000001')");
        }
    }
    @AfterEach void teardown() throws Exception {
        if(database==null) return;assertTrue(database.matches("mmr_cleanup_test_[a-f0-9]{32}"));
        try(var c=DriverManager.getConnection(base,"root","");var s=c.createStatement()) {s.execute("DROP DATABASE "+database);}
    }
    @Test void realProceduresFenceBatchAndIdempotentAcknowledge() throws Exception {
        var store=new RoomCleanupStore(this::connection,true);var fence=store.capture(1,"fixture");var files=store.batch(fence);assertEquals(1,files.size());
        assertFalse(store.commit(fence,List.of()),"Unacknowledged bytes must retain metadata");
        assertThrows(RoomException.class,() -> store.commit(new RoomCleanupStore.Fence(1,"foreign","generation-one"),files));
        assertThrows(RoomException.class,() -> store.commit(new RoomCleanupStore.Fence(1,"fixture","generation-old"),files));
        assertTrue(store.commit(fence,files));assertTrue(store.commit(fence,files));assertTrue(store.batch(fence).isEmpty());
    }
    @Test void boundedMessagesAndMissingProcedureAreExplicit() throws Exception {
        try(var c=connection();var s=c.prepareStatement("INSERT INTO Tin_Nhan(Ma_Phong,Ma_Nguoi_Gui,Loai_Tin_Nhan,Noi_Dung) VALUES(1,1,'VAN_BAN','fixture')")) {for(int i=0;i<1001;i++) s.addBatch();s.executeBatch();}
        var store=new RoomCleanupStore(this::connection,true);var fence=store.capture(1,"fixture");
        assertFalse(store.commit(fence,store.batch(fence)));assertFalse(store.commit(fence,List.of()));assertTrue(store.commit(fence,List.of()));
        try(var c=connection();var s=c.createStatement()) {s.execute("DROP PROCEDURE mmr_cleanup_batch_v1");}
        assertEquals(1305,assertThrows(SQLException.class,() -> store.batch(fence)).getErrorCode());
    }
}
