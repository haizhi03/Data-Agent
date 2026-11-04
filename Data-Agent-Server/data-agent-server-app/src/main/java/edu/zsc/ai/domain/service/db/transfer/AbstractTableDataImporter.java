package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.SequenceInputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Template-method base class for table data importers. The skeleton lives here:
 * open the connection, resolve table columns, let the subclass parse the file
 * and validate headers, then run the batch-insert loop inside a single
 * transaction with unified error handling. Subclasses only implement
 * {@link #prepare} which returns a row-iterable {@link ImportSource}.
 *
 * <p>To add a new import format: create a class extending this base, implement
 * {@link #fileType()} and {@link #prepare}, and annotate with {@code @Component}.
 */
public abstract class AbstractTableDataImporter implements TableDataImporter {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ConnectionService connectionService;
    protected final ImportPrepareSupport prepareSupport;
    protected final ColumnValueConverter valueConverter;
    protected final ImportBatchSupport batchSupport;
    protected final InferColumnTypeSupport inferSupport;
    protected final CreateTableDdlGenerator ddlGenerator;

    protected AbstractTableDataImporter(ConnectionService connectionService,
                                        ImportPrepareSupport prepareSupport,
                                        ColumnValueConverter valueConverter,
                                        ImportBatchSupport batchSupport,
                                        InferColumnTypeSupport inferSupport,
                                        CreateTableDdlGenerator ddlGenerator) {
        this.connectionService = connectionService;
        this.prepareSupport = prepareSupport;
        this.valueConverter = valueConverter;
        this.batchSupport = batchSupport;
        this.inferSupport = inferSupport;
        this.ddlGenerator = ddlGenerator;
    }

    @Override
    public final ImportTableDataResponse importData(DbContext db, String tableName,
                                                     InputStream inputStream, String encoding) {
        return importData(db, tableName, inputStream, encoding, false);
    }

    /**
     * Import with an optional auto-create-table flag. When {@code createTableIfMissing}
     * is true and the target table does not exist, the file is pre-scanned for
     * headers + sample rows, a CREATE TABLE DDL is executed, and the original
     * import flow resumes against the newly-created table.
     */
    public final ImportTableDataResponse importData(DbContext db, String tableName,
                                                     InputStream inputStream, String encoding,
                                                     boolean createTableIfMissing) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ColumnManager columnManager = DefaultPluginManager.getInstance()
                .getColumnManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();

            ImportPrepareSupport.ResolvedTableColumns resolved =
                    prepareSupport.resolveTableColumns(columnManager, connection, db, tableName);

            // --- Auto-create-table path ---
            if (resolved.columns().isEmpty() && createTableIfMissing) {
                PushbackInputStream pb = new PushbackInputStream(inputStream, 64 * 1024);
                PeekResult peek = peekHeadersAndSample(pb, encoding);
                if (!peek.headerNames().isEmpty()) {
                    List<ColumnMetadata> inferred = inferSupport.inferColumnTypes(
                            peek.headerNames(), peek.sampleRows());
                    String identifierQuote = ImportPrepareSupport.resolveIdentifierQuote(connection);
                    String ddl = ddlGenerator.buildCreateTable(identifierQuote, db, tableName, inferred);
                    log.info("Auto-creating table {} before import: {}", tableName, ddl);
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(ddl);
                    }
                    resolved = prepareSupport.resolveTableColumns(columnManager, connection, db, tableName);
                }
                // Replay the consumed prefix (including parser read-ahead), then
                // continue the original stream. Full-buffer formats are already at EOF.
                inputStream = peek.content() != null
                        ? new SequenceInputStream(new ByteArrayInputStream(peek.content()), pb)
                        : pb;
            }

            if (resolved.columns().isEmpty()) {
                throw new ImportFailureException(1, "Cannot resolve columns of table: " + tableName);
            }

            try (ImportSource source = prepare(inputStream, encoding, resolved, tableName)) {
                List<ColumnMetadata> targetColumns = source.targetColumns();
                String identifierQuote = ImportPrepareSupport.resolveIdentifierQuote(connection);
                String insertSql = prepareSupport.buildImportInsertSql(identifierQuote, tableName, targetColumns);
                return runImportBatch(connection, insertSql, targetColumns, source, db, tableName);
            }
        } catch (ImportFailureException failure) {
            // Header-phase validation failure from prepare()
            log.warn("{} import failed at row {}: connectionId={}, tableName={}",
                    fileType(), failure.fileRow(), db.connectionId(), tableName);
            return ImportTableDataResponse.builder()
                    .success(false)
                    .totalRows(0)
                    .insertedRows(0)
                    .failedAtRow(failure.fileRow())
                    .errorMessage(failure.getMessage())
                    .build();
        } catch (SQLException | IOException ex) {
            throw new RuntimeException("Import " + fileType() + " failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Runs the batch-insert loop inside a single transaction (via
     * {@link ImportBatchSupport#runInTransaction}). {@link ImportFailureException}
     * is mapped to a structured failure response; any other error has already
     * been rolled back and rethrown by the transaction template.
     */
    private ImportTableDataResponse runImportBatch(Connection connection, String insertSql,
                                                   List<ColumnMetadata> targetColumns,
                                                   ImportSource source,
                                                   DbContext db, String tableName) throws SQLException {
        int[] totalRowsHolder = new int[1]; // stays visible to the failure path below
        try {
            batchSupport.runInTransaction(connection, () -> {
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    int batched = 0;
                    int batchFirstRow = -1; // Lazily set to the first data row of each batch.
                    while (source.hasNextRow()) {
                        ImportRow row = source.nextRow();
                        if (batchFirstRow < 0) {
                            batchFirstRow = row.fileRowNumber();
                        }
                        totalRowsHolder[0]++;
                        for (int i = 0; i < targetColumns.size(); i++) {
                            valueConverter.setImportParameter(statement, i + 1,
                                    targetColumns.get(i), row.valueAt(i), row.fileRowNumber());
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
                    return totalRowsHolder[0];
                }
            });
        } catch (ImportFailureException failure) {
            log.warn("{} import failed at row {}: connectionId={}, tableName={}",
                    fileType(), failure.fileRow(), db.connectionId(), tableName);
            return ImportBatchSupport.failureResponse(failure.fileRow(), failure.getMessage(),
                    totalRowsHolder[0]);
        }

        log.info("{} import completed: connectionId={}, tableName={}, totalRows={}",
                fileType(), db.connectionId(), tableName, totalRowsHolder[0]);
        return ImportTableDataResponse.builder()
                .success(true)
                .totalRows(totalRowsHolder[0])
                .insertedRows(totalRowsHolder[0])
                .build();
    }

    // --- Abstract methods ---

    /** Returns the file format name, e.g. "CSV", "JSON", "SQL". */
    public abstract String fileType();

    /**
     * Pre-scans the file on a {@link PushbackInputStream}, extracts the header
     * (column names) and up to {@link InferColumnTypeSupport#SAMPLE_ROW_LIMIT}
     * data rows for type inference. Return consumed bytes in {@link PeekResult#content()}
     * so the caller can replay them before the remainder of the stream.
     *
     * <p>Format importers that do not support per-column type inference (e.g.
     * SQL — the file already contains DDL) should return {@link PeekResult#empty()}
     * so that no auto-create is attempted.
     */
    protected abstract PeekResult peekHeadersAndSample(PushbackInputStream inputStream,
                                                       String encoding) throws IOException;

    /**
     * Parse the uploaded file, validate its header against the target table
     * columns, and return a row-iterable source. The returned source's
     * {@link ImportSource#targetColumns()} must return the mapped columns in
     * the order they appear in the file header.
     */
    protected abstract ImportSource prepare(InputStream inputStream, String encoding,
                                           ImportPrepareSupport.ResolvedTableColumns resolved,
                                           String tableName) throws IOException;

    // --- Shared types ---

    /**
     * Result of pre-scanning a file for auto-create-table: header names + sample
     * rows, one String per column per row, plus any consumed bytes to replay
     * before the remainder of the stream ({@code null} when nothing was consumed).
     */
    public record PeekResult(List<String> headerNames, List<String[]> sampleRows, byte[] content) {
        public static PeekResult empty() {
            return new PeekResult(List.of(), List.of(), null);
        }
    }

    /** A row-iterable source produced by {@link #prepare}. */
    public interface ImportSource extends AutoCloseable {
        /** Columns mapped from the file header, in file order. */
        List<ColumnMetadata> targetColumns();

        /** True if another data row is available. */
        boolean hasNextRow() throws IOException;

        /** Returns the next data row. */
        ImportRow nextRow() throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    /** One import data row: raw text values and the 1-based file row number. */
    public record ImportRow(String[] values, int fileRowNumber) {
        /** Returns the value at {@code index}, or {@code null} if out of bounds. */
        public String valueAt(int index) {
            return index < values.length ? values[index] : null;
        }
    }
}
