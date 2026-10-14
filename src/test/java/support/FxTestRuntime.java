package support;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import static org.junit.jupiter.api.Assertions.*;

/** One JavaFX runtime shared by hidden-window integration tests in the same JVM. */
public final class FxTestRuntime {
    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static boolean requested;

    private FxTestRuntime() { }

    public static synchronized void start() throws Exception {
        if (!requested) {
            requested = true;
            // Missing graphics support is a test failure, never a silently skipped integration test.
            Platform.startup(() -> { Platform.setImplicitExit(false); STARTED.countDown(); });
        }
        assertTrue(STARTED.await(10, TimeUnit.SECONDS), "JavaFX did not initialize");
    }

    public static <T> T call(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    public static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (call(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for the JavaFX room state");
    }
}
