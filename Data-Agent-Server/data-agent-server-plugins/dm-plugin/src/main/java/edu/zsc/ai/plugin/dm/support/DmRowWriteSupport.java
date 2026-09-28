package edu.zsc.ai.plugin.dm.support;

import edu.zsc.ai.plugin.dm.value.DmLobPreviewValues;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageLevel;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Row-level INSERT/UPDATE/DELETE support for DM (DaMeng).
 *
 * <p>DM-specific behaviour compared with the MySQL counterpart:
 * <ul>
 *   <li>Identifiers are always double-quoted and fully qualified as
 *       {@code "schema"."table"} (schema is the qualifier in DM; catalog is
 *       only used as a fallback).</li>
 *   <li>Empty strings and SQL NULL remain distinct JDBC inputs. The server's
 *       compatibility mode decides how an empty string is stored.</li>
 *   <li>Truncated LOB preview placeholders ({@code [BLOB: xMB]} /
 *       {@code [CLOB: xMB]}, produced by the DM value processors for large
 *       objects) are refused as INSERT/UPDATE inputs: they are size metadata,
 *       not content, and writing them back would silently corrupt the LOB
 *       (live-verified). Rejected with code
 *       {@link DmLobPreviewValues#PREVIEW_NOT_WRITABLE_CODE}.</li>
 * </ul>
 *
 * @author hhz
 */
public final class DmRowWriteSupport {

    public static final String DELETE_REQUIRES_FORCE_CODE = "DELETE_REQUIRES_FORCE";
    public static final String UPDATE_REQUIRES_FORCE_CODE = "UPDATE_REQUIRES_FORCE";

    private final DmRowWriteSqlTemplate sqlTemplate = new DmRowWriteSqlTemplate();

    public SqlCommandResult insertRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> values) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Connection and table name must not be null or empty");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Insert values must not be empty");
        }

        String fullTableName = buildFullTableName(catalog, schema, tableName);
        List<String> quotedColumns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Insert column name must not be blank");
            }
            quotedColumns.add(DmIdentifierQuoter.quote(entry.columnName().trim()));
            params.add(normalizePreparedValue(entry.value()));
        }

        String sql = sqlTemplate.buildInsertRowSql(fullTableName, quotedColumns);

        TableRowValue preview = firstTruncatedLobPreview(values);
        if (preview != null) {
            return buildPreviewRejectedResult(sql, preview);
        }

        return executePreparedUpdate(connection, sql, params);
    }

    public SqlCommandResult deleteRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> matchValues, boolean force) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Connection and table name must not be null or empty");
        }
        if (matchValues == null || matchValues.isEmpty()) {
            throw new IllegalArgumentException("Delete match values must not be empty");
        }

        String fullTableName = buildFullTableName(catalog, schema, tableName);
        MatchClause matchClause = buildMatchClause(matchValues);
        long matchedRows = countRowsByMatch(connection, fullTableName, matchClause);

        if (matchedRows == 0) {
            return buildFailedUpdateResult(sqlTemplate.buildDeleteRowSql(fullTableName, matchClause.whereSql()),
                    "No rows matched the selected row");
        }
        if (matchedRows > 1 && !force) {
            return buildFailedUpdateResult(
                    sqlTemplate.buildDeleteRowSql(fullTableName, matchClause.whereSql()),
                    String.format("Delete target is ambiguous: matched %d rows. Retry with force=true to continue.", matchedRows),
                    DELETE_REQUIRES_FORCE_CODE,
                    matchedRows
            );
        }

        String sql = sqlTemplate.buildDeleteRowSql(fullTableName, matchClause.whereSql());
        return executePreparedUpdate(connection, sql, matchClause.params());
    }

    public SqlCommandResult updateRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> setValues, List<TableRowValue> matchValues, boolean force) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Connection and table name must not be null or empty");
        }
        if (setValues == null || setValues.isEmpty()) {
            throw new IllegalArgumentException("Update set values must not be empty");
        }
        if (matchValues == null || matchValues.isEmpty()) {
            throw new IllegalArgumentException("Update match values must not be empty");
        }

        String fullTableName = buildFullTableName(catalog, schema, tableName);
        SetClause setClause = buildSetClause(setValues);
        MatchClause matchClause = buildMatchClause(matchValues);
        String sql = sqlTemplate.buildUpdateRowSql(fullTableName, setClause.setSql(), matchClause.whereSql());

        TableRowValue preview = firstTruncatedLobPreview(setValues);
        if (preview != null) {
            return buildPreviewRejectedResult(sql, preview);
        }

        long matchedRows = countRowsByMatch(connection, fullTableName, matchClause);
        if (matchedRows == 0) {
            return buildFailedUpdateResult(sql, "No rows matched the selected row");
        }
        if (matchedRows > 1 && !force) {
            return buildFailedUpdateResult(
                    sql,
                    String.format("Update target is ambiguous: matched %d rows. Retry with force=true to continue.", matchedRows),
                    UPDATE_REQUIRES_FORCE_CODE,
                    matchedRows
            );
        }

        List<Object> params = new ArrayList<>(setClause.params());
        params.addAll(matchClause.params());
        return executePreparedUpdate(connection, sql, params);
    }

    /**
     * Build the fully qualified double-quoted table name.
     * DM qualifies objects by schema; catalog is only a fallback.
     */
    private String buildFullTableName(String catalog, String schema, String tableName) {
        String qualifier = StringUtils.isNotBlank(schema) ? schema : catalog;
        return DmIdentifierQuoter.buildFullIdentifier(qualifier, tableName);
    }

    private SetClause buildSetClause(List<TableRowValue> values) {
        List<String> assignments = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Set column name must not be blank");
            }

            String quotedColumn = DmIdentifierQuoter.quote(entry.columnName().trim());
            assignments.add(quotedColumn + " = ?");
            params.add(normalizePreparedValue(entry.value()));
        }

        return new SetClause(String.join(", ", assignments), params);
    }

    private MatchClause buildMatchClause(List<TableRowValue> values) {
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Match column name must not be blank");
            }

            String quotedColumn = DmIdentifierQuoter.quote(entry.columnName().trim());
            Object value = normalizePreparedValue(entry.value());
            if (value == null) {
                predicates.add(quotedColumn + " IS NULL");
            } else {
                predicates.add(quotedColumn + " = ?");
                params.add(value);
            }
        }

        if (predicates.isEmpty()) {
            throw new IllegalArgumentException("At least one match column is required");
        }

        return new MatchClause(String.join(" AND ", predicates), params);
    }

    private long countRowsByMatch(Connection connection, String fullTableName, MatchClause matchClause) {
        String sql = sqlTemplate.buildCountMatchingRowsSql(fullTableName, matchClause.whereSql());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPreparedParameters(statement, matchClause.params());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getLong("total");
                }
                return 0L;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count matching rows: " + e.getMessage(), e);
        }
    }

    private SqlCommandResult executePreparedUpdate(Connection connection, String sql, List<Object> params) {
        long startTime = System.currentTimeMillis();
        SqlCommandResult result = new SqlCommandResult();
        result.setSuccess(true);
        result.setOriginalSql(sql);
        result.setExecutedSql(sql);
        result.setQuery(false);
        result.setStartTime(startTime);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindPreparedParameters(statement, params);
            int affectedRows = statement.executeUpdate();
            long endTime = System.currentTimeMillis();
            result.setAffectedRows(affectedRows);
            result.setExecutionMs(endTime - startTime);
            result.setFetchingMs(0L);
            result.setEndTime(endTime);
            result.setExecutionTime(endTime - startTime);
            return result;
        } catch (SQLException e) {
            return buildFailedUpdateResult(sql, e);
        }
    }

    private void bindPreparedParameters(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value == null) {
                statement.setNull(i + 1, Types.NULL);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    /**
     * Normalize a value before binding.
     *
     * Preserve an empty string as an empty JDBC value. Only an explicit Java
     * null is bound as SQL NULL or matched with IS NULL.
     */
    private Object normalizePreparedValue(Object value) {
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof java.util.Date
                || value instanceof java.time.temporal.TemporalAccessor) {
            return value;
        }
        if (value instanceof CharSequence charSequence) {
            return charSequence.toString();
        }
        return String.valueOf(value);
    }

    /**
     * Find the first value that is a truncated LOB preview placeholder, or null.
     * Such values are size metadata, not content; see {@link DmLobPreviewValues}.
     */
    private TableRowValue firstTruncatedLobPreview(List<TableRowValue> values) {
        for (TableRowValue entry : values) {
            if (entry != null && DmLobPreviewValues.isTruncatedLobPreview(entry.value())) {
                return entry;
            }
        }
        return null;
    }

    private SqlCommandResult buildPreviewRejectedResult(String sql, TableRowValue entry) {
        SqlCommandResult result = buildFailedUpdateResult(sql,
                DmLobPreviewValues.rejectionMessage(entry.columnName().trim(), entry.value()),
                DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE,
                0L);
        result.setStatementState(StatementExecutionState.NOT_EXECUTED);
        return result;
    }

    private SqlCommandResult buildFailedUpdateResult(String sql, String message) {
        return buildFailedUpdateResult(sql, message, null, 0L);
    }

    private SqlCommandResult buildFailedUpdateResult(String sql, String message, String code, long affectedRows) {
        SqlCommandResult result = new SqlCommandResult();
        result.setSuccess(false);
        result.setOriginalSql(sql);
        result.setExecutedSql(sql);
        result.setQuery(false);
        result.setErrorMessage(message);
        result.setAffectedRows((int) Math.min(Integer.MAX_VALUE, Math.max(0L, affectedRows)));
        result.setMessages(List.of(new SqlMessageInfo(
                SqlMessageLevel.ERROR,
                code,
                null,
                message,
                message
        )));
        return result;
    }

    private SqlCommandResult buildFailedUpdateResult(String sql, SQLException e) {
        SqlCommandResult result = buildFailedUpdateResult(sql, e.getClass().getSimpleName() + ": " + e.getMessage());
        result.setErrorCode(e.getErrorCode());
        result.setSqlState(e.getSQLState());
        result.setErrorDetail(e.toString());
        result.setMessages(List.of(new SqlMessageInfo(
                SqlMessageLevel.ERROR,
                String.valueOf(e.getErrorCode()),
                e.getSQLState(),
                e.getMessage(),
                e.toString()
        )));
        return result;
    }

    private record MatchClause(String whereSql, List<Object> params) {
    }

    private record SetClause(String setSql, List<Object> params) {
    }
}
