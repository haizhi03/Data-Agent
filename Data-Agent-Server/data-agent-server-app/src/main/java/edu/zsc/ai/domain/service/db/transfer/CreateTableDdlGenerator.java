package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.List;
import java.util.Locale;

/**
 * Builds a {@code CREATE TABLE} DDL statement from a list of inferred
 * {@link ColumnMetadata}. Used to auto-create a missing target table before
 * importing file data.
 *
 * <p>Output example (identifier quote {@code "}):
 * <pre>
 * CREATE TABLE "mySchema"."newTable" (
 *   "id" BIGINT NULL,
 *   "name" VARCHAR(255) NULL,
 *   "price" DECIMAL(18,4) NULL
 * );
 * </pre>
 *
 * <p>Schema is prepended only when non-blank (DM always has one; MySQL typically
 * does not). Column order follows the list as given. No primary key, constraints
 * or indexes are generated — those cannot be safely inferred from a data file.
 */
@Component
public class CreateTableDdlGenerator {

    /**
     * Build the CREATE TABLE DDL.
     *
     * @param identifierQuote  value from {@code connection.getMetaData().getIdentifierQuoteString()}
     * @param db               target database context
     * @param tableName        unquoted table name
     * @param columns          inferred columns in desired order
     */
    public String buildCreateTable(String identifierQuote, DbContext db,
                                    String tableName, List<ColumnMetadata> columns) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ");
        boolean hasSchema = db.schema() != null && !db.schema().isBlank();
        if (hasSchema) {
            sql.append(ImportPrepareSupport.quoteIdentifier(identifierQuote, db.schema()))
                    .append('.');
        }
        sql.append(ImportPrepareSupport.quoteIdentifier(identifierQuote, tableName));
        sql.append(" (\n");

        for (int i = 0; i < columns.size(); i++) {
            ColumnMetadata col = columns.get(i);
            sql.append("  ").append(ImportPrepareSupport.quoteIdentifier(identifierQuote, col.name()))
                    .append(' ').append(columnTypeClause(col))
                    .append(col.nullable() ? " NULL" : " NOT NULL");
            if (i < columns.size() - 1) {
                sql.append(',');
            }
            sql.append('\n');
        }
        sql.append(");");
        return sql.toString();
    }

    /**
     * Returns the column type clause (without NULL/NOT NULL). E.g. "BIGINT",
     * "VARCHAR(255)", "DECIMAL(18,4)". Falls back to {@code col.typeName} when
     * the JDBC type is unrecognised.
     */
    private String columnTypeClause(ColumnMetadata col) {
        String rawName = col.typeName();
        if (rawName == null || rawName.isBlank()) {
            return "VARCHAR(255)";
        }
        String upper = rawName.toUpperCase(Locale.ROOT);
        return switch (col.dataType()) {
            case Types.VARCHAR, Types.CHAR, Types.LONGVARCHAR, Types.NVARCHAR, Types.NCHAR, Types.LONGNVARCHAR -> upper + '(' + col.columnSize() + ')';
            case Types.DECIMAL, Types.NUMERIC -> upper + '(' + col.columnSize() + ',' + col.decimalDigits() + ')';
            // INT family, DATE/TIME, BIT, BLOB — no length modifier needed
            default -> upper;
        };
    }
}
