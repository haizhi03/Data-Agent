package edu.zsc.ai.domain.service.db.transfer.export;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
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

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.util.List;

/**
 * Exports all rows of a table as a pretty-printed JSON array of objects, page by page.
 */
@Component
@RequiredArgsConstructor
public class JsonTableDataExporter {

    private static final Logger log = LoggerFactory.getLogger(JsonTableDataExporter.class);

    private final ConnectionService connectionService;

    public void export(DbContext db, String tableName, OutputStream outputStream,
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
                    tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);

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

                offset += TransferConstants.EXPORT_PAGE_SIZE;
                while (firstRows.size() == TransferConstants.EXPORT_PAGE_SIZE) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    for (List<Object> row : rows) {
                        writeJsonObject(generator, headers, row);
                    }
                    generator.flush();
                    if (rows.size() < TransferConstants.EXPORT_PAGE_SIZE) {
                        break;
                    }
                    offset += TransferConstants.EXPORT_PAGE_SIZE;
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
}
