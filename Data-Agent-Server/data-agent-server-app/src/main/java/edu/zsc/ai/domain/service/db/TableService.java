package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.BatchTableRowOperationRequest;
import edu.zsc.ai.domain.model.dto.response.db.BatchTableRowsResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.plugin.model.db.TableRowValue;

import java.io.OutputStream;
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
     * Export all rows of a table as CSV (UTF-8 with BOM) to the given output stream.
     * Data is fetched page by page and written incrementally.
     * The {@code onBeforeFirstWrite} callback runs after the first page query succeeds
     * and before any byte is written, so the caller can set response headers only when
     * the export can actually start.
     */
    void exportTableDataCsv(DbContext db, String tableName, OutputStream outputStream,
                            Runnable onBeforeFirstWrite);

    /**
     * Export all rows of a table as a JSON array of objects (keys are column headers)
     * to the given output stream. Data is fetched page by page and written incrementally.
     */
    void exportTableDataJson(DbContext db, String tableName, OutputStream outputStream,
                             Runnable onBeforeFirstWrite);

    /**
     * Export all rows of a table as INSERT statements to the given output stream.
     * Data is fetched page by page and written incrementally.
     */
    void exportTableDataSql(DbContext db, String tableName, OutputStream outputStream,
                            Runnable onBeforeFirstWrite);

    /**
     * Export all rows of a table as an Excel workbook (.xlsx or .xls) to the given
     * output stream. XLSX is written with the streaming SXSSF API; XLS uses HSSF and
     * is limited to 65535 data rows.
     * @param format "XLSX" or "XLS"
     */
    void exportTableDataExcel(DbContext db, String tableName, String format,
                              OutputStream outputStream, Runnable onBeforeFirstWrite);

    /**
     * Import rows from an uploaded CSV file into a table. The first CSV line must be
     * a header row whose names match table columns. All inserts run inside one
     * transaction: any row failure triggers a full rollback.
     */
    edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse importTableDataCsv(
            DbContext db, String tableName, java.io.InputStream inputStream);

    /**
     * Import rows from an uploaded JSON file into a table. The file must contain a
     * top-level JSON array of row objects whose field names match table columns;
     * the first row object fixes the column list like a CSV header. All inserts run
     * inside one transaction: any row failure triggers a full rollback.
     */
    edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse importTableDataJson(
            DbContext db, String tableName, java.io.InputStream inputStream);

    /**
     * Import rows from an uploaded SQL file into a table. The file must contain
     * INSERT statements (comments are skipped); only INSERT is allowed so destructive
     * statements in the file are rejected. All statements run inside one transaction:
     * any failure triggers a full rollback.
     */
    edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse importTableDataSql(
            DbContext db, String tableName, java.io.InputStream inputStream);

    /**
     * Import rows from an uploaded Excel file (.xlsx or .xls, auto-detected) into a
     * table. The first sheet's first row must be a header row whose names match table
     * columns. All inserts run inside one transaction: any row failure triggers a
     * full rollback.
     */
    edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse importTableDataExcel(
            DbContext db, String tableName, java.io.InputStream inputStream);
}

