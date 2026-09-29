package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        if (tableColumns.isEmpty()) {
            throw new BusinessException("Cannot resolve columns of table: " + tableName);
        }
        Map<String, ColumnMetadata> columnsByName = new HashMap<>();
        for (ColumnMetadata column : tableColumns) {
            columnsByName.put(column.name().toLowerCase(), column);
        }
        return new ResolvedTableColumns(tableColumns, columnsByName);
    }

    public String resolveIdentifierQuote(Connection connection) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return (quote == null || quote.isBlank()) ? "" : quote;
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

    private String quoteIdentifier(String quote, String identifier) {
        return quote.isEmpty() ? identifier : quote + identifier + quote;
    }
}
