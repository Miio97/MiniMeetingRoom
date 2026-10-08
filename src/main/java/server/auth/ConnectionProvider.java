package server.auth;

import java.sql.Connection;
import java.sql.SQLException;

/** Injection seam for database integration tests and alternative server connection pools. */
@FunctionalInterface
public interface ConnectionProvider {
    Connection getConnection() throws SQLException;
}
