package server.database;

import java.util.Locale;

/** Read-only SELECT 1 benchmark. Prints timing only, never configuration or driver errors. */
public final class MeasureDatabaseLatency {
    private MeasureDatabaseLatency() { }
    public static void main(String[] args) {
        try {
            for (int sample = 0; sample < 4; sample++) {
                long before = System.nanoTime();
                try (var connection = DatabaseConnection.getConnection()) {
                    long borrowed = System.nanoTime();
                    connection.setReadOnly(true);
                    try (var statement = connection.createStatement()) {
                        statement.setQueryTimeout(5);
                        try (var rows = statement.executeQuery("SELECT 1")) {
                            if (!rows.next() || rows.getInt(1) != 1) throw new IllegalStateException("PROBE_FAILED");
                        }
                    }
                    System.out.printf(Locale.ROOT, "DB_LATENCY sample=%d borrow_ms=%.3f readonly_query_ms=%.3f%n",
                            sample, (borrowed - before) / 1_000_000.0, (System.nanoTime() - borrowed) / 1_000_000.0);
                }
            }
        } catch (Exception failure) {
            System.err.println("DB_LATENCY_PROBE_ERROR_TYPE=" + failure.getClass().getSimpleName());
            if (failure instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[A-Z0-9]{5}")) System.err.println("DB_LATENCY_PROBE_SQLSTATE=" + sql.getSQLState());
            throw new IllegalStateException("DB_LATENCY_PROBE_FAILED");
        } finally {
            DatabaseConnection.close();
            com.mysql.cj.jdbc.AbandonedConnectionCleanupThread.checkedShutdown();
        }
    }
}
