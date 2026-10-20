package client.media;

import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.onvoid.webrtc.media.video.*;
import support.*;
import static org.junit.jupiter.api.Assertions.*;

/** The same workload is runnable against the pre-migration commit, entirely in isolated H2/TLS/Go. */
@Timeout(180)
class NetworkStatsGroupIntegrationTest {
    @TempDir static Path temporary;
    static RoomTestFixture.Tls tls;
    @BeforeAll static void start() throws Exception {FxTestRuntime.start();tls=RoomTestFixture.certificates(temporary);}
    @ParameterizedTest @ValueSource(ints={2,5,10}) void groupCallStatsAndResourceEvidence(int members) throws Exception {
        var sessions=new ArrayList<client.meeting.MeetingSession>();var videos=new ArrayList<VideoCallController>();
        try(var fixture=new VideoTestFixture(tls,temporary.resolve("group-"+members))) {
            try {
                for(int i=0;i<members;i++) sessions.add(fixture.session(fixture.database.account("group"+members+"u"+i,"User "+i)));
                var host=sessions.getFirst();var room=host.create(new common.meeting.CreateRoomParams("Stats group","1234",members,false)).get(10,TimeUnit.SECONDS);
                host.join(false,false).get(10,TimeUnit.SECONDS);
                for(int i=1;i<members;i++) {var s=sessions.get(i);s.lookup(room.joinCode(),"1234").get(10,TimeUnit.SECONDS);s.join(false,false).get(10,TimeUnit.SECONDS);}
                for(var s:sessions) {var video=s.video();videos.add(video);video.publishPreparedCamera().get(10,TimeUnit.SECONDS);}
                int publishers=members<=5?members:1;
                for(int i=0;i<publishers;i++) videos.get(i).startInput(Frames::new).get(15,TimeUnit.SECONDS);
                for(int i=0;i<2;i++) {var audio=videos.get(i).audio();audio.access(true).get(5,TimeUnit.SECONDS);FxTestRuntime.call(audio::toggle).get(15,TimeUnit.SECONDS);}
                for(int i=1;i<members;i++) {var video=videos.get(i);FxTestRuntime.await(() -> video.frames(host.selfParticipantId()).receivedFrames()>2 && video.audio().receivedSamples(host.selfParticipantId())>4800);}
                var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
                var gc=ManagementFactory.getGarbageCollectorMXBeans();long gcBefore=gc.stream().mapToLong(g -> Math.max(0,g.getCollectionCount())).sum();
                long cpu=os.getProcessCpuTime(),at=System.nanoTime(),messages=fixture.telemetryMessages.get();
                int threads=ManagementFactory.getThreadMXBean().getThreadCount();Thread.sleep(5000);
                double seconds=(System.nanoTime()-at)/1e9,cores=(os.getProcessCpuTime()-cpu)/(seconds*1e9);
                long heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();long collections=gc.stream().mapToLong(g -> Math.max(0,g.getCollectionCount())).sum()-gcBefore;
                boolean migrated;int maximum=0;double interval=0;
                try {
                    var history=VideoCallController.class.getMethod("networkHistory");migrated=true;
                    for(var video:videos) {Object[] samples=(Object[])history.invoke(video);maximum=Math.max(maximum,samples.length);assertTrue(samples.length<=40);}
                    Object[] h=(Object[])history.invoke(videos.getFirst());
                    if(h.length>1) {var accessor=h[0].getClass().getMethod("atNanos");interval=((long)accessor.invoke(h[h.length-1])-(long)accessor.invoke(h[0]))/1e6/(h.length-1);}
                    assertEquals(0,fixture.telemetryMessages.get());
                } catch(NoSuchMethodException oldVersion) {migrated=false;assertTrue(fixture.telemetryMessages.get()>0);}
                Path output=Path.of(System.getProperty("mmr.stats.benchmark.output","target/stats-after.csv"));Files.createDirectories(output.toAbsolutePath().getParent());
                if(!Files.exists(output)) Files.writeString(output,"members,publishers,seconds,java_cpu_cores,heap_mib,gc_count,threads_before,threads_after,telemetry_messages,buffer_max,mean_sample_ms,migrated\n");
                String row=String.format(Locale.ROOT,"%d,%d,%.3f,%.3f,%.2f,%d,%d,%d,%d,%d,%.2f,%s%n",members,publishers,seconds,cores,heap/1048576.0,collections,threads,ManagementFactory.getThreadMXBean().getThreadCount(),fixture.telemetryMessages.get()-messages,maximum,interval,migrated);
                Files.writeString(output,row,StandardOpenOption.APPEND);System.out.print("STATS_GROUP "+row);
                assertEquals(members,fixture.database.count("SELECT COUNT(*) FROM Thanh_Vien_Phong WHERE Roi_Phong_Luc IS NULL"));
            } finally {sessions.forEach(client.meeting.MeetingSession::close);for(var video:videos) video.closeComplete().get(25,TimeUnit.SECONDS);}
        }
    }
    static final class Frames implements VideoInput {
        final CustomVideoSource source=new CustomVideoSource();final ScheduledExecutorService producer=Executors.newSingleThreadScheduledExecutor();
        boolean closed;int frame;
        public int width(){return 320;}public int height(){return 240;}public int fps(){return 15;}
        public VideoTrackSource source(){return source;}public void start(){producer.scheduleAtFixedRate(this::frame,0,67,TimeUnit.MILLISECONDS);}
        synchronized void frame() {if(closed)return;var buffer=NativeI420Buffer.allocate(320,240);try {fill(buffer.getDataY(),80+(frame++%70));fill(buffer.getDataU(),80);fill(buffer.getDataV(),160);source.pushFrame(new VideoFrame(buffer,System.nanoTime()));}finally {buffer.release();}}
        static void fill(ByteBuffer bytes,int value){while(bytes.hasRemaining())bytes.put((byte)value);}
        public synchronized void close(){if(closed)return;closed=true;producer.shutdownNow();source.dispose();}
    }
}
