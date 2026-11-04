package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Exports all rows of a table as CSV (UTF-8 with BOM), page by page.
 */
@Component
public class CsvTableDataExporter extends AbstractTableDataExporter<CsvTableDataExporter.CsvSession> {

    public CsvTableDataExporter(ConnectionService connectionService) {
        super(connectionService);
    }

    @Override
    public String fileType() {
        return "CSV";
    }

    @Override
    public String extension(String fileType) {
        return ".csv";
    }

    @Override
    public String contentType(String fileType) {
        return "text/csv; charset=UTF-8";
    }

    @Override
    protected CsvSession onStart(OutputStream outputStream, DbContext db, String tableName, String fileType,
                                 List<String> headers, List<Integer> columnIndexes,
                                 SqlCommandResult firstPage, String identifierQuote) throws IOException {
        OutputStreamWriter streamWriter = new OutputStreamWriter(nonClosing(outputStream), StandardCharsets.UTF_8);
        BufferedWriter writer = new BufferedWriter(streamWriter);
        boolean hasRows = firstPage.getRows() != null && !firstPage.getRows().isEmpty();
        // Empty table without headers writes nothing at all (original behavior).
        if (hasRows || !headers.isEmpty()) {
            writer.write('\ufeff');
            writeCsvHeader(writer, headers, columnIndexes);
        }
        return new CsvSession(writer, columnIndexes);
    }

    @Override
    protected void writeRows(CsvSession session, List<List<Object>> rows) throws IOException {
        BufferedWriter writer = session.writer();
        List<Integer> columnIndexes = session.columnIndexes();
        for (List<Object> row : rows) {
            for (int i = 0; i < columnIndexes.size(); i++) {
                if (i > 0) {
                    writer.write(',');
                }
                int index = columnIndexes.get(i);
                Object value = index < row.size() ? row.get(index) : null;
                writer.write(toCsvField(value == null ? "" : value.toString()));
            }
            writer.write("\r\n");
        }
    }

    @Override
    protected void onFinish(CsvSession session) throws IOException {
        session.writer().flush();
    }

    @Override
    protected void cleanup(CsvSession session) {
        // Do NOT close the writer — it wraps response.getOutputStream() and
        // closing it would prematurely terminate the HTTP download stream.
        // Spring owns the response stream lifecycle; we only flush in onFinish.
    }

    private void writeCsvHeader(BufferedWriter writer, List<String> headers,
                                List<Integer> columnIndexes) throws IOException {
        for (int i = 0; i < columnIndexes.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(toCsvField(headers.get(columnIndexes.get(i))));
        }
        writer.write("\r\n");
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

    /**
     * Session that carries the writer and selected columns across template-method calls.
     */
    record CsvSession(BufferedWriter writer, List<Integer> columnIndexes) {
    }
}
