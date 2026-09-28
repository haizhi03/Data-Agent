package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for DM TIMESTAMP WITH TIME ZONE / TIMESTAMP WITH LOCAL TIME ZONE.
 *
 * <p>Renders the driver's raw string representation
 * ({@code yyyy-MM-dd HH:mm:ss.ffffff +hh:mm}), which is the only form that keeps
 * the <b>original zone offset</b> and is accepted verbatim by the DM driver on
 * write-back via {@code setObject(String)} (live-verified).
 *
 * <p>The JDBC 4.2 {@code getObject(..., OffsetDateTime.class)} mapping is
 * deliberately NOT used: the DM driver normalizes the value to the session time
 * zone (e.g. {@code +05:30} is returned as {@code +08:00}), silently discarding
 * the stored offset. Writing such a normalized value back would change the row.
 *
 * @author hhz
 */
public class DmTimestampWithTimeZoneProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();

        // The raw driver string preserves the original offset and is re-writable
        // as-is; it is the lossless representation for this type.
        return resultSet.getString(columnIndex);
    }
}
