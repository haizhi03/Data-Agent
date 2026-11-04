package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.BatchTableRowOperationRequest;
import edu.zsc.ai.domain.model.dto.response.db.BatchTableRowsResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.domain.service.db.transfer.TableDataExporter;
import edu.zsc.ai.plugin.model.db.TableRowValue;

import java.io.InputStream;
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

    /**
     * Return the exporter registered for the given format (CSV / JSON / SQL /
     * XLSX / XLS, case-insensitive). The caller uses its metadata (extension,
     * content type) and its export method directly; unsupported formats are
     * rejected with a BusinessException.
     */
    TableDataExporter resolveExporter(String fileType);

    /**
     * Import rows from an uploaded file into a table. The format is resolved
     * through the strategy registry; unsupported formats are rejected. All
     * inserts run inside one transaction: any row failure triggers a full rollback.
     * @param fileType format key (CSV / JSON / SQL / XLSX / XLS), case-insensitive
     * @param encoding requested file charset (AUTO / UTF-8 / GBK); only text
     *                 formats like CSV use it
     */
    ImportTableDataResponse importTableData(
            DbContext db, String tableName, String fileType, InputStream inputStream, String encoding);

    /**
     * Import with optional auto-create-table flag. When {@code createTableIfMissing}
     * is true and the target table does not exist, the importer infers column
     * types from the file header + sample rows, runs a CREATE TABLE DDL, then
     * resumes with the normal import. SQL importers treat {@code createTableIfMissing}
     * as informational — they already accept CREATE TABLE statements in the file.
     */
    ImportTableDataResponse importTableData(
            DbContext db, String tableName, String fileType, InputStream inputStream, String encoding,
            boolean createTableIfMissing);
}

