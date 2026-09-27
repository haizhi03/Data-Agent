package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.common.converter.db.SqlExecutionConverter;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.model.dto.response.db.TableDataResponse;
import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.TableService;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.model.command.sql.SqlColumnInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TableServiceImpl implements TableService {

    private static final int EXPORT_PAGE_SIZE = 5000;

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
    public void exportTableDataCsv(DbContext db, String tableName, OutputStream outputStream,
                                   Runnable onBeforeFirstWrite) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             OutputStreamWriter streamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(streamWriter)) {
            Connection connection = borrowed.connection();
            int offset = 0;

            // Query the first page before writing anything, so connection/query errors
            // surface before the HTTP response is committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, offset, EXPORT_PAGE_SIZE);

            // First page query succeeded: it is now safe to commit file-download headers.
            onBeforeFirstWrite.run();

            List<List<Object>> firstRows = first.getRows();
            if (firstRows == null || firstRows.isEmpty()) {
                // Empty table: still write the header row when headers are available.
                if (first.getHeaders() != null && !first.getHeaders().isEmpty()) {
                    writer.write('\ufeff');
                    writeCsvHeader(writer, first.getHeaders());
                }
                writer.flush();
                return;
            }

            writer.write('\ufeff');
            writeCsvHeader(writer, first.getHeaders());
            writeCsvRows(writer, firstRows);
            log.info("CSV export started: connectionId={}, tableName={}, firstPageRows={}",
                    db.connectionId(), tableName, firstRows.size());

            offset += EXPORT_PAGE_SIZE;
            while (firstRows.size() == EXPORT_PAGE_SIZE) {
                SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                        tableName, offset, EXPORT_PAGE_SIZE);
                List<List<Object>> rows = page.getRows();
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                writeCsvRows(writer, rows);
                if (rows.size() < EXPORT_PAGE_SIZE) {
                    break;
                }
                offset += EXPORT_PAGE_SIZE;
            }
            writer.flush();
            log.info("CSV export completed: connectionId={}, tableName={}, lastOffset={}",
                    db.connectionId(), tableName, offset);
        } catch (IOException ex) {
            throw new RuntimeException("Export CSV failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public void exportTableDataJson(DbContext db, String tableName, OutputStream outputStream,
                                    Runnable onBeforeFirstWrite) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());
        JsonFactory jsonFactory = new JsonFactory();

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             JsonGenerator generator = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8)) {
            // Pretty-printed JSON: each field on its own indented line.
            generator.useDefaultPrettyPrinter();
            Connection connection = borrowed.connection();
            int offset = 0;

            // Query the first page before writing anything, so connection/query errors
            // surface before the HTTP response is committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, offset, EXPORT_PAGE_SIZE);

            // First page query succeeded: it is now safe to commit file-download headers.
            onBeforeFirstWrite.run();

            List<String> headers = first.getHeaders();
            generator.writeStartArray();

            List<List<Object>> firstRows = first.getRows();
            if (firstRows != null && !firstRows.isEmpty()) {
                for (List<Object> row : firstRows) {
                    writeJsonObject(generator, headers, row);
                }
                log.info("JSON export started: connectionId={}, tableName={}, firstPageRows={}",
                        db.connectionId(), tableName, firstRows.size());

                offset += EXPORT_PAGE_SIZE;
                while (firstRows.size() == EXPORT_PAGE_SIZE) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    for (List<Object> row : rows) {
                        writeJsonObject(generator, headers, row);
                    }
                    generator.flush();
                    if (rows.size() < EXPORT_PAGE_SIZE) {
                        break;
                    }
                    offset += EXPORT_PAGE_SIZE;
                }
            }

            generator.writeEndArray();
            generator.flush();
            log.info("JSON export completed: connectionId={}, tableName={}, lastOffset={}",
                    db.connectionId(), tableName, offset);
        } catch (IOException ex) {
            throw new RuntimeException("Export JSON failed: " + ex.getMessage(), ex);
        }
    }

    private void writeJsonObject(JsonGenerator generator, List<String> headers, List<Object> row)
            throws IOException {
        generator.writeStartObject();
        for (int i = 0; i < headers.size(); i++) {
            generator.writeFieldName(headers.get(i));
            Object value = i < row.size() ? row.get(i) : null;
            writeJsonValue(generator, value);
        }
        generator.writeEndObject();
    }

    private void writeJsonValue(JsonGenerator generator, Object value) throws IOException {
        if (value == null) {
            generator.writeNull();
        } else if (value instanceof Number number) {
            // Write via string representation to preserve long/BigInteger precision.
            generator.writeNumber(number.toString());
        } else if (value instanceof Boolean bool) {
            generator.writeBoolean(bool);
        } else {
            generator.writeString(value.toString());
        }
    }

    @Override
    public void exportTableDataSql(DbContext db, String tableName, OutputStream outputStream,
                                   Runnable onBeforeFirstWrite) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             OutputStreamWriter streamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(streamWriter)) {
            Connection connection = borrowed.connection();
            int offset = 0;

            // Query the first page before writing anything, so connection/query errors
            // surface before the HTTP response is committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, offset, EXPORT_PAGE_SIZE);

            // First page query succeeded: it is now safe to commit file-download headers.
            onBeforeFirstWrite.run();

            List<String> headers = first.getHeaders();
            Map<String, Integer> jdbcTypeByName = resolveJdbcTypes(first, headers);

            writer.write("-- DataAgent data export");
            writer.write("\r\n");
            writer.write("-- Table: " + tableName);
            writer.write("\r\n");
            writer.write("-- Exported at: " + new Date());
            writer.write("\r\n");
            writer.write("SET NAMES utf8mb4;");
            writer.write("\r\n\r\n");

            String columnList = String.join(", ", headers);
            List<List<Object>> firstRows = first.getRows();
            if (firstRows != null && !firstRows.isEmpty()) {
                writeInsertStatements(writer, tableName, columnList, headers, jdbcTypeByName, firstRows);
                log.info("SQL export started: connectionId={}, tableName={}, firstPageRows={}",
                        db.connectionId(), tableName, firstRows.size());

                offset += EXPORT_PAGE_SIZE;
                while (firstRows.size() == EXPORT_PAGE_SIZE) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    writeInsertStatements(writer, tableName, columnList, headers, jdbcTypeByName, rows);
                    writer.flush();
                    if (rows.size() < EXPORT_PAGE_SIZE) {
                        break;
                    }
                    offset += EXPORT_PAGE_SIZE;
                }
            }

            writer.flush();
            log.info("SQL export completed: connectionId={}, tableName={}, lastOffset={}",
                    db.connectionId(), tableName, offset);
        } catch (IOException ex) {
            throw new RuntimeException("Export SQL failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Integer> resolveJdbcTypes(SqlCommandResult result, List<String> headers) {
        Map<String, Integer> map = new HashMap<>();
        if (result.getColumns() != null) {
            for (SqlColumnInfo column : result.getColumns()) {
                if (column.getName() != null && column.getJdbcType() != null) {
                    map.put(column.getName(), column.getJdbcType());
                }
            }
        }
        return map;
    }

    private void writeInsertStatements(BufferedWriter writer, String tableName, String columnList,
                                       List<String> headers, Map<String, Integer> jdbcTypeByName,
                                       List<List<Object>> rows) throws IOException {
        for (List<Object> row : rows) {
            writer.write("INSERT INTO " + tableName + " (" + columnList + ") VALUES (");
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) {
                    writer.write(", ");
                }
                Object value = i < row.size() ? row.get(i) : null;
                Integer jdbcType = jdbcTypeByName.get(headers.get(i));
                writer.write(toSqlLiteral(value, jdbcType));
            }
            writer.write(");\r\n");
        }
    }

    private String toSqlLiteral(Object value, Integer jdbcType) {
        if (value == null) {
            return "NULL";
        }

        // Numeric types: emit the raw representation.
        if (jdbcType != null && isNumericJdbcType(jdbcType)) {
            return value.toString();
        }
        if (jdbcType == null && value instanceof Number) {
            return value.toString();
        }

        // Boolean: 1/0 works across MySQL and DM.
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }

        // Binary data: standard SQL hex literal, also accepted by MySQL.
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            return hex.append("'").toString();
        }

        // Temporal and all other types are emitted as quoted strings.
        return "'" + escapeSqlString(value.toString()) + "'";
    }

    private boolean isNumericJdbcType(int jdbcType) {
        return jdbcType == Types.BIT
                || jdbcType == Types.TINYINT
                || jdbcType == Types.SMALLINT
                || jdbcType == Types.INTEGER
                || jdbcType == Types.BIGINT
                || jdbcType == Types.REAL
                || jdbcType == Types.FLOAT
                || jdbcType == Types.DOUBLE
                || jdbcType == Types.NUMERIC
                || jdbcType == Types.DECIMAL;
    }

    private String escapeSqlString(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\0", "");
    }

    /** HSSF (.xls) supports 65536 rows in total; row 0 is the header row. */
    private static final int XLS_MAX_DATA_ROWS = 65535;

    private static final int IMPORT_BATCH_SIZE = 1000;

    @Override
    public ImportTableDataResponse importTableDataCsv(DbContext db, String tableName,
                                                      InputStream inputStream) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ColumnManager columnManager = DefaultPluginManager.getInstance()
                .getColumnManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();

            List<ColumnMetadata> tableColumns = columnManager.getColumns(
                    connection, db.catalog(), db.schema(), tableName);
            if (tableColumns.isEmpty()) {
                throw new BusinessException("Cannot resolve columns of table: " + tableName);
            }
            Map<String, ColumnMetadata> columnsByName = new HashMap<>();
            for (ColumnMetadata column : tableColumns) {
                columnsByName.put(column.name().toLowerCase(), column);
            }

            // Read as UTF-8 and skip an optional BOM written by Excel on export.
            BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(inputStream, StandardCharsets.UTF_8));
            reader.mark(1);
            int firstChar = reader.read();
            if (firstChar != '\uFEFF') {
                reader.reset();
            }

            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build();
            CSVParser parser = csvFormat.parse(reader);
            List<String> csvHeaders = parser.getHeaderNames();
            if (csvHeaders.isEmpty()) {
                throw new BusinessException("CSV file is empty or missing a header row");
            }

            // Map every CSV header to a real table column.
            List<ColumnMetadata> targetColumns = new ArrayList<>();
            Set<String> seenHeaders = new HashSet<>();
            for (String header : csvHeaders) {
                String normalized = header.trim();
                if (normalized.isEmpty()) {
                    throw new BusinessException("CSV header contains an empty column name");
                }
                if (!seenHeaders.add(normalized.toLowerCase())) {
                    throw new BusinessException("Duplicate CSV header: " + normalized);
                }
                ColumnMetadata column = columnsByName.get(normalized.toLowerCase());
                if (column == null) {
                    throw new BusinessException("CSV header '" + normalized
                            + "' does not match any column of table " + tableName);
                }
                targetColumns.add(column);
            }

            String identifierQuote = resolveIdentifierQuote(connection);
            String insertSql = buildImportInsertSql(identifierQuote, tableName, targetColumns);

            boolean originalAutoCommit = connection.getAutoCommit();
            int totalRows = 0;
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                connection.setAutoCommit(false);

                int batched = 0;
                int batchFirstRow = 2; // Header is line 1, first data row is line 2.
                for (CSVRecord record : parser) {
                    totalRows++;
                    int fileRowNumber = totalRows + 1;
                    for (int i = 0; i < targetColumns.size(); i++) {
                        ColumnMetadata column = targetColumns.get(i);
                        String raw = record.isSet(i) ? record.get(i) : null;
                        setImportParameter(statement, i + 1, column, raw, fileRowNumber);
                    }
                    statement.addBatch();
                    batched++;

                    if (batched >= IMPORT_BATCH_SIZE) {
                        executeImportBatch(statement, batchFirstRow);
                        batched = 0;
                        batchFirstRow = totalRows + 2;
                    }
                }
                if (batched > 0) {
                    executeImportBatch(statement, batchFirstRow);
                }
                connection.commit();
            } catch (ImportFailureException failure) {
                safeRollback(connection);
                log.warn("CSV import failed at file row {}: connectionId={}, tableName={}",
                        failure.fileRow, db.connectionId(), tableName);
                return ImportTableDataResponse.builder()
                        .success(false)
                        .totalRows(totalRows)
                        .insertedRows(0)
                        .failedAtRow(failure.fileRow)
                        .errorMessage(failure.getMessage())
                        .build();
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }

            log.info("CSV import completed: connectionId={}, tableName={}, totalRows={}",
                    db.connectionId(), tableName, totalRows);
            return ImportTableDataResponse.builder()
                    .success(true)
                    .totalRows(totalRows)
                    .insertedRows(totalRows)
                    .build();
        } catch (SQLException | IOException ex) {
            throw new RuntimeException("Import CSV failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ImportTableDataResponse importTableDataJson(DbContext db, String tableName,
                                                       InputStream inputStream) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ColumnManager columnManager = DefaultPluginManager.getInstance()
                .getColumnManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             JsonParser parser = new JsonFactory().createParser(inputStream)) {
            Connection connection = borrowed.connection();

            List<ColumnMetadata> tableColumns = columnManager.getColumns(
                    connection, db.catalog(), db.schema(), tableName);
            if (tableColumns.isEmpty()) {
                throw new BusinessException("Cannot resolve columns of table: " + tableName);
            }
            Map<String, ColumnMetadata> columnsByName = new HashMap<>();
            for (ColumnMetadata column : tableColumns) {
                columnsByName.put(column.name().toLowerCase(), column);
            }

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new BusinessException("JSON file must contain a top-level array of row objects");
            }

            // The first row object fixes the column list, mirroring the CSV header row.
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new BusinessException("JSON file contains no row objects");
            }
            JsonRow firstRow = readJsonRow(parser, 1);
            List<ColumnMetadata> targetColumns = new ArrayList<>();
            Map<String, Integer> fieldIndexByName = new HashMap<>();
            for (int i = 0; i < firstRow.names().size(); i++) {
                String fieldName = firstRow.names().get(i);
                ColumnMetadata column = columnsByName.get(fieldName.toLowerCase());
                if (column == null) {
                    throw new ImportFailureException(1, "JSON field '" + fieldName
                            + "' does not match any column of table " + tableName);
                }
                targetColumns.add(column);
                fieldIndexByName.put(fieldName.toLowerCase(), i);
            }
            if (targetColumns.isEmpty()) {
                throw new ImportFailureException(1, "JSON file's first row object contains no fields");
            }

            String identifierQuote = resolveIdentifierQuote(connection);
            String insertSql = buildImportInsertSql(identifierQuote, tableName, targetColumns);

            boolean originalAutoCommit = connection.getAutoCommit();
            int totalRows = 0;
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                connection.setAutoCommit(false);

                int batched = 0;
                int batchFirstRow = 1; // 1-based index of the JSON row object starting the batch.
                // The already-parsed first row joins the same batch sequence.
                totalRows = 1;
                setImportRowParameters(statement, targetColumns,
                        mapImportRowValues(firstRow, fieldIndexByName, columnsByName, tableName, 1), 1);
                statement.addBatch();
                batched = 1;

                JsonToken token;
                while ((token = parser.nextToken()) != null && token != JsonToken.END_ARRAY) {
                    if (token != JsonToken.START_OBJECT) {
                        throw new ImportFailureException(totalRows + 1,
                                "Expected a JSON row object but found " + token);
                    }
                    totalRows++;
                    int rowIndex = totalRows;
                    JsonRow row = readJsonRow(parser, rowIndex);
                    setImportRowParameters(statement, targetColumns,
                            mapImportRowValues(row, fieldIndexByName, columnsByName, tableName, rowIndex),
                            rowIndex);
                    statement.addBatch();
                    batched++;

                    if (batched >= IMPORT_BATCH_SIZE) {
                        executeImportBatch(statement, batchFirstRow);
                        batched = 0;
                        batchFirstRow = rowIndex + 1;
                    }
                }
                if (batched > 0) {
                    executeImportBatch(statement, batchFirstRow);
                }
                connection.commit();
            } catch (ImportFailureException failure) {
                safeRollback(connection);
                log.warn("JSON import failed at row {}: connectionId={}, tableName={}",
                        failure.fileRow, db.connectionId(), tableName);
                return ImportTableDataResponse.builder()
                        .success(false)
                        .totalRows(totalRows)
                        .insertedRows(0)
                        .failedAtRow(failure.fileRow)
                        .errorMessage(failure.getMessage())
                        .build();
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }

            log.info("JSON import completed: connectionId={}, tableName={}, totalRows={}",
                    db.connectionId(), tableName, totalRows);
            return ImportTableDataResponse.builder()
                    .success(true)
                    .totalRows(totalRows)
                    .insertedRows(totalRows)
                    .build();
        } catch (SQLException | IOException ex) {
            throw new RuntimeException("Import JSON failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public ImportTableDataResponse importTableDataSql(DbContext db, String tableName,
                                                      InputStream inputStream) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();

            String script = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            if (!script.isEmpty() && script.charAt(0) == '\uFEFF') {
                script = script.substring(1);
            }
            List<String> statements = splitSqlStatements(script);
            if (statements.isEmpty()) {
                throw new BusinessException("SQL file contains no INSERT statements");
            }

            boolean originalAutoCommit = connection.getAutoCommit();
            int totalStatements = 0;
            int insertedRows = 0;
            try {
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    for (String sql : statements) {
                        totalStatements++;
                        String lower = sql.toLowerCase();
                        if (!lower.startsWith("insert")) {
                            throw new ImportFailureException(totalStatements,
                                    "Only INSERT statements are supported, but statement "
                                            + totalStatements + " starts with '"
                                            + firstSqlWord(sql) + "'");
                        }
                        try {
                            insertedRows += statement.executeUpdate(sql);
                        } catch (SQLException ex) {
                            throw new ImportFailureException(totalStatements,
                                    "Database rejected statement " + totalStatements
                                            + ": " + ex.getMessage());
                        }
                    }
                }
                connection.commit();
            } catch (ImportFailureException failure) {
                safeRollback(connection);
                log.warn("SQL import failed at statement {}: connectionId={}, tableName={}",
                        failure.fileRow, db.connectionId(), tableName);
                return ImportTableDataResponse.builder()
                        .success(false)
                        .totalRows(totalStatements)
                        .insertedRows(0)
                        .failedAtRow(failure.fileRow)
                        .errorMessage(failure.getMessage())
                        .build();
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }

            log.info("SQL import completed: connectionId={}, tableName={}, statements={}, affectedRows={}",
                    db.connectionId(), tableName, totalStatements, insertedRows);
            return ImportTableDataResponse.builder()
                    .success(true)
                    .totalRows(totalStatements)
                    .insertedRows(insertedRows)
                    .build();
        } catch (SQLException | IOException ex) {
            throw new RuntimeException("Import SQL failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Splits a SQL script into statements on semicolons that sit outside quotes and
     * comments. Line comments (-- and #), block comments, string literals with
     * backslash and doubled-quote escapes, and quoted identifiers are handled.
     */
    private List<String> splitSqlStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-'
                    && (i + 2 >= n || Character.isWhitespace(script.charAt(i + 2)))) {
                // Line comment: skip up to (not including) the line break.
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '#') {
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                // Block comment: replace with a space to keep tokens separated.
                current.append(' ');
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                // Quoted string or identifier: copy verbatim honoring escapes.
                current.append(c);
                i++;
                while (i < n) {
                    char ch = script.charAt(i);
                    current.append(ch);
                    i++;
                    if (ch == '\\' && i < n) {
                        current.append(script.charAt(i));
                        i++;
                        continue;
                    }
                    if (ch == c) {
                        if (i + 1 < n && script.charAt(i + 1) == c) {
                            current.append(c);
                            i++;
                            continue;
                        }
                        break;
                    }
                }
            } else if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private String firstSqlWord(String sql) {
        String trimmed = sql.trim();
        int end = 0;
        while (end < trimmed.length() && !Character.isWhitespace(trimmed.charAt(end))) {
            end++;
        }
        String word = trimmed.substring(0, end);
        return word.length() > 20 ? word.substring(0, 20) + "..." : word;
    }

    @Override
    public ImportTableDataResponse importTableDataExcel(DbContext db, String tableName,
                                                        InputStream inputStream) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ColumnManager columnManager = DefaultPluginManager.getInstance()
                .getColumnManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            Connection connection = borrowed.connection();
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException("Excel file contains no sheets");
            }

            List<ColumnMetadata> tableColumns = columnManager.getColumns(
                    connection, db.catalog(), db.schema(), tableName);
            if (tableColumns.isEmpty()) {
                throw new BusinessException("Cannot resolve columns of table: " + tableName);
            }
            Map<String, ColumnMetadata> columnsByName = new HashMap<>();
            for (ColumnMetadata column : tableColumns) {
                columnsByName.put(column.name().toLowerCase(), column);
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BusinessException("Excel file is empty or missing a header row");
            }

            // The first row fixes the column list, mirroring the CSV header row.
            List<ColumnMetadata> targetColumns = new ArrayList<>();
            Set<String> seenHeaders = new HashSet<>();
            for (int c = 0; c < headerRow.getLastCellNum(); c++) {
                Cell cell = headerRow.getCell(c);
                String header = cell == null ? "" : readExcelCellAsString(cell);
                header = header == null ? "" : header.trim();
                if (header.isEmpty()) {
                    throw new BusinessException("Excel header contains an empty column name");
                }
                if (!seenHeaders.add(header.toLowerCase())) {
                    throw new BusinessException("Duplicate Excel header: " + header);
                }
                ColumnMetadata column = columnsByName.get(header.toLowerCase());
                if (column == null) {
                    throw new BusinessException("Excel header '" + header
                            + "' does not match any column of table " + tableName);
                }
                targetColumns.add(column);
            }
            if (targetColumns.isEmpty()) {
                throw new BusinessException("Excel file's header row contains no columns");
            }

            String identifierQuote = resolveIdentifierQuote(connection);
            String insertSql = buildImportInsertSql(identifierQuote, tableName, targetColumns);

            boolean originalAutoCommit = connection.getAutoCommit();
            int totalRows = 0;
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                connection.setAutoCommit(false);

                int batched = 0;
                int batchFirstRow = -1; // Resolved when the first data row of the batch is read.
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (isEmptyExcelRow(row)) {
                        continue;
                    }
                    totalRows++;
                    int fileRowNumber = r + 1; // 1-based, header included.
                    if (batchFirstRow < 0) {
                        batchFirstRow = fileRowNumber;
                    }
                    for (int i = 0; i < targetColumns.size(); i++) {
                        String raw = readExcelCellAsString(row.getCell(i));
                        setImportParameter(statement, i + 1, targetColumns.get(i), raw, fileRowNumber);
                    }
                    statement.addBatch();
                    batched++;

                    if (batched >= IMPORT_BATCH_SIZE) {
                        executeImportBatch(statement, batchFirstRow);
                        batched = 0;
                        batchFirstRow = -1;
                    }
                }
                if (batched > 0) {
                    executeImportBatch(statement, batchFirstRow);
                }
                connection.commit();
            } catch (ImportFailureException failure) {
                safeRollback(connection);
                log.warn("Excel import failed at file row {}: connectionId={}, tableName={}",
                        failure.fileRow, db.connectionId(), tableName);
                return ImportTableDataResponse.builder()
                        .success(false)
                        .totalRows(totalRows)
                        .insertedRows(0)
                        .failedAtRow(failure.fileRow)
                        .errorMessage(failure.getMessage())
                        .build();
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }

            log.info("Excel import completed: connectionId={}, tableName={}, totalRows={}",
                    db.connectionId(), tableName, totalRows);
            return ImportTableDataResponse.builder()
                    .success(true)
                    .totalRows(totalRows)
                    .insertedRows(totalRows)
                    .build();
        } catch (SQLException | IOException ex) {
            throw new RuntimeException("Import Excel failed: " + ex.getMessage(), ex);
        }
    }

    private boolean isEmptyExcelRow(Row row) {
        if (row == null) {
            return true;
        }
        short lastCellNum = row.getLastCellNum(); // 1-based count; -1 when the row has no cells.
        for (int c = 0; c < lastCellNum; c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    private static final DateTimeFormatter EXCEL_DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter EXCEL_DATE_TIME_MILLIS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Converts an Excel cell to a plain string for the shared import parameter
     * conversion (null = SQL NULL). Whole numbers lose the ".0" tail, decimals avoid
     * scientific notation, date-formatted cells become "yyyy-MM-dd HH:mm:ss[.SSS]",
     * formulas use their cached result.
     */
    private String readExcelCellAsString(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> {
                String value = cell.getStringCellValue();
                yield value.isEmpty() ? null : value;
            }
            case NUMERIC -> readExcelNumericCellAsString(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> null; // BLANK or unsupported cached type
        };
    }

    private String readExcelNumericCellAsString(Cell cell) {
        double value = cell.getNumericCellValue();
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return null;
        }
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDateTime dateTime = cell.getLocalDateTimeCellValue();
            return dateTime.getNano() == 0
                    ? dateTime.format(EXCEL_DATE_TIME_FORMAT)
                    : dateTime.format(EXCEL_DATE_TIME_MILLIS_FORMAT);
        }
        // Whole numbers without a ".0" tail; other decimals via BigDecimal to avoid
        // scientific notation.
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            return String.valueOf((long) value);
        }
        return new BigDecimal(String.valueOf(value)).toPlainString();
    }

    /** One JSON row object: field names in file order and their text values (null for JSON null). */
    private record JsonRow(List<String> names, List<String> values) {
    }

    /**
     * Reads one JSON row object field by field. JSON numbers keep their exact source
     * text (no precision loss), booleans become "true"/"false", nested objects/arrays
     * are re-serialized to a JSON string for JSON-type columns.
     */
    private JsonRow readJsonRow(JsonParser parser, int rowIndex) throws IOException {
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String fieldName = parser.currentName() == null ? "" : parser.currentName().trim();
            if (fieldName.isEmpty()) {
                throw new ImportFailureException(rowIndex, "JSON field name is empty");
            }
            if (!seen.add(fieldName.toLowerCase())) {
                throw new ImportFailureException(rowIndex, "Duplicate JSON field: " + fieldName);
            }
            JsonToken valueToken = parser.nextToken();
            String text;
            if (valueToken == JsonToken.VALUE_NULL) {
                text = null;
            } else if (valueToken == JsonToken.VALUE_STRING
                    || valueToken == JsonToken.VALUE_NUMBER_INT
                    || valueToken == JsonToken.VALUE_NUMBER_FLOAT) {
                // getText returns the exact source characters, preserving long-number precision.
                text = parser.getText();
            } else if (valueToken == JsonToken.VALUE_TRUE
                    || valueToken == JsonToken.VALUE_FALSE) {
                text = valueToken == JsonToken.VALUE_TRUE ? "true" : "false";
            } else if (valueToken == JsonToken.START_OBJECT
                    || valueToken == JsonToken.START_ARRAY) {
                text = readJsonSubtreeAsString(parser);
            } else {
                throw new ImportFailureException(rowIndex,
                        "Unsupported JSON value for field '" + fieldName + "'");
            }
            names.add(fieldName);
            values.add(text);
        }
        return new JsonRow(names, values);
    }

    /** Copies the object/array at the parser's current position into a JSON string. */
    private String readJsonSubtreeAsString(JsonParser parser) throws IOException {
        StringWriter writer = new StringWriter();
        try (JsonGenerator generator = new JsonFactory().createGenerator(writer)) {
            generator.copyCurrentStructure(parser);
        }
        return writer.toString();
    }

    /**
     * Maps one row's fields onto the column list fixed by the first row object.
     * Unknown fields and fields outside the first row's set are rejected; fields
     * missing from later rows become SQL NULL.
     */
    private String[] mapImportRowValues(JsonRow row, Map<String, Integer> fieldIndexByName,
                                        Map<String, ColumnMetadata> columnsByName, String tableName,
                                        int rowIndex) {
        String[] values = new String[fieldIndexByName.size()];
        for (int i = 0; i < row.names().size(); i++) {
            String fieldName = row.names().get(i);
            String key = fieldName.toLowerCase();
            if (!columnsByName.containsKey(key)) {
                throw new ImportFailureException(rowIndex, "JSON field '" + fieldName
                        + "' does not match any column of table " + tableName);
            }
            Integer index = fieldIndexByName.get(key);
            if (index == null) {
                throw new ImportFailureException(rowIndex, "JSON field '" + fieldName
                        + "' was not present in the first row object");
            }
            values[index] = row.values().get(i);
        }
        return values;
    }

    private void setImportRowParameters(PreparedStatement statement, List<ColumnMetadata> targetColumns,
                                        String[] values, int rowIndex) throws SQLException {
        for (int i = 0; i < targetColumns.size(); i++) {
            setImportParameter(statement, i + 1, targetColumns.get(i),
                    i < values.length ? values[i] : null, rowIndex);
        }
    }

    private void executeImportBatch(PreparedStatement statement, int batchFirstRow) {
        try {
            statement.executeBatch();
        } catch (SQLException ex) {
            throw new ImportFailureException(batchFirstRow,
                    "Database rejected the batch: " + ex.getMessage());
        }
    }

    private void safeRollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackEx) {
            log.warn("Rollback after CSV import failure failed: {}", rollbackEx.getMessage());
        }
    }

    private void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            connection.setAutoCommit(originalAutoCommit);
        } catch (SQLException ex) {
            log.warn("Failed to restore autoCommit: {}", ex.getMessage());
        }
    }

    private String resolveIdentifierQuote(Connection connection) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return (quote == null || quote.isBlank()) ? "" : quote;
    }

    private String buildImportInsertSql(String quote, String tableName,
                                        List<ColumnMetadata> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(quoteIdentifier(quote, tableName))
                .append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                placeholders.append(", ");
            }
            sql.append(quoteIdentifier(quote, columns.get(i).name()));
            placeholders.append('?');
        }
        sql.append(") VALUES (").append(placeholders).append(')');
        return sql.toString();
    }

    private String quoteIdentifier(String quote, String identifier) {
        return quote.isEmpty() ? identifier : quote + identifier + quote;
    }

    private void setImportParameter(PreparedStatement statement, int parameterIndex,
                                    ColumnMetadata column, String raw, int fileRow)
            throws SQLException {
        // Empty / missing CSV cells become SQL NULL.
        if (raw == null || raw.isEmpty()) {
            statement.setNull(parameterIndex, column.dataType());
            return;
        }

        try {
            switch (column.dataType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER ->
                        statement.setInt(parameterIndex, parseIntValue(raw.trim()));
                case Types.BIGINT -> statement.setLong(parameterIndex, parseLongValue(raw.trim()));
                case Types.REAL, Types.FLOAT ->
                        statement.setFloat(parameterIndex, Float.parseFloat(raw.trim()));
                case Types.DOUBLE -> statement.setDouble(parameterIndex, Double.parseDouble(raw.trim()));
                case Types.NUMERIC, Types.DECIMAL ->
                        statement.setBigDecimal(parameterIndex, new BigDecimal(raw.trim()));
                case Types.BIT, Types.BOOLEAN ->
                        statement.setBoolean(parameterIndex, parseCsvBoolean(raw));
                case Types.DATE -> statement.setDate(parameterIndex,
                        java.sql.Date.valueOf(parseCsvDate(raw)));
                case Types.TIME -> statement.setTime(parameterIndex,
                        java.sql.Time.valueOf(LocalTime.parse(raw.trim())));
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                        statement.setTimestamp(parameterIndex, parseCsvTimestamp(raw));
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                        statement.setBytes(parameterIndex, raw.getBytes(StandardCharsets.UTF_8));
                case Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
                        statement.setNString(parameterIndex, raw);
                default -> statement.setString(parameterIndex, raw);
            }
        } catch (RuntimeException parseError) {
            throw new ImportFailureException(fileRow, "Column '" + column.name()
                    + "' cannot accept value \"" + raw + "\": " + parseError.getMessage());
        }
    }

    /** Parses an integer column value; tolerates "45.0"-style text written by Excel generators. */
    private int parseIntValue(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(raw).intValueExact();
            } catch (ArithmeticException alsoIgnored) {
                throw new NumberFormatException("not an integer value");
            }
        }
    }

    /** Parses a bigint column value; tolerates "1.0"-style text written by Excel generators. */
    private long parseLongValue(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(raw).longValueExact();
            } catch (ArithmeticException alsoIgnored) {
                throw new NumberFormatException("not an integer value");
            }
        }
    }

    private boolean parseCsvBoolean(String raw) {
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "1", "true", "yes", "y", "t" -> true;
            case "0", "false", "no", "n", "f" -> false;
            default -> throw new IllegalArgumentException("not a boolean value");
        };
    }

    private LocalDate parseCsvDate(String raw) {
        // Accept a date optionally followed by a time part (e.g. "2025-05-02 00:00:00"
        // written by Excel date cells); only the date component is used.
        String value = raw.trim();
        int spaceIndex = value.indexOf(' ');
        if (spaceIndex > 0) {
            value = value.substring(0, spaceIndex);
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return java.sql.Date.valueOf(value).toLocalDate();
        }
    }

    private Timestamp parseCsvTimestamp(String raw) {
        // Accept ISO 'T' or a space separator, with optional seconds and fraction:
        // yyyy-mm-dd[ T]hh:mm[:ss[.fff...]]; a missing seconds field is padded with zeros.
        String value = raw.trim().replace('T', ' ');
        int spaceIndex = value.indexOf(' ');
        if (spaceIndex > 0) {
            String timePart = value.substring(spaceIndex + 1);
            long colonCount = timePart.chars().filter(ch -> ch == ':').count();
            if (colonCount == 1) {
                timePart = timePart + ":00";
            } else if (colonCount == 2 && timePart.endsWith(":")) {
                timePart = timePart + "00";
            }
            value = value.substring(0, spaceIndex + 1) + timePart;
        }
        return Timestamp.valueOf(value);
    }

    /** Carries the 1-based file row number that caused an import failure. */
    private static class ImportFailureException extends RuntimeException {
        private final int fileRow;

        ImportFailureException(int fileRow, String message) {
            super(message);
            this.fileRow = fileRow;
        }
    }

    @Override
    public void exportTableDataExcel(DbContext db, String tableName, String format,
                                     OutputStream outputStream, Runnable onBeforeFirstWrite) {
        boolean xlsx = !"XLS".equalsIgnoreCase(format);

        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

        SXSSFWorkbook sxssfWorkbook = null;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();

            // XLS has a hard 65535-data-row limit; verify the count before writing anything.
            if (!xlsx) {
                long totalRows = provider.getTableDataCount(connection, db.catalog(), db.schema(), tableName);
                if (totalRows > XLS_MAX_DATA_ROWS) {
                    throw new BusinessException("XLS format supports at most " + XLS_MAX_DATA_ROWS
                            + " data rows (found " + totalRows + "); please choose XLSX instead");
                }
            }

            // Query the first page so connection/query errors surface before headers are committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, 0, EXPORT_PAGE_SIZE);

            // First page query succeeded: it is now safe to commit file-download headers.
            onBeforeFirstWrite.run();

            Workbook workbook;
            if (xlsx) {
                // Keep only 500 rows in memory; older rows are flushed to temporary files.
                sxssfWorkbook = new SXSSFWorkbook(500);
                workbook = sxssfWorkbook;
            } else {
                workbook = new HSSFWorkbook();
            }

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat()
                    .getFormat("yyyy-mm-dd hh:mm:ss"));

            List<String> headers = first.getHeaders();
            Sheet sheet = workbook.createSheet(buildSafeSheetName(tableName));
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers.get(i));
                cell.setCellStyle(headerStyle);
            }

            int rowIndex = 1;
            List<List<Object>> firstRows = first.getRows();
            if (firstRows != null && !firstRows.isEmpty()) {
                rowIndex = writeExcelRows(sheet, firstRows, rowIndex, dateStyle);
                log.info("Excel export started: connectionId={}, tableName={}, format={}, firstPageRows={}",
                        db.connectionId(), tableName, format, firstRows.size());

                int offset = EXPORT_PAGE_SIZE;
                while (firstRows.size() == EXPORT_PAGE_SIZE) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    rowIndex = writeExcelRows(sheet, rows, rowIndex, dateStyle);
                    if (rows.size() < EXPORT_PAGE_SIZE) {
                        break;
                    }
                    offset += EXPORT_PAGE_SIZE;
                }
            }

            workbook.write(outputStream);
            workbook.close();
            outputStream.flush();
            log.info("Excel export completed: connectionId={}, tableName={}, format={}, dataRows={}",
                    db.connectionId(), tableName, format, rowIndex - 1);
        } catch (IOException ex) {
            throw new RuntimeException("Export Excel failed: " + ex.getMessage(), ex);
        } finally {
            // Remove SXSSF temporary files.
            if (sxssfWorkbook != null) {
                sxssfWorkbook.dispose();
            }
        }
    }

    private int writeExcelRows(Sheet sheet, List<List<Object>> rows, int startRowIndex,
                               CellStyle dateStyle) {
        int rowIndex = startRowIndex;
        for (List<Object> rowData : rows) {
            Row row = sheet.createRow(rowIndex);
            for (int columnIndex = 0; columnIndex < rowData.size(); columnIndex++) {
                Cell cell = row.createCell(columnIndex);
                setExcelCellValue(cell, rowData.get(columnIndex), dateStyle);
            }
            rowIndex++;
        }
        return rowIndex;
    }

    private void setExcelCellValue(Cell cell, Object value, CellStyle dateStyle) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            if (value instanceof Integer || value instanceof Long
                    || value instanceof Short || value instanceof Byte) {
                cell.setCellValue(number.longValue());
            } else {
                cell.setCellValue(number.doubleValue());
            }
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else if (value instanceof java.util.Date date) {
            cell.setCellValue(date);
            cell.setCellStyle(dateStyle);
        } else if (value instanceof LocalDateTime dateTime) {
            cell.setCellValue(Timestamp.valueOf(dateTime));
            cell.setCellStyle(dateStyle);
        } else if (value instanceof LocalDate localDate) {
            cell.setCellValue(java.sql.Date.valueOf(localDate));
            cell.setCellStyle(dateStyle);
        } else if (value instanceof byte[]) {
            // Binary content cannot be represented in a spreadsheet cell.
            cell.setCellValue("");
        } else {
            cell.setCellValue(value.toString());
        }
    }

    private String buildSafeSheetName(String tableName) {
        // Sheet names must be <= 31 chars and cannot contain : \ / ? * [ ].
        String safe = tableName.replaceAll("[:\\\\/?*\\[\\]]", "_");
        return safe.length() > 31 ? safe.substring(0, 31) : safe;
    }

    private void writeCsvHeader(BufferedWriter writer, List<String> headers) throws IOException {
        for (int i = 0; i < headers.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(toCsvField(headers.get(i)));
        }
        writer.write("\r\n");
    }

    private void writeCsvRows(BufferedWriter writer, List<List<Object>> rows) throws IOException {
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    writer.write(',');
                }
                Object value = row.get(i);
                writer.write(toCsvField(value == null ? "" : value.toString()));
            }
            writer.write("\r\n");
        }
    }

    private String toCsvField(String value) {
        boolean needsQuotes = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
