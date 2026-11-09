package edu.zsc.ai.plugin.dm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import edu.zsc.ai.plugin.constant.DatabaseObjectTypeEnum;
import edu.zsc.ai.plugin.model.command.sql.AbstractSqlExecutor;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.dm.constant.DmSqlTemplate;
import edu.zsc.ai.plugin.dm.value.DmValueProcessor;
import edu.zsc.ai.plugin.dm.util.DmIdentifierBuilder;
import edu.zsc.ai.plugin.dm.util.DmIdentifierEscaper;
import edu.zsc.ai.plugin.value.JdbcValueContext;
import edu.zsc.ai.plugin.value.ValueProcessor;

/**
 * Shared helpers for DM metadata managers: object DDL via DBMS_METADATA,
 * DROP statements, and paginated table/view data queries.
 */
public final class DmMetadataSupport {

    private final AbstractSqlExecutor sqlExecutor = new DmSqlExecutor();

    public SqlCommandResult execute(Connection connection, String schema, String sql) {
        return sqlExecutor.executeCommand(
                SqlCommandRequest.ofWithoutTransaction(connection, sql, sql, null, schema));
    }

    /**
     * Resolve the effective schema for dictionary queries while preserving the
     * database-reported spelling of quoted, case-sensitive identifiers.
     */
    public String resolveSchema(Connection connection, String schema) {
        if (StringUtils.isNotBlank(schema)) {
            return schema.trim();
        }
        String current = null;
        try {
            current = connection.getSchema();
        } catch (Throwable ignored) {
            // some drivers do not implement Connection#getSchema
        }
        if (StringUtils.isBlank(current)) {
            try (PreparedStatement statement = connection.prepareStatement(DmSqlTemplate.SQL_CURRENT_USER);
                 ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    current = resultSet.getString(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to resolve current DM schema: " + e.getMessage(), e);
            }
        }
        if (StringUtils.isBlank(current)) {
            throw new IllegalArgumentException("Schema must not be blank for DM metadata operations");
        }
        return current.trim();
    }

    /**
     * Get object DDL via DBMS_METADATA.GET_DDL using the exact metadata name.
     */
    public String getObjectDdl(Connection connection, String schema, String objectName,
                               String ddlSql, String objectType) {
        requireConnectionAndName(connection, objectName);

        String owner = resolveSchema(connection, schema);
        String name = objectName.trim();

        try (PreparedStatement statement = connection.prepareStatement(ddlSql)) {
            statement.setString(1, name);
            statement.setString(2, owner);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String ddl = resultSet.getString(1);
                    if (StringUtils.isNotBlank(ddl)) {
                        return ddl;
                    }
                }
            }
            throw new RuntimeException(String.format("Failed to get %s DDL: No result returned", objectType));
        } catch (SQLException e) {
            throw new RuntimeException(String.format("Failed to get %s DDL: %s", objectType, e.getMessage()), e);
        }
    }

    public String getTableComment(Connection connection, String owner, String tableName) {
        try (PreparedStatement statement = connection.prepareStatement(DmSqlTemplate.SQL_TABLE_COMMENT)) {
            statement.setString(1, owner);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get table comment: " + e.getMessage(), e);
        }
        return null;
    }

    public Map<String, String> getTableComments(Connection connection, String owner) {
        if (connection == null || StringUtils.isBlank(owner)) {
            return Map.of();
        }

        Map<String, String> comments = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(DmSqlTemplate.SQL_TABLE_COMMENTS)) {
            statement.setString(1, owner);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString(1);
                    String comment = resultSet.getString(2);
                    if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(comment)) {
                        comments.put(name, comment);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get table comments: " + e.getMessage(), e);
        }
        return comments;
    }

    public String buildTableCommentSql(String schema, String tableName, String comment) {
        String fullName = DmIdentifierBuilder.buildFullIdentifier(schema, tableName);
        String escapedComment = DmIdentifierEscaper.getInstance()
                .escapeStringLiteral(comment == null ? "" : comment);
        return String.format(DmSqlTemplate.SQL_COMMENT_ON_TABLE, fullName, escapedComment);
    }

    public void updateTableComment(Connection connection, String schema, String tableName, String comment) {
        requireConnectionAndName(connection, tableName);

        String sql = buildTableCommentSql(schema, tableName.trim(), comment);
        SqlCommandResult result = execute(connection, schema, sql);
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to update table comment: " + result.getErrorMessage());
        }
    }

    /**
     * Query column comments from ALL_COL_COMMENTS.
     *
     * @return rows of [COLUMN_NAME, COMMENTS], comments may be null
     */
    public List<String[]> getColumnComments(Connection connection, String owner, String tableName) {
        List<String[]> comments = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(DmSqlTemplate.SQL_COLUMN_COMMENTS)) {
            statement.setString(1, owner);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    comments.add(new String[]{resultSet.getString(1), resultSet.getString(2)});
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get column comments: " + e.getMessage(), e);
        }
        return comments;
    }

    public Set<String> getAutoIncrementColumns(Connection connection, String owner, String tableName) {
        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(DmSqlTemplate.SQL_AUTO_INCREMENT_COLUMNS)) {
            statement.setString(1, tableName);
            statement.setString(2, owner);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String name = resultSet.getString(1);
                    if (StringUtils.isNotBlank(name)) {
                        columns.add(name);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get auto-increment columns: " + e.getMessage(), e);
        }
        return columns;
    }

    public void dropObject(Connection connection, String schema, String objectName,
                           String sqlTemplate, DatabaseObjectTypeEnum objectType) {
        requireConnectionAndName(connection, objectName);

        String fullName = DmIdentifierBuilder.buildFullIdentifier(schema, objectName);
        String sql = String.format(sqlTemplate, fullName);

        SqlCommandResult result = execute(connection, schema, sql);
        if (!result.isSuccess()) {
            throw new RuntimeException(String.format(
                    "Failed to delete %s: %s", objectType.getValue(), result.getErrorMessage()));
        }
    }

    public void renameObject(Connection connection, String schema, String objectName,
                             String newObjectName, String sqlTemplate, String objectType) {
        requireConnectionAndName(connection, objectName);
        if (StringUtils.isBlank(newObjectName)) {
            throw new IllegalArgumentException("New object name must not be null or empty");
        }

        String fullName = DmIdentifierBuilder.buildFullIdentifier(schema, objectName);
        String quotedNewName = DmIdentifierEscaper.getInstance().quoteIdentifier(newObjectName.trim());
        String sql = String.format(sqlTemplate, fullName, quotedNewName);

        SqlCommandResult result = execute(connection, schema, sql);
        if (!result.isSuccess()) {
            throw new RuntimeException(String.format(
                    "Failed to rename %s: %s", objectType, result.getErrorMessage()));
        }
    }

    public SqlCommandResult getTableLikeData(Connection connection, String schema,
                                             String objectName, int offset, int pageSize) {
        requireConnectionAndName(connection, objectName);

        String fullObjectName = DmIdentifierBuilder.buildFullIdentifier(schema, objectName);
        String sql = String.format(DmSqlTemplate.SQL_SELECT_TABLE_DATA, fullObjectName, offset, pageSize);

        SqlCommandResult result = execute(connection, schema, sql);
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to get table data: " + result.getErrorMessage());
        }
        return result;
    }

    public SqlCommandResult getTableLikeData(Connection connection, String schema, String objectName,
                                             int offset, int pageSize, String whereClause,
                                             String orderByColumn, String orderByDirection) {
        requireConnectionAndName(connection, objectName);

        String fullObjectName = DmIdentifierBuilder.buildFullIdentifier(schema, objectName);
        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(fullObjectName);

        if (StringUtils.isNotBlank(whereClause)) {
            sql.append(" WHERE ").append(whereClause);
        }
        if (StringUtils.isNotBlank(orderByColumn)) {
            String direction = "desc".equalsIgnoreCase(orderByDirection) ? "DESC" : "ASC";
            String quotedColumn = DmIdentifierEscaper.getInstance().quoteIdentifier(orderByColumn.trim());
            sql.append(" ORDER BY ").append(quotedColumn).append(" ").append(direction);
        }
        sql.append(" LIMIT ").append(offset).append(", ").append(pageSize);

        SqlCommandResult result = execute(connection, schema, sql.toString());
        if (!result.isSuccess()) {
            throw new RuntimeException("Failed to get table data: " + result.getErrorMessage());
        }
        return result;
    }

    public long getTableLikeDataCount(Connection connection, String schema, String objectName) {
        return getTableLikeDataCount(connection, schema, objectName, null);
    }

    public long getTableLikeDataCount(Connection connection, String schema, String objectName, String whereClause) {
        requireConnectionAndName(connection, objectName);

        String fullObjectName = DmIdentifierBuilder.buildFullIdentifier(schema, objectName);
        String sql = StringUtils.isNotBlank(whereClause)
                ? "SELECT COUNT(*) AS total FROM " + fullObjectName + " WHERE " + whereClause
                : String.format(DmSqlTemplate.SQL_COUNT_TABLE_DATA, fullObjectName);

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getLong("total");
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get table data count: " + e.getMessage(), e);
        }
    }

    private void requireConnectionAndName(Connection connection, String objectName) {
        if (connection == null || StringUtils.isBlank(objectName)) {
            throw new IllegalArgumentException("Connection and object name must not be null or empty");
        }
    }

    /**
     * Minimal DM SQL executor using the DM-specific value processor,
     * consistent with the main {@code DmSqlExecutor} in the executor package.
     */
    private static final class DmSqlExecutor extends AbstractSqlExecutor {

        private static final ValueProcessor VALUE_PROCESSOR = DmValueProcessor.INSTANCE;

        @Override
        protected Object getJdbcValue(JdbcValueContext context) throws SQLException {
            return VALUE_PROCESSOR.getJdbcValue(context);
        }
    }
}
