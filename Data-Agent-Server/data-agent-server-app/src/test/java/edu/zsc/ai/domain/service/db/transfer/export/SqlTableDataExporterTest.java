package edu.zsc.ai.domain.service.db.transfer.export;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.model.command.sql.SqlColumnInfo;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTableDataExporterTest {

    @Test
    void mysqlExportWritesCharsetAndMysqlStringEscaping() throws Exception {
        String sql = export("mysql");

        assertTrue(sql.contains("SET NAMES utf8mb4;"));
        assertTrue(sql.contains("VALUES ('C:\\\\temp\\\\owner''s');"), sql);
    }

    @Test
    void dmExportOmitsMysqlCharsetAndPreservesBackslashes() throws Exception {
        String sql = export("dm");

        assertFalse(sql.contains("SET NAMES"));
        assertTrue(sql.contains("VALUES ('C:\\temp\\owner''s');"), sql);
    }

    private String export(String dbType) throws Exception {
        SqlTableDataExporter exporter = new SqlTableDataExporter(null);
        SqlCommandResult firstPage = new SqlCommandResult();
        SqlColumnInfo column = new SqlColumnInfo();
        column.setName("value");
        column.setJdbcType(Types.VARCHAR);
        firstPage.setColumns(List.of(column));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SqlTableDataExporter.SqlSession session = exporter.onStart(
                output,
                new DbContext(1L, null, "HR"),
                "messages",
                "SQL",
                List.of("value"),
                List.of(0),
                firstPage,
                "\"",
                dbType
        );
        exporter.writeRows(session, List.of(List.of("C:\\temp\\owner's")));
        exporter.onFinish(session);
        return output.toString(StandardCharsets.UTF_8);
    }
}
