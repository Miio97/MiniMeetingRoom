package server.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import server.config.ServerConfig;

/** Server-side JDBC connections. Credentials belong in ignored local configuration. */
public final class DatabaseConnection {
    private DatabaseConnection() {
    }

    public static Connection getConnection() throws SQLException {
        String url = ServerConfig.get("db.url", "jdbc:mysql://localhost:3306/Mini_Meeting_Room");
        Properties options = new Properties();
        options.setProperty("user", ServerConfig.get("db.user", "root"));
        options.setProperty("password", ServerConfig.get("db.password", ""));
        options.setProperty("connectTimeout", "5000");
        options.setProperty("socketTimeout", "10000");
        options.setProperty("characterEncoding", "UTF-8");
        options.setProperty("connectionTimeZone", "UTC");
        options.setProperty("forceConnectionTimeZoneToSession", "true");
        return DriverManager.getConnection(url, options);
    }
}
