package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.JdbcValueContext;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline format tests for the DM date/time value processors. The rendered
 * strings must be verbatim re-writable through the DM driver
 * ({@code setObject(String)}), which the live matrix test verifies end-to-end.
 */
class DmDateTimeValueFormatTest {

    private final DmDateTimeProcessor dateProcessor = new DmDateTimeProcessor();
    private final DmTimestampProcessor timestampProcessor = new DmTimestampProcessor();
    private final DmTimestampWithTimeZoneProcessor tzProcessor = new DmTimestampWithTimeZoneProcessor();

    @Test
    void dateIsRenderedAsIsoDateOnly() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getDate(1)).thenReturn(java.sql.Date.valueOf("2026-09-27"));

        assertEquals("2026-09-27", dateProcessor.convertJdbcValueByType(context(rs)));
    }

    @Test
    void dateFallsBackToStringWhenGetDateFails() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getDate(1)).thenThrow(new java.sql.SQLException("driver quirk"));
        when(rs.getString(1)).thenReturn("2026-09-27");

        assertEquals("2026-09-27", dateProcessor.convertJdbcValueByType(context(rs)));
    }

    @Test
    void timestampUsesSpaceSeparatorAndKeepsMicros() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1)).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 27, 13, 14, 15, 123456000)));

        assertEquals("2026-09-27 13:14:15.123456", timestampProcessor.convertJdbcValueByType(context(rs)));
    }

    @Test
    void timestampWithoutFractionOmitsFractionalPart() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1)).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 27, 13, 14, 15)));

        assertEquals("2026-09-27 13:14:15", timestampProcessor.convertJdbcValueByType(context(rs)));
    }

    @Test
    void timestampNullStaysNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1)).thenReturn(null);

        assertNull(timestampProcessor.convertJdbcValueByType(context(rs)));
    }

    @Test
    void timestampWithTimeZoneKeepsRawDriverStringWithOriginalOffset() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        // The OffsetDateTime mapping would normalize +05:30 to the session zone;
        // the raw string keeps the stored offset and is re-writable verbatim.
        when(rs.getString(1)).thenReturn("2026-09-27 13:14:15.123456 +05:30");

        assertEquals("2026-09-27 13:14:15.123456 +05:30", tzProcessor.convertJdbcValueByType(context(rs)));
    }

    @Test
    void timestampWithTimeZoneNullStaysNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);

        assertNull(tzProcessor.convertJdbcValueByType(context(rs)));
    }

    private JdbcValueContext context(ResultSet rs) {
        return JdbcValueContext.builder().resultSet(rs).columnIndex(1).build();
    }
}
