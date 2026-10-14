package server.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import server.config.ServerConfig;

/** Server-side JDBC connections. Credentials belong in ignored local configuration. */
public final class DatabaseConnection {
    private static JdbcPool pool;
    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        return pool().getConnection();
    }

    private static synchronized JdbcPool pool() throws SQLException {
        if (pool != null) return pool;
        String url = ServerConfig.get("db.url", "jdbc:mysql://localhost:3306/Mini_Meeting_Room");
        Properties options = new Properties();
        options.setProperty("user", ServerConfig.get("db.user", "root"));
        options.setProperty("password", ServerConfig.get("db.password", ""));
        options.setProperty("connectTimeout", "5000");
        options.setProperty("socketTimeout", "10000");
        options.setProperty("characterEncoding", "UTF-8");
        options.setProperty("connectionTimeZone", "UTC");
        options.setProperty("forceConnectionTimeZoneToSession", "true");
        try {
            pool = new JdbcPool(url, options, ServerConfig.getInt("db.pool.maxConnections", 8),
                    ServerConfig.getInt("db.pool.waitMillis", 5000));
            return pool;
        } catch (RuntimeException failure) {
            // Never expose a driver's URL/credentials through a startup exception or console stack.
            throw new SQLException("DATABASE_POOL_UNAVAILABLE", "08001");
        }
    }

    public static synchronized void close() {
        if (pool != null) { pool.close(); pool = null; }
    }
}
