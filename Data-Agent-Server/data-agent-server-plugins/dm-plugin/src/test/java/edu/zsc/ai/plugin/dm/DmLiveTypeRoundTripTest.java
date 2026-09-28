package edu.zsc.ai.plugin.dm;

import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.dm.executor.DmSqlExecutor;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestFixture;
import edu.zsc.ai.plugin.dm.fixture.DmLiveTestRun;
import edu.zsc.ai.plugin.dm.support.DmRowWriteSupport;
import edu.zsc.ai.plugin.dm.value.DmLobPreviewValues;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.db.TableRowValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M1-08 DM8 type round-trip matrix against a real DM8 instance.
 *
 * <p>Every test exercises the full chain: Database -> JDBC read -> Java model
 * (via {@link DmSqlExecutor} + {@code DmValueProcessor}, the real read path) ->
 * JSON serialization simulation -> client data-editor write-back (via
 * {@link DmRowWriteSupport}, the real write path) -> database re-read -> compare.
 *
 * <p>Findings are recorded in docs/certification/dm8/M1-type-matrix.md.
 *
 * <p>Run with:
 * mvn test -pl data-agent-server-plugins/dm-plugin -Ddm.live=true -Ddm.password=... -Dtest=DmLiveTypeRoundTripTest
 */
@EnabledIfSystemProperty(named = "dm.live", matches = "true")
class DmLiveTypeRoundTripTest {

    private static final String SCHEMA = "M1_CERT";
    private static final String NAMESPACE = "M1E_";

    private final DmSqlExecutor executor = new DmSqlExecutor();
    private final DmRowWriteSupport rowWrite = new DmRowWriteSupport();

    private Connection connect() throws Exception {
        ConnectionConfig config = DmLiveTestFixture.baseConfig();
        Connection connection = new Dm8Plugin().connect(config);
        connection.setAutoCommit(true);
        ensureSchema(connection);
        return connection;
    }

    private void ensureSchema(Connection connection) throws Exception {
        try (Statement st = connection.createStatement()) {
            try {
                st.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION SYSDBA");
            } catch (Exception ignored) {
                // schema already exists (created by the M1-00 environment certification)
            }
        }
    }

    // ---------------------------------------------------------------- BIGINT / DECIMAL

