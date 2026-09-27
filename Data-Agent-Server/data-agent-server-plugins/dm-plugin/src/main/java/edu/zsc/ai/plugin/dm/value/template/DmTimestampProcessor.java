package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for DM TIMESTAMP / DATETIME types.
 *
 * <p>Renders as {@code yyyy-MM-dd HH:mm:ss[.fraction]} (space-separated, fractional
 * seconds preserved via {@link java.time.LocalDateTime#toString()}). The DM driver
 * rejects the ISO-8601 {@code 'T'} separator on write-back
 * ({@code setObject(String)} fails with errorCode 6007 "类型转换异常", live-verified),
 * while the space-separated form round-trips losslessly through both read and write.
 *
 * @author hhz
 */
public class DmTimestampProcessor extends DefaultValueProcessor {

    /**
     * Render a LocalDateTime in the DM-writable space-separated form.
     */
    static String toDmDateTimeString(java.time.LocalDateTime dateTime) {
        return dateTime.toString().replace('T', ' ');
    }

    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();
        java.sql.Timestamp timestamp = resultSet.getTimestamp(columnIndex);
        return timestamp != null ? toDmDateTimeString(timestamp.toLocalDateTime()) : null;
    }
}
