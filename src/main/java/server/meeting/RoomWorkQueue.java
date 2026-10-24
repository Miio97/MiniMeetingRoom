package server.meeting;

import java.util.concurrent.*;

/** Fixed stripes serialize a room without allocating a thread/queue per room. */
public final class RoomWorkQueue implements AutoCloseable {
    private final ThreadPoolExecutor[] stripes;

    public RoomWorkQueue(String name, int workers, int queuedPerWorker) {
        stripes = new ThreadPoolExecutor[workers];
        for (int i = 0; i < workers; i++)
            stripes[i] =
                    new ThreadPoolExecutor(
                            1,
                            1,
                            0,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(queuedPerWorker),
                            Thread.ofPlatform().daemon().name(name + "-" + i).factory());
    }

    public void execute(String room, Runnable operation) {
        stripes[Math.floorMod(room.hashCode(), stripes.length)].execute(operation);
    }

    @Override
    public void close() {
        for (var stripe : stripes) stripe.shutdownNow();
        for (var stripe : stripes)
            try {
                stripe.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
    }
}
