package client.media;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Windows process loopback: excludes this JVM and its descendants, including meeting playback. */
public final class WindowsSystemAudioInput implements SystemAudioInput {
    private final AtomicBoolean closed=new AtomicBoolean(),failed=new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> frames=new ArrayBlockingQueue<>(10);
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("system-audio-clock").factory());
    private Process process;
    private Path directory;
    private Thread reader;
    public static boolean supported() {return System.getProperty("os.name","").startsWith("Windows");}
    @Override public synchronized void start(Consumer<byte[]> samples) throws Exception {
        if(!supported()) throw new IllegalStateException("SYSTEM_AUDIO_UNAVAILABLE");
        if(closed.get() || process!=null) throw new IllegalStateException("SYSTEM_AUDIO_FAILED");
        try {
            directory=Files.createTempDirectory("mmr-system-audio-");
            Path source=directory.resolve("SystemAudioLoopback.cs");
            try(var bundled=WindowsSystemAudioInput.class.getResourceAsStream("/media/SystemAudioLoopback.cs")) {
                if(bundled==null) throw new IOException();Files.copy(bundled,source);
            }
            String script="$ErrorActionPreference='Stop'; try { Add-Type -Path '"+source.toString().replace("'","''")
                    +"'; [MiniMeeting.SystemAudioLoopback]::Run([uint32]"+ProcessHandle.current().pid()+") } catch { exit 2 }";
            String command=Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
            Path powershell=Path.of(System.getenv("SystemRoot"),"System32","WindowsPowerShell","v1.0","powershell.exe");
            process=new ProcessBuilder(powershell.toString(),"-NoLogo","-NoProfile","-NonInteractive","-WindowStyle","Hidden",
                    "-EncodedCommand",command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            var ready=new CompletableFuture<Void>();
            reader=Thread.ofPlatform().daemon().name("system-audio-pcm").start(() -> {
                try(var input=process.getInputStream()) {
                    if(!Arrays.equals(input.readNBytes(8),"MMRSA01\n".getBytes(StandardCharsets.US_ASCII))) throw new IOException();
                    ready.complete(null);
                    while(!closed.get()) {
                        byte[] block=input.readNBytes(1920);if(block.length!=1920) throw new EOFException();
                        if(!frames.offer(block)) {frames.poll();frames.offer(block);}
                    }
                } catch(Exception failure) {if(!closed.get()) failed.set(true);ready.completeExceptionally(new IllegalStateException("SYSTEM_AUDIO_UNAVAILABLE"));}
            });
            ready.get(12,TimeUnit.SECONDS);
            clock.scheduleAtFixedRate(() -> {
                if(closed.get() || failed.get()) return;
                byte[] block=frames.poll();
                try {samples.accept(block==null?new byte[1920]:block);}
                catch(RuntimeException | LinkageError failure) {failed.set(true);}
            },0,10,TimeUnit.MILLISECONDS);
        } catch(Exception | LinkageError failure) {close();throw new IllegalStateException("SYSTEM_AUDIO_UNAVAILABLE");}
    }
    @Override public boolean failed() {return failed.get();}
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        clock.shutdownNow();
        try {if(!clock.awaitTermination(2,TimeUnit.SECONDS)) failed.set(true);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        if(process!=null) {
            try {process.getOutputStream().write('\n');process.getOutputStream().flush();process.getOutputStream().close();} catch(IOException ignored) { }
            try {if(!process.waitFor(2,TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2,TimeUnit.SECONDS);}
            catch(InterruptedException interrupted) {process.destroyForcibly();Thread.currentThread().interrupt();}
        }
        if(reader!=null) try {reader.join(2000);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        frames.clear();
        if(directory!=null) try {Files.deleteIfExists(directory.resolve("SystemAudioLoopback.cs"));Files.deleteIfExists(directory);} catch(IOException ignored) { }
    }
}