    @Test
    void bigintAndDecimalRoundTripLossless() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("NUM");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t
                    + " (ID INT, C_BIG BIGINT, C_NUM38 NUMBER(38,0), C_DEC184 DECIMAL(18,4), C_DEC102 DECIMAL(10,2))");
            run.track("TABLE", t);

            insertNum(conn, t, 1, Long.MAX_VALUE, "12345678901234567890123456789", "12345678901234.5678", "99999999.99");
            insertNum(conn, t, 2, Long.MIN_VALUE, "-99999999999999999999999999999999999999", "-0.0001", "-99999999.99");

            // JDBC read -> Java model: exact strings, no precision loss (>2^53 and >2^63 safe)
            SqlCommandResult result = selectAll(conn, t);
            List<Object> row1 = rowOf(result, 0);
            assertModelValue("9223372036854775807", value(result, row1, "C_BIG"));
            assertModelValue("12345678901234567890123456789", value(result, row1, "C_NUM38"));
            assertModelValue("12345678901234.5678", value(result, row1, "C_DEC184"));
            assertModelValue("99999999.99", value(result, row1, "C_DEC102"));
            List<Object> row2 = rowOf(result, 1);
            assertModelValue("-9223372036854775808", value(result, row2, "C_BIG"));
            assertModelValue("-99999999999999999999999999999999999999", value(result, row2, "C_NUM38"));
            assertModelValue("-0.0001", value(result, row2, "C_DEC184"));
            assertModelValue("-99999999.99", value(result, row2, "C_DEC102"));

            // client data editor write-back: echo the model strings into new rows
            for (int src = 0; src < 2; src++) {
                List<Object> row = rowOf(result, src);
                int newId = 10 + src;
                SqlCommandResult write = rowWrite.insertRow(conn, null, SCHEMA, t, List.of(
                        new TableRowValue("ID", newId),
                        new TableRowValue("C_BIG", value(result, row, "C_BIG")),
                        new TableRowValue("C_NUM38", value(result, row, "C_NUM38")),
                        new TableRowValue("C_DEC184", value(result, row, "C_DEC184")),
                        new TableRowValue("C_DEC102", value(result, row, "C_DEC102"))));
                assertTrue(write.isSuccess(), "write-back failed: " + write.getErrorMessage());

                // database re-read: raw values must equal the originals
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT C_BIG, C_NUM38, C_DEC184, C_DEC102 FROM " + SCHEMA + "." + t + " WHERE ID = ?")) {
                    ps.setInt(1, newId);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(Long.parseLong((String) value(result, row, "C_BIG")), rs.getLong(1));
                        assertEquals(new BigDecimal((String) value(result, row, "C_NUM38")), rs.getBigDecimal(2));
                        assertEquals(new BigDecimal((String) value(result, row, "C_DEC184")), rs.getBigDecimal(3));
                        assertEquals(new BigDecimal((String) value(result, row, "C_DEC102")), rs.getBigDecimal(4));
                    }
                }
            }
        }
    }

    private void insertNum(Connection conn, String t, int id, long big, String num38, String dec184, String dec102)
            throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?,?,?,?)")) {
            ps.setInt(1, id);
            ps.setLong(2, big);
            ps.setBigDecimal(3, new BigDecimal(num38));
            ps.setBigDecimal(4, new BigDecimal(dec184));
            ps.setBigDecimal(5, new BigDecimal(dec102));
            ps.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- Unicode / String

    @Test
    void unicodeChineseAndEmojiRoundTripLossless() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("CHR");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_V VARCHAR(200))");
            run.track("TABLE", t);

            List<String> samples = List.of(
                    "中文测试abc",
                    "emoji😀🎉🚀end",
                    "混合mix中en文😀tail",
                    "引号\"双'单\\反斜杠\n换行\t制表");
            for (int i = 0; i < samples.size(); i++) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?)")) {
                    ps.setInt(1, i + 1);
                    ps.setString(2, samples.get(i));
                    ps.executeUpdate();
                }
            }

            SqlCommandResult result = selectAll(conn, t);
            for (int i = 0; i < samples.size(); i++) {
                Object model = value(result, rowOf(result, i), "C_V");
                // JDBC read -> Java model lossless (GB18030 server charset covers full Unicode)
                assertModelValue(samples.get(i), model);

                // client write-back -> re-read
                int newId = 10 + i;
                SqlCommandResult write = rowWrite.insertRow(conn, null, SCHEMA, t, List.of(
                        new TableRowValue("ID", newId), new TableRowValue("C_V", model)));
                assertTrue(write.isSuccess(), "write-back failed: " + write.getErrorMessage());
                assertEquals(samples.get(i), rawString(conn, t, newId));
            }
        }
    }

    @Test
    void emptyStringAndNullStayDistinct() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("EMN");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_V VARCHAR(50))");
            run.track("TABLE", t);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?)")) {
                ps.setInt(1, 1);
                ps.setString(2, "");
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setNull(2, Types.VARCHAR);
                ps.executeUpdate();
            }

            // DM native mode (COMPATIBLE_MODE=0): '' is stored as an empty string,
            // NOT coerced to NULL (server-side IS NULL check is false).
            SqlCommandResult result = selectAll(conn, t);
            Object emptyModel = value(result, rowOf(result, 0), "C_V");
            Object nullModel = value(result, rowOf(result, 1), "C_V");
            assertModelValue("", emptyModel);
            assertNull(nullModel);
            assertFalse(isNullInDb(conn, t, 1), "empty string must not be stored as NULL");
            assertTrue(isNullInDb(conn, t, 2), "NULL must stay NULL");

            // write-back of '' stays ''; write-back of null stays NULL
            SqlCommandResult writeEmpty = rowWrite.insertRow(conn, null, SCHEMA, t, List.of(
                    new TableRowValue("ID", 11), new TableRowValue("C_V", emptyModel)));
            assertTrue(writeEmpty.isSuccess(), "write-back failed: " + writeEmpty.getErrorMessage());
            assertFalse(isNullInDb(conn, t, 11));
            assertEquals("", rawString(conn, t, 11));

            SqlCommandResult writeNull = rowWrite.insertRow(conn, null, SCHEMA, t, List.of(
                    new TableRowValue("ID", 12), new TableRowValue("C_V", nullModel)));
            assertTrue(writeNull.isSuccess(), "write-back failed: " + writeNull.getErrorMessage());
            assertTrue(isNullInDb(conn, t, 12));
        }
    }

    // ---------------------------------------------------------------- date / time

    @Test
    void dateTimeAndTimestampRoundTripLossless() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("DT");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_DATE DATE, C_TIME TIME, "
                    + "C_TS TIMESTAMP, C_TS3 TIMESTAMP(3), C_TS0 TIMESTAMP(0), C_DTC DATETIME)");
            run.track("TABLE", t);

            // NOTE: DM native mode truncates the time part of DATE at write time
            run.execQuietly("INSERT INTO " + SCHEMA + "." + t + " VALUES (1, "
                    + "'2026-09-27 13:14:15', '13:14:15', '2026-09-27 13:14:15.123456', "
                    + "'2026-09-27 13:14:15.789', '2026-09-27 13:14:15', '2026-09-27 13:14:15.654321')");

            SqlCommandResult result = selectAll(conn, t);
            List<Object> row = rowOf(result, 0);
            // space-separated form: the only representation the DM driver accepts back
            assertModelValue("2026-09-27", value(result, row, "C_DATE"));
            assertModelValue("13:14:15", value(result, row, "C_TIME"));
            assertModelValue("2026-09-27 13:14:15.123456", value(result, row, "C_TS"));
            assertModelValue("2026-09-27 13:14:15.789", value(result, row, "C_TS3"));
            assertModelValue("2026-09-27 13:14:15", value(result, row, "C_TS0"));
            assertModelValue("2026-09-27 13:14:15.654321", value(result, row, "C_DTC"));

            // write-back every model value into a new row, then re-read and compare
            List<TableRowValue> values = new ArrayList<>();
            values.add(new TableRowValue("ID", 2));
            for (String col : new String[]{"C_DATE", "C_TIME", "C_TS", "C_TS3", "C_TS0", "C_DTC"}) {
                values.add(new TableRowValue(col, value(result, row, col)));
            }
            SqlCommandResult write = rowWrite.insertRow(conn, null, SCHEMA, t, values);
            assertTrue(write.isSuccess(), "write-back failed: " + write.getErrorMessage());

            SqlCommandResult reread = executor.executeCommand(SqlCommandRequest.ofWithoutTransaction(conn,
                    "SELECT * FROM " + SCHEMA + "." + t + " WHERE ID = 2",
                    "SELECT * FROM " + SCHEMA + "." + t + " WHERE ID = 2", null, SCHEMA));
            assertTrue(reread.isSuccess());
            List<Object> rereadRow = rowOf(reread, 0);
            for (String col : new String[]{"C_DATE", "C_TIME", "C_TS", "C_TS3", "C_TS0", "C_DTC"}) {
                assertEquals(value(result, row, col), value(reread, rereadRow, col),
                        col + " round-trip mismatch");
            }
        }
    }

    @Test
    void timestampWithTimeZonePreservesOriginalOffset() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("TSTZ");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_TSTZ TIMESTAMP WITH TIME ZONE)");
            run.track("TABLE", t);

            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + "." + t + " VALUES (?, ?)")) {
                ps.setInt(1, 1);
                ps.setObject(2, "2026-09-27 13:14:15.123456 +05:30");
                ps.executeUpdate();
                ps.setInt(1, 2);
                ps.setObject(2, "2026-09-27 13:14:15.123456 -08:00");
                ps.executeUpdate();
            }

            SqlCommandResult result = selectAll(conn, t);
            Object plusFiveThirty = value(result, rowOf(result, 0), "C_TSTZ");
            Object minusEight = value(result, rowOf(result, 1), "C_TSTZ");
            // the raw driver string keeps the ORIGINAL offset (an OffsetDateTime
            // mapping would normalize both to the session zone, losing the offset)
            assertModelValue("2026-09-27 13:14:15.123456 +05:30", plusFiveThirty);
            assertModelValue("2026-09-27 13:14:15.123456 -08:00", minusEight);

            // write-back and re-read: offset must survive the full round-trip
            SqlCommandResult write = rowWrite.insertRow(conn, null, SCHEMA, t, List.of(
                    new TableRowValue("ID", 11), new TableRowValue("C_TSTZ", plusFiveThirty)));
            assertTrue(write.isSuccess(), "write-back failed: " + write.getErrorMessage());
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT TO_CHAR(C_TSTZ, 'YYYY-MM-DD HH24:MI:SS.FF6 TZH:TZM') FROM " + SCHEMA + "." + t
                            + " WHERE ID = 11")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("2026-09-27 13:14:15.123456 +05:30", rs.getString(1),
                            "offset lost during write-back");
                }
            }
        }
    }

    // ---------------------------------------------------------------- binary

    @Test
    void binaryRoundTripsViaHexPreview() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("BIN");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_BIN BINARY(8), C_VARBIN VARBINARY(64))");
            run.track("TABLE", t);

            byte[] bin = new byte[]{0x00, 0x01, 0x7F, (byte) 0x80, (byte) 0xFF};
            byte[] varbin = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00};
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?,?)")) {
                ps.setInt(1, 1);
                ps.setBytes(2, bin);
                ps.setBytes(3, varbin);
                ps.executeUpdate();
            }

            SqlCommandResult result = selectAll(conn, t);
            List<Object> row = rowOf(result, 0);
            // BINARY(8) is fixed-length: the server zero-pads to 8 bytes
            Object hexBin = value(result, row, "C_BIN");
            Object hexVarbin = value(result, row, "C_VARBIN");
            assertModelValue("0x00017f80ff000000", hexBin);
            assertModelValue("0xdeadbeef00", hexVarbin);

            // write-back: the DM driver parses "0x..." hex strings back into bytes
            SqlCommandResult write = rowWrite.insertRow(conn, null, SCHEMA, t, List.of(
                    new TableRowValue("ID", 2),
                    new TableRowValue("C_BIN", hexBin),
                    new TableRowValue("C_VARBIN", hexVarbin)));
            assertTrue(write.isSuccess(), "write-back failed: " + write.getErrorMessage());

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT C_BIN, C_VARBIN FROM " + SCHEMA + "." + t + " WHERE ID = 2")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    byte[] padded = Arrays.copyOf(bin, 8); // zero-padded fixed-length semantics
                    assertArrayEquals(padded, rs.getBytes(1));
                    assertArrayEquals(varbin, rs.getBytes(2));
                }
            }
        }
    }

    // ---------------------------------------------------------------- LOB

    @Test
    void smallClobIsEditableAndLossless() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("CLS");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_CLOB CLOB)");
            run.track("TABLE", t);

            String content = "中文CLOB内容😀" + "x".repeat(5000) + "尾";
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?)")) {
                ps.setInt(1, 1);
                ps.setString(2, content);
                ps.executeUpdate();
            }

            SqlCommandResult result = selectAll(conn, t);
            Object model = value(result, rowOf(result, 0), "C_CLOB");
            assertModelValue(content, model);

            SqlCommandResult write = rowWrite.updateRow(conn, null, SCHEMA, t,
                    List.of(new TableRowValue("C_CLOB", model)),
                    List.of(new TableRowValue("ID", 1)), false);
            assertTrue(write.isSuccess(), "write-back failed: " + write.getErrorMessage());
            assertEquals(content, rawClob(conn, t, 1));
        }
    }

    @Test
    void smallBlobPreviewIsCompleteBase64ButNotWritable() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("BLS");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_BLOB BLOB)");
            run.track("TABLE", t);

            byte[] content = new byte[100 * 1024];
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i % 251);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?)")) {
                ps.setInt(1, 1);
                ps.setBytes(2, content);
                ps.executeUpdate();
            }

            SqlCommandResult result = selectAll(conn, t);
            Object model = value(result, rowOf(result, 0), "C_BLOB");
            String dataUri = assertInstanceOf(String.class, model);
            assertTrue(dataUri.startsWith("data:application/octet-stream;base64,"),
                    "unexpected BLOB preview: " + dataUri.substring(0, Math.min(60, dataUri.length())));
            // the preview is COMPLETE: it decodes back to the exact original bytes
            byte[] decoded = Base64.getDecoder().decode(
                    dataUri.substring("data:application/octet-stream;base64,".length()));
            assertArrayEquals(content, decoded);
            // but it is a display encoding: the current write path has no decoder,
            // so BLOB is classified read-only (see M1-type-matrix.md).
        }
    }

    @Test
    void truncatedLargeLobPreviewIsRejectedOnWriteBack() throws Exception {
        try (Connection conn = connect(); DmLiveTestRun run = DmLiveTestRun.start(conn, SCHEMA, NAMESPACE)) {
            run.sweepStaleRunObjects();
            String t = run.name("LBIG");
            run.execQuietly("CREATE TABLE " + SCHEMA + "." + t + " (ID INT, C_BLOB BLOB, C_CLOB CLOB)");
            run.track("TABLE", t);

            byte[] bigBlob = new byte[1200 * 1024];
            Arrays.fill(bigBlob, (byte) 0x5A);
            String bigClob = "达梦数据库大对象测试😀".repeat(110_000); // ~1.1M chars
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SCHEMA + "." + t + " VALUES (?,?,?)")) {
                ps.setInt(1, 1);
                ps.setBytes(2, bigBlob);
                ps.setString(3, bigClob);
                ps.executeUpdate();
            }

            SqlCommandResult result = selectAll(conn, t);
            List<Object> row = rowOf(result, 0);
            Object blobPreview = value(result, row, "C_BLOB");
            Object clobPreview = value(result, row, "C_CLOB");
            // >= 1MB LOBs render as truncated size-metadata placeholders
            assertTrue(DmLobPreviewValues.isTruncatedLobPreview(blobPreview),
                    "expected BLOB placeholder, got: " + abbrev(blobPreview));
            assertTrue(DmLobPreviewValues.isTruncatedLobPreview(clobPreview),
                    "expected CLOB placeholder, got: " + abbrev(clobPreview));

            // P0: the truncated preview must NEVER be accepted as a write-back value
            SqlCommandResult writeBlob = rowWrite.updateRow(conn, null, SCHEMA, t,
                    List.of(new TableRowValue("C_BLOB", blobPreview)),
                    List.of(new TableRowValue("ID", 1)), false);
            assertFalse(writeBlob.isSuccess(), "truncated BLOB preview must not be writable");
            assertEquals(DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE,
                    writeBlob.getMessages().get(0).getCode());

            SqlCommandResult writeClob = rowWrite.updateRow(conn, null, SCHEMA, t,
                    List.of(new TableRowValue("C_CLOB", clobPreview)),
                    List.of(new TableRowValue("ID", 1)), false);
            assertFalse(writeClob.isSuccess(), "truncated CLOB preview must not be writable");
            assertEquals(DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE,
                    writeClob.getMessages().get(0).getCode());

            // the stored LOBs are untouched
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT C_BLOB, C_CLOB FROM " + SCHEMA + "." + t + " WHERE ID = 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertArrayEquals(bigBlob, rs.getBytes(1));
                    java.sql.Clob clob = rs.getClob(2);
                    try {
                        // note: the DM driver reports Clob.length() in code points,
                        // not UTF-16 chars (emoji count once)
                        assertEquals(bigClob, clob.getSubString(1, (int) clob.length()));
                    } finally {
                        clob.free();
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Read via the real executor path and assert every model value is a JSON-safe
     * type (String/Boolean/null) that survives a JSON wire round-trip unchanged.
     */
    private SqlCommandResult selectAll(Connection conn, String table) {
        String sql = "SELECT * FROM " + SCHEMA + "." + table;
        SqlCommandResult result = executor.executeCommand(
                SqlCommandRequest.ofWithoutTransaction(conn, sql, sql, null, SCHEMA));
        assertTrue(result.isSuccess(), () -> "read failed: " + result.getErrorMessage());
        assertNotNull(result.getRows());
        for (List<Object> row : result.getRows()) {
            for (Object v : row) {
                assertJsonSafe(v);
            }
        }
        return result;
    }

    private void assertModelValue(String expected, Object model) {
        assertEquals(expected, model);
        assertJsonSafe(model);
    }

    /**
     * Simulate the API JSON serialization leg: model values must be JSON-safe
     * types (String/Boolean/null), and strings must survive a JSON string-literal
     * encode/decode cycle unchanged (covers Unicode, emoji, quotes, controls).
     */
    private static void assertJsonSafe(Object value) {
        if (value == null || value instanceof Boolean) {
            return;
        }
        String s = assertInstanceOf(String.class, value,
                "model value must be a JSON-safe String/Boolean/null, got " + value.getClass());
        assertEquals(s, jsonDecode(jsonEncode(s)), "JSON round-trip mismatch");
    }

    private static String jsonEncode(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String jsonDecode(String json) {
        assertTrue(json.startsWith("\"") && json.endsWith("\""));
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < json.length() - 1; i++) {
            char c = json.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char esc = json.charAt(++i);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("bad escape: \\" + esc);
            }
        }
        return sb.toString();
    }

    private static List<Object> rowOf(SqlCommandResult result, int index) {
        return result.getRows().get(index);
    }

    private static Object value(SqlCommandResult result, List<Object> row, String column) {
        return result.getValueByColumnName(row, column);
    }

    private boolean isNullInDb(Connection conn, String table, int id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT CASE WHEN C_V IS NULL THEN 1 ELSE 0 END FROM " + SCHEMA + "." + table + " WHERE ID = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1) == 1;
            }
        }
    }

    private String rawString(Connection conn, String table, int id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT C_V FROM " + SCHEMA + "." + table + " WHERE ID = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String rawClob(Connection conn, String table, int id) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT C_CLOB FROM " + SCHEMA + "." + table + " WHERE ID = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                java.sql.Clob clob = rs.getClob(1);
                assertNotNull(clob);
                try {
                    return clob.getSubString(1, (int) clob.length());
                } finally {
                    clob.free();
                }
            }
        }
    }

    private static String abbrev(Object v) {
        String s = String.valueOf(v);
        return s.length() > 60 ? s.substring(0, 60) + "...<len=" + s.length() + ">" : s;
    }
}
