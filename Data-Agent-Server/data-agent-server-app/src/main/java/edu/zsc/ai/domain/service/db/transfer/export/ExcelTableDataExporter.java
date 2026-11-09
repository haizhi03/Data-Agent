package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
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
public class ExcelTableDataExporter extends AbstractTableDataExporter<ExcelTableDataExporter.ExcelSession> {

    public ExcelTableDataExporter(ConnectionService connectionService) {
        super(connectionService);
    }

    @Override
    public String fileType() {
        return "XLSX";
    }

    @Override
    public List<String> aliases() {
        return List.of("XLS");
    }

    @Override
    public String extension(String fileType) {
        return "XLS".equalsIgnoreCase(fileType) ? ".xls" : ".xlsx";
    }

    @Override
    public String contentType(String fileType) {
        return "XLS".equalsIgnoreCase(fileType)
                ? "application/vnd.ms-excel"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    protected void preCheck(TableManager provider, Connection connection, DbContext db,
                            String tableName, String fileType) {
        if ("XLS".equalsIgnoreCase(fileType)) {
            long totalRows = provider.getTableDataCount(connection, db.catalog(), db.schema(), tableName);
            if (totalRows > TransferConstants.XLS_MAX_DATA_ROWS) {
                throw new BusinessException("XLS format supports at most " + TransferConstants.XLS_MAX_DATA_ROWS
                        + " data rows (found " + totalRows + "); please choose XLSX instead");
            }
        }
    }

    @Override
    protected ExcelSession onStart(OutputStream outputStream, DbContext db, String tableName, String fileType,
                                   List<String> headers, List<Integer> columnIndexes,
                                   SqlCommandResult firstPage, String identifierQuote, String dbType) throws IOException {
        boolean xlsx = !"XLS".equalsIgnoreCase(fileType);

        SXSSFWorkbook sxssfWorkbook = null;
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

        Sheet sheet = workbook.createSheet(buildSafeSheetName(tableName));
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < columnIndexes.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(columnIndexes.get(i)));
            cell.setCellStyle(headerStyle);
        }

        return new ExcelSession(workbook, sxssfWorkbook, sheet, columnIndexes, dateStyle, outputStream, 1);
    }

    @Override
    protected void writeRows(ExcelSession session, List<List<Object>> rows) throws IOException {
        for (List<Object> rowData : rows) {
            Row row = session.sheet().createRow(session.rowIndex());
            List<Integer> columnIndexes = session.columnIndexes();
            for (int i = 0; i < columnIndexes.size(); i++) {
                int columnIndex = columnIndexes.get(i);
                Cell cell = row.createCell(i);
                setExcelCellValue(cell, columnIndex < rowData.size() ? rowData.get(columnIndex) : null,
                        session.dateStyle());
            }
            session.incrementRowIndex();
        }
    }

    @Override
    protected void onFinish(ExcelSession session) throws IOException {
        Workbook workbook = session.workbook();
        workbook.write(session.outputStream());
        workbook.close();
        session.outputStream().flush();
    }

    @Override
    protected void cleanup(ExcelSession session) {
        SXSSFWorkbook sxssf = session.sxssfWorkbook();
        if (sxssf != null) {
            sxssf.dispose();
        }
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

    /**
     * Session that carries the workbook, sheet, and current row index across template-method calls.
     */
    static class ExcelSession {
        private final Workbook workbook;
        private final SXSSFWorkbook sxssfWorkbook;
        private final Sheet sheet;
        private final List<Integer> columnIndexes;
        private final CellStyle dateStyle;
        private final OutputStream outputStream;
        private int rowIndex;

        ExcelSession(Workbook workbook, SXSSFWorkbook sxssfWorkbook, Sheet sheet,
                     List<Integer> columnIndexes, CellStyle dateStyle, OutputStream outputStream,
                     int initialRowIndex) {
            this.workbook = workbook;
            this.sxssfWorkbook = sxssfWorkbook;
            this.sheet = sheet;
            this.columnIndexes = columnIndexes;
            this.dateStyle = dateStyle;
            this.outputStream = outputStream;
            this.rowIndex = initialRowIndex;
        }

        Workbook workbook() { return workbook; }
        SXSSFWorkbook sxssfWorkbook() { return sxssfWorkbook; }
        Sheet sheet() { return sheet; }
        List<Integer> columnIndexes() { return columnIndexes; }
        CellStyle dateStyle() { return dateStyle; }
        OutputStream outputStream() { return outputStream; }
        int rowIndex() { return rowIndex; }
        void incrementRowIndex() { rowIndex++; }
    }
}
