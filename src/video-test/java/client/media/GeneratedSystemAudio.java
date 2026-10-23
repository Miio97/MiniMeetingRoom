package client.media;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

final class GeneratedSystemAudio implements SystemAudioInput {
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final AtomicInteger starts,closes;
    GeneratedSystemAudio(AtomicInteger starts,AtomicInteger closes) {this.starts=starts;this.closes=closes;}
    public void start(Consumer<byte[]> sink) {
        starts.incrementAndGet();clock.scheduleAtFixedRate(new Runnable() {
            long sample;
            public void run() {
                byte[] block=new byte[1920];
                for(int i=0;i<480;i++) {
                    short value=(short)(Math.sin(2*Math.PI*660*sample++/48000)*5000);
                    for(int channel=0;channel<2;channel++) {block[i*4+channel*2]=(byte)value;block[i*4+channel*2+1]=(byte)(value>>8);}
                }
                if(!closed.get()) sink.accept(block);
            }
        },0,10,TimeUnit.MILLISECONDS);
    }
    public void close() {
        if(!closed.compareAndSet(false,true)) return;
        closes.incrementAndGet();clock.shutdownNow();
        try {clock.awaitTermination(2,TimeUnit.SECONDS);} catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
    }
}
