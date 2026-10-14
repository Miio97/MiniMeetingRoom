package server.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JdbcPoolTest {
    @Test void reuseRollsBackUncommittedWorkAndResetsTransactionState() throws Exception {
        try (JdbcPool pool = pool()) {
            Object physical;
            int isolation;
            try (Connection first = pool.getConnection()) {
                physical = first.unwrap(org.h2.jdbc.JdbcConnection.class);
                isolation = first.getTransactionIsolation();
                first.createStatement().execute("CREATE TABLE Items (Id INT PRIMARY KEY)");
                first.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); first.setAutoCommit(false);
                first.createStatement().executeUpdate("INSERT INTO Items VALUES (1)");
            }
            try (Connection second = pool.getConnection(); var query = second.createStatement();
                    var rows = query.executeQuery("SELECT COUNT(*) FROM Items")) {
                assertSame(physical, second.unwrap(org.h2.jdbc.JdbcConnection.class));
                assertTrue(second.getAutoCommit()); assertEquals(isolation, second.getTransactionIsolation());
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
    @Test void poolLimitAndWaitAreBoundedAndCloseIsIdempotent() throws Exception {
        JdbcPool pool = pool();
        try (Connection held = pool.getConnection()) {
            long started = System.nanoTime();
            assertThrows(SQLException.class, pool::getConnection);
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1500);
        } finally { pool.close(); pool.close(); }
        assertThrows(SQLException.class, pool::getConnection);
    }
    private JdbcPool pool() {
        Properties properties = new Properties(); properties.setProperty("user", "sa"); properties.setProperty("password", "");
        return new JdbcPool("jdbc:h2:mem:pool_" + UUID.randomUUID(), properties, 1, 250);
    }
}
