package edu.zsc.ai.plugin.dm.structure;

import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.structure.StructureDefaultKind;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructure.Check;
import edu.zsc.ai.plugin.model.structure.TableStructure.Column;
import edu.zsc.ai.plugin.model.structure.TableStructure.ForeignKey;
import edu.zsc.ai.plugin.model.structure.TableStructure.Index;
import edu.zsc.ai.plugin.model.structure.TableStructure.PrimaryKey;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads one DM table into a {@link TableStructure}. */
public final class DmTableStructureLoader {

    private static final Pattern NOT_NULL_CHECK = Pattern.compile(
            "(?i)^\"?[A-Za-z0-9_$#]+\"?\\s+IS\\s+NOT\\s+NULL$");
    private static final String TABLE_EXISTS_SQL =
            "SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = ? AND TABLE_NAME = ?";
    private static final String COLUMN_SQL =
            "SELECT COLUMN_NAME, DATA_TYPE, DATA_LENGTH, DATA_PRECISION, DATA_SCALE,"
                    + " NULLABLE, DATA_DEFAULT, COLUMN_ID, CHAR_LENGTH"
                    + " FROM ALL_TAB_COLUMNS"
                    + " WHERE OWNER = ? AND TABLE_NAME = ?"
                    + " ORDER BY COLUMN_ID";

    private final DmMetadataSupport metadata;
    private final DmObjectQuerySupport objects;

    public DmTableStructureLoader(DmMetadataSupport metadata, DmObjectQuerySupport objects) {
        this.metadata = metadata;
        this.objects = objects;
    }

