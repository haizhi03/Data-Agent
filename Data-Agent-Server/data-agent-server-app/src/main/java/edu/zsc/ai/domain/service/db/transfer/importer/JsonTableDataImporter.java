package edu.zsc.ai.domain.service.db.transfer.importer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.transfer.AbstractTableDataImporter;
import edu.zsc.ai.domain.service.db.transfer.ColumnValueConverter;
import edu.zsc.ai.domain.service.db.transfer.CreateTableDdlGenerator;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.domain.service.db.transfer.InferColumnTypeSupport;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * Imports rows from a JSON file containing a top-level array of row objects. The
 * first row object fixes the column list; all inserts run in one transaction.
 */
@Component
public class JsonTableDataImporter extends AbstractTableDataImporter {

    public JsonTableDataImporter(ConnectionService connectionService,
                                 ImportPrepareSupport prepareSupport,
                                 ColumnValueConverter valueConverter,
                                 ImportBatchSupport batchSupport,
                                 InferColumnTypeSupport inferSupport,
                                 CreateTableDdlGenerator ddlGenerator) {
        super(connectionService, prepareSupport, valueConverter, batchSupport, inferSupport, ddlGenerator);
    }

    @Override
    public String fileType() {
        return "JSON";
    }

    @Override
    protected PeekResult peekHeadersAndSample(PushbackInputStream pushback, String encoding) throws IOException {
        byte[] allBytes = pushback.readAllBytes();
        if (allBytes.length == 0) {
            return PeekResult.empty();
        }
        try {
            JsonParser parser = new JsonFactory().createParser(new ByteArrayInputStream(allBytes));
            try {
                if (parser.nextToken() != JsonToken.START_ARRAY) return PeekResult.empty();
                if (parser.nextToken() != JsonToken.START_OBJECT) return PeekResult.empty();

                JsonRow firstRow = readJsonRow(parser, 1);
                List<String> headers = firstRow.names();
                if (headers.isEmpty()) return PeekResult.empty();

                List<String[]> samples = new ArrayList<>();
                Map<String, Integer> fieldIndexByName = new HashMap<>();
                for (int i = 0; i < headers.size(); i++) {
                    fieldIndexByName.put(headers.get(i).toLowerCase(Locale.ROOT), i);
                }
                samples.add(sampleValues(firstRow, fieldIndexByName, 1));
                int row = 1;
                while (samples.size() < InferColumnTypeSupport.SAMPLE_ROW_LIMIT) {
                    JsonToken t = parser.nextToken();
                    if (t == null || t == JsonToken.END_ARRAY) break;
                    if (t != JsonToken.START_OBJECT) break;
                    row++;
                    JsonRow r = readJsonRow(parser, row);
                    samples.add(sampleValues(r, fieldIndexByName, row));
                }
                // Return the full bytes so the caller re-parses from a fresh stream
                // (the whole file no longer fits a bounded pushback buffer).
                return new PeekResult(headers, samples, allBytes);
            } finally {
                parser.close();
            }
        } catch (RuntimeException ex) {
            // Malformed JSON — surface so that prepare() fails later with a specific message
            throw new IOException("Failed to peek JSON header: " + ex.getMessage(), ex);
        }
    }

    @Override
    protected ImportSource prepare(InputStream inputStream, String encoding,
                                   ImportPrepareSupport.ResolvedTableColumns resolved,
                                   String tableName) throws IOException {
        JsonParser parser = new JsonFactory().createParser(inputStream);
        try {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new ImportFailureException(1, "JSON file must contain a top-level array of row objects");
            }
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new ImportFailureException(1, "JSON file contains no row objects");
            }

            JsonRow firstRow = readJsonRow(parser, 1);
            List<ColumnMetadata> targetColumns =
                    prepareSupport.matchColumns(firstRow.names(), resolved, tableName, "JSON");
            Map<String, Integer> fieldIndexByName = new HashMap<>();
            for (int i = 0; i < firstRow.names().size(); i++) {
                fieldIndexByName.put(firstRow.names().get(i).toLowerCase(Locale.ROOT), i);
            }
            if (targetColumns.isEmpty()) {
                throw new ImportFailureException(1, "JSON file's first row object contains no fields");
            }

            final String[] firstValues = mapImportRowValues(firstRow, fieldIndexByName, resolved, tableName, 1);

            return new ImportSource() {
                private boolean firstRowPending = true;
                private int currentRow = 1;
                private JsonToken peeked = null;

                @Override
                public List<ColumnMetadata> targetColumns() {
                    return targetColumns;
                }

                @Override
                public boolean hasNextRow() throws IOException {
                    if (firstRowPending) {
                        return true;
                    }
                    if (peeked == null) {
                        peeked = parser.nextToken();
                    }
                    return peeked != null && peeked != JsonToken.END_ARRAY;
                }

                @Override
                public ImportRow nextRow() throws IOException {
                    if (firstRowPending) {
                        firstRowPending = false;
                        return new ImportRow(firstValues, 1);
                    }
                    JsonToken token = peeked != null ? peeked : parser.nextToken();
                    peeked = null;
                    if (token == null || token == JsonToken.END_ARRAY) {
                        throw new IOException("No more rows available");
                    }
                    currentRow++;
                    if (token != JsonToken.START_OBJECT) {
                        throw new ImportFailureException(currentRow,
                                "Expected a JSON row object but found " + token);
                    }
                    JsonRow row = readJsonRow(parser, currentRow);
                    String[] values = mapImportRowValues(row, fieldIndexByName, resolved, tableName, currentRow);
                    return new ImportRow(values, currentRow);
                }

                @Override
                public void close() throws IOException {
                    parser.close();
                }
            };
        } catch (RuntimeException | IOException e) {
            parser.close();
            throw e;
        }
    }

    // --- JSON row parsing ---

    private String[] sampleValues(JsonRow row, Map<String, Integer> fieldIndexByName, int fileRow) {
        String[] values = new String[fieldIndexByName.size()];
        for (int i = 0; i < row.names().size(); i++) {
            Integer index = fieldIndexByName.get(row.names().get(i).toLowerCase(Locale.ROOT));
            if (index == null) {
                throw new ImportFailureException(fileRow, "JSON field '" + row.names().get(i)
                        + "' was not present in the first row object");
            }
            values[index] = row.values().get(i);
        }
        return values;
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
            if (!seen.add(fieldName.toLowerCase(Locale.ROOT))) {
                throw new ImportFailureException(rowIndex, "Duplicate JSON field: " + fieldName);
            }
            JsonToken valueToken = parser.nextToken();
            String text;
            if (valueToken == JsonToken.VALUE_NULL) {
                text = null;
            } else if (valueToken == JsonToken.VALUE_STRING
                    || valueToken == JsonToken.VALUE_NUMBER_INT
                    || valueToken == JsonToken.VALUE_NUMBER_FLOAT) {
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
                                        ImportPrepareSupport.ResolvedTableColumns resolved,
                                        String tableName, int rowIndex) {
        String[] values = new String[fieldIndexByName.size()];
        for (int i = 0; i < row.names().size(); i++) {
            String fieldName = row.names().get(i);
            String key = fieldName.toLowerCase(Locale.ROOT);
            if (!resolved.byName().containsKey(key)) {
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
}
