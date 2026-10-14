package server.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/** Bounded physical connections; closing a borrowed Connection returns it and resets JDBC state. */
final class JdbcPool implements AutoCloseable {
    private final HikariDataSource source;

    JdbcPool(String url, Properties properties, int maximum, long waitMillis) {
        if (maximum < 1 || maximum > 32 || waitMillis < 250 || waitMillis > 10000)
            throw new IllegalArgumentException("INVALID_POOL_LIMITS");
        HikariConfig config = new HikariConfig();
        config.setPoolName("mmr-db"); config.setJdbcUrl(url); config.setDataSourceProperties(properties);
        config.setMaximumPoolSize(maximum); config.setMinimumIdle(Math.min(2, maximum));
        config.setConnectionTimeout(waitMillis); config.setValidationTimeout(Math.min(2000, waitMillis));
        // Establish one physical connection before serving requests; cold TLS/MySQL setup
        // must not consume the bounded wait intended for borrowing an already-running pool.
        config.setInitializationFailTimeout(1);
        config.setMaxLifetime(600000); config.setIdleTimeout(120000); config.setKeepaliveTime(120000);
        source = new HikariDataSource(config);
    }
    Connection getConnection() throws SQLException { return source.getConnection(); }
    @Override public void close() { source.close(); }
    @Override public String toString() { return "JdbcPool[redacted]"; }
}
