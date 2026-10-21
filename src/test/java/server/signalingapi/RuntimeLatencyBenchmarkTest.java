package server.signalingapi;

import client.signalingapi.SignalingClient;
import com.google.gson.*;
import common.meeting.RoomSnapshot;
import common.signaling.*;
import java.net.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import server.auth.SessionValidator;
import server.meeting.*;
import support.RoomTestFixture;
import static org.junit.jupiter.api.Assertions.*;

/** Same harness can run against the parent revision. Isolated H2 and generated TLS only. */
class RuntimeLatencyBenchmarkTest {
    @TempDir Path temporary;
    @Test void measureChatAckAndPushWithoutClaimingInternetCapacity() throws Exception {
        var tls=RoomTestFixture.certificates(temporary);var json=new Gson();
        String previousDir=System.getProperty("mmr.diagnostics.dir");System.setProperty("mmr.diagnostics.dir",temporary.resolve("diagnostics").toString());
        try(var diagnostics=common.diagnostics.Diagnostics.start("server");var fixture=new RoomTestFixture(tls)) {
            var acquisitions=new ConcurrentLinkedQueue<Double>();var queries=new ConcurrentLinkedQueue<Double>();
            server.auth.ConnectionProvider measured=() -> {
                long before=System.nanoTime();var raw=fixture.connection();acquisitions.add((System.nanoTime()-before)/1e6);
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args) -> {
                    try {
                        Object value=method.invoke(raw,args);
                        if(value instanceof java.sql.PreparedStatement statement) return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a) -> {
                            long started=System.nanoTime();try {return m.invoke(statement,a);} catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
                            finally {if(m.getName().startsWith("execute")) queries.add((System.nanoTime()-started)/1e6);}
                        });
                        return value;
                    } catch(java.lang.reflect.InvocationTargetException failure) {throw failure.getCause();}
                });
            };
            String schema=Files.readString(Path.of("database/migrations/001-meeting-runtime.sql")).replaceAll("(?m)^--[^\\r\\n]*", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "").replace("ENGINE=InnoDB", "");
            for(String sql:schema.split(";")) if(!sql.isBlank()) fixture.execute(sql);
            fixture.execute("CREATE TABLE IF NOT EXISTS Tin_Nhan(Ma_Tin_Nhan BIGINT AUTO_INCREMENT PRIMARY KEY,Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop(Ma_Phong),Ma_Nguoi_Gui BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),Loai_Tin_Nhan VARCHAR(20),Noi_Dung TEXT,Ngay_Gui TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,Ngay_Xoa TIMESTAMP)");
            var current=new AtomicReference<SignalingServer>();
            try(var rooms=new RoomService(new RoomRepository(measured),Clock.systemUTC(),"benchmark",new byte[32],20,event -> {
                var server=current.get();if(server!=null) server.event(event.recipientConnectionId(),event.type(),event.roomId(),event.revision(),json.toJsonTree(event.body()).getAsJsonObject());
            });var handler=new MeetingHandler(rooms)) {
                rooms.start();
                try(var server=new SignalingServer(tls.server(),new InetSocketAddress("127.0.0.1",0),new SessionValidator(measured,Clock.systemUTC()),handler,rooms.serverEpoch(),SignalingServer.Options.defaults())) {
                    current.set(server);server.start();var account=fixture.account("benchHost","Host");
                    var pushed=new ConcurrentHashMap<String,Long>();
                    try(var client=new SignalingClient(new SignalingClient.Settings("localhost",server.port(),null,SignalWire.DEFAULT_MAX_BYTES),tls.client(),event -> {
                        if(event.type().equals("CHAT_MESSAGE")) pushed.put(event.body().get("content").getAsString(),System.nanoTime());
                    })) {
                        client.connect(account.token());var body=new JsonObject();body.addProperty("name","Benchmark");body.addProperty("pin","1234");body.addProperty("capacity",20);
                        var room=json.fromJson(client.request("CREATE_ROOM",null,body).get(10,TimeUnit.SECONDS).body().get("room"),RoomSnapshot.class);
                        var join=new JsonObject();join.addProperty("joinCode",room.joinCode());join.addProperty("pin","1234");client.request("JOIN_ROOM",room.roomId(),join).get(10,TimeUnit.SECONDS);
                        var acknowledgements=new ArrayList<Double>();var events=new ArrayList<Double>();
                        acquisitions.clear();queries.clear();long gcBefore=java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0,bean.getCollectionTime())).sum();
                        var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();long cpu=os.getProcessCpuTime();long wall=System.nanoTime();
                        for(int i=0;i<33;i++) {
                            var text="benchmark-"+i;var message=new JsonObject();message.addProperty("content",text);
                            long start=System.nanoTime();client.request("CHAT_SEND",room.roomId(),message).get(10,TimeUnit.SECONDS);double ack=(System.nanoTime()-start)/1e6;
                            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!pushed.containsKey(text) && System.nanoTime()<until) Thread.sleep(1);
                            assertTrue(pushed.containsKey(text));if(i>=3) {acknowledgements.add(ack);events.add((pushed.get(text)-start)/1e6);}
                            Thread.sleep(220);
                        }
                        Collections.sort(acknowledgements);Collections.sort(events);
                        var memory=java.lang.management.ManagementFactory.getMemoryMXBean();var threads=java.lang.management.ManagementFactory.getThreadMXBean();
                        System.out.printf(Locale.ROOT,"RUNTIME_BENCHMARK samples=30 ackMedianMs=%.3f ackP95Ms=%.3f pushMedianMs=%.3f pushP95Ms=%.3f cpuMs=%.1f wallMs=%.1f heapBytes=%d threads=%d%n",acknowledgements.get(15),acknowledgements.get(28),events.get(15),events.get(28),(os.getProcessCpuTime()-cpu)/1e6,(System.nanoTime()-wall)/1e6,memory.getHeapMemoryUsage().getUsed(),threads.getThreadCount());
                        long gcAfter=java.lang.management.ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0,bean.getCollectionTime())).sum();
                        diagnostics.close();var queue=new ArrayList<Double>();
                        for(String line:Files.readAllLines(diagnostics.logPath())) {var value=JsonParser.parseString(line).getAsJsonObject();var fields=value.getAsJsonObject("fields");if(value.get("event").getAsString().equals("signaling.request.completed") && fields.has("action") && fields.get("action").getAsString().equals("CHAT_SEND")) queue.add(fields.get("queue_ms").getAsDouble());}
                        System.out.printf(Locale.ROOT,"RUNTIME_DB_QUEUE acquireP95Ms=%.3f queryP95Ms=%.3f signalingQueueP95Ms=%.3f gcMs=%d queryCount=%d%n",p95(acquisitions),p95(queries),p95(queue),gcAfter-gcBefore,queries.size());
                    }
                }
            }
        } finally {if(previousDir==null) System.clearProperty("mmr.diagnostics.dir");else System.setProperty("mmr.diagnostics.dir",previousDir);}
    }
    private static double p95(java.util.Collection<Double> values) {var sorted=new ArrayList<>(values);Collections.sort(sorted);return sorted.isEmpty()?Double.NaN:sorted.get(Math.min(sorted.size()-1,(int)Math.ceil(sorted.size()*.95)-1));}
}
