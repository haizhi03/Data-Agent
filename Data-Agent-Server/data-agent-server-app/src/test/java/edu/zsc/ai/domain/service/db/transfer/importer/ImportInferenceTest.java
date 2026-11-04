package edu.zsc.ai.domain.service.db.transfer.importer;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.transfer.*;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import edu.zsc.ai.plugin.mysql.value.MySQLDataTypeEnum;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportInferenceTest {
    private final InferColumnTypeSupport inference = new InferColumnTypeSupport();
    private final CreateTableDdlGenerator ddl = new CreateTableDdlGenerator();
    private final ImportPrepareSupport prepare = new ImportPrepareSupport();

    private PushbackInputStream stream(byte[] bytes) {
        return new PushbackInputStream(new ByteArrayInputStream(bytes), 65536);
    }

    @Test
    void jsonSamplingAlignsReorderedAndMissingFieldsByName() throws Exception {
        var importer = new JsonTableDataImporter(null, prepare, null, null, inference, ddl);
        String longName = "B".repeat(100);
        byte[] bytes = ("[{\"id\":1,\"name\":\"A\"},{\"NAME\":\"" + longName
                + "\",\"id\":2},{\"name\":\"C\"}]").getBytes(StandardCharsets.UTF_8);
        var sample = importer.peekHeadersAndSample(stream(bytes), "UTF-8");
        assertArrayEquals(new String[]{"2", longName}, sample.sampleRows().get(1));
        assertArrayEquals(new String[]{null, "C"}, sample.sampleRows().get(2));
        var columns = inference.inferColumnTypes(sample.headerNames(), sample.sampleRows());
        assertEquals("BIGINT", columns.get(0).typeName());
        assertTrue(columns.get(1).columnSize() >= longName.length());
        var byName = new HashMap<String, ColumnMetadata>();
        columns.forEach(column -> byName.put(column.name().toLowerCase(Locale.ROOT), column));
        try (var source = importer.prepare(new ByteArrayInputStream(sample.content()), "UTF-8",
                new ImportPrepareSupport.ResolvedTableColumns(columns, byName), "users")) {
            assertTrue(source.hasNextRow());
            assertArrayEquals(new String[]{"1", "A"}, source.nextRow().values());
            assertTrue(source.hasNextRow());
            assertArrayEquals(new String[]{"2", longName}, source.nextRow().values());
        }
    }

    @Test
    void booleanDdlRoundTripsThroughMysqlMetadataAndValueConversion() throws Exception {
        var columns = inference.inferColumnTypes(List.of("enabled"),
                List.of(new String[]{"true"}, new String[]{"false"}, new String[]{"yes"}));
        String sql = ddl.buildCreateTable("`", new DbContext(1L, "test", null), "flags", columns);
        assertTrue(sql.contains("`enabled` BIT NULL"), sql);
        var actual = new ColumnMetadata("enabled", MySQLDataTypeEnum.toSqlType(columns.get(0).typeName()),
                "BIT", 1, 0, true, 1, null);
        var statement = mock(PreparedStatement.class);
        var converter = new ColumnValueConverter();
        converter.setImportParameter(statement, 1, actual, "true", 1);
        converter.setImportParameter(statement, 2, actual, "false", 2);
        converter.setImportParameter(statement, 3, actual, "yes", 3);
        verify(statement).setBoolean(1, true);
        verify(statement).setBoolean(2, false);
        verify(statement).setBoolean(3, true);
    }

    @Test
    void csvSamplesWholeWideRecordsAndReplaysReadAheadWithoutBufferingTheWholeFile() throws Exception {
        var importer = new CsvTableDataImporter(null, prepare, null, null, inference, ddl);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < 100; i++) headers.add("col" + i);
        String wideRow = String.join(",", Collections.nCopies(100, "\"" + "x".repeat(700) + "\""));
        String sqlText = "\uFEFF" + String.join(",", headers) + "\r\n" + (wideRow + "\r\n").repeat(80);
        byte[] bytes = sqlText.getBytes(StandardCharsets.UTF_8);
        var original = stream(bytes);
        var sample = importer.peekHeadersAndSample(original, "AUTO");
        assertEquals(headers, sample.headerNames());
        assertEquals(50, sample.sampleRows().size());
        assertEquals("x".repeat(700), sample.sampleRows().get(0)[99]);
        assertTrue(sample.content().length < bytes.length, "CSV must keep streaming after the sample");
        try (var replay = new SequenceInputStream(new ByteArrayInputStream(sample.content()), original)) {
            assertArrayEquals(bytes, replay.readAllBytes());
        }
    }

    @Test
    void csvReplayCanBeParsedAgainIncludingItsUnsampledTail() throws Exception {
        var importer = new CsvTableDataImporter(null, prepare, null, null, inference, ddl);
        String text = "id,name\n";
        StringBuilder file = new StringBuilder(text);
        for (int i = 1; i <= 2000; i++) file.append(i).append(",name ").append(i).append('\n');
        var original = stream(file.toString().getBytes(StandardCharsets.UTF_8));
        var sample = importer.peekHeadersAndSample(original, "UTF-8");
        var columns = inference.inferColumnTypes(sample.headerNames(), sample.sampleRows());
        var byName = new HashMap<String, ColumnMetadata>();
        columns.forEach(column -> byName.put(column.name(), column));
        var replay = new SequenceInputStream(new ByteArrayInputStream(sample.content()), original);
        try (var source = importer.prepare(replay, "UTF-8",
                new ImportPrepareSupport.ResolvedTableColumns(columns, byName), "users")) {
            int count = 0;
            while (source.hasNextRow()) {
                var row = source.nextRow();
                count++;
                assertEquals(Integer.toString(count), row.valueAt(0));
                assertEquals("name " + count, row.valueAt(1));
            }
            assertEquals(2000, count);
        }
    }
}
