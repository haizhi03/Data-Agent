package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ImportTableDataResponse;

import java.io.InputStream;
import java.util.List;

/**
 * Strategy interface for importing rows from an uploaded file in one format.
 * Implementations are registered in {@link TableDataTransferRegistry} by
 * {@link #fileType()} (plus optional {@link #aliases()}), so callers never
 * switch on format names.
 */
public interface TableDataImporter {

    /** Upper-case format key, e.g. "CSV". */
    String fileType();

    /** Extra format keys handled by this importer (e.g. "XLS" for the Excel importer). */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * Import rows into a table. All inserts run inside one transaction: any row
     * failure triggers a full rollback.
     *
     * @param encoding requested file charset (AUTO / UTF-8 / GBK); only text
     *                 formats like CSV use it, others ignore it
     */
    ImportTableDataResponse importData(DbContext db, String tableName, InputStream inputStream, String encoding);

    /**
     * Import with optional auto-create-table flag. Defaults to {@code false}
     * (identical to the 4-arg form). Formats that cannot infer a CREATE TABLE
     * from their input (e.g. SQL — the file may already contain DDL) may
     * override without honouring the flag.
     */
    default ImportTableDataResponse importData(DbContext db, String tableName,
                                                InputStream inputStream, String encoding,
                                                boolean createTableIfMissing) {
        return importData(db, tableName, inputStream, encoding);
    }
}
