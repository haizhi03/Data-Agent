package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.converter.db.SqlExecutionConverter;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.BatchTableRowOperationRequest;
import edu.zsc.ai.domain.model.dto.request.db.TableRowValueRequest;
import edu.zsc.ai.domain.model.dto.response.db.BatchTableRowsResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlWriteTarget;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.domain.service.db.transfer.TableDataExporter;
import edu.zsc.ai.domain.service.db.transfer.TableDataTransferRegistry;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlColumnInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageInfo;
import edu.zsc.ai.plugin.model.db.RowWriteNotSupportedException;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Core table operations: metadata (list/DDL/count), table lifecycle, single-row
 * changes and paged data queries. Format-specific export and import work is
 * delegated to dedicated components under
 * {@link edu.zsc.ai.domain.service.db.transfer}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableServiceImpl implements TableService {

    private final ConnectionService connectionService;

    private final TableDataTransferRegistry transferRegistry;

    @Override
    public List<String> getTables(DbContext db) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return provider.getTableNames(borrowed.connection(), db.catalog(), db.schema());
        }
    }

    @Override
    public List<String> searchTables(DbContext db, String tableNamePattern) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return provider.searchTables(borrowed.connection(), db.catalog(), db.schema(), tableNamePattern);
        }
    }

    @Override
    public long countTables(DbContext db, String tableNamePattern) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return provider.countTables(borrowed.connection(), db.catalog(), db.schema(), tableNamePattern);
        }
    }

    @Override
    public String getTableDdl(DbContext db, String tableName) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return provider.getTableDdl(borrowed.connection(), db.catalog(), db.schema(), tableName);
        }
    }

    @Override
    public Map<String, String> getTableComments(DbContext db) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return loadCommentsSafely(provider, borrowed.connection(), db);
        }
    }

    @Override
    public Map<String, String> getTableCommentsStrict(DbContext db) {
        connectionService.openConnection(db);
        var active = ActiveConnectionRegistry.getOwnedConnection(db);
        var provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (var borrowed = active.borrowConnection()) {
            return provider.getTableComments(borrowed.connection(), db.catalog(), db.schema());
        }
    }

    /**
     * Comments are supplementary metadata: a failed lookup must not hide the
     * table list in the explorer, so degrade to an empty map. Scoped to the
     * provider call only — connection-acquisition failures still propagate.
     */
    private Map<String, String> loadCommentsSafely(TableManager provider, Connection connection, DbContext db) {
        try {
            return provider.getTableComments(connection, db.catalog(), db.schema());
        } catch (RuntimeException e) {
            log.warn("Failed to load table comments: connectionId={}, catalog={}, schema={}",
                    db.connectionId(), db.catalog(), db.schema(), e);
            return Map.of();
        }
    }

    @Override
    public void updateTableComment(DbContext db, String tableName, String comment) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        String normalizedComment = comment == null ? "" : comment.trim();
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            provider.updateTableComment(
                    borrowed.connection(), db.catalog(), db.schema(), tableName, normalizedComment);
        }

        log.info("Table comment updated successfully: connectionId={}, catalog={}, schema={}, tableName={}",
                db.connectionId(), db.catalog(), db.schema(), tableName);
    }

    @Override
    public long countTableRows(DbContext db, String tableName) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return provider.getTableDataCount(borrowed.connection(), db.catalog(), db.schema(), tableName);
        }
    }

    @Override
    public void deleteTable(DbContext db, String tableName) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            provider.deleteTable(borrowed.connection(), db.catalog(), db.schema(), tableName);
        }

        log.info("Table deleted successfully: connectionId={}, catalog={}, schema={}, tableName={}",
                db.connectionId(), db.catalog(), db.schema(), tableName);
    }

    @Override
    public void renameTable(DbContext db, String tableName, String newTableName) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            provider.renameTable(borrowed.connection(), db.catalog(), db.schema(), tableName, newTableName);
        }

        log.info("Table renamed successfully: connectionId={}, catalog={}, schema={}, tableName={}, newTableName={}",
                db.connectionId(), db.catalog(), db.schema(), tableName, newTableName);
    }

    @Override
    public ExecuteSqlResponse insertRow(DbContext db, String tableName, List<TableRowValue> values) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            result = provider.insertRow(borrowed.connection(), db.catalog(), db.schema(), tableName, values);
        }
        return toExecuteSqlResponse(result, db);
    }

    @Override
    public ExecuteSqlResponse deleteRow(DbContext db, String tableName, List<TableRowValue> matchValues, boolean force) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            result = provider.deleteRow(borrowed.connection(), db.catalog(), db.schema(), tableName, matchValues, force);
        }
        return toExecuteSqlResponse(result, db);
    }

    @Override
    public ExecuteSqlResponse updateRow(DbContext db, String tableName, List<TableRowValue> setValues,
                                        List<TableRowValue> matchValues, boolean force) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            result = provider.updateRow(borrowed.connection(), db.catalog(), db.schema(), tableName,
                    setValues, matchValues, force);
        }
        return toExecuteSqlResponse(result, db);
    }

    @Override
    public TableDataResponse getTableData(DbContext db, String tableName,
                                          Integer currentPage, Integer pageSize) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        int offset = (currentPage - 1) * pageSize;
        long totalCount;
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            totalCount = provider.getTableDataCount(borrowed.connection(), db.catalog(), db.schema(), tableName);
            result = provider.getTableData(borrowed.connection(), db.catalog(), db.schema(), tableName, offset, pageSize);
        }

        long totalPages = (totalCount + pageSize - 1) / pageSize;

        return TableDataResponse.builder()
                .headers(result.getHeaders())
                .rows(result.getRows())
                .totalCount(totalCount)
                .currentPage(currentPage)
                .pageSize(pageSize)
                .totalPages(totalPages)
                .writeTarget(buildWriteTarget(db, tableName, result))
                .build();
    }

    @Override
    public TableDataResponse getTableData(DbContext db, String tableName,
            Integer currentPage, Integer pageSize, String whereClause, String orderByColumn, String orderByDirection) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        int offset = (currentPage - 1) * pageSize;
        long totalCount;
        SqlCommandResult result;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            totalCount = provider.getTableDataCount(borrowed.connection(), db.catalog(), db.schema(), tableName, whereClause);
            result = provider.getTableData(borrowed.connection(), db.catalog(), db.schema(), tableName, offset, pageSize,
                    whereClause, orderByColumn, orderByDirection);
        }

        long totalPages = (totalCount + pageSize - 1) / pageSize;

        return TableDataResponse.builder()
                .headers(result.getHeaders())
                .rows(result.getRows())
                .totalCount(totalCount)
                .currentPage(currentPage)
                .pageSize(pageSize)
                .totalPages(totalPages)
                .writeTarget(buildWriteTarget(db, tableName, result))
                .build();
    }

    /**
     * Write target for one table page. A table is writable even without a primary
     * key (rows are then matched by every selected column), and the matching rule
     * is resolved here so the client never chooses one on its own. Both the key
     * and the headers come from the page query, so rendering a page costs no
     * additional metadata round trip.
     */
    private ExecuteSqlWriteTarget buildWriteTarget(DbContext db, String tableName, SqlCommandResult result) {
        List<String> primaryKeyColumns = resolvePrimaryKeyColumns(result.getColumns());
        List<String> matchColumns = primaryKeyColumns.isEmpty()
                ? (result.getHeaders() == null ? List.of() : result.getHeaders())
                : primaryKeyColumns;
        return ExecuteSqlWriteTarget.builder()
                .editable(true)
                .objectType("TABLE")
                .catalog(db.catalog())
                .schema(db.schema())
                .table(tableName)
                .primaryKeyColumns(primaryKeyColumns.isEmpty() ? null : primaryKeyColumns)
                .matchColumns(matchColumns.isEmpty() ? null : matchColumns)
                .build();
    }

    /** Primary key columns in key order, as reported by the page query's metadata. */
    private List<String> resolvePrimaryKeyColumns(List<SqlColumnInfo> columns) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        return columns.stream()
                .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
                .filter(column -> column.getName() != null && !column.getName().isBlank())
                .sorted(Comparator.comparing(column -> column.getPrimaryKeyOrdinal() == null
                        ? Integer.MAX_VALUE
                        : column.getPrimaryKeyOrdinal()))
                .map(SqlColumnInfo::getName)
                .toList();
    }

    private ExecuteSqlResponse toExecuteSqlResponse(SqlCommandResult result, DbContext db) {
        ExecuteSqlResponse response = SqlExecutionConverter.toResponse(result);
        if (response != null) {
            response.setDatabaseName(db.catalog());
            response.setSchemaName(db.schema());
        }
        return response;
    }

    @Override
    public TableDataExporter resolveExporter(String fileType) {
        return transferRegistry.resolveExporter(fileType);
    }

    @Override
    public ImportTableDataResponse importTableData(DbContext db, String tableName, String fileType,
                                                   InputStream inputStream, String encoding) {
        return importTableData(db, tableName, fileType, inputStream, encoding, false);
    }

    @Override
    public ImportTableDataResponse importTableData(DbContext db, String tableName, String fileType,
                                                   InputStream inputStream, String encoding,
                                                   boolean createTableIfMissing) {
        return transferRegistry.resolveImporter(fileType).importData(
                db, tableName, inputStream, encoding, createTableIfMissing);
    }

    @Override
    public BatchTableRowsResponse batchTableRows(DbContext db, String tableName,
                                                 List<BatchTableRowOperationRequest> operations, boolean force,
                                                 String mode, String planHash) {
        // PLAN resolves the plugin from the active connection, not from the connection
        // record: the preview must be rendered by the same plugin instance that will
        // execute the statements, otherwise the two could drift apart.
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

        boolean planMode = "PLAN".equalsIgnoreCase(mode);

        if (planMode) {
            List<String> statements;
            try {
                statements = buildRowStatements(provider, db, tableName, operations);
            } catch (RuntimeException ex) {
                log.warn("Failed to build row write plan: connectionId={}, tableName={}",
                        db.connectionId(), tableName, ex);
                return BatchTableRowsResponse.builder()
                        .success(false)
                        .committed(false)
                        .total(operations.size())
                        .failedAtIndex(0)
                        .errorCode(planErrorCode(ex))
                        .errorMessage(ex.getMessage())
                        .results(List.of())
                        .build();
            }
            return BatchTableRowsResponse.builder()
                    .success(true)
                    .committed(false)
                    .total(operations.size())
                    .statements(statements)
                    .planHash(hashStatements(statements))
                    .results(List.of())
                    .build();
        }

        if (planHash != null && !planHash.isBlank()) {
            try {
                List<String> expected = buildRowStatements(provider, db, tableName, operations);
                if (!planHash.equals(hashStatements(expected))) {
                    return BatchTableRowsResponse.builder()
                            .success(false)
                            .committed(false)
                            .total(operations.size())
                            .succeeded(0)
                            .failedAtIndex(0)
                            .errorCode("PLAN_STALE")
                            .errorMessage("The staged changes differ from the previewed SQL. Preview again and retry.")
                            .statements(expected)
                            .planHash(hashStatements(expected))
                            .results(List.of())
                            .build();
                }
            } catch (RuntimeException ex) {
                // A value the provider refuses to write is not a hash mismatch: fail
                // with the same code EXECUTE would return instead of running blind.
                if (ex instanceof RowWriteNotSupportedException rejected) {
                    return BatchTableRowsResponse.builder()
                            .success(false)
                            .committed(false)
                            .total(operations.size())
                            .succeeded(0)
                            .failedAtIndex(0)
                            .errorCode(rejected.code())
                            .errorMessage(ex.getMessage())
                            .results(List.of())
                            .build();
                }
                // The client asked for its preview to be validated; if the expected
                // statements cannot be rebuilt, refuse instead of executing blind.
                log.warn("Rejecting batch write: plan hash could not be validated, connectionId={}, tableName={}",
                        db.connectionId(), tableName, ex);
                return BatchTableRowsResponse.builder()
                        .success(false)
                        .committed(false)
                        .total(operations.size())
                        .succeeded(0)
                        .failedAtIndex(0)
                        .errorCode("PLAN_UNAVAILABLE")
                        .errorMessage("The previewed SQL could not be validated. Retry, and execute without a preview if it persists.")
                        .results(List.of())
                        .build();
            }
        }

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();
            boolean originalAutoCommit = connection.getAutoCommit();

            List<BatchTableRowsResponse.ItemResult> itemResults = new ArrayList<>();
            boolean allSuccess = true;
            Integer failedAtIndex = null;
            String errorMessage = null;
            String forceCode = null;
            int succeeded = 0;

            connection.setAutoCommit(false);
            try {
                for (int i = 0; i < operations.size(); i++) {
                    BatchTableRowOperationRequest operation = operations.get(i);
                    SqlCommandResult commandResult = dispatchBatchOperation(
                            provider, connection, db, tableName, operation, force);

                    String code = extractForceCode(commandResult);
                    itemResults.add(BatchTableRowsResponse.ItemResult.builder()
                            .type(operation.getType())
                            .success(commandResult.isSuccess())
                            .affectedRows(commandResult.getAffectedRows())
                            .errorCode(code)
                            .errorMessage(commandResult.getErrorMessage())
                            .build());

                    if (!commandResult.isSuccess()) {
                        allSuccess = false;
                        failedAtIndex = i;
                        errorMessage = commandResult.getErrorMessage();
                        forceCode = code;
                        break;
                    }
                    succeeded++;
                }

                if (allSuccess) {
                    connection.commit();
                } else {
                    connection.rollback();
                }
            } catch (Exception ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }

            return BatchTableRowsResponse.builder()
                    .success(allSuccess)
                    .committed(allSuccess)
                    .requiresForce(forceCode != null)
                    .forceCode(forceCode)
                    .total(operations.size())
                    .succeeded(succeeded)
                    .failedAtIndex(failedAtIndex)
                    .errorMessage(errorMessage)
                    .results(itemResults)
                    .build();
        } catch (SQLException ex) {
            throw new RuntimeException("Batch row transaction failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Render the statements EXECUTE would run. The plugin owns both the preview
     * and the executed statement, so the two cannot drift apart.
     */
    private List<String> buildRowStatements(TableManager provider, DbContext db, String tableName,
                                            List<BatchTableRowOperationRequest> operations) {        List<String> statements = new ArrayList<>(operations.size());
        for (BatchTableRowOperationRequest operation : operations) {
            String type = operation.getType() == null ? "" : operation.getType().trim().toUpperCase();
            statements.add(switch (type) {
                case "INSERT" -> provider.previewInsertRowSql(db.catalog(), db.schema(), tableName,
                        toPluginRowValues(operation.getValues()));
                case "UPDATE" -> provider.previewUpdateRowSql(db.catalog(), db.schema(), tableName,
                        toPluginRowValues(operation.getSetValues()),
                        toPluginRowValues(operation.getMatchValues()));
                case "DELETE" -> provider.previewDeleteRowSql(db.catalog(), db.schema(), tableName,
                        toPluginRowValues(operation.getMatchValues()));
                default -> throw new IllegalArgumentException(
                        "Unknown batch operation type: " + operation.getType());
            });
        }
        return statements;
    }

    /** Error code for a failed plan: a provider rejection keeps its own code. */
    private static String planErrorCode(RuntimeException ex) {
        return ex instanceof RowWriteNotSupportedException rejected ? rejected.code() : "PLAN_FAILED";
    }

    private String hashStatements(List<String> statements) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", statements).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private SqlCommandResult dispatchBatchOperation(TableManager provider, Connection connection, DbContext db,
                                                     String tableName, BatchTableRowOperationRequest operation,
                                                     boolean force) {
        String type = operation.getType() == null ? "" : operation.getType().trim().toUpperCase();
        return switch (type) {
            case "INSERT" -> provider.insertRow(connection, db.catalog(), db.schema(), tableName,
                    toPluginRowValues(operation.getValues()));
            case "UPDATE" -> provider.updateRow(connection, db.catalog(), db.schema(), tableName,
                    toPluginRowValues(operation.getSetValues()),
                    toPluginRowValues(operation.getMatchValues()), force);
            case "DELETE" -> provider.deleteRow(connection, db.catalog(), db.schema(), tableName,
                    toPluginRowValues(operation.getMatchValues()), force);
            default -> throw new IllegalArgumentException("Unknown batch operation type: " + operation.getType());
        };
    }

    private List<TableRowValue> toPluginRowValues(List<TableRowValueRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.stream()
                .filter(item -> item != null && item.getColumnName() != null)
                .map(item -> new TableRowValue(item.getColumnName(), item.getValue()))
                .toList();
    }

    private String extractForceCode(SqlCommandResult result) {
        if (result == null || result.isSuccess() || result.getMessages() == null) {
            return null;
        }
        for (SqlMessageInfo message : result.getMessages()) {
            String code = message.getCode();
            if ("UPDATE_REQUIRES_FORCE".equals(code) || "DELETE_REQUIRES_FORCE".equals(code)) {
                return code;
            }
        }
        return null;

    }
}
