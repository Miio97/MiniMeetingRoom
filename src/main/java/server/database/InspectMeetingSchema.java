package server.database;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import server.auth.ConnectionProvider;

/** Read-only audit of known meeting schema metadata; never reads account or room rows. */
public final class InspectMeetingSchema {
    private static final List<Table> TABLES = List.of(
            new Table("Nguoi_Dung", "Ma_Nguoi_Dung", List.of(
                    column("Ma_Nguoi_Dung", Family.INTEGER, false),
                    column("Ten_Dang_Nhap", Family.TEXT, false),
                    column("Mat_Khau_Bam", Family.TEXT, false),
                    column("Ten_Hien_Thi", Family.TEXT, false),
                    column("Email", Family.TEXT, true),
                    column("Trang_Thai", Family.TEXT, false),
                    column("Ngay_Tao", Family.TIME, false),
                    column("Ngay_Cap_Nhat", Family.TIME, false),
                    column("Lan_Dang_Nhap_Cuoi", Family.TIME, true)),
                    List.of("Ten_Dang_Nhap", "Email"), List.of()),
            new Table("Phien_Dang_Nhap", "Ma_Phien", List.of(
                    column("Ma_Phien", Family.INTEGER, false),
                    column("Ma_Nguoi_Dung", Family.INTEGER, false),
                    column("Token_Bam", Family.TEXT, false),
                    column("Ngay_Tao", Family.TIME, false),
                    column("Het_Han_Luc", Family.TIME, false),
                    column("Thu_Hoi_Luc", Family.TIME, true),
                    column("Thong_Tin_Client", Family.TEXT, true)),
                    List.of("Token_Bam"), List.of(new ForeignKey("Ma_Nguoi_Dung", "Nguoi_Dung", "Ma_Nguoi_Dung"))),
            new Table("Phong_Hop", "Ma_Phong", List.of(
                    column("Ma_Phong", Family.INTEGER, false),
                    column("Ma_Phong_Tham_Gia", Family.TEXT, false),
                    column("Ten_Phong", Family.TEXT, false),
                    column("Ma_PIN_Bam", Family.TEXT, true),
                    column("Ma_Chu_Phong", Family.INTEGER, false),
                    column("Trang_Thai", Family.TEXT, false),
                    column("Da_Khoa", Family.BOOLEAN, false),
                    column("So_Nguoi_Toi_Da", Family.INTEGER, false),
                    column("Ngay_Tao", Family.TIME, false),
                    column("Ngay_Dong", Family.TIME, true)),
                    List.of("Ma_Phong_Tham_Gia"), List.of(new ForeignKey("Ma_Chu_Phong", "Nguoi_Dung", "Ma_Nguoi_Dung"))),
            new Table("Thanh_Vien_Phong", "Ma_Thanh_Vien_Phong", List.of(
                    column("Ma_Thanh_Vien_Phong", Family.INTEGER, false),
                    column("Ma_Phong", Family.INTEGER, false),
                    column("Ma_Nguoi_Dung", Family.INTEGER, false),
                    column("Vai_Tro", Family.TEXT, false),
                    column("Tham_Gia_Luc", Family.TIME, false),
                    column("Roi_Phong_Luc", Family.TIME, true),
                    column("Ly_Do_Roi", Family.TEXT, true)),
                    List.of(), List.of(new ForeignKey("Ma_Phong", "Phong_Hop", "Ma_Phong"),
                            new ForeignKey("Ma_Nguoi_Dung", "Nguoi_Dung", "Ma_Nguoi_Dung"))));

    private InspectMeetingSchema() { }

    public static void main(String[] args) {
        int status = run(DatabaseConnection::getConnection, System.out);
        if (status != 0) System.exit(status);
    }

    /** Exit codes: 0 known structures present, 1 missing/incompatible, 2 inspection failed. */
    public static int run(ConnectionProvider connections, PrintStream output) {
        output.println("MiniMeetingRoom schema inspection v1 (metadata only; no row data)");
        try (Connection connection = connections.getConnection()) {
            Inspection inspection = inspect(connection);
            inspection.lines().forEach(output::println);
            output.println("NOT_VERIFIED check expressions, enum values, defaults, generated values, triggers, privileges");
            output.println("NOT_VERIFIED active membership uniqueness and live room capacity policy");
            output.println("RESULT " + (inspection.missing().isEmpty() ? "KNOWN_STRUCTURES_PRESENT" : "REVIEW_REQUIRED")
                    + " missing_or_incompatible=" + inspection.missing().size());
            return inspection.missing().isEmpty() ? 0 : 1;
        } catch (SQLException | RuntimeException failure) {
            // JDBC exceptions may contain credentials, URL, database names or raw queries.
            output.println("RESULT INSPECTION_FAILED DATABASE_UNAVAILABLE_OR_METADATA_UNSUPPORTED");
            return 2;
        }
    }

