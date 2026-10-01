package edu.zsc.ai.api.controller.db;

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
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.domain.service.db.TableService;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Validated
@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class TableController {

    private final TableService tableService;

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
            @RequestParam("file") MultipartFile file) {
        log.info("Importing table data: connectionId={}, tableName={}, catalog={}, schema={}, fileType={}, size={}",
                connectionId, tableName, catalog, schema, fileType, file.getSize());

        if (file.isEmpty()) {
            throw new edu.zsc.ai.domain.exception.BusinessException("Uploaded file is empty");
        }

        DbContext db = new DbContext(connectionId, catalog, schema);
        String type = fileType == null ? "" : fileType.trim().toUpperCase();
        try {
            ImportTableDataResponse result = switch (type) {
                case "CSV" -> tableService.importTableDataCsv(db, tableName, file.getInputStream());
                case "JSON" -> tableService.importTableDataJson(db, tableName, file.getInputStream());
                case "SQL" -> tableService.importTableDataSql(db, tableName, file.getInputStream());
                case "XLSX", "XLS" -> tableService.importTableDataExcel(db, tableName, file.getInputStream());
                default -> throw new edu.zsc.ai.domain.exception.BusinessException(
                        "Import format '" + fileType + "' is not supported yet");
            };
            return ApiResponse.success(result);
        } catch (java.io.IOException ex) {
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
            HttpServletResponse response) throws java.io.IOException {
        log.info("Exporting table data: connectionId={}, tableName={}, catalog={}, schema={}, fileType={}",
                connectionId, tableName, catalog, schema, fileType);

        String type = fileType == null ? "" : fileType.trim().toUpperCase();
        if (!"CSV".equals(type) && !"JSON".equals(type) && !"SQL".equals(type)
                && !"XLSX".equals(type) && !"XLS".equals(type)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String extension = switch (type) {
            case "JSON" -> ".json";
            case "SQL" -> ".sql";
            case "XLSX" -> ".xlsx";
            case "XLS" -> ".xls";
            default -> ".csv";
        };
        String contentType = switch (type) {
            case "JSON" -> "application/json; charset=UTF-8";
            case "SQL" -> "application/sql; charset=UTF-8";
            case "XLSX" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "XLS" -> "application/vnd.ms-excel";
            default -> "text/csv; charset=UTF-8";
        };

        DbContext db = new DbContext(connectionId, catalog, schema);
        Runnable prepareHeaders = () -> {
            String encodedFileName = URLEncoder.encode(tableName + extension, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setContentType(contentType);
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + encodedFileName);
        };

        switch (type) {
            case "JSON" -> tableService.exportTableDataJson(db, tableName,
                    response.getOutputStream(), prepareHeaders);
            case "SQL" -> tableService.exportTableDataSql(db, tableName,
                    response.getOutputStream(), prepareHeaders);
            case "XLSX", "XLS" -> tableService.exportTableDataExcel(db, tableName, type,
                    response.getOutputStream(), prepareHeaders);
            default -> tableService.exportTableDataCsv(db, tableName,
                    response.getOutputStream(), prepareHeaders);
        }
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
