package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Processor for the DM DATE type.
 *
 * <p>Under the certified DM8 configuration (COMPATIBLE_MODE=0, native mode) a
 * DATE column is date-only: the server truncates any time component at write
 * time (live-verified, see docs/certification/dm8/M1-type-matrix.md). The value
 * is therefore rendered as an ISO date string {@code yyyy-MM-dd}, which the DM
 * driver also accepts verbatim on write-back via {@code setObject(String)} —
 * a full-fidelity round-trip.
 *
 * @author hhz
 */
public class DmDateTimeProcessor extends DefaultValueProcessor {
    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();

        try {
            java.sql.Date date = resultSet.getDate(columnIndex);
            if (date != null) {
                return date.toLocalDate().toString();
            }
        } catch (SQLException e) {
            String stringValue = resultSet.getString(columnIndex);
            if (stringValue != null && !stringValue.isEmpty()) {
                return stringValue;
            }
        }
        return null;
    }
}
