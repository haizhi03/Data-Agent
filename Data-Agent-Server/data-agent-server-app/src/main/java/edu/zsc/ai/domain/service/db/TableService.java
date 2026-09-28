package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.BatchTableRowOperationRequest;
import edu.zsc.ai.domain.model.dto.response.db.BatchTableRowsResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.plugin.model.db.TableRowValue;

import java.util.List;

public interface TableService {

    List<String> getTables(DbContext db);

    List<String> searchTables(DbContext db, String tableNamePattern);

    long countTables(DbContext db, String tableNamePattern);

    long countTableRows(DbContext db, String tableName);

    String getTableDdl(DbContext db, String tableName);

    void deleteTable(DbContext db, String tableName);

    void renameTable(DbContext db, String tableName, String newTableName);

    ExecuteSqlResponse insertRow(DbContext db, String tableName, List<TableRowValue> values);

    ExecuteSqlResponse deleteRow(DbContext db, String tableName, List<TableRowValue> matchValues, boolean force);

    ExecuteSqlResponse updateRow(DbContext db, String tableName, List<TableRowValue> setValues,
            List<TableRowValue> matchValues, boolean force);

    TableDataResponse getTableData(DbContext db, String tableName, Integer currentPage, Integer pageSize);

    TableDataResponse getTableData(DbContext db, String tableName,
            Integer currentPage, Integer pageSize, String whereClause, String orderByColumn, String orderByDirection);

    /**
     * Execute an ordered list of INSERT/UPDATE/DELETE operations within a single
     * database transaction. The transaction commits only when all operations succeed;
     * otherwise it rolls back and the first failure details are returned.
     */
    BatchTableRowsResponse batchTableRows(DbContext db, String tableName,
            List<BatchTableRowOperationRequest> operations, boolean force);
}