    public TableStructure load(Connection connection, String catalog, String schema, String tableName) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Connection and table name are required");
        }
        String owner = objects.resolveSchema(connection, StringUtils.firstNonBlank(schema, catalog));
        if (StringUtils.isBlank(owner)) {
            throw new IllegalArgumentException("Schema is required");
        }
        String table = tableName.trim();
        if (objects.query(connection, TABLE_EXISTS_SQL, owner, table).isEmpty()) {
            throw new IllegalArgumentException("Table not found: " + table);
        }

        Map<String, String> comments = new LinkedHashMap<>();
        for (String[] row : metadata.getColumnComments(connection, owner, table)) {
            if (row.length > 0 && StringUtils.isNotBlank(row[0])) {
                comments.put(row[0], row.length > 1 ? StringUtils.defaultString(row[1]) : "");
            }
        }
        Set<String> identity = metadata.getAutoIncrementColumns(connection, owner, table);
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> row : objects.query(connection, COLUMN_SQL, owner, table)) {
            columns.add(readColumn(row, comments, identity));
        }

        ConstraintBundle bundle = readConstraints(connection, owner, table);

        List<Index> indexes = readIndexes(connection, owner, table);
        String comment = metadata.getTableComment(connection, owner, table);
        return new TableStructure(
                table,
                StringUtils.defaultString(comment),
                null,
                null,
                null,
                columns,
                bundle.primaryKey(),
                indexes,
                bundle.foreignKeys(),
                bundle.checks()
        );
    }

    private ConstraintBundle readConstraints(Connection connection, String owner, String table) {
        Map<String, ForeignKeyAccumulator> foreignKeys = new LinkedHashMap<>();
        Map<String, List<String>> primaryColumns = new LinkedHashMap<>();
        String primaryName = null;
        List<Check> checks = new ArrayList<>();
        Set<String> seenChecks = new java.util.LinkedHashSet<>();
        for (Map<String, Object> row : objects.query(connection, DmObjectSql.SQL_LIST_CONSTRAINTS, owner, table)) {
            String name = DmObjectQuerySupport.stringValue(row.get("CONSTRAINT_NAME"));
            String type = DmObjectQuerySupport.stringValue(row.get("CONSTRAINT_TYPE"));
            String column = DmObjectQuerySupport.stringValue(row.get("COLUMN_NAME"));
            if ("P".equalsIgnoreCase(type)) {
                primaryName = name;
                if (StringUtils.isNotBlank(column)) {
                    primaryColumns.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                }
            } else if ("R".equalsIgnoreCase(type)) {
                ForeignKeyAccumulator accumulator = foreignKeys.computeIfAbsent(name, ignored -> new ForeignKeyAccumulator(
                        name,
                        DmObjectQuerySupport.stringValue(row.get("R_OWNER")),
                        DmObjectQuerySupport.stringValue(row.get("REF_TABLE_NAME"))
                ));
                if (StringUtils.isNotBlank(column)) {
                    accumulator.columns.add(column);
                    String referenced = DmObjectQuerySupport.stringValue(row.get("REF_COLUMN_NAME"));
                    if (StringUtils.isNotBlank(referenced)) {
                        accumulator.referencedColumns.add(referenced);
                    }
                }
            } else if ("C".equalsIgnoreCase(type)) {
                String expression = DmObjectQuerySupport.stringValue(row.get("SEARCH_CONDITION")).trim();
                if (StringUtils.isBlank(expression) || NOT_NULL_CHECK.matcher(expression).matches() || !seenChecks.add(name)) {
                    continue;
                }
                checks.add(new Check(name, name, expression));
            }
        }
        PrimaryKey primaryKey = null;
        if (primaryName != null) {
            primaryKey = new PrimaryKey(primaryName, List.copyOf(primaryColumns.getOrDefault(primaryName, List.of())));
        }
        List<ForeignKey> keys = new ArrayList<>();
        for (ForeignKeyAccumulator accumulator : foreignKeys.values()) {
            keys.add(new ForeignKey(
                    accumulator.name,
                    accumulator.name,
                    List.copyOf(accumulator.columns),
                    StringUtils.trimToNull(accumulator.referencedSchema),
                    accumulator.referencedTable,
                    List.copyOf(accumulator.referencedColumns),
                    null,
                    null
            ));
        }
        return new ConstraintBundle(primaryKey, keys, checks);
    }

    private List<Index> readIndexes(Connection connection, String owner, String table) {
        Map<String, Boolean> uniqueByName = new LinkedHashMap<>();
        for (Map<String, Object> row : objects.query(connection, DmObjectSql.SQL_LIST_INDEXES, owner, table)) {
            if (number(row.get("IS_PRIMARY")) == 1) {
                continue;
            }
            String name = DmObjectQuerySupport.stringValue(row.get("INDEX_NAME"));
            if (StringUtils.isBlank(name)) {
                continue;
            }
            uniqueByName.put(name, "UNIQUE".equalsIgnoreCase(DmObjectQuerySupport.stringValue(row.get("UNIQUENESS"))));
        }
        Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
        for (Map<String, Object> row : objects.query(connection, DmObjectSql.SQL_LIST_INDEX_COLUMNS, owner, table)) {
            String name = DmObjectQuerySupport.stringValue(row.get("INDEX_NAME"));
            String column = DmObjectQuerySupport.stringValue(row.get("COLUMN_NAME"));
            if (uniqueByName.containsKey(name) && StringUtils.isNotBlank(column)) {
                columnsByIndex.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
            }
        }
        List<Index> indexes = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : uniqueByName.entrySet()) {
            List<String> columns = columnsByIndex.getOrDefault(entry.getKey(), List.of());
            List<Integer> prefixes = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                prefixes.add(null);
            }
            indexes.add(new Index(entry.getKey(), entry.getKey(), entry.getValue(), "NORMAL", columns, prefixes));
        }
        return indexes;
    }

    private static Column readColumn(Map<String, Object> row, Map<String, String> comments, Set<String> identity) {
        String name = DmObjectQuerySupport.stringValue(row.get("COLUMN_NAME"));
        String dataType = DmObjectQuerySupport.stringValue(row.get("DATA_TYPE"));
        Integer precision = integer(row.get("DATA_PRECISION"));
        Integer scale = integer(row.get("DATA_SCALE"));
        Integer charLength = integer(row.get("CHAR_LENGTH"));
        if (charLength == null) {
            charLength = integer(row.get("DATA_LENGTH"));
        }
        String defaultRaw = DmObjectQuerySupport.stringValue(row.get("DATA_DEFAULT")).trim();
        String kind = StructureDefaultKind.NONE;
        String defaultValue = null;
        if ("NULL".equalsIgnoreCase(defaultRaw)) {
            kind = StructureDefaultKind.NULL;
        } else if (defaultRaw.startsWith("'") && defaultRaw.endsWith("'") && defaultRaw.length() >= 2) {
            kind = StructureDefaultKind.VALUE;
            defaultValue = defaultRaw.substring(1, defaultRaw.length() - 1).replace("''", "'");
        } else if (defaultRaw.matches("-?\\d+(\\.\\d+)?")) {
            kind = StructureDefaultKind.VALUE;
            defaultValue = defaultRaw;
        } else if (StringUtils.isNotBlank(defaultRaw)) {
            kind = StructureDefaultKind.EXPRESSION;
            defaultValue = defaultRaw;
        }
        boolean numeric = isNumeric(dataType);
        Integer length = numeric ? precision : (isChar(dataType) ? charLength : null);
        return new Column(
                name,
                name,
                dataType,
                buildColumnType(dataType, precision, scale, charLength),
                length,
                numeric ? scale : null,
                "Y".equalsIgnoreCase(DmObjectQuerySupport.stringValue(row.get("NULLABLE"))),
                identity.contains(name),
                false,
                kind,
                defaultValue,
                null,
                comments.getOrDefault(name, ""),
                null,
                null
        );
    }

    private static String buildColumnType(String dataType, Integer precision, Integer scale, Integer charLength) {
        String type = StringUtils.defaultIfBlank(dataType, "VARCHAR");
        if (isNumeric(type) && precision != null && precision > 0) {
            if (scale != null && scale > 0) {
                return type + "(" + precision + "," + scale + ")";
            }
            return type + "(" + precision + ")";
        }
        if (isChar(type) && charLength != null && charLength > 0) {
            return type + "(" + charLength + ")";
        }
        return type;
    }

    private static boolean isNumeric(String dataType) {
        String type = dataType.toUpperCase(Locale.ROOT);
        return type.contains("NUMBER") || type.contains("DECIMAL") || type.contains("NUMERIC");
    }

    private static boolean isChar(String dataType) {
        String type = dataType.toUpperCase(Locale.ROOT);
        return type.contains("CHAR") || type.contains("VARCHAR");
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static int number(Object value) {
        Integer parsed = integer(value);
        return parsed == null ? 0 : parsed;
    }

    private record ConstraintBundle(PrimaryKey primaryKey, List<ForeignKey> foreignKeys, List<Check> checks) {
    }

    private static final class ForeignKeyAccumulator {
        private final String name;
        private final String referencedSchema;
        private final String referencedTable;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();

        private ForeignKeyAccumulator(String name, String referencedSchema, String referencedTable) {
            this.name = name;
            this.referencedSchema = referencedSchema;
            this.referencedTable = referencedTable;
        }
    }
}
