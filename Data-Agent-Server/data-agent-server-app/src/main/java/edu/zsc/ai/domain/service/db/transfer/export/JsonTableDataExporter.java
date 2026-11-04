package edu.zsc.ai.domain.service.db.transfer.export;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Exports all rows of a table as a pretty-printed JSON array of objects, page by page.
 */
@Component
public class JsonTableDataExporter extends AbstractTableDataExporter<JsonTableDataExporter.JsonSession> {

    public JsonTableDataExporter(ConnectionService connectionService) {
        super(connectionService);
    }

    @Override
    public String fileType() {
        return "JSON";
    }

    @Override
    public String extension(String fileType) {
        return ".json";
    }

    @Override
    public String contentType(String fileType) {
        return "application/json; charset=UTF-8";
    }

    @Override
    protected JsonSession onStart(OutputStream outputStream, DbContext db, String tableName, String fileType,
                                  List<String> headers, List<Integer> columnIndexes,
                                  SqlCommandResult firstPage, String identifierQuote) throws IOException {
        JsonGenerator generator = new JsonFactory().createGenerator(nonClosing(outputStream), JsonEncoding.UTF8);
        // Pretty-printed JSON: each field on its own indented line.
        generator.useDefaultPrettyPrinter();
        generator.writeStartArray();
        return new JsonSession(generator, headers, columnIndexes);
    }

    @Override
    protected void writeRows(JsonSession session, List<List<Object>> rows) throws IOException {
        for (List<Object> row : rows) {
            writeJsonObject(session.generator(), session.headers(), row, session.columnIndexes());
        }
    }

    @Override
    protected void flushPage(JsonSession session) throws IOException {
        session.generator().flush();
    }

    @Override
    protected void onFinish(JsonSession session) throws IOException {
        session.generator().writeEndArray();
        session.generator().flush();
    }

    @Override
    protected void cleanup(JsonSession session) {
        // Do NOT close the generator — it wraps response.getOutputStream() and
        // closing it would prematurely terminate the HTTP download stream.
        // Spring owns the response stream lifecycle; we only flush in onFinish.
    }

    private void writeJsonObject(JsonGenerator generator, List<String> headers, List<Object> row,
                                 List<Integer> columnIndexes) throws IOException {
        generator.writeStartObject();
        for (int i = 0; i < columnIndexes.size(); i++) {
            int index = columnIndexes.get(i);
            generator.writeFieldName(headers.get(index));
            Object value = index < row.size() ? row.get(index) : null;
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

    /**
     * Session that carries the generator and resolved columns across template-method calls.
     */
    record JsonSession(JsonGenerator generator, List<String> headers, List<Integer> columnIndexes) {
    }
}
