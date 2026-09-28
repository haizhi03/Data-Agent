package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.ConstraintManager;
import edu.zsc.ai.plugin.dm.constant.DmObjectSql;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import org.apache.commons.lang3.StringUtils;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** DM constraint discovery; composite FK columns are paired by dictionary POSITION. */
public final class DmConstraintManager implements ConstraintManager {

    private final DmObjectQuerySupport support;

    public DmConstraintManager(DmObjectQuerySupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public List<ConstraintMetadata> getConstraints(Connection connection, String catalog,
                                                   String schema, String tableName) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            return List.of();
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        if (StringUtils.isBlank(effectiveSchema)) {
            return List.of();
        }
        Map<String, ConstraintBuilder> builders = new LinkedHashMap<>();
        for (Map<String, Object> row : support.query(connection, DmObjectSql.SQL_LIST_CONSTRAINTS,
                effectiveSchema, tableName)) {
            String name = DmObjectQuerySupport.stringValue(row.get("CONSTRAINT_NAME"));
            if (name.isBlank()) {
                continue;
            }
            ConstraintBuilder builder = builders.computeIfAbsent(name, ignored -> new ConstraintBuilder(row));
            String column = DmObjectQuerySupport.stringValue(row.get("COLUMN_NAME"));
            if (!column.isBlank()) {
                builder.columns.add(column);
                String referencedColumn = DmObjectQuerySupport.stringValue(row.get("REF_COLUMN_NAME"));
                if (!referencedColumn.isBlank()) {
                    builder.referencedColumns.add(referencedColumn);
                }
            }
        }
        return builders.values().stream().map(ConstraintBuilder::build).toList();
    }

    @Override
    public String getConstraintDdl(Connection connection, String catalog, String schema, String constraintName) {
        return support.getObjectDdl(connection, DmObjectSql.OBJECT_TYPE_CONSTRAINT,
                support.resolveSchema(connection, schema), constraintName);
    }

    @Override
    public void deleteConstraint(Connection connection, String catalog, String schema,
                                 String tableName, String constraintName) {
        if (connection == null || StringUtils.isBlank(tableName) || StringUtils.isBlank(constraintName)) {
            throw new IllegalArgumentException("Connection, table name and constraint name are required");
        }
        String effectiveSchema = support.resolveSchema(connection, schema);
        String sql = String.format(DmObjectSql.SQL_DROP_CONSTRAINT,
                DmObjectQuerySupport.buildFullIdentifier(effectiveSchema, tableName),
                DmObjectQuerySupport.quoteIdentifier(constraintName));
        support.executeDdl(connection, sql, "Failed to delete constraint");
    }

    private static String readText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Clob clob) {
            try {
                return clob.getSubString(1, (int) clob.length());
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to read constraint condition", e);
            }
        }
        return value.toString();
    }

    private static final class ConstraintBuilder {
        private final String name;
        private final String tableName;
        private final String type;
        private final String referencedSchema;
        private final String referencedTable;
        private final String checkExpression;
        private final boolean enabled;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();

        private ConstraintBuilder(Map<String, Object> row) {
            name = DmObjectQuerySupport.stringValue(row.get("CONSTRAINT_NAME"));
            tableName = DmObjectQuerySupport.stringValue(row.get("TABLE_NAME"));
            type = DmObjectQuerySupport.stringValue(row.get("CONSTRAINT_TYPE"));
            referencedSchema = readText(row.get("R_OWNER"));
            referencedTable = readText(row.get("REF_TABLE_NAME"));
            checkExpression = readText(row.get("SEARCH_CONDITION"));
            enabled = "ENABLED".equalsIgnoreCase(DmObjectQuerySupport.stringValue(row.get("STATUS")));
        }

        private ConstraintMetadata build() {
            return new ConstraintMetadata(name, tableName, type, List.copyOf(columns),
                    referencedSchema, referencedTable, List.copyOf(referencedColumns),
                    checkExpression, enabled);
        }
    }
}
