package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;

/**
 * Exports all rows of a table as CSV (UTF-8 with BOM), page by page.
 */
@Component
@RequiredArgsConstructor
public class CsvTableDataExporter {

    private static final Logger log = LoggerFactory.getLogger(CsvTableDataExporter.class);

    private final ConnectionService connectionService;

    public void export(DbContext db, String tableName, OutputStream outputStream,
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
                    tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);

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

            offset += TransferConstants.EXPORT_PAGE_SIZE;
            while (firstRows.size() == TransferConstants.EXPORT_PAGE_SIZE) {
                SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                        tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);
                List<List<Object>> rows = page.getRows();
                if (rows == null || rows.isEmpty()) {
                    break;
                }
                writeCsvRows(writer, rows);
                if (rows.size() < TransferConstants.EXPORT_PAGE_SIZE) {
                    break;
                }
                offset += TransferConstants.EXPORT_PAGE_SIZE;
            }
            writer.flush();
            log.info("CSV export completed: connectionId={}, tableName={}, lastOffset={}",
                    db.connectionId(), tableName, offset);
        } catch (IOException ex) {
            throw new RuntimeException("Export CSV failed: " + ex.getMessage(), ex);
        }
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
