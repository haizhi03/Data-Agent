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
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Imports rows from a CSV file. The first CSV line is a header row whose names
 * must match table columns; all inserts run in one transaction.
 */
@Component
@RequiredArgsConstructor
public class CsvTableDataImporter {

    private static final Logger log = LoggerFactory.getLogger(CsvTableDataImporter.class);

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

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();

            ImportPrepareSupport.ResolvedTableColumns resolved =
                    prepareSupport.resolveTableColumns(columnManager, connection, db, tableName);

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
                ColumnMetadata column = resolved.byName().get(normalized.toLowerCase());
                if (column == null) {
                    throw new BusinessException("CSV header '" + normalized
                            + "' does not match any column of table " + tableName);
                }
                targetColumns.add(column);
            }

            String identifierQuote = prepareSupport.resolveIdentifierQuote(connection);
            String insertSql = prepareSupport.buildImportInsertSql(identifierQuote, tableName, targetColumns);

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
                        valueConverter.setImportParameter(statement, i + 1, column, raw, fileRowNumber);
                    }
                    statement.addBatch();
                    batched++;

                    if (batched >= TransferConstants.IMPORT_BATCH_SIZE) {
                        batchSupport.executeImportBatch(statement, batchFirstRow);
                        batched = 0;
                        batchFirstRow = totalRows + 2;
                    }
                }
                if (batched > 0) {
                    batchSupport.executeImportBatch(statement, batchFirstRow);
                }
                connection.commit();
            } catch (edu.zsc.ai.domain.service.db.transfer.ImportFailureException failure) {
                batchSupport.safeRollback(connection);
                log.warn("CSV import failed at file row {}: connectionId={}, tableName={}",
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
}
