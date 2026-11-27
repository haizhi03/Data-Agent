package edu.zsc.ai.common.converter.db;

import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlColumn;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlExecutionInfo;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlMessage;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlMessageLevel;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponseType;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResultSet;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlSubResult;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlWriteTarget;
import edu.zsc.ai.plugin.model.command.sql.SqlColumnInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageLevel;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandSubResult;
import edu.zsc.ai.plugin.model.transaction.BatchExecutionState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class SqlExecutionConverter {

    private SqlExecutionConverter() {
    }

    /**
     * Convert plugin SqlCommandResult to ExecuteSqlResponse DTO.
     */
    public static ExecuteSqlResponse toResponse(SqlCommandResult r) {
        return toResponse(r, null);
    }

    /**
     * Convert plugin SqlCommandResult to ExecuteSqlResponse DTO, tagging the batch state
     * when the statement was part of a batch.
     */
    public static ExecuteSqlResponse toResponse(SqlCommandResult r, BatchExecutionState batchState) {
        if (r == null) {
            return null;
        }
        ExecuteSqlResponseType type = resolveType(r);
        ExecuteSqlResultSet resultSet = buildResultSet(r);
        ExecuteSqlExecutionInfo executionInfo = buildExecutionInfo(r);
        List<ExecuteSqlMessage> messages = buildMessages(r);
        List<ExecuteSqlSubResult> results = buildSubResults(r);
        return ExecuteSqlResponse.builder()
                .success(r.isSuccess())
                .errorMessage(r.getErrorMessage())
                .executionTimeMs(r.getExecutionTime())
                .originalSql(r.getOriginalSql())
                .executedSql(r.getExecutedSql())
                .query(r.isQuery())
                .headers(r.getHeaders())
                .rows(r.getRows())
                .affectedRows(r.getAffectedRows())
                .type(type)
                .resultSet(resultSet)
                .executionInfo(executionInfo)
                .messages(messages)
                .results(results)
                .statementState(r.getStatementState())
                .transactionOutcome(r.getTransactionOutcome())
                .batchState(batchState)
                .build();
    }

    private static ExecuteSqlResponseType resolveType(SqlCommandResult r) {
        if (!r.isSuccess()) {
            return ExecuteSqlResponseType.ERROR;
        }
        return r.isQuery() ? ExecuteSqlResponseType.QUERY : ExecuteSqlResponseType.UPDATE;
    }

    private static ExecuteSqlResultSet buildResultSet(SqlCommandResult r) {
        if (!r.isQuery()) {
            return null;
        }
        return buildResultSet(r.getColumns(), r.getHeaders(), r.getRows(), r.getTruncated(),
                r.getSourceTablePrimaryKeyColumns());
    }

    private static ExecuteSqlResultSet buildResultSet(SqlCommandSubResult r) {
        if (r == null || !r.isQuery()) {
            return null;
        }
        return buildResultSet(r.getColumns(), r.getHeaders(), r.getRows(), r.getTruncated(),
                r.getSourceTablePrimaryKeyColumns());
    }

    private static ExecuteSqlResultSet buildResultSet(
            List<SqlColumnInfo> columnInfos,
            List<String> headers,
            List<List<Object>> rows,
            Boolean truncated,
            List<String> sourceTablePrimaryKeys) {
        List<ExecuteSqlColumn> columns = null;
        if (columnInfos != null) {
            columns = columnInfos.stream()
                    .map(SqlExecutionConverter::toExecuteSqlColumn)
                    .collect(Collectors.toList());
        } else if (headers != null) {
            columns = headers.stream()
                    .map(header -> ExecuteSqlColumn.builder()
                            .name(header)
                            .label(header)
                            .build())
                    .collect(Collectors.toList());
        }
        Integer fetchRows = rows == null ? null : rows.size();
        return ExecuteSqlResultSet.builder()
                .columns(columns)
                .rows(rows)
                .fetchRows(fetchRows)
                .truncated(truncated)
                .writeTarget(buildWriteTarget(columns, rows, sourceTablePrimaryKeys))
                .build();
    }

    /**
     * Resolve whether the result rows can be written back and to which table row.
     * Purely metadata-driven: no extra database round trip. Ambiguous matches are
     * still guarded by the batch write's force confirmation.
     *
     * @param sourceTablePrimaryKeys the source table's complete key, or null when it
     *                               could not be resolved
     */
    private static ExecuteSqlWriteTarget buildWriteTarget(List<ExecuteSqlColumn> columns, List<List<Object>> rows,
                                                          List<String> sourceTablePrimaryKeys) {
        if (columns == null || columns.isEmpty()) {
            return null;
        }
        boolean allFromTable = columns.stream()
                .allMatch(column -> column.getTableName() != null && !column.getTableName().isBlank());
        if (!allFromTable) {
            return ExecuteSqlWriteTarget.builder().editable(false).reason("DERIVED_COLUMN").build();
        }
        Set<String> sourceTables = columns.stream()
                .map(column -> column.getCatalogName() + "\u0000" + column.getSchemaName() + "\u0000" + column.getTableName())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (sourceTables.size() > 1) {
            return ExecuteSqlWriteTarget.builder().editable(false).reason("MULTIPLE_TABLES").build();
        }

        List<Integer> primaryKeyIndexes = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (Boolean.TRUE.equals(columns.get(i).getPrimaryKey())) {
                primaryKeyIndexes.add(i);
            }
        }
        ExecuteSqlColumn first = columns.get(0);
        ExecuteSqlWriteTarget target = ExecuteSqlWriteTarget.builder()
                .catalog(first.getCatalogName())
                .schema(first.getSchemaName())
                .table(first.getTableName())
                .build();
        if (primaryKeyIndexes.isEmpty()) {
            // "Key metadata unreadable" and "table has no key" both block editing
            // but tell the user different things.
            target.setReason(sourceTablePrimaryKeys == null ? "PRIMARY_KEY_UNAVAILABLE" : "NO_PRIMARY_KEY");
            return target;
        }
        // A partially selected composite key would match by a non-unique prefix.
        if (sourceTablePrimaryKeys != null && !sourceTablePrimaryKeys.isEmpty()) {
            List<String> missingKeys = missingKeyColumns(columns, primaryKeyIndexes, sourceTablePrimaryKeys);
            if (!missingKeys.isEmpty()) {
                target.setReason("MISSING_PRIMARY_KEY");
                target.setPrimaryKeyColumns(missingKeys);
                return target;
            }
        }
        primaryKeyIndexes.sort(Comparator.comparing(index -> {
            Integer ordinal = columns.get(index).getPrimaryKeyOrdinal();
            return ordinal == null ? Integer.MAX_VALUE : ordinal;
        }));
        target.setPrimaryKeyColumns(primaryKeyIndexes.stream()
                .map(index -> columns.get(index).getName())
                .collect(Collectors.toList()));

        Set<String> seenKeys = new HashSet<>();
        for (List<Object> row : rows == null ? List.<List<Object>>of() : rows) {
            StringBuilder key = new StringBuilder();
            for (int index : primaryKeyIndexes) {
                Object value = index < row.size() ? row.get(index) : null;
                if (value == null) {
                    target.setReason("NULL_PRIMARY_KEY");
                    return target;
                }
                key.append(value).append('\u0000');
            }
            if (!seenKeys.add(key.toString())) {
                target.setReason("DUPLICATE_PRIMARY_KEY");
                return target;
            }
        }
        target.setEditable(true);
        target.setObjectType("TABLE");
        // A result row can only be matched by the primary key: the selected column
        // list is not guaranteed to be unique.
        target.setMatchColumns(target.getPrimaryKeyColumns());
        return target;
    }

    /** Key columns of the source table that the result does not carry. */
    private static List<String> missingKeyColumns(List<ExecuteSqlColumn> columns,
                                                  List<Integer> primaryKeyIndexes,
                                                  List<String> sourceTablePrimaryKeys) {
        Set<String> selected = primaryKeyIndexes.stream()
                .map(index -> columns.get(index).getName())
                .filter(Objects::nonNull)
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return sourceTablePrimaryKeys.stream()
                .filter(Objects::nonNull)
                .filter(name -> !selected.contains(name.toUpperCase(Locale.ROOT)))
                .toList();
    }

    private static ExecuteSqlColumn toExecuteSqlColumn(SqlColumnInfo col) {
        return ExecuteSqlColumn.builder()
                .name(col.getName())
                .label(col.getLabel())
                .typeName(col.getTypeName())
                .jdbcType(col.getJdbcType())
                .precision(col.getPrecision())
                .scale(col.getScale())
                .nullable(col.getNullable())
                .tableName(col.getTableName())
                .catalogName(col.getCatalogName())
                .schemaName(col.getSchemaName())
                .primaryKey(col.getPrimaryKey())
                .primaryKeyOrdinal(col.getPrimaryKeyOrdinal())
                .comment(col.getComment())
                .build();
    }

    private static List<ExecuteSqlSubResult> buildSubResults(SqlCommandResult r) {
        if (r.getResults() == null || r.getResults().isEmpty()) {
            return Collections.emptyList();
        }
        return r.getResults()
                .stream()
                .map(SqlExecutionConverter::mapSubResult)
                .collect(Collectors.toList());
    }

    private static ExecuteSqlSubResult mapSubResult(SqlCommandSubResult sub) {
        ExecuteSqlResponseType type = sub.isQuery() ? ExecuteSqlResponseType.QUERY : ExecuteSqlResponseType.UPDATE;
        ExecuteSqlResultSet resultSet = buildResultSet(sub);
        List<ExecuteSqlMessage> messages = buildMessages(sub);
        return ExecuteSqlSubResult.builder()
                .type(type)
                .resultSet(resultSet)
                .headers(sub.getHeaders())
                .rows(sub.getRows())
                .affectedRows(sub.getAffectedRows())
                .messages(messages)
                .build();
    }

    private static ExecuteSqlExecutionInfo buildExecutionInfo(SqlCommandResult r) {
        return ExecuteSqlExecutionInfo.builder()
                .executionId(null)
                .startTime(r.getStartTime())
                .endTime(r.getEndTime())
                .durationMs(r.getExecutionTime())
                .executionMs(r.getExecutionMs())
                .fetchingMs(r.getFetchingMs())
                .affectedRows(r.getAffectedRows())
                .fetchRows(r.getFetchRows())
                .truncated(r.getTruncated())
                .limitApplied(r.getLimitApplied())
                .build();
    }

    private static List<ExecuteSqlMessage> buildMessages(SqlCommandResult r) {
        List<ExecuteSqlMessage> messages;
        if (r.getMessages() != null && !r.getMessages().isEmpty()) {
            messages = r.getMessages()
                    .stream()
                    .map(SqlExecutionConverter::mapMessage)
                    .collect(Collectors.toList());
        } else if (r.getErrorMessage() == null || r.getErrorMessage().isBlank()) {
            messages = new java.util.ArrayList<>();
        } else {
            ExecuteSqlMessage message = ExecuteSqlMessage.builder()
                    .level(ExecuteSqlMessageLevel.ERROR)
                    .code(r.getErrorCode() == null ? null : String.valueOf(r.getErrorCode()))
                    .sqlState(r.getSqlState())
                    .message(r.getErrorMessage())
                    .detail(r.getErrorDetail())
                    .build();
            messages = new java.util.ArrayList<>(Collections.singletonList(message));
        }
        if (r.isSuccess()) {
            ExecuteSqlMessage summary = buildSummaryMessage(r);
            if (summary != null) {
                messages.add(summary);
            }
        }
        return messages;
    }

    private static List<ExecuteSqlMessage> buildMessages(SqlCommandSubResult r) {
        if (r == null || r.getMessages() == null || r.getMessages().isEmpty()) {
            return Collections.emptyList();
        }
        return r.getMessages()
                .stream()
                .map(SqlExecutionConverter::mapMessage)
                .collect(Collectors.toList());
    }

    private static ExecuteSqlMessage mapMessage(SqlMessageInfo info) {
        return ExecuteSqlMessage.builder()
                .level(mapLevel(info.getLevel()))
                .code(info.getCode())
                .sqlState(info.getSqlState())
                .message(info.getMessage())
                .detail(info.getDetail())
                .build();
    }

    private static ExecuteSqlMessageLevel mapLevel(SqlMessageLevel level) {
        if (level == null) {
            return ExecuteSqlMessageLevel.INFO;
        }
        if (level == SqlMessageLevel.WARN) {
            return ExecuteSqlMessageLevel.WARN;
        }
        if (level == SqlMessageLevel.ERROR) {
            return ExecuteSqlMessageLevel.ERROR;
        }
        return ExecuteSqlMessageLevel.INFO;
    }

    private static ExecuteSqlMessage buildSummaryMessage(SqlCommandResult r) {
        ExecuteSqlResponseType type = resolveType(r);
        String message;
        if (type == ExecuteSqlResponseType.QUERY) {
            Integer fetchRows = r.getFetchRows() != null ? r.getFetchRows()
                    : (r.getRows() == null ? 0 : r.getRows().size());
            message = String.format("Query OK, fetched %d rows in %d ms", fetchRows, r.getExecutionTime());
        } else if (type == ExecuteSqlResponseType.UPDATE) {
            message = String.format("Update OK, affected %d rows in %d ms", r.getAffectedRows(),
                    r.getExecutionTime());
        } else {
            return null;
        }
        return ExecuteSqlMessage.builder()
                .level(ExecuteSqlMessageLevel.INFO)
                .message(message)
                .build();
    }
}
