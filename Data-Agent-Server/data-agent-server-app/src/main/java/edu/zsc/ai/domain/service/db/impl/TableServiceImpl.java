package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.converter.db.SqlExecutionConverter;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.BatchTableRowOperationRequest;
import edu.zsc.ai.domain.model.dto.request.db.TableRowValueRequest;
import edu.zsc.ai.domain.model.dto.response.db.BatchTableRowsResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.command.sql.SqlMessageInfo;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TableServiceImpl implements TableService {

    private final ConnectionService connectionService;

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
                .build();
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
    public BatchTableRowsResponse batchTableRows(DbContext db, String tableName,
                                                  List<BatchTableRowOperationRequest> operations, boolean force) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

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