    /** Caller owns connection. Requires a fresh autocommit connection and restores its settings. */
    public static Inspection inspect(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Inspection requires a fresh connection");
        boolean previousReadOnly = connection.isReadOnly();
        boolean transactionStarted = false;
        List<String> lines = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        try {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            transactionStarted = true;
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            for (Table table : TABLES) inspectTable(metadata, catalog, schema, table, lines, missing);
            return new Inspection(lines, missing);
        } finally {
            // Never commit, including during restoration. A failed rollback leaves settings untouched
            // so the owning run() closes the connection rather than implicitly committing anything.
            if (transactionStarted) connection.rollback();
            connection.setAutoCommit(true);
            connection.setReadOnly(previousReadOnly);
        }
    }

    private static void inspectTable(DatabaseMetaData metadata, String catalog, String schema, Table table,
            List<String> lines, List<String> missing) throws SQLException {
        String actualName = findTable(metadata, catalog, schema, table.name());
        if (actualName == null) {
            missing(lines, missing, "table " + table.name());
            return;
        }
        lines.add("TABLE " + table.name());
        Map<String, ObservedColumn> observed = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getColumns(catalog, schema, actualName, null)) {
            while (rows.next()) {
                if (!table.name().equalsIgnoreCase(rows.getString("TABLE_NAME"))) continue;
                Column known = table.column(rows.getString("COLUMN_NAME"));
                if (known != null) observed.put(known.name(), new ObservedColumn(rows.getInt("DATA_TYPE"),
                        rows.getInt("COLUMN_SIZE"), rows.getInt("NULLABLE")));
            }
        }
        for (Column column : table.columns()) {
            ObservedColumn value = observed.get(column.name());
            if (value == null) {
                missing(lines, missing, "column " + table.name() + "." + column.name());
                continue;
            }
            lines.add("  COLUMN " + column.name() + " type=" + typeName(value.type())
                    + " size=" + Math.max(0, value.size()) + " nullable=" + nullable(value.nullability()));
            if (!column.family().accepts(value.type())) {
                missing(lines, missing, "compatible_type " + table.name() + "." + column.name());
            }
            if (!column.nullable() && value.nullability() != DatabaseMetaData.columnNoNulls) {
                missing(lines, missing, "not_null " + table.name() + "." + column.name());
            }
        }
        TreeMap<Integer, String> primary = new TreeMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, actualName)) {
            while (rows.next()) {
                Column column = table.column(rows.getString("COLUMN_NAME"));
                primary.put(rows.getInt("KEY_SEQ"), column == null ? "" : column.name());
            }
        }
        if (List.copyOf(primary.values()).equals(List.of(table.primaryKey()))) {
            lines.add("  PRIMARY_KEY (" + table.primaryKey() + ")");
        } else missing(lines, missing, "primary_key " + table.name() + "(" + table.primaryKey() + ")");

        Map<String, Index> indices = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog, schema, actualName, false, false)) {
            while (rows.next()) {
                if (rows.getInt("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                // Constraint/index names stay internal: arbitrary metadata names can contain secrets.
                String name = rows.getString("INDEX_NAME");
                if (name == null) continue;
                Index index = indices.computeIfAbsent(name, ignored -> new Index());
                index.unique = !rows.getBoolean("NON_UNIQUE");
                Column column = table.column(rows.getString("COLUMN_NAME"));
                if (column == null) index.safe = false;
                else index.columns.put(rows.getInt("ORDINAL_POSITION"), column.name());
            }
        }
        for (Index index : indices.values()) {
            if (index.safe && !index.columns.isEmpty()) {
                lines.add("  " + (index.unique ? "UNIQUE_INDEX" : "INDEX")
                        + " (" + String.join(",", index.columns.values()) + ")");
            }
        }
        for (String required : table.unique()) {
            boolean found = indices.values().stream().anyMatch(index -> index.safe && index.unique
                    && List.copyOf(index.columns.values()).equals(List.of(required)));
            if (!found) missing(lines, missing, "unique " + table.name() + "(" + required + ")");
        }
        inspectForeignKeys(metadata, catalog, schema, actualName, table, lines, missing);
    }

    private static void inspectForeignKeys(DatabaseMetaData metadata, String catalog, String schema,
            String actualName, Table table, List<String> lines, List<String> missing) throws SQLException {
        Map<String, ForeignIndex> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getImportedKeys(catalog, schema, actualName)) {
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                // Unnamed composite FKs cannot be grouped reliably from JDBC's sorted rows.
                // Keep them for manual review rather than treating one component as a complete FK.
                boolean named = name != null;
                if (!named) name = "unnamed-" + groups.size();
                ForeignIndex group = groups.computeIfAbsent(name, ignored -> new ForeignIndex());
                if (!named) group.safe = false;
                Column local = table.column(rows.getString("FKCOLUMN_NAME"));
                Table remoteTable = knownTable(rows.getString("PKTABLE_NAME"));
                Column remote = remoteTable == null ? null : remoteTable.column(rows.getString("PKCOLUMN_NAME"));
                // Cross-catalog/schema constraints do not prove a relationship to our audited tables.
                boolean sameCatalog = sameNamespace(catalog, rows.getString("PKTABLE_CAT"));
                boolean sameSchema = sameNamespace(schema, rows.getString("PKTABLE_SCHEM"));
                if (local == null || remote == null || !sameCatalog || !sameSchema) group.safe = false;
                else group.keys.put(rows.getInt("KEY_SEQ"), new ForeignKey(local.name(), remoteTable.name(), remote.name()));
                group.deleteRule = rows.getInt("DELETE_RULE");
                group.updateRule = rows.getInt("UPDATE_RULE");
            }
        }
        for (ForeignKey required : table.foreignKeys()) {
            ForeignIndex found = groups.values().stream().filter(group -> group.safe
                    && List.copyOf(group.keys.values()).equals(List.of(required))).findFirst().orElse(null);
            String description = table.name() + "(" + required.local() + ")->"
                    + required.table() + "(" + required.remote() + ")";
            if (found == null) missing(lines, missing, "foreign_key " + description);
            else lines.add("  FOREIGN_KEY " + description + " delete=" + rule(found.deleteRule)
                    + " update=" + rule(found.updateRule));
        }
    }

    private static String findTable(DatabaseMetaData metadata, String catalog, String schema, String known)
            throws SQLException {
        for (String candidate : List.of(known, known.toUpperCase(Locale.ROOT), known.toLowerCase(Locale.ROOT))) {
            try (ResultSet rows = metadata.getTables(catalog, schema, candidate, new String[]{"TABLE"})) {
                while (rows.next()) {
                    String actual = rows.getString("TABLE_NAME");
                    if (known.equalsIgnoreCase(actual)) return actual;
                }
            }
        }
        return null;
    }

    private static boolean sameNamespace(String current, String referenced) {
        return current == null ? referenced == null : current.equalsIgnoreCase(referenced);
    }

    private static Table knownTable(String name) {
        return TABLES.stream().filter(table -> table.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    private static void missing(List<String> lines, List<String> missing, String description) {
        missing.add(description);
        lines.add("MISSING_OR_INCOMPATIBLE " + description);
    }

    private static Column column(String name, Family family, boolean nullable) {
        return new Column(name, family, nullable);
    }

    private static String nullable(int value) {
        return switch (value) {
            case DatabaseMetaData.columnNoNulls -> "false";
            case DatabaseMetaData.columnNullable -> "true";
            default -> "unknown";
        };
    }

    private static String rule(int value) {
        return switch (value) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeySetNull -> "SET_NULL";
            case DatabaseMetaData.importedKeyNoAction -> "NO_ACTION";
            case DatabaseMetaData.importedKeySetDefault -> "SET_DEFAULT";
            default -> "UNKNOWN";
        };
    }

    private static String typeName(int type) {
        return switch (type) {
            case Types.BIGINT -> "BIGINT";
            case Types.INTEGER -> "INTEGER";
            case Types.SMALLINT -> "SMALLINT";
            case Types.TINYINT -> "TINYINT";
            case Types.VARCHAR -> "VARCHAR";
            case Types.CHAR -> "CHAR";
            case Types.NVARCHAR -> "NVARCHAR";
            case Types.NCHAR -> "NCHAR";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP_WITH_TIMEZONE";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.BIT -> "BIT";
            default -> "OTHER";
        };
    }

    public record Inspection(List<String> lines, List<String> missing) {
        public Inspection { lines = List.copyOf(lines); missing = List.copyOf(missing); }
    }

    private enum Family {
        INTEGER, TEXT, TIME, BOOLEAN;
        boolean accepts(int type) {
            return switch (this) {
                case INTEGER -> type == Types.BIGINT || type == Types.INTEGER || type == Types.SMALLINT || type == Types.TINYINT;
                case TEXT -> type == Types.VARCHAR || type == Types.CHAR || type == Types.NVARCHAR || type == Types.NCHAR;
                case TIME -> type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE;
                case BOOLEAN -> type == Types.BOOLEAN || type == Types.BIT || type == Types.TINYINT;
            };
        }
    }

    private record Column(String name, Family family, boolean nullable) { }
    private record ObservedColumn(int type, int size, int nullability) { }
    private record ForeignKey(String local, String table, String remote) { }
    private record Table(String name, String primaryKey, List<Column> columns, List<String> unique,
            List<ForeignKey> foreignKeys) {
        Column column(String name) {
            return columns.stream().filter(column -> column.name().equalsIgnoreCase(name)).findFirst().orElse(null);
        }
    }
    private static final class Index {
        final TreeMap<Integer, String> columns = new TreeMap<>();
        boolean safe = true, unique;
    }
    private static final class ForeignIndex {
        final TreeMap<Integer, ForeignKey> keys = new TreeMap<>();
        boolean safe = true;
        int deleteRule, updateRule;
    }
}
