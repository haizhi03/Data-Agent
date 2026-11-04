package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.plugin.model.command.sql.SqlColumnInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exports all rows of a table as INSERT statements, page by page.
 */
@Component
public class SqlTableDataExporter extends AbstractTableDataExporter<SqlTableDataExporter.SqlSession> {

    public SqlTableDataExporter(ConnectionService connectionService) {
        super(connectionService);
    }

    @Override
    public String fileType() {
        return "SQL";
    }

    @Override
    public String extension(String fileType) {
        return ".sql";
    }

    @Override
    public String contentType(String fileType) {
        return "application/sql; charset=UTF-8";
    }

    @Override
    protected SqlSession onStart(OutputStream outputStream, DbContext db, String tableName, String fileType,
                                 List<String> headers, List<Integer> columnIndexes,
                                 SqlCommandResult firstPage, String identifierQuote) throws IOException {
        OutputStreamWriter streamWriter = new OutputStreamWriter(nonClosing(outputStream), StandardCharsets.UTF_8);
        BufferedWriter writer = new BufferedWriter(streamWriter);

        List<String> selectedHeaders = columnIndexes.stream().map(headers::get).toList();
        Map<String, Integer> jdbcTypeByName = resolveJdbcTypes(firstPage);

        writer.write("-- DataAgent data export");
        writer.write("\r\n");
        writer.write("-- Table: " + tableName);
        writer.write("\r\n");
        writer.write("-- Exported at: " + new Date());
        writer.write("\r\n");
        writer.write("SET NAMES utf8mb4;");
        writer.write("\r\n\r\n");

        // Quote table and column identifiers so reserved words (order, group, ...)
        // round-trip through export -> import.
        String quotedTable = ImportPrepareSupport.quoteIdentifier(identifierQuote, tableName);
        String columnList = selectedHeaders.stream()
                .map(header -> ImportPrepareSupport.quoteIdentifier(identifierQuote, header))
                .collect(Collectors.joining(", "));
        return new SqlSession(writer, quotedTable, columnList, selectedHeaders, columnIndexes, jdbcTypeByName);
    }

    @Override
    protected void writeRows(SqlSession session, List<List<Object>> rows) throws IOException {
        BufferedWriter writer = session.writer();
        for (List<Object> row : rows) {
            writer.write("INSERT INTO " + session.quotedTable() + " (" + session.columnList() + ") VALUES (");
            List<Integer> columnIndexes = session.columnIndexes();
            for (int i = 0; i < columnIndexes.size(); i++) {
                if (i > 0) {
                    writer.write(", ");
                }
                int index = columnIndexes.get(i);
                Object value = index < row.size() ? row.get(index) : null;
                Integer jdbcType = session.jdbcTypeByName().get(session.selectedHeaders().get(i));
                writer.write(toSqlLiteral(value, jdbcType));
            }
            writer.write(");\r\n");
        }
    }

    @Override
    protected void flushPage(SqlSession session) throws IOException {
        session.writer().flush();
    }

    @Override
    protected void onFinish(SqlSession session) throws IOException {
        session.writer().flush();
    }

    @Override
    protected void cleanup(SqlSession session) {
        // Do NOT close the writer — it wraps response.getOutputStream() and
        // closing it would prematurely terminate the HTTP download stream.
        // Spring owns the response stream lifecycle; we only flush in onFinish.
    }

    private Map<String, Integer> resolveJdbcTypes(SqlCommandResult result) {
        Map<String, Integer> map = new HashMap<>();
        if (result.getColumns() != null) {
            for (SqlColumnInfo column : result.getColumns()) {
                if (column.getName() != null && column.getJdbcType() != null) {
                    map.put(column.getName(), column.getJdbcType());
                }
            }
        }
        return map;
    }

    private String toSqlLiteral(Object value, Integer jdbcType) {
        if (value == null) {
            return "NULL";
        }

        // Numeric types: emit the raw representation.
        if (jdbcType != null && isNumericJdbcType(jdbcType)) {
            return value.toString();
        }
        if (jdbcType == null && value instanceof Number) {
            return value.toString();
        }

        // Boolean: 1/0 works across MySQL and DM.
        if (value instanceof Boolean bool) {
            return bool ? "1" : "0";
        }

        // Binary data: standard SQL hex literal, also accepted by MySQL.
        if (value instanceof byte[] bytes) {
            StringBuilder hex = new StringBuilder("X'");
            for (byte b : bytes) {
                hex.append(String.format("%02X", b));
            }
            return hex.append("'").toString();
        }

        // Temporal and all other types are emitted as quoted strings.
        return "'" + escapeSqlString(value.toString()) + "'";
    }

    private boolean isNumericJdbcType(int jdbcType) {
        return jdbcType == Types.BIT
                || jdbcType == Types.TINYINT
                || jdbcType == Types.SMALLINT
                || jdbcType == Types.INTEGER
                || jdbcType == Types.BIGINT
                || jdbcType == Types.REAL
                || jdbcType == Types.FLOAT
                || jdbcType == Types.DOUBLE
                || jdbcType == Types.NUMERIC
                || jdbcType == Types.DECIMAL;
    }

    private String escapeSqlString(String value) {
        return value.replace("\\", "\\\\")
                .replace("'", "''")
                .replace("\0", "");
    }

    /**
     * Session that carries the writer and resolved statement context across template-method calls.
     */
    record SqlSession(BufferedWriter writer, String quotedTable, String columnList,
                      List<String> selectedHeaders, List<Integer> columnIndexes,
                      Map<String, Integer> jdbcTypeByName) {
    }
}
