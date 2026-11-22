package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.model.context.DbContext;

import java.io.OutputStream;
import java.util.List;

/**
 * Strategy interface for exporting a table's rows in one file format.
 * Implementations are registered in {@link TableDataTransferRegistry} by
 * {@link #fileType()} (plus optional {@link #aliases()}), so callers never
 * switch on format names.
 */
public interface TableDataExporter {

    /** Upper-case format key, e.g. "CSV". */
    String fileType();

    /** Extra format keys handled by this exporter (e.g. "XLS" for the Excel exporter). */
    default List<String> aliases() {
        return List.of();
    }

    /** File name extension including the dot, e.g. ".csv". */
    String extension(String fileType);

    /** HTTP Content-Type of the exported file. */
    String contentType(String fileType);

    /**
     * Export all rows of a table to the given output stream, page by page.
     * The {@code onBeforeFirstWrite} callback runs after the first page query
     * succeeds and before any byte is written, so the caller can set response
     * headers only when the export can actually start.
     *
     * @param fileType the requested format key (only relevant for multi-format
     *                 implementations like Excel; single-format ones ignore it)
     * @param columns  column names to export (case-insensitive, in table order);
     *                 null exports all columns
     * @param whereClause optional WHERE condition without the keyword (null/blank
     *                    exports every row)
     * @param orderByColumn optional column to order by (null/blank uses the
     *                      database's natural order)
     * @param orderByDirection "asc" or "desc", ignored when no order column
     */
    void export(DbContext db, String tableName, String fileType, List<String> columns,
                String whereClause, String orderByColumn, String orderByDirection,
                OutputStream outputStream, Runnable onBeforeFirstWrite);
}
