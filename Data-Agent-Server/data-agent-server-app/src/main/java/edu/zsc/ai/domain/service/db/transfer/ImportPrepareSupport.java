package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/**
 * Prepares an import: resolves the target table columns, the database identifier
 * quote string, and the parameterized INSERT statement.
 */
@Component
public class ImportPrepareSupport {

    /** Target table columns in database order plus a lookup map keyed by lower-case name. */
    public record ResolvedTableColumns(
            List<ColumnMetadata> columns,
            Map<String, ColumnMetadata> byName) {
    }

    public ResolvedTableColumns resolveTableColumns(ColumnManager columnManager, Connection connection,
                                                    DbContext db, String tableName) throws SQLException {
        List<ColumnMetadata> tableColumns = columnManager.getColumns(
                connection, db.catalog(), db.schema(), tableName);
        Map<String, ColumnMetadata> columnsByName = new HashMap<>();
        for (ColumnMetadata column : tableColumns) {
            columnsByName.put(column.name().toLowerCase(Locale.ROOT), column);
        }
        return new ResolvedTableColumns(tableColumns, columnsByName);
    }

    /**
     * Resolves the database's identifier quote string (e.g. backtick on MySQL),
     * falling back to no quoting when metadata is unavailable: a metadata hiccup
     * must not fail an import or export.
     */
    public static String resolveIdentifierQuote(Connection connection) {
        try {
            String quote = connection.getMetaData().getIdentifierQuoteString();
            return (quote == null || quote.isBlank()) ? "" : quote.trim();
        } catch (SQLException ex) {
            return "";
        }
    }

    public String buildImportInsertSql(String quote, String tableName,
                                       List<ColumnMetadata> columns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ")
                .append(quoteIdentifier(quote, tableName))
                .append(" (");
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
                placeholders.append(", ");
            }
            sql.append(quoteIdentifier(quote, columns.get(i).name()));
            placeholders.append('?');
        }
        sql.append(") VALUES (").append(placeholders).append(')');
        return sql.toString();
    }

    /** Wraps an identifier in the database's quote string ("" quote = no quoting). */
    public static String quoteIdentifier(String quote, String identifier) {
        return quote.isEmpty() ? identifier : quote + identifier + quote;
    }

    /**
     * Maps a file header's column names onto real table columns, in file order.
     * Rejects empty names, duplicates (case-insensitive), and names that do not
     * match any column of the target table; {@code formatName} (e.g. "CSV") is
     * used in the error messages.
     */
    public List<ColumnMetadata> matchColumns(List<String> headerNames,
                                             ResolvedTableColumns resolved,
                                             String tableName, String formatName) {
        List<ColumnMetadata> targetColumns = new ArrayList<>();
        Set<String> seenHeaders = new HashSet<>();
        for (String header : headerNames) {
            String normalized = header == null ? "" : header.trim();
            if (normalized.isEmpty()) {
                throw new ImportFailureException(1,
                        formatName + " header contains an empty column name");
            }
            if (!seenHeaders.add(normalized.toLowerCase(Locale.ROOT))) {
                throw new ImportFailureException(1,
                        "Duplicate " + formatName + " header: " + normalized);
            }
            ColumnMetadata column = resolved.byName().get(normalized.toLowerCase(Locale.ROOT));
            if (column == null) {
                throw new ImportFailureException(1, formatName + " header '" + normalized
                        + "' does not match any column of table " + tableName);
            }
            targetColumns.add(column);
        }
        return targetColumns;
    }
}
