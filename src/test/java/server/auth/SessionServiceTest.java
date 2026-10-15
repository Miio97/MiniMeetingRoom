package server.auth;

import common.filetransfer.TransferException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00Z");
    private String url;
    private SessionService sessions;

    @BeforeEach void setUp() throws SQLException {
        url = "jdbc:h2:mem:session_" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        execute("CREATE TABLE Nguoi_Dung (Ma_Nguoi_Dung BIGINT PRIMARY KEY, Trang_Thai VARCHAR(20))");
        execute("""
                CREATE TABLE Phien_Dang_Nhap (Token_Bam VARCHAR(255) PRIMARY KEY,
                    Ma_Nguoi_Dung BIGINT REFERENCES Nguoi_Dung, Het_Han_Luc TIMESTAMP,
                    Thu_Hoi_Luc TIMESTAMP NULL)
                """);
        execute("INSERT INTO Nguoi_Dung VALUES (17, 'HOAT_DONG')");
        sessions = new SessionService(() -> DriverManager.getConnection(url), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach void tearDown() throws SQLException { execute("DROP ALL OBJECTS"); }

    @Test void derivesUserFromHashedTokenAndRechecksRevocation() throws Exception {
        addSession("opaque-token", NOW.plusSeconds(1));
        assertEquals(17, sessions.authenticate("opaque-token"));
        rejected("other-token");
        execute("UPDATE Phien_Dang_Nhap SET Thu_Hoi_Luc = CURRENT_TIMESTAMP");
        rejected("opaque-token");
    }

    @Test void expiryBoundaryAndDisabledAccountRejectOtherwiseValidToken() throws Exception {
        addSession("expired", NOW);
        addSession("valid", NOW.plusSeconds(3600));
        rejected("expired");
        assertEquals(17, sessions.authenticate("valid"));
        execute("UPDATE Nguoi_Dung SET Trang_Thai = 'BI_KHOA'");
        rejected("valid");
    }

    @Test void invalidTokenDoesNotOpenDatabaseAndSqlFailuresRemainVisibleToServer() {
        AtomicInteger connections = new AtomicInteger();
        SessionService unavailable = new SessionService(() -> {
            connections.incrementAndGet();
            throw new SQLException("test database failure");
        });
        for (String token : new String[]{null, "", " ", "x".repeat(257)}) {
            assertEquals("INVALID_SESSION", assertThrows(TransferException.class,
                    () -> unavailable.authenticate(token)).code());
        }
        assertEquals(0, connections.get());
        assertThrows(SQLException.class, () -> unavailable.authenticate("valid-size-token"));
        assertEquals(1, connections.get());
    }

    private void rejected(String token) {
        assertEquals("INVALID_SESSION", assertThrows(TransferException.class,
                () -> sessions.authenticate(token)).code());
    }

    private void addSession(String token, Instant expiresAt) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url);
                PreparedStatement statement = connection.prepareStatement("INSERT INTO Phien_Dang_Nhap VALUES (?, 17, ?, NULL)")) {
            statement.setString(1, AuthService.tokenHash(token));
            statement.setTimestamp(2, Timestamp.from(expiresAt), Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.executeUpdate();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
