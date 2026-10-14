package server.database;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InspectMeetingSchemaTest {
    private static final Set<String> KNOWN_TABLES = Set.of(
            "nguoi_dung", "phien_dang_nhap", "phong_hop", "thanh_vien_phong");
    private static final String ACCOUNT_DDL = """
            CREATE TABLE Nguoi_Dung (
                Ma_Nguoi_Dung BIGINT PRIMARY KEY,
                Ten_Dang_Nhap VARCHAR(50) NOT NULL,
                Mat_Khau_Bam VARCHAR(255) NOT NULL,
                Ten_Hien_Thi VARCHAR(100) NOT NULL,
                Email VARCHAR(255),
                Trang_Thai VARCHAR(20) NOT NULL,
                Ngay_Tao TIMESTAMP NOT NULL,
                Ngay_Cap_Nhat TIMESTAMP NOT NULL,
                Lan_Dang_Nhap_Cuoi TIMESTAMP,
                secret_extra_column VARCHAR(255),
                CONSTRAINT private_unique_name UNIQUE (Ten_Dang_Nhap),
                CONSTRAINT private_email_index UNIQUE (Email)
            )
            """;
    private String url;

    @BeforeEach
    void createDatabase() {
        url = "jdbc:h2:mem:private_database_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
    }

    @AfterEach
    void cleanDatabase() throws SQLException {
        execute("DROP ALL OBJECTS");
    }

    @Test
    void auditsKnownStructuresWithoutReadingRowsAndRollsBackReadOnlyTransaction() throws Exception {
        completeSchema(ACCOUNT_DDL);
        execute("CREATE TABLE private_secret_table (private_column VARCHAR(255))");
        execute("CREATE INDEX private_mixed_index ON Nguoi_Dung(Ten_Dang_Nhap, secret_extra_column)");
        execute("""
                INSERT INTO Nguoi_Dung VALUES
                (1, 'private_username', 'private_password_hash', 'private_display_name',
                 'private_email@example.invalid', 'HOAT_DONG', CURRENT_TIMESTAMP,
                 CURRENT_TIMESTAMP, NULL, 'private_column_value')
                """);
        execute("""
                INSERT INTO Phien_Dang_Nhap VALUES
                (1, 1, 'private_token_hash', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, 'private_client')
                """);
        execute("""
                INSERT INTO Phong_Hop VALUES
                (1, 'private_join_code', 'private_room_name', 'private_pin_hash', 1,
                 'CHO', FALSE, 10, CURRENT_TIMESTAMP, NULL)
                """);

        List<String> calls = new ArrayList<>();
        InspectMeetingSchema.Inspection inspection;
        try (Connection connection = DriverManager.getConnection(url)) {
            inspection = InspectMeetingSchema.inspect(guarded(connection, calls));
            assertTrue(connection.getAutoCommit());
            assertEquals(List.of("readOnly=true", "autoCommit=false", "rollback", "autoCommit=true", "readOnly=false"), calls);
        }
        assertTrue(inspection.missing().isEmpty(), () -> String.join("\n", inspection.missing()));
        String output = String.join("\n", inspection.lines());
        assertEquals(4, inspection.lines().stream().filter(line -> line.startsWith("TABLE ")).count());
        assertTrue(output.contains("COLUMN Token_Bam type=VARCHAR"));
        assertTrue(output.contains("PRIMARY_KEY (Ma_Phong)"));
        assertTrue(output.contains("UNIQUE_INDEX (Ma_Phong_Tham_Gia)"));
        assertTrue(output.contains("FOREIGN_KEY Thanh_Vien_Phong(Ma_Phong)->Phong_Hop(Ma_Phong)"));
        assertFalse(output.contains("private_"));
        assertFalse(output.contains("secret_extra_column"));
        assertFalse(output.contains(url));
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM Phong_Hop")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
        }
    }

    @Test
    void reportsMissingTablesColumnsRequiredConstraintsAndIncompatibleColumns() throws Exception {
        execute("""
                CREATE TABLE Nguoi_Dung (
                    Ma_Nguoi_Dung VARCHAR(100),
                    Ten_Dang_Nhap VARCHAR(50),
                    Mat_Khau_Bam VARCHAR(255),
                    Ten_Hien_Thi VARCHAR(100),
                    Email VARCHAR(255)
                )
                """);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT,
                    Ma_Nguoi_Dung BIGINT,
                    Token_Bam VARCHAR(255)
                )
                """);
        try (Connection connection = DriverManager.getConnection(url)) {
            List<String> missing = InspectMeetingSchema.inspect(connection).missing();
            assertTrue(missing.contains("table Phong_Hop"));
            assertTrue(missing.contains("table Thanh_Vien_Phong"));
            assertTrue(missing.contains("column Phien_Dang_Nhap.Het_Han_Luc"));
            assertTrue(missing.contains("compatible_type Nguoi_Dung.Ma_Nguoi_Dung"));
            assertTrue(missing.contains("not_null Nguoi_Dung.Ten_Dang_Nhap"));
            assertTrue(missing.contains("primary_key Phien_Dang_Nhap(Ma_Phien)"));
            assertTrue(missing.contains("unique Phien_Dang_Nhap(Token_Bam)"));
            assertTrue(missing.contains("foreign_key Phien_Dang_Nhap(Ma_Nguoi_Dung)->Nguoi_Dung(Ma_Nguoi_Dung)"));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(1, InspectMeetingSchema.run(() -> DriverManager.getConnection(url), stream(output)));
        String report = output.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("RESULT REVIEW_REQUIRED"));
        assertTrue(report.contains("NOT_VERIFIED check expressions, enum values"));
        assertFalse(report.contains(url));
    }

    @Test
    void compositeUniqueAndForeignKeyToAnotherSchemaDoNotSatisfyRequiredConstraints() throws Exception {
        completeSchema(ACCOUNT_DDL.replace("UNIQUE (Ten_Dang_Nhap)", "UNIQUE (Ten_Dang_Nhap, Ten_Hien_Thi)"));
        execute("CREATE SCHEMA private_other_schema");
        execute("CREATE TABLE private_other_schema.Nguoi_Dung (Ma_Nguoi_Dung BIGINT PRIMARY KEY)");
        execute("ALTER TABLE Phien_Dang_Nhap DROP CONSTRAINT private_session_fk");
        execute("""
                ALTER TABLE Phien_Dang_Nhap ADD CONSTRAINT private_wrong_schema_fk
                FOREIGN KEY (Ma_Nguoi_Dung) REFERENCES private_other_schema.Nguoi_Dung(Ma_Nguoi_Dung)
                """);
        try (Connection connection = DriverManager.getConnection(url)) {
            var inspection = InspectMeetingSchema.inspect(connection);
            assertTrue(inspection.missing().contains("unique Nguoi_Dung(Ten_Dang_Nhap)"));
            assertTrue(inspection.missing().contains(
                    "foreign_key Phien_Dang_Nhap(Ma_Nguoi_Dung)->Nguoi_Dung(Ma_Nguoi_Dung)"));
            assertFalse(String.join("\n", inspection.lines()).contains("private_"));
        }
    }

    @Test
    void failuresNeverPrintSqlMessagesConnectionDetailsOrCredentials() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = InspectMeetingSchema.run(() -> {
            throw new SQLException("jdbc:mysql://private-db/private-name user=private-user password=private-password SELECT secret");
        }, stream(output));
        assertEquals(2, status);
        String report = output.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("RESULT INSPECTION_FAILED"));
        assertFalse(report.contains("private"));
        assertFalse(report.contains("SELECT"));
        assertFalse(report.contains("SQLException"));
    }

    @Test
    void refusesAnExistingTransactionWithoutRollingBackCallerWork() throws Exception {
        List<String> calls = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> InspectMeetingSchema.inspect(guarded(connection, calls)));
            assertFalse(connection.getAutoCommit());
            assertTrue(calls.isEmpty());
            connection.rollback();
        }
    }

    @Test
    void rollsBackAndRestoresSettingsWhenMetadataFails() throws Exception {
        List<String> calls = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url)) {
            Connection guarded = guarded(connection, calls);
            Connection failing = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getMetaData")) throw new SQLException("private_metadata_error");
                        try { return method.invoke(guarded, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
            assertThrows(SQLException.class, () -> InspectMeetingSchema.inspect(failing));
            assertEquals(List.of("readOnly=true", "autoCommit=false", "rollback", "autoCommit=true", "readOnly=false"), calls);
            assertTrue(connection.getAutoCommit());
        }
    }

    private Connection guarded(Connection connection, List<String> calls) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (Set.of("createStatement", "prepareStatement", "prepareCall", "commit").contains(name)) {
                        fail("Inspector must not issue SQL or commit: " + name);
                    }
                    if (name.equals("setReadOnly")) calls.add("readOnly=" + args[0]);
                    if (name.equals("setAutoCommit")) calls.add("autoCommit=" + args[0]);
                    if (name.equals("rollback")) calls.add("rollback");
                    try {
                        Object result = method.invoke(connection, args);
                        if (name.equals("getMetaData")) return guardedMetadata((DatabaseMetaData) result);
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private DatabaseMetaData guardedMetadata(DatabaseMetaData metadata) {
        return (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (Set.of("getURL", "getUserName", "getCatalogs", "getSchemas").contains(name)) {
                        fail("Inspector must not enumerate identities: " + name);
                    }
                    if (Set.of("getTables", "getColumns", "getPrimaryKeys", "getIndexInfo", "getImportedKeys").contains(name)) {
                        assertTrue(KNOWN_TABLES.contains(((String) args[2]).toLowerCase(java.util.Locale.ROOT)), name);
                    }
                    try { return method.invoke(metadata, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private void completeSchema(String accountDdl) throws SQLException {
        execute(accountDdl);
        execute("""
                CREATE TABLE Phien_Dang_Nhap (
                    Ma_Phien BIGINT PRIMARY KEY,
                    Ma_Nguoi_Dung BIGINT NOT NULL,
                    Token_Bam VARCHAR(255) NOT NULL UNIQUE,
                    Ngay_Tao TIMESTAMP NOT NULL,
                    Het_Han_Luc TIMESTAMP NOT NULL,
                    Thu_Hoi_Luc TIMESTAMP,
                    Thong_Tin_Client VARCHAR(255),
                    CONSTRAINT private_session_fk FOREIGN KEY (Ma_Nguoi_Dung) REFERENCES Nguoi_Dung(Ma_Nguoi_Dung)
                )
                """);
        execute("""
                CREATE TABLE Phong_Hop (
                    Ma_Phong BIGINT PRIMARY KEY,
                    Ma_Phong_Tham_Gia VARCHAR(20) NOT NULL UNIQUE,
                    Ten_Phong VARCHAR(100) NOT NULL,
                    Ma_PIN_Bam VARCHAR(255),
                    Ma_Chu_Phong BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Trang_Thai VARCHAR(20) NOT NULL,
                    Da_Khoa BOOLEAN NOT NULL,
                    So_Nguoi_Toi_Da SMALLINT NOT NULL CHECK (So_Nguoi_Toi_Da > 0),
                    Ngay_Tao TIMESTAMP NOT NULL,
                    Ngay_Dong TIMESTAMP
                )
                """);
        execute("""
                CREATE TABLE Thanh_Vien_Phong (
                    Ma_Thanh_Vien_Phong BIGINT PRIMARY KEY,
                    Ma_Phong BIGINT NOT NULL REFERENCES Phong_Hop(Ma_Phong),
                    Ma_Nguoi_Dung BIGINT NOT NULL REFERENCES Nguoi_Dung(Ma_Nguoi_Dung),
                    Vai_Tro VARCHAR(20) NOT NULL,
                    Tham_Gia_Luc TIMESTAMP NOT NULL,
                    Roi_Phong_Luc TIMESTAMP,
                    Ly_Do_Roi VARCHAR(20)
                )
                """);
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static PrintStream stream(ByteArrayOutputStream output) {
        return new PrintStream(output, true, StandardCharsets.UTF_8);
    }
}
