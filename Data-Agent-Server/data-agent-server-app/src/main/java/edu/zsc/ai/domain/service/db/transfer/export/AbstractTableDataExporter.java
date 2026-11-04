package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.domain.service.db.transfer.TableDataExporter;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.Connection;
import java.util.List;

/**
 * Template-method base class for table data exporters. The skeleton lives here:
 * open the connection, query the first page before committing anything, resolve
 * the selected columns, then stream page by page. Subclasses only implement the
 * format-specific parts: how to start the file, how to write one page of rows,
 * and how to finish.
 *
 * @param <S> the per-export session type (writer, generator, workbook holder, ...)
 */
public abstract class AbstractTableDataExporter<S> implements TableDataExporter {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final ConnectionService connectionService;

    protected AbstractTableDataExporter(ConnectionService connectionService) {
        this.connectionService = connectionService;
    }

    @Override
    public final void export(DbContext db, String tableName, String fileType, List<String> columns,
                             OutputStream outputStream, Runnable onBeforeFirstWrite) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

        S session = null;
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();
            String identifierQuote = ImportPrepareSupport.resolveIdentifierQuote(connection);
            preCheck(provider, connection, db, tableName, fileType);

            // Query the first page before writing anything, so connection/query errors
            // surface before the HTTP response is committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, 0, TransferConstants.EXPORT_PAGE_SIZE);

            List<String> headers = first.getHeaders() == null ? List.of() : first.getHeaders();
            List<Integer> columnIndexes = ExportColumnSelector.resolve(headers, columns);

            // First page query succeeded: it is now safe to commit file-download headers.
            onBeforeFirstWrite.run();

            session = onStart(outputStream, db, tableName, fileType, headers, columnIndexes,
                    first, identifierQuote);

            int offset = 0;
            long totalRows = 0;
            List<List<Object>> firstRows = first.getRows();
            if (firstRows != null && !firstRows.isEmpty()) {
                writeRows(session, firstRows);
                totalRows += firstRows.size();
                log.info("{} export started: connectionId={}, tableName={}, firstPageRows={}",
                        fileType(), db.connectionId(), tableName, firstRows.size());

                offset += TransferConstants.EXPORT_PAGE_SIZE;
                // Keep fetching while the last page came back completely full;
                // a short page means the table is exhausted.
                boolean morePages = firstRows.size() == TransferConstants.EXPORT_PAGE_SIZE;
                while (morePages) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    writeRows(session, rows);
                    flushPage(session);
                    totalRows += rows.size();
                    morePages = rows.size() == TransferConstants.EXPORT_PAGE_SIZE;
                    offset += TransferConstants.EXPORT_PAGE_SIZE;
                }
            }

            onFinish(session);
            log.info("{} export completed: connectionId={}, tableName={}, totalRows={}",
                    fileType(), db.connectionId(), tableName, totalRows);
        } catch (IOException ex) {
            throw new RuntimeException("Export " + fileType() + " failed: " + ex.getMessage(), ex);
        } finally {
            if (session != null) {
                cleanup(session);
            }
        }
    }

    /**
     * Optional validation before the first page query (e.g. the XLS row limit).
     * Default: no checks.
     */
    protected void preCheck(TableManager provider, Connection connection, DbContext db,
                            String tableName, String fileType) {
    }

    /**
     * Servlet responses own the output stream. Closing a writer or generator
     * must not close that stream, or the download is cut off before the client
     * finishes reading it.
     */
    protected static OutputStream nonClosing(OutputStream outputStream) {
        return new FilterOutputStream(outputStream) {
            @Override
            public void close() throws IOException {
                flush();
            }
        };
    }

    /**
     * Create the format session and write anything that precedes the data rows
     * (BOM, header line, file preamble, workbook header row, ...).
     * @param identifierQuote the database's identifier quote string ("" when the
     *                        database does not quote); only SQL output needs it
     */
    protected abstract S onStart(OutputStream outputStream, DbContext db, String tableName, String fileType,
                                 List<String> headers, List<Integer> columnIndexes,
                                 SqlCommandResult firstPage, String identifierQuote) throws IOException;

    /** Write one page of data rows into the session. */
    protected abstract void writeRows(S session, List<List<Object>> rows) throws IOException;

    /** Flush after each streamed page. Default: no-op (flush happens in onFinish). */
    protected void flushPage(S session) throws IOException {
    }

    /** Finalize the file (closing brackets, flushing, writing the workbook, ...). */
    protected abstract void onFinish(S session) throws IOException;

    /**
     * Release session resources in a finally block (closing writers, disposing
     * temporary files, ...). Default: no-op.
     */
    protected void cleanup(S session) {
    }
}
