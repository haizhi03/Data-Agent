package edu.zsc.ai.api.controller.db;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.BatchTableRowsRequest;
import edu.zsc.ai.domain.model.dto.request.db.DeleteTableRowRequest;
import edu.zsc.ai.domain.model.dto.request.db.DeleteTableRequest;
import edu.zsc.ai.domain.model.dto.request.db.InsertTableRowRequest;
import edu.zsc.ai.domain.model.dto.request.db.RenameTableRequest;
import edu.zsc.ai.domain.model.dto.request.db.TableRowValueRequest;
import edu.zsc.ai.domain.model.dto.request.db.UpdateTableRowRequest;
import edu.zsc.ai.domain.model.dto.response.base.ApiResponse;
import edu.zsc.ai.domain.model.dto.response.db.BatchTableRowsResponse;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.domain.service.db.ErDiagramService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.domain.service.db.transfer.TableDataExporter;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class TableController {

    private final TableService tableService;
    private final ErDiagramService erDiagramService;

    @GetMapping("/er-diagram")
    public ApiResponse<ErDiagramResponse> erDiagram(
            @RequestParam @NotNull(message = "connectionId is required") Long connectionId,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        log.info("Loading ER diagram: connectionId={}, catalog={}, schema={}", connectionId, catalog, schema);
        return ApiResponse.success(erDiagramService.load(new DbContext(connectionId, catalog, schema)));
    }

    @GetMapping
    public ApiResponse<List<String>> listTables(
            @RequestParam @NotNull(message = "connectionId is required") Long connectionId,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        log.info("Listing tables: connectionId={}, catalog={}, schema={}", connectionId, catalog, schema);
        DbContext db = new DbContext(connectionId, catalog, schema);
        List<String> tables = tableService.getTables(db);
        return ApiResponse.success(tables);
    }

    @GetMapping("/ddl")
    public ApiResponse<String> getTableDdl(
            @RequestParam @NotNull(message = "connectionId is required") Long connectionId,
            @RequestParam @NotNull(message = "tableName is required") String tableName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema) {
        log.info("Getting table DDL: connectionId={}, tableName={}, catalog={}, schema={}",
                connectionId, tableName, catalog, schema);
        DbContext db = new DbContext(connectionId, catalog, schema);
        String ddl = tableService.getTableDdl(db, tableName);
        return ApiResponse.success(ddl);
    }

    @DeleteMapping
    public ApiResponse<Void> deleteTable(@Valid @RequestBody DeleteTableRequest request) {
        log.info("Deleting table: connectionId={}, tableName={}, catalog={}, schema={}",
                request.getConnectionId(), request.getTableName(), request.getCatalog(), request.getSchema());
        DbContext db = new DbContext(request.getConnectionId(), request.getCatalog(), request.getSchema());
        tableService.deleteTable(db, request.getTableName());
        return ApiResponse.success(null);
    }

    @PutMapping("/rename")
    public ApiResponse<Void> renameTable(@Valid @RequestBody RenameTableRequest request) {
        log.info("Renaming table: connectionId={}, tableName={}, newTableName={}, catalog={}, schema={}",
                request.getConnectionId(), request.getTableName(), request.getNewTableName(),
                request.getCatalog(), request.getSchema());
        DbContext db = DbContext.from(request);
        tableService.renameTable(db, request.getTableName(), request.getNewTableName());
        return ApiResponse.success(null);
    }

    @PostMapping("/rows")
    public ApiResponse<ExecuteSqlResponse> insertRow(@Valid @RequestBody InsertTableRowRequest request) {
        log.info("Inserting table row: connectionId={}, tableName={}, catalog={}, schema={}",
                request.getConnectionId(), request.getTableName(), request.getCatalog(), request.getSchema());
        DbContext db = DbContext.from(request);
        return ApiResponse.success(tableService.insertRow(db, request.getTableName(), toRowValues(request.getValues())));
    }

    @DeleteMapping("/rows")
    public ApiResponse<ExecuteSqlResponse> deleteRow(@Valid @RequestBody DeleteTableRowRequest request) {
        log.info("Deleting table row: connectionId={}, tableName={}, catalog={}, schema={}",
                request.getConnectionId(), request.getTableName(), request.getCatalog(), request.getSchema());
        DbContext db = DbContext.from(request);
        return ApiResponse.success(tableService.deleteRow(
                db,
                request.getTableName(),
                toRowValues(request.getMatchValues()),
                request.isForce()
        ));
    }

    @PutMapping("/rows")
    public ApiResponse<ExecuteSqlResponse> updateRow(@Valid @RequestBody UpdateTableRowRequest request) {
        log.info("Updating table row: connectionId={}, tableName={}, catalog={}, schema={}",
                request.getConnectionId(), request.getTableName(), request.getCatalog(), request.getSchema());
        DbContext db = DbContext.from(request);
        return ApiResponse.success(tableService.updateRow(
                db,
                request.getTableName(),
                toRowValues(request.getSetValues()),
                toRowValues(request.getMatchValues()),
                request.isForce()
        ));
    }

    @PostMapping("/rows/batch")
    public ApiResponse<BatchTableRowsResponse> batchRows(@Valid @RequestBody BatchTableRowsRequest request) {
        log.info("Batch updating table rows: connectionId={}, tableName={}, operationCount={}, force={}",
                request.getConnectionId(), request.getTableName(), request.getOperations().size(), request.isForce());
        DbContext db = DbContext.from(request);
        return ApiResponse.success(tableService.batchTableRows(
                db,
                request.getTableName(),
                request.getOperations(),
                request.isForce()
        ));
    }

    @GetMapping("/data")
    public ApiResponse<TableDataResponse> getTableData(
            @RequestParam @NotNull(message = "connectionId is required") Long connectionId,
            @RequestParam @NotNull(message = "tableName is required") String tableName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema,
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "100") Integer pageSize,
            @RequestParam(required = false) String whereClause,
            @RequestParam(required = false) String orderByColumn,
            @RequestParam(required = false) String orderByDirection) {
        log.info("Getting table data: connectionId={}, tableName={}, catalog={}, schema={}, currentPage={}, pageSize={}",
                connectionId, tableName, catalog, schema, currentPage, pageSize);
        DbContext db = new DbContext(connectionId, catalog, schema);
        boolean hasFilter = (whereClause != null && !whereClause.isBlank())
                || (orderByColumn != null && !orderByColumn.isBlank());
        TableDataResponse response = hasFilter
                ? tableService.getTableData(db, tableName, currentPage, pageSize,
                        whereClause, orderByColumn, orderByDirection)
                : tableService.getTableData(db, tableName, currentPage, pageSize);
        return ApiResponse.success(response);
    }

    @PostMapping("/import")
    public ApiResponse<ImportTableDataResponse> importTableData(
            @RequestParam @NotNull(message = "connectionId is required") Long connectionId,
            @RequestParam @NotNull(message = "tableName is required") String tableName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema,
            @RequestParam(defaultValue = "CSV") String fileType,
            @RequestParam(defaultValue = "AUTO") String encoding,
            @RequestParam(defaultValue = "false") boolean createTableIfMissing,
            @RequestParam("file") MultipartFile file) {
        log.info("Importing table data: connectionId={}, tableName={}, catalog={}, schema={}, fileType={}, encoding={}, autoCreate={}, size={}",
                connectionId, tableName, catalog, schema, fileType, encoding, createTableIfMissing, file.getSize());

        if (file.isEmpty()) {
            throw new BusinessException("Uploaded file is empty");
        }

        DbContext db = new DbContext(connectionId, catalog, schema);
        try {
            ImportTableDataResponse result = tableService.importTableData(
                    db, tableName, fileType, file.getInputStream(), encoding, createTableIfMissing);
            return ApiResponse.success(result);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to read uploaded file: " + ex.getMessage(), ex);
        }
    }

    @GetMapping("/export")
    public void exportTable(
            @RequestParam @NotNull(message = "connectionId is required") Long connectionId,
            @RequestParam @NotNull(message = "tableName is required") String tableName,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String schema,
            @RequestParam(defaultValue = "CSV") String fileType,
            @RequestParam(required = false) String columns,
            HttpServletResponse response) throws IOException {
        log.info("Exporting table data: connectionId={}, tableName={}, catalog={}, schema={}, fileType={}, columns={}",
                connectionId, tableName, catalog, schema, fileType, columns);

        // Format metadata comes from the strategy itself; this controller knows no formats.
        TableDataExporter exporter = tableService.resolveExporter(fileType);
        List<String> selectedColumns = parseExportColumns(columns);

        DbContext db = new DbContext(connectionId, catalog, schema);
        String extension = exporter.extension(fileType);
        Runnable prepareHeaders = () -> {
            String encodedFileName = URLEncoder.encode(tableName + extension, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setContentType(exporter.contentType(fileType));
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encodedFileName);
        };

        exporter.export(db, tableName, fileType, selectedColumns, response.getOutputStream(), prepareHeaders);
    }

    /**
     * Parses the comma-separated column list of the export request.
     * Returns null (export all columns) when the parameter is blank.
     */
    private List<String> parseExportColumns(String columns) {
        if (columns == null || columns.isBlank()) {
            return null;
        }
        return Arrays.stream(columns.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private List<TableRowValue> toRowValues(List<TableRowValueRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        return requests.stream()
                .filter(item -> item != null && item.getColumnName() != null)
                .map(item -> new TableRowValue(item.getColumnName(), item.getValue()))
                .toList();
    }
}
