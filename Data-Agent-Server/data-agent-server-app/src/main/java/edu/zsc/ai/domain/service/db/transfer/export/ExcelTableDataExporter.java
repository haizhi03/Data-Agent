package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import lombok.RequiredArgsConstructor;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Exports all rows of a table as an Excel workbook. XLSX uses the streaming SXSSF
 * API (500 rows in memory); XLS uses HSSF and is limited to 65535 data rows.
 */
@Component
@RequiredArgsConstructor
public class ExcelTableDataExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelTableDataExporter.class);

    private final ConnectionService connectionService;

    public void export(DbContext db, String tableName, String format,
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
                if (totalRows > TransferConstants.XLS_MAX_DATA_ROWS) {
                    throw new BusinessException("XLS format supports at most " + TransferConstants.XLS_MAX_DATA_ROWS
                            + " data rows (found " + totalRows + "); please choose XLSX instead");
                }
            }

            // Query the first page so connection/query errors surface before headers are committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, 0, TransferConstants.EXPORT_PAGE_SIZE);

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

                int offset = TransferConstants.EXPORT_PAGE_SIZE;
                while (firstRows.size() == TransferConstants.EXPORT_PAGE_SIZE) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    rowIndex = writeExcelRows(sheet, rows, rowIndex, dateStyle);
                    if (rows.size() < TransferConstants.EXPORT_PAGE_SIZE) {
                        break;
                    }
                    offset += TransferConstants.EXPORT_PAGE_SIZE;
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
}
