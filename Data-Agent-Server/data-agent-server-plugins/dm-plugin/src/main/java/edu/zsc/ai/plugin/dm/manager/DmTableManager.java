package edu.zsc.ai.plugin.dm.manager;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.constant.DatabaseObjectTypeEnum;
import edu.zsc.ai.plugin.dm.constant.DmSqlTemplate;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.dm.support.DmRowWriteSupport;
import edu.zsc.ai.plugin.dm.util.DmIdentifierBuilder;
import edu.zsc.ai.plugin.dm.util.DmIdentifierEscaper;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.db.TableRowValue;

/**
 * DM table manager. Table listing/counting reuse the SPI default
 * {@code DatabaseMetaData}-based implementations; DDL, DROP and paginated
 * data access are implemented with DM-specific SQL.
 */
public final class DmTableManager implements TableManager {

    private final DmMetadataSupport support;
    private final DmRowWriteSupport rowWriteSupport = new DmRowWriteSupport();

    public DmTableManager(DmMetadataSupport support) {
        this.support = Objects.requireNonNull(support, "support");
    }

    @Override
    public String getTableComment(Connection connection, String catalog, String schema, String tableName) {
        return support.getTableComment(connection, support.resolveSchema(connection, schema), tableName.trim());
    }

    @Override
    public String getTableDdl(Connection connection, String catalog, String schema, String tableName) {
        String owner = support.resolveSchema(connection, schema);
        String ddl = support.getObjectDdl(
                connection,
                owner,
                tableName,
                DmSqlTemplate.SQL_GET_TABLE_DDL,
                DatabaseObjectTypeEnum.TABLE.getValue()
        );

        String exactTableName = tableName.trim();
        StringBuilder script = new StringBuilder(ddl.trim());
        if (script.length() == 0 || script.charAt(script.length() - 1) != ';') {
            script.append(';');
        }

        String fullTableName = DmIdentifierBuilder.buildFullIdentifier(owner, tableName);

        String tableComment = support.getTableComment(connection, owner, exactTableName);
        if (StringUtils.isNotBlank(tableComment)) {
            script.append(System.lineSeparator()).append(String.format(
                    DmSqlTemplate.SQL_COMMENT_ON_TABLE,
                    fullTableName,
                    DmIdentifierEscaper.getInstance().escapeStringLiteral(tableComment.trim())));
        }

        List<String[]> columnComments = support.getColumnComments(connection, owner, exactTableName);
        for (String[] columnComment : columnComments) {
            String columnName = columnComment[0];
            String comment = columnComment[1];
            if (StringUtils.isBlank(columnName) || StringUtils.isBlank(comment)) {
                continue;
            }
            String quotedColumn = DmIdentifierEscaper.getInstance().quoteIdentifier(columnName);
            script.append(System.lineSeparator()).append(String.format(
                    DmSqlTemplate.SQL_COMMENT_ON_COLUMN,
                    fullTableName,
                    quotedColumn,
                    DmIdentifierEscaper.getInstance().escapeStringLiteral(comment.trim())));
        }

        return script.toString();
    }

    @Override
    public void renameTable(Connection connection, String catalog, String schema,
                            String tableName, String newTableName) {
        support.renameObject(
                connection,
                support.resolveSchema(connection, schema),
                tableName,
                newTableName,
                DmSqlTemplate.SQL_RENAME_TABLE,
                DatabaseObjectTypeEnum.TABLE.getValue()
        );
    }

    @Override
    public void deleteTable(Connection connection, String catalog, String schema, String tableName) {
        support.dropObject(
                connection,
                schema,
                tableName,
                DmSqlTemplate.SQL_DROP_TABLE,
                DatabaseObjectTypeEnum.TABLE
        );
    }

    @Override
    public SqlCommandResult getTableData(Connection connection, String catalog, String schema,
                                         String tableName, int offset, int pageSize) {
        return support.getTableLikeData(connection, schema, tableName, offset, pageSize);
    }

    @Override
    public long getTableDataCount(Connection connection, String catalog, String schema, String tableName) {
        return support.getTableLikeDataCount(connection, schema, tableName);
    }

    @Override
    public SqlCommandResult getTableData(Connection connection, String catalog, String schema, String tableName,
                                         int offset, int pageSize, String whereClause,
                                         String orderByColumn, String orderByDirection) {
        return support.getTableLikeData(
                connection,
                schema,
                tableName,
                offset,
                pageSize,
                whereClause,
                orderByColumn,
                orderByDirection
        );
    }

    @Override
    public long getTableDataCount(Connection connection, String catalog, String schema,
                                  String tableName, String whereClause) {
        return support.getTableLikeDataCount(connection, schema, tableName, whereClause);
    }

    @Override
    public SqlCommandResult insertRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> values) {
        return rowWriteSupport.insertRow(connection, catalog, schema, tableName, values);
    }

    @Override
    public SqlCommandResult deleteRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> matchValues, boolean force) {
        return rowWriteSupport.deleteRow(connection, catalog, schema, tableName, matchValues, force);
    }

    @Override
    public SqlCommandResult updateRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> setValues, List<TableRowValue> matchValues,
                                      boolean force) {
        return rowWriteSupport.updateRow(connection, catalog, schema, tableName, setValues, matchValues, force);
    }
}
