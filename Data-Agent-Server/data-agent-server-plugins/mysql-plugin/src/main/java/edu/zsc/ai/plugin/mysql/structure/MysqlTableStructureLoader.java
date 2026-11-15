package edu.zsc.ai.plugin.mysql.structure;

import edu.zsc.ai.plugin.model.structure.StructureDefaultKind;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads one MySQL base table into a {@link TableStructure}. */
public final class MysqlTableStructureLoader {

    private static final String TABLE_SQL = """
            SELECT t.TABLE_COMMENT, t.ENGINE, t.TABLE_COLLATION, c.CHARACTER_SET_NAME
            FROM information_schema.TABLES t
            LEFT JOIN information_schema.COLLATIONS c ON c.COLLATION_NAME = t.TABLE_COLLATION
            WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ? AND t.TABLE_TYPE = 'BASE TABLE'
            """;

    private static final String COLUMN_SQL = """
            SELECT COLUMN_NAME, ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE, DATA_TYPE, COLUMN_TYPE,
                   EXTRA, COLUMN_COMMENT, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE,
                   CHARACTER_SET_NAME, COLLATION_NAME
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;

    private static final String INDEX_SQL = """
            SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, INDEX_TYPE, SUB_PART
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """;

    private static final String FOREIGN_KEY_SQL = """
            SELECT k.CONSTRAINT_NAME, k.COLUMN_NAME, k.ORDINAL_POSITION,
                   k.REFERENCED_TABLE_SCHEMA, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME,
                   r.UPDATE_RULE, r.DELETE_RULE
            FROM information_schema.KEY_COLUMN_USAGE k
            JOIN information_schema.REFERENTIAL_CONSTRAINTS r
              ON r.CONSTRAINT_SCHEMA = k.TABLE_SCHEMA
             AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
             AND r.TABLE_NAME = k.TABLE_NAME
            WHERE k.TABLE_SCHEMA = ? AND k.TABLE_NAME = ? AND k.REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY k.CONSTRAINT_NAME, k.ORDINAL_POSITION
            """;

    private static final String CHECK_SQL = """
            SELECT c.CONSTRAINT_NAME, c.CHECK_CLAUSE
            FROM information_schema.TABLE_CONSTRAINTS t
            JOIN information_schema.CHECK_CONSTRAINTS c
              ON c.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA
             AND c.CONSTRAINT_NAME = t.CONSTRAINT_NAME
            WHERE t.TABLE_SCHEMA = ? AND t.TABLE_NAME = ? AND t.CONSTRAINT_TYPE = 'CHECK'
            """;

    public TableStructure load(Connection connection, String catalog, String schema, String tableName) {
        String database = StringUtils.isNotBlank(catalog) ? catalog : schema;
        if (connection == null || StringUtils.isBlank(database) || StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Connection, database and table name are required");
        }
        try {
            TableHeader header = queryTable(connection, database, tableName);
            List<Column> columns = queryColumns(connection, database, tableName, header.charset());
            IndexBundle indexes = queryIndexes(connection, database, tableName);
            return new TableStructure(
                    tableName,
                    header.comment(),
                    header.engine(),
                    header.charset(),
                    header.collation(),
                    columns,
                    indexes.primaryKey(),
                    indexes.indexes(),
                    queryForeignKeys(connection, database, tableName),
                    queryChecks(connection, database, tableName)
            );
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to load table structure for " + tableName + ": " + exception.getMessage(), exception);
        }
    }

    private TableHeader queryTable(Connection connection, String database, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(TABLE_SQL)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalArgumentException("Table not found: " + tableName);
                }
                return new TableHeader(
                        StringUtils.defaultString(resultSet.getString("TABLE_COMMENT")),
                        resultSet.getString("ENGINE"),
                        resultSet.getString("CHARACTER_SET_NAME"),
                        resultSet.getString("TABLE_COLLATION")
                );
            }
        }
    }

    private List<Column> queryColumns(Connection connection, String database, String tableName, String tableCharset)
            throws SQLException {
        List<Column> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_SQL)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    columns.add(readColumn(resultSet, tableCharset));
                }
            }
        }
        return columns;
    }

    private Column readColumn(ResultSet resultSet, String tableCharset) throws SQLException {
        String name = resultSet.getString("COLUMN_NAME");
        String dataType = StringUtils.defaultString(resultSet.getString("DATA_TYPE"));
        String columnType = StringUtils.defaultString(resultSet.getString("COLUMN_TYPE"));
        String extra = StringUtils.defaultString(resultSet.getString("EXTRA"));
        String extraLower = extra.toLowerCase(Locale.ROOT);
        String defaultValue = resultSet.getString("COLUMN_DEFAULT");
        boolean expression = extraLower.contains("default_generated") || isExpression(defaultValue);
        String kind = defaultValue == null && !expression
                ? StructureDefaultKind.NONE
                : expression ? StructureDefaultKind.EXPRESSION : StructureDefaultKind.VALUE;
        Integer length = integer(resultSet, "CHARACTER_MAXIMUM_LENGTH");
        Integer scale = null;
        if (isDecimal(dataType)) {
            length = integer(resultSet, "NUMERIC_PRECISION");
            scale = integer(resultSet, "NUMERIC_SCALE");
        }
        String charset = resultSet.getString("CHARACTER_SET_NAME");
        if (charset != null && charset.equalsIgnoreCase(tableCharset)) {
            charset = null;
        }
        String onUpdate = null;
        int onUpdateAt = extraLower.indexOf("on update ");
        if (onUpdateAt >= 0) {
            onUpdate = extra.substring(onUpdateAt + "on update ".length()).trim();
        }
        return new Column(
                name,
                name,
                dataType,
                columnType,
                length,
                scale,
                "YES".equalsIgnoreCase(resultSet.getString("IS_NULLABLE")),
                extraLower.contains("auto_increment"),
                columnType.toLowerCase(Locale.ROOT).contains("unsigned"),
                kind,
                expression || defaultValue != null ? defaultValue : null,
                onUpdate,
                StringUtils.defaultString(resultSet.getString("COLUMN_COMMENT")),
                charset,
                charset == null ? null : resultSet.getString("COLLATION_NAME")
        );
    }

    private IndexBundle queryIndexes(Connection connection, String database, String tableName) throws SQLException {
        Map<String, IndexAccumulator> indexes = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(INDEX_SQL)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("INDEX_NAME");
                    IndexAccumulator accumulator = indexes.get(name);
                    if (accumulator == null) {
                        accumulator = new IndexAccumulator(
                                name,
                                resultSet.getInt("NON_UNIQUE") == 0,
                                resultSet.getString("INDEX_TYPE")
                        );
                        indexes.put(name, accumulator);
                    }
                    accumulator.columns.add(resultSet.getString("COLUMN_NAME"));
                    int prefix = resultSet.getInt("SUB_PART");
                    accumulator.prefixes.add(resultSet.wasNull() ? null : prefix);
                }
            }
        }
        List<String> primaryColumns = List.of();
        List<Index> secondary = new ArrayList<>();
        for (IndexAccumulator accumulator : indexes.values()) {
            if ("PRIMARY".equalsIgnoreCase(accumulator.name)) {
                primaryColumns = copyNullable(accumulator.columns);
            } else {
                secondary.add(new Index(
                        accumulator.name,
                        accumulator.name,
                        accumulator.unique,
                        accumulator.type,
                        copyNullable(accumulator.columns),
                        copyNullable(accumulator.prefixes)
                ));
            }
        }
        PrimaryKey primaryKey = primaryColumns.isEmpty() ? null : new PrimaryKey("PRIMARY", primaryColumns);
        return new IndexBundle(primaryKey, secondary);
    }

    private List<ForeignKey> queryForeignKeys(Connection connection, String database, String tableName) throws SQLException {
        Map<String, ForeignKeyAccumulator> foreignKeys = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(FOREIGN_KEY_SQL)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("CONSTRAINT_NAME");
                    ForeignKeyAccumulator accumulator = foreignKeys.get(name);
                    if (accumulator == null) {
                        accumulator = new ForeignKeyAccumulator(
                                name,
                                resultSet.getString("REFERENCED_TABLE_SCHEMA"),
                                resultSet.getString("REFERENCED_TABLE_NAME"),
                                resultSet.getString("UPDATE_RULE"),
                                resultSet.getString("DELETE_RULE")
                        );
                        foreignKeys.put(name, accumulator);
                    }
                    accumulator.columns.add(resultSet.getString("COLUMN_NAME"));
                    accumulator.referencedColumns.add(resultSet.getString("REFERENCED_COLUMN_NAME"));
                }
            }
        }
        List<ForeignKey> result = new ArrayList<>();
        for (ForeignKeyAccumulator accumulator : foreignKeys.values()) {
            result.add(new ForeignKey(
                    accumulator.name,
                    accumulator.name,
                    copyNullable(accumulator.columns),
                    accumulator.referencedSchema,
                    accumulator.referencedTable,
                    copyNullable(accumulator.referencedColumns),
                    accumulator.onUpdate,
                    accumulator.onDelete
            ));
        }
        return result;
    }

    private List<Check> queryChecks(Connection connection, String database, String tableName) {
        List<Check> checks = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(CHECK_SQL)) {
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString("CONSTRAINT_NAME");
                    checks.add(new Check(name, name, resultSet.getString("CHECK_CLAUSE")));
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }
        return checks;
    }

    /**
     * {@link List#copyOf} rejects null elements. Index prefix lengths and
     * expression-index column names are null in MySQL's statistics table.
     */
    private static <T> List<T> copyNullable(List<T> values) {
        return new ArrayList<>(values);
    }

    private static boolean isExpression(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("current_timestamp") || normalized.startsWith("(");
    }

    private static boolean isDecimal(String dataType) {
        return "decimal".equalsIgnoreCase(dataType) || "numeric".equalsIgnoreCase(dataType);
    }

    private static Integer integer(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        if (resultSet.wasNull() || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    private record TableHeader(String comment, String engine, String charset, String collation) {
    }

    private record IndexBundle(PrimaryKey primaryKey, List<Index> indexes) {
    }

    private static final class IndexAccumulator {
        private final String name;
        private final boolean unique;
        private final String type;
        private final List<String> columns = new ArrayList<>();
        private final List<Integer> prefixes = new ArrayList<>();

        private IndexAccumulator(String name, boolean unique, String type) {
            this.name = name;
            this.unique = unique;
            this.type = type;
        }
    }

    private static final class ForeignKeyAccumulator {
        private final String name;
        private final String referencedSchema;
        private final String referencedTable;
        private final String onUpdate;
        private final String onDelete;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();

        private ForeignKeyAccumulator(String name, String referencedSchema, String referencedTable,
                                      String onUpdate, String onDelete) {
            this.name = name;
            this.referencedSchema = referencedSchema;
            this.referencedTable = referencedTable;
            this.onUpdate = onUpdate;
            this.onDelete = onDelete;
        }
    }
}
