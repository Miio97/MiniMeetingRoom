package server.meeting;

import common.meeting.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import server.auth.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fixture server on loopback only. Never uses ServerConfig or Railway credentials. */
@EnabledIfSystemProperty(named="mmr.test.routines.port",matches="[0-9]+")
class RoomRoutineIntegrationTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    static String base;
    String database;
    String url;
    @BeforeEach void prepare() throws Exception {
        int port=Integer.parseInt(System.getProperty("mmr.test.routines.port"));
        assertTrue(port>1024 && port<65536);
        base="jdbc:mysql://127.0.0.1:"+port+"/?sslMode=DISABLED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&socketTimeout=10000";
        database="mmr_java_routines_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(base,"root","");var s=c.createStatement()) {s.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4");}
        url=base.replace("/?","/"+database+"?");
        String schema=Files.readString(Path.of("database/schema.sql"));
        schema=schema.substring(schema.indexOf("CREATE TABLE Nguoi_Dung"));
        try(var c=connection();var s=c.createStatement()) {
            for(String q:schema.replaceAll("(?m)^[ \t]*--[^\\r\\n]*","").split(";")) if(!q.isBlank()) s.execute(q);
            for(String q:Files.readString(Path.of("database/migrations/001-meeting-runtime.sql")).replaceAll("(?m)^[ \t]*--[^\\r\\n]*","").split(";")) if(!q.isBlank()) s.execute(q);
            String routines=Files.readString(Path.of("database/procedures/005-room-operations-v1.sql")).replaceAll("(?m)^DELIMITER[^\\r\\n]*","");
            for(String q:routines.split("\\$\\$")) if(q.contains("CREATE PROCEDURE")) s.execute(q);
            s.execute("INSERT INTO Nguoi_Dung(Ten_Dang_Nhap,Mat_Khau_Bam,Ten_Hien_Thi) VALUES('fixture-host','unused','Host'),('fixture-guest','unused','Guest')");
            s.execute("INSERT INTO Phien_Dang_Nhap(Ma_Nguoi_Dung,Token_Bam,Het_Han_Luc) VALUES(1,REPEAT('A',43),CURRENT_TIMESTAMP+INTERVAL 1 DAY),(2,REPEAT('B',43),CURRENT_TIMESTAMP+INTERVAL 1 DAY)");
        }
    }
    Connection connection() throws SQLException {return DriverManager.getConnection(url,"root","");}
    SessionPrincipal user(int id) {return new SessionPrincipal(id,id,"Fixture",Instant.now().plusSeconds(3600),String.valueOf((char)(64+id)).repeat(43));}
    @AfterEach void finish() throws Exception {
        if(database==null) return;
        assertTrue(database.matches("mmr_java_routines_[a-f0-9]{32}"));
        try(var c=DriverManager.getConnection(base,"root","");var s=c.createStatement()) {s.execute("DROP DATABASE "+database);}
    }
    @Test void createJoinTransferCloseUseInstalledRoutinesWithoutDoubleHistoryWrites() throws Exception {
        var repository=new RoomRepository(this::connection,true);
        var cleanups=new AtomicInteger();
        try(var rooms=new RoomService(repository,Clock.systemUTC(),"routine-test",new byte[32],20,event -> {})) {
            rooms.onRoomClosed(id -> cleanups.incrementAndGet());rooms.start();
            String create=UUID.randomUUID().toString();
            var room=rooms.create(user(1),create,new CreateRoomParams("Routine",null,2,false));
            assertEquals(room.roomId(),rooms.create(user(1),create,new CreateRoomParams("Routine",null,2,false)).roomId());
            rooms.join(user(1),"host",1,UUID.randomUUID().toString(),room.joinCode(),null,false,false);
            var guest=rooms.join(user(2),"guest",1,UUID.randomUUID().toString(),room.joinCode(),null,false,false);
            rooms.leaveRequested("host",room.roomId(),UUID.randomUUID().toString(),guest.selfParticipantId(),false);
            assertEquals(guest.selfParticipantId(),rooms.getRoom("guest",room.roomId()).hostParticipantId());
            rooms.leaveRequested("guest",room.roomId(),UUID.randomUUID().toString(),null,true);
            assertEquals(0,rooms.roomCount());assertEquals(1,cleanups.get());
            try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM MMR_Yeu_Cau_Phong WHERE Operation='EXIT_ROOM'")) {assertTrue(r.next());assertEquals(2,r.getInt(1));}
            try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL")) {assertTrue(r.next());assertEquals(0,r.getInt(1));}
        }
    }
    @Test void refusesOuterTransactionInsteadOfImplicitlyCommittingIt() throws Exception {
        var raw=connection();raw.setAutoCommit(false);
        var routine=new RoomRoutineClient(() -> raw);
        var failed=assertThrows(SQLException.class,() -> routine.invoke("mmr_create_room_v1"));
        assertEquals("ROUTINE_REQUIRES_AUTOCOMMIT",failed.getMessage());
        try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM Phong_Hop")) {assertTrue(r.next());assertEquals(0,r.getInt(1));}
    }
    @Test void lostExitAckRetriesSameReceiptAndEventuallyUpdatesLiveRoom() throws Exception {
        AtomicBoolean lose=new AtomicBoolean();
        ConnectionProvider provider=() -> {
            var raw=connection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,m,args) -> {
                try {
                    Object result=m.invoke(raw,args);
                    if(m.getName().equals("prepareStatement") && args[0].toString().startsWith("CALL mmr_exit_room_v1")) {
                        var statement=(PreparedStatement)result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,sm,sa) -> {
                            try {Object value=sm.invoke(statement,sa);if(sm.getName().equals("execute") && lose.compareAndSet(true,false)) throw new SQLException("SIMULATED_ACK_LOSS","08006");return value;}
                            catch(InvocationTargetException failure) {throw failure.getCause();}
                        });
                    }
                    return result;
                } catch(InvocationTargetException failure) {throw failure.getCause();}
            });
        };
        try(var rooms=new RoomService(new RoomRepository(provider,true),Clock.systemUTC(),"routine-test",new byte[32],20,event -> {})) {
            rooms.start();var room=rooms.create(user(1),UUID.randomUUID().toString(),new CreateRoomParams("Ack",null,2,false));
            rooms.join(user(1),"host",1,UUID.randomUUID().toString(),room.joinCode(),null,false,false);
            lose.set(true);
            assertThrows(SQLException.class,() -> rooms.leaveRequested("host",room.roomId(),UUID.randomUUID().toString(),null,true));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
            while(rooms.roomCount()!=0 && System.nanoTime()<deadline) Thread.sleep(20);
            assertEquals(0,rooms.roomCount());
            try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM MMR_Yeu_Cau_Phong WHERE Operation='EXIT_ROOM'")) {assertTrue(r.next());assertEquals(1,r.getInt(1));}
        }
    }
    @Test void measureClientStatementCountsAgainstReferenceRepository() throws Exception {
        for(boolean routines:new boolean[]{false,true}) {
            AtomicInteger statements=new AtomicInteger();
            ConnectionProvider measured=() -> {
                var raw=connection();
                return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,m,args) -> {
                    try {
                        Object result=m.invoke(raw,args);
                        if(m.getName().equals("prepareStatement")) {
                            var statement=(PreparedStatement)result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,sm,sa) -> {
                                try {
                                    if(sm.getName().startsWith("execute")) statements.incrementAndGet();
                                    return sm.invoke(statement,sa);
                                } catch(InvocationTargetException failure) {throw failure.getCause();}
                            });
                        }
                        return result;
                    } catch(InvocationTargetException failure) {throw failure.getCause();}
                });
            };
            try(var rooms=new RoomService(new RoomRepository(measured,routines),Clock.systemUTC(),"statement-test",new byte[32],20,event -> {})) {
                rooms.start();
                var counts=new int[3];var elapsed=new double[3];
                for(int sample=0;sample<6;sample++) {
                    statements.set(0);long t=System.nanoTime();
                    var room=rooms.create(user(1),UUID.randomUUID().toString(),new CreateRoomParams("Count",null,2,false));
                    if(sample>0) {counts[0]+=statements.get();elapsed[0]+=(System.nanoTime()-t)/1e6;}
                    statements.set(0);t=System.nanoTime();
                    rooms.join(user(1),"host",1,UUID.randomUUID().toString(),room.joinCode(),null,false,false);
                    if(sample>0) {counts[1]+=statements.get();elapsed[1]+=(System.nanoTime()-t)/1e6;}
                    statements.set(0);t=System.nanoTime();
                    rooms.leaveRequested("host",room.roomId(),UUID.randomUUID().toString(),null,true);
                    if(sample>0) {counts[2]+=statements.get();elapsed[2]+=(System.nanoTime()-t)/1e6;}
                }
                for(int action=0;action<3;action++) {
                    System.out.printf(Locale.ROOT,"ROOM_ROUTINE_BENCH mode=%s action=%s samples=5 statementsPerOperation=%.1f meanMs=%.3f%n",
                        routines?"CALL":"REFERENCE",new String[]{"CREATE","JOIN","EXIT"}[action],counts[action]/5.0,elapsed[action]/5.0);
                    if(routines) assertEquals(5,counts[action],"One prepared CALL for each successful operation");
                }
            }
        }
    }
    @Test void tlsRequestsUseRoutinesAndRevokedSessionCannotCreateOrExit() throws Exception {
        var tls=support.RoomTestFixture.certificates(temporary);
        String token="A".repeat(43),hash=Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try(var c=connection();var q=c.prepareStatement("UPDATE Phien_Dang_Nhap SET Token_Bam=? WHERE Ma_Phien=1")) {q.setString(1,hash);q.executeUpdate();}
        var reference=new AtomicReference<server.signalingapi.SignalingServer>();
        try(var rooms=new RoomService(new RoomRepository(this::connection,true),Clock.systemUTC(),"tls-routines",new byte[32],20,event -> {
            var server=reference.get();if(server!=null) server.event(event.recipientConnectionId(),event.type(),event.roomId(),event.revision(),new com.google.gson.Gson().toJsonTree(event.body()).getAsJsonObject());
        });var handler=new server.signalingapi.MeetingHandler(rooms)) {
            rooms.start();
            try(var signaling=new server.signalingapi.SignalingServer(tls.server(),new java.net.InetSocketAddress("127.0.0.1",0),
                new SessionValidator(this::connection,Clock.systemUTC()),handler,rooms.serverEpoch(),server.signalingapi.SignalingServer.Options.defaults())) {
                reference.set(signaling);signaling.start();
                try(var client=new client.signalingapi.SignalingClient(new client.signalingapi.SignalingClient.Settings("localhost",signaling.port(),null,common.signaling.SignalWire.DEFAULT_MAX_BYTES),tls.client(),event -> {})) {
                    client.connect(token);var body=new com.google.gson.JsonObject();body.addProperty("name","TLS");body.addProperty("capacity",2);body.addProperty("pin","");
                    var created=client.request("CREATE_ROOM",null,body).get(10,java.util.concurrent.TimeUnit.SECONDS).body().getAsJsonObject("room");
                    String opaque=created.get("roomId").getAsString();
                    var join=new com.google.gson.JsonObject();join.addProperty("joinCode",created.get("joinCode").getAsString());join.addProperty("pin","");
                    client.request("JOIN_ROOM",opaque,join).get(10,java.util.concurrent.TimeUnit.SECONDS);
                    try(var c=connection();var s=c.createStatement()) {s.execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc=CURRENT_TIMESTAMP WHERE Ma_Phien=1");}
                    var exit=new com.google.gson.JsonObject();exit.addProperty("closeRoom",true);
                    var failed=assertThrows(java.util.concurrent.ExecutionException.class,() -> client.request("LEAVE_ROOM",opaque,exit).get(10,java.util.concurrent.TimeUnit.SECONDS));
                    assertInstanceOf(common.signaling.SignalException.class,failed.getCause());
                    assertTrue(Set.of("SESSION_INVALID","CONNECTION_CLOSED").contains(((common.signaling.SignalException)failed.getCause()).code()));
                    // Background revocation may independently close the live room; it must
                    // never turn this unauthorized explicit exit into an acknowledged mutation.
                    try(var c=connection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM MMR_Yeu_Cau_Phong WHERE Operation='EXIT_ROOM'")) {assertTrue(r.next());assertEquals(0,r.getInt(1));}
                }
            }
        }
    }
}
