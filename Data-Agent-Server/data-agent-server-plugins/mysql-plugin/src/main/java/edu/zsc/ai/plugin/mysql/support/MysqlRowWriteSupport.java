package edu.zsc.ai.plugin.mysql.support;

import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageLevel;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import edu.zsc.ai.plugin.capability.MysqlIdentifierEscaper;
import edu.zsc.ai.plugin.mysql.util.MysqlIdentifierBuilder;
import org.apache.commons.lang3.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

public final class MysqlRowWriteSupport {

    public static final String DELETE_REQUIRES_FORCE_CODE = "DELETE_REQUIRES_FORCE";
    public static final String UPDATE_REQUIRES_FORCE_CODE = "UPDATE_REQUIRES_FORCE";

    private final MysqlRowWriteSqlTemplate sqlTemplate = new MysqlRowWriteSqlTemplate();

    public SqlCommandResult insertRow(Connection connection, String catalog, String schema, String tableName,
                                      List<TableRowValue> values) {
        if (connection == null || StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Connection and table name must not be null or empty");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Insert values must not be empty");
        }

        String fullTableName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, tableName);
        List<String> quotedColumns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Insert column name must not be blank");
            }
            quotedColumns.add(MysqlIdentifierEscaper.getInstance().quoteIdentifier(entry.columnName().trim()));
            params.add(normalizePreparedValue(entry.value()));
        }

        String sql = sqlTemplate.buildInsertRowSql(fullTableName, quotedColumns);

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

        String fullTableName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, tableName);
        MatchClause matchClause = buildMatchClause(matchValues, false);
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

        String fullTableName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, tableName);
        SetClause setClause = buildSetClause(setValues, false);
        MatchClause matchClause = buildMatchClause(matchValues, false);
        String sql = sqlTemplate.buildUpdateRowSql(fullTableName, setClause.setSql(), matchClause.whereSql());

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

    /** Render the INSERT this support would execute, with inline literals. */
    public String previewInsertRowSql(String catalog, String schema, String tableName,
                                      List<TableRowValue> values) {
        if (StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Table name must not be null or empty");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Insert values must not be empty");
        }

        String fullTableName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, tableName);
        List<String> quotedColumns = new ArrayList<>();
        List<String> literals = new ArrayList<>();
        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Insert column name must not be blank");
            }
            quotedColumns.add(MysqlIdentifierEscaper.getInstance().quoteIdentifier(entry.columnName().trim()));
            literals.add(toLiteral(normalizePreparedValue(entry.value())));
        }
        return sqlTemplate.buildInsertRowSql(fullTableName, quotedColumns, literals);
    }

    /** Render the UPDATE this support would execute, with inline literals. */
    public String previewUpdateRowSql(String catalog, String schema, String tableName,
                                      List<TableRowValue> setValues, List<TableRowValue> matchValues) {
        if (StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Table name must not be null or empty");
        }
        if (setValues == null || setValues.isEmpty()) {
            throw new IllegalArgumentException("Update set values must not be empty");
        }
        if (matchValues == null || matchValues.isEmpty()) {
            throw new IllegalArgumentException("Update match values must not be empty");
        }

        String fullTableName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, tableName);
        SetClause setClause = buildSetClause(setValues, true);
        MatchClause matchClause = buildMatchClause(matchValues, true);
        return sqlTemplate.buildUpdateRowSql(fullTableName, setClause.setSql(), matchClause.whereSql());
    }

    /** Render the DELETE this support would execute, with inline literals. */
    public String previewDeleteRowSql(String catalog, String schema, String tableName,
                                      List<TableRowValue> matchValues) {
        if (StringUtils.isBlank(tableName)) {
            throw new IllegalArgumentException("Table name must not be null or empty");
        }
        if (matchValues == null || matchValues.isEmpty()) {
            throw new IllegalArgumentException("Delete match values must not be empty");
        }

        String fullTableName = MysqlIdentifierBuilder.buildFullIdentifier(catalog, tableName);
        MatchClause matchClause = buildMatchClause(matchValues, true);
        return sqlTemplate.buildDeleteRowSql(fullTableName, matchClause.whereSql());
    }

    private SetClause buildSetClause(List<TableRowValue> values, boolean inlineLiterals) {
        List<String> assignments = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Set column name must not be blank");
            }

            String quotedColumn = MysqlIdentifierEscaper.getInstance().quoteIdentifier(entry.columnName().trim());
            Object value = normalizePreparedValue(entry.value());
            if (inlineLiterals) {
                assignments.add(quotedColumn + " = " + toLiteral(value));
            } else {
                assignments.add(quotedColumn + " = ?");
                params.add(value);
            }
        }

        return new SetClause(String.join(", ", assignments), params);
    }

    private MatchClause buildMatchClause(List<TableRowValue> values, boolean inlineLiterals) {
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        for (TableRowValue entry : values) {
            if (entry == null || StringUtils.isBlank(entry.columnName())) {
                throw new IllegalArgumentException("Match column name must not be blank");
            }

            String quotedColumn = MysqlIdentifierEscaper.getInstance().quoteIdentifier(entry.columnName().trim());
            Object value = normalizePreparedValue(entry.value());
            if (value == null) {
                predicates.add(quotedColumn + " IS NULL");
            } else if (inlineLiterals) {
                predicates.add(quotedColumn + " = " + toLiteral(value));
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

    /** Render a value as a MySQL literal for previews. */
    private String toLiteral(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return hexLiteral(bytes);
        }
        String text = value.toString();
        // Backslashes (and NUL) are mode-dependent: MySQL reads "\\" as one
        // backslash unless NO_BACKSLASH_ESCAPES is set. A hex literal carries the
        // exact bytes in every sql_mode, so such values are previewed that way.
        if (text.indexOf('\\') >= 0 || text.indexOf('\0') >= 0) {
            return hexLiteral(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return "'" + text.replace("'", "''") + "'";
    }

    private String hexLiteral(byte[] bytes) {
        StringBuilder hex = new StringBuilder("X'");
        for (byte b : bytes) {
            hex.append(String.format("%02X", b));
        }
        return hex.append("'").toString();
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

    private Object normalizePreparedValue(Object value) {
        if (value == null
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof CharSequence
                || value instanceof java.util.Date
                || value instanceof java.time.temporal.TemporalAccessor) {
            return value;
        }
        return String.valueOf(value);
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
