package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.domain.service.db.transfer.TransferConstants;
import edu.zsc.ai.plugin.capability.TableManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlColumnInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Types;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports all rows of a table as INSERT statements, page by page.
 */
@Component
@RequiredArgsConstructor
public class SqlTableDataExporter {

    private static final Logger log = LoggerFactory.getLogger(SqlTableDataExporter.class);

    private final ConnectionService connectionService;

    public void export(DbContext db, String tableName, OutputStream outputStream,
                       Runnable onBeforeFirstWrite) {
        connectionService.openConnection(db);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableManager provider = DefaultPluginManager.getInstance().getTableManagerByPluginId(active.pluginId());

        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection();
             OutputStreamWriter streamWriter = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(streamWriter)) {
            Connection connection = borrowed.connection();
            int offset = 0;

            // Query the first page before writing anything, so connection/query errors
            // surface before the HTTP response is committed.
            SqlCommandResult first = provider.getTableData(connection, db.catalog(), db.schema(),
                    tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);

            // First page query succeeded: it is now safe to commit file-download headers.
            onBeforeFirstWrite.run();

            List<String> headers = first.getHeaders();
            Map<String, Integer> jdbcTypeByName = resolveJdbcTypes(first, headers);

            writer.write("-- DataAgent data export");
            writer.write("\r\n");
            writer.write("-- Table: " + tableName);
            writer.write("\r\n");
            writer.write("-- Exported at: " + new Date());
            writer.write("\r\n");
            writer.write("SET NAMES utf8mb4;");
            writer.write("\r\n\r\n");

            String columnList = String.join(", ", headers);
            List<List<Object>> firstRows = first.getRows();
            if (firstRows != null && !firstRows.isEmpty()) {
                writeInsertStatements(writer, tableName, columnList, headers, jdbcTypeByName, firstRows);
                log.info("SQL export started: connectionId={}, tableName={}, firstPageRows={}",
                        db.connectionId(), tableName, firstRows.size());

                offset += TransferConstants.EXPORT_PAGE_SIZE;
                while (firstRows.size() == TransferConstants.EXPORT_PAGE_SIZE) {
                    SqlCommandResult page = provider.getTableData(connection, db.catalog(), db.schema(),
                            tableName, offset, TransferConstants.EXPORT_PAGE_SIZE);
                    List<List<Object>> rows = page.getRows();
                    if (rows == null || rows.isEmpty()) {
                        break;
                    }
                    writeInsertStatements(writer, tableName, columnList, headers, jdbcTypeByName, rows);
                    writer.flush();
                    if (rows.size() < TransferConstants.EXPORT_PAGE_SIZE) {
                        break;
                    }
                    offset += TransferConstants.EXPORT_PAGE_SIZE;
                }
            }

            writer.flush();
            log.info("SQL export completed: connectionId={}, tableName={}, lastOffset={}",
                    db.connectionId(), tableName, offset);
        } catch (IOException ex) {
            throw new RuntimeException("Export SQL failed: " + ex.getMessage(), ex);
        }
    }

    private Map<String, Integer> resolveJdbcTypes(SqlCommandResult result, List<String> headers) {
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

    private void writeInsertStatements(BufferedWriter writer, String tableName, String columnList,
                                       List<String> headers, Map<String, Integer> jdbcTypeByName,
                                       List<List<Object>> rows) throws IOException {
        for (List<Object> row : rows) {
            writer.write("INSERT INTO " + tableName + " (" + columnList + ") VALUES (");
            for (int i = 0; i < headers.size(); i++) {
                if (i > 0) {
                    writer.write(", ");
                }
                Object value = i < row.size() ? row.get(i) : null;
                Integer jdbcType = jdbcTypeByName.get(headers.get(i));
                writer.write(toSqlLiteral(value, jdbcType));
            }
            writer.write(");\r\n");
        }
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
}
