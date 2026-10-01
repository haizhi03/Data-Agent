package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.ColumnValueConverter;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Imports rows from an Excel file (.xlsx or .xls, auto-detected). The first
 * sheet's first row is a header row whose names must match table columns; all
 * inserts run in one transaction.
 */
@Component
@RequiredArgsConstructor
public class ExcelTableDataImporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelTableDataImporter.class);

    private final ConnectionService connectionService;
    private final ImportPrepareSupport prepareSupport;
    private final ColumnValueConverter valueConverter;
    private final ImportBatchSupport batchSupport;

    public ImportTableDataResponse importData(DbContext db, String tableName,
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

            ImportPrepareSupport.ResolvedTableColumns resolved =
                    prepareSupport.resolveTableColumns(columnManager, connection, db, tableName);

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new BusinessException("Excel file is empty or missing a header row");
            }

            // The first row fixes the column list, mirroring the CSV header row.
            List<ColumnMetadata> targetColumns = new ArrayList<>();
            HashSet<String> seenHeaders = new HashSet<>();
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
                ColumnMetadata column = resolved.byName().get(header.toLowerCase());
                if (column == null) {
                    throw new BusinessException("Excel header '" + header
                            + "' does not match any column of table " + tableName);
                }
                targetColumns.add(column);
            }
            if (targetColumns.isEmpty()) {
                throw new BusinessException("Excel file's header row contains no columns");
            }

            String identifierQuote = prepareSupport.resolveIdentifierQuote(connection);
            String insertSql = prepareSupport.buildImportInsertSql(identifierQuote, tableName, targetColumns);

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
                        valueConverter.setImportParameter(statement, i + 1, targetColumns.get(i), raw, fileRowNumber);
                    }
                    statement.addBatch();
                    batched++;

                    if (batched >= TransferConstants.IMPORT_BATCH_SIZE) {
                        batchSupport.executeImportBatch(statement, batchFirstRow);
                        batched = 0;
                        batchFirstRow = -1;
                    }
                }
                if (batched > 0) {
                    batchSupport.executeImportBatch(statement, batchFirstRow);
                }
                connection.commit();
            } catch (edu.zsc.ai.domain.service.db.transfer.ImportFailureException failure) {
                batchSupport.safeRollback(connection);
                log.warn("Excel import failed at file row {}: connectionId={}, tableName={}",
                        failure.fileRow(), db.connectionId(), tableName);
                return ImportTableDataResponse.builder()
                        .success(false)
                        .totalRows(totalRows)
                        .insertedRows(0)
                        .failedAtRow(failure.fileRow())
                        .errorMessage(failure.getMessage())
                        .build();
            } catch (Exception unexpected) {
                // Any error outside the normal ImportFailureException path must roll
                // back too; otherwise restoring autoCommit in finally could commit
                // partial rows on some JDBC drivers.
                batchSupport.safeRollback(connection);
                if (unexpected instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException("Import failed; transaction rolled back: "
                        + unexpected.getMessage(), unexpected);
            } finally {
                batchSupport.restoreAutoCommit(connection, originalAutoCommit);
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
}
