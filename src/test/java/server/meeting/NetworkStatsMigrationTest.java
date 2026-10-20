package server.meeting;
import java.sql.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NetworkStatsMigrationTest {
    @Test void destructiveMigrationDropsOnlyStatisticsAndIsIdempotent() throws Exception {
        try(var db=DriverManager.getConnection("jdbc:h2:mem:drop_stats;MODE=MySQL");var sql=db.createStatement()) {
            sql.execute("CREATE TABLE Nguoi_Dung (id INT PRIMARY KEY)");sql.execute("INSERT INTO Nguoi_Dung VALUES (1)");
            sql.execute("CREATE TABLE Phong_Hop (id INT PRIMARY KEY)");sql.execute("INSERT INTO Phong_Hop VALUES (1)");
            sql.execute("CREATE TABLE Thong_Ke_Ket_Noi (id INT PRIMARY KEY, measurement INT)");sql.execute("INSERT INTO Thong_Ke_Ket_Noi VALUES (1,42)");
            String migration=Files.readString(Path.of("database/migrations/004_drop_network_statistics.sql")).replaceAll("(?m)^--.*$","").strip();
            sql.execute(migration);sql.execute(migration);
            assertThrows(SQLException.class,() -> sql.executeQuery("SELECT * FROM Thong_Ke_Ket_Noi"));
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM Nguoi_Dung")) {assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM Phong_Hop")) {assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
        }
        assertFalse(Files.readString(Path.of("database/schema.sql")).contains("CREATE TABLE Thong_Ke_Ket_Noi"));
    }
}
