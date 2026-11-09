package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.constant.JdbcMetaDataConstants;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public interface TableManager {

    default List<String> getTableNames(Connection connection, String catalog, String schema) {
        return searchTables(connection, catalog, schema, null);
    }

    default List<String> searchTables(Connection connection, String catalog, String schema, String tableNamePattern) {
        try {
            List<String> list = new ArrayList<>();
            String pattern = StringUtils.isBlank(tableNamePattern) ? null : tableNamePattern;
            try (ResultSet rs = connection.getMetaData().getTables(
                    catalog, schema, pattern, new String[] {JdbcMetaDataConstants.TABLE_TYPE_TABLE})) {
                while (rs.next()) {
                    String name = rs.getString(JdbcMetaDataConstants.TABLE_NAME);
                    if (StringUtils.isNotBlank(name)) {
                        list.add(name);
                    }
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to search tables: " + e.getMessage(), e);
        }
    }

    default long countTables(Connection connection, String catalog, String schema, String tableNamePattern) {
        return searchTables(connection, catalog, schema, tableNamePattern).size();
    }

    default String getTableDdl(Connection connection, String catalog, String schema, String tableName) {
        throw new UnsupportedOperationException("Plugin does not support getting table DDL");
    }

    default String getTableComment(Connection connection, String catalog, String schema, String tableName) {
        return null;
    }

    /**
     * Get non-blank comments for all base tables in the given scope, keyed by
     * table name. The default reads JDBC metadata remarks; plugins whose driver
     * does not expose remarks (e.g. MySQL without useInformationSchema) must
     * override it with a dictionary query.
     */
    default Map<String, String> getTableComments(Connection connection, String catalog, String schema) {
        Map<String, String> comments = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getTables(
                catalog, schema, null, new String[] {JdbcMetaDataConstants.TABLE_TYPE_TABLE})) {
            while (rs.next()) {
                String name = rs.getString(JdbcMetaDataConstants.TABLE_NAME);
                String comment = rs.getString(JdbcMetaDataConstants.REMARKS);
                if (StringUtils.isNotBlank(name) && StringUtils.isNotBlank(comment)) {
                    comments.put(name, comment);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get table comments: " + e.getMessage(), e);
        }
        return comments;
    }

    default void updateTableComment(Connection connection, String catalog, String schema,
                                    String tableName, String comment) {
        throw new UnsupportedOperationException("Plugin does not support updating table comments");
    }

    default void deleteTable(Connection connection, String catalog, String schema, String tableName) {
        throw new UnsupportedOperationException("Plugin does not support deleting table");
    }

    default void renameTable(Connection connection, String catalog, String schema, String tableName, String newTableName) {
        throw new UnsupportedOperationException("Plugin does not support renaming table");
    }

    default SqlCommandResult insertRow(Connection connection, String catalog, String schema, String tableName,
                                       List<TableRowValue> values) {
        throw new UnsupportedOperationException("Plugin does not support inserting table rows");
    }

    default SqlCommandResult deleteRow(Connection connection, String catalog, String schema, String tableName,
                                       List<TableRowValue> matchValues, boolean force) {
        throw new UnsupportedOperationException("Plugin does not support deleting table rows");
    }

    default SqlCommandResult updateRow(Connection connection, String catalog, String schema, String tableName,
                                       List<TableRowValue> setValues, List<TableRowValue> matchValues, boolean force) {
        throw new UnsupportedOperationException("Plugin does not support updating table rows");
    }

    default SqlCommandResult getTableData(Connection connection, String catalog, String schema, String tableName, int offset, int pageSize) {
        throw new UnsupportedOperationException("Plugin does not support getting table data");
    }

    default long getTableDataCount(Connection connection, String catalog, String schema, String tableName) {
        throw new UnsupportedOperationException("Plugin does not support getting table data count");
    }

    /**
     * Get table data with optional WHERE clause and single-column ORDER BY.
     * @param whereClause optional WHERE condition (without "WHERE"), e.g. "status = 1"
     * @param orderByColumn optional column name for ordering
     * @param orderByDirection "asc" or "desc"
     */
    default SqlCommandResult getTableData(Connection connection, String catalog, String schema, String tableName,
            int offset, int pageSize, String whereClause, String orderByColumn, String orderByDirection) {
        if (StringUtils.isBlank(whereClause) && StringUtils.isBlank(orderByColumn)) {
            return getTableData(connection, catalog, schema, tableName, offset, pageSize);
        }
        throw new UnsupportedOperationException("Plugin does not support filtered table data");
    }

    /**
     * Get table row count with optional WHERE clause.
     */
    default long getTableDataCount(Connection connection, String catalog, String schema, String tableName, String whereClause) {
        if (StringUtils.isBlank(whereClause)) {
            return getTableDataCount(connection, catalog, schema, tableName);
        }
        throw new UnsupportedOperationException("Plugin does not support filtered table count");
    }
}
