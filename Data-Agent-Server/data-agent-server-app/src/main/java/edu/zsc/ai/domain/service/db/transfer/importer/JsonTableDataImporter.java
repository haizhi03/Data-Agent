package edu.zsc.ai.domain.service.db.transfer.importer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.ColumnValueConverter;
import edu.zsc.ai.domain.service.db.transfer.ImportBatchSupport;
import edu.zsc.ai.domain.service.db.transfer.ImportFailureException;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Imports rows from a JSON file containing a top-level array of row objects. The
 * first row object fixes the column list; all inserts run in one transaction.
 */
@Component
@RequiredArgsConstructor
public class JsonTableDataImporter {

    private static final Logger log = LoggerFactory.getLogger(JsonTableDataImporter.class);

    private final ConnectionService connectionService;
    private final ImportPrepareSupport prepareSupport;
    private final ColumnValueConverter valueConverter;
    private final ImportBatchSupport batchSupport;

    /** One JSON row object: field names in file order and their text values (null for JSON null). */
    private record JsonRow(List<String> names, List<String> values) {
    }

    public ImportTableDataResponse importData(DbContext db, String tableName,
                                              InputStream inputStream) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ColumnManager columnManager = DefaultPluginManager.getInstance()
                .getColumnManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             JsonParser parser = new JsonFactory().createParser(inputStream)) {
            Connection connection = borrowed.connection();

            ImportPrepareSupport.ResolvedTableColumns resolved =
                    prepareSupport.resolveTableColumns(columnManager, connection, db, tableName);

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new BusinessException("JSON file must contain a top-level array of row objects");
            }

            // The first row object fixes the column list, mirroring the CSV header row.
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new BusinessException("JSON file contains no row objects");
            }
            JsonRow firstRow = readJsonRow(parser, 1);
            List<ColumnMetadata> targetColumns = new ArrayList<>();
            java.util.HashMap<String, Integer> fieldIndexByName = new java.util.HashMap<>();
            for (int i = 0; i < firstRow.names().size(); i++) {
                String fieldName = firstRow.names().get(i);
                ColumnMetadata column = resolved.byName().get(fieldName.toLowerCase());
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

            String identifierQuote = prepareSupport.resolveIdentifierQuote(connection);
            String insertSql = prepareSupport.buildImportInsertSql(identifierQuote, tableName, targetColumns);

            boolean originalAutoCommit = connection.getAutoCommit();
            int totalRows = 0;
            try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                connection.setAutoCommit(false);

                int batched = 0;
                int batchFirstRow = 1; // 1-based index of the JSON row object starting the batch.
                // The already-parsed first row joins the same batch sequence.
                totalRows = 1;
                setImportRowParameters(statement, targetColumns,
                        mapImportRowValues(firstRow, fieldIndexByName, resolved, tableName, 1), 1);
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
                            mapImportRowValues(row, fieldIndexByName, resolved, tableName, rowIndex),
                            rowIndex);
                    statement.addBatch();
                    batched++;

                    if (batched >= TransferConstants.IMPORT_BATCH_SIZE) {
                        batchSupport.executeImportBatch(statement, batchFirstRow);
                        batched = 0;
                        batchFirstRow = rowIndex + 1;
                    }
                }
                if (batched > 0) {
                    batchSupport.executeImportBatch(statement, batchFirstRow);
                }
                connection.commit();
            } catch (ImportFailureException failure) {
                batchSupport.safeRollback(connection);
                log.warn("JSON import failed at row {}: connectionId={}, tableName={}",
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
                                        ImportPrepareSupport.ResolvedTableColumns resolved,
                                        String tableName, int rowIndex) {
        String[] values = new String[fieldIndexByName.size()];
        for (int i = 0; i < row.names().size(); i++) {
            String fieldName = row.names().get(i);
            String key = fieldName.toLowerCase();
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

    private void setImportRowParameters(PreparedStatement statement, List<ColumnMetadata> targetColumns,
                                        String[] values, int rowIndex) throws SQLException {
        for (int i = 0; i < targetColumns.size(); i++) {
            valueConverter.setImportParameter(statement, i + 1, targetColumns.get(i),
                    i < values.length ? values[i] : null, rowIndex);
        }
    }
}
