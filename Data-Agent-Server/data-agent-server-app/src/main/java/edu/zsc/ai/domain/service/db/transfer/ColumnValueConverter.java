package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Converts one imported text value to the JDBC type of its target column. Used by
 * every format importer (CSV / JSON / SQL files only run statements / Excel).
 */
@Component
public class ColumnValueConverter {

    public void setImportParameter(PreparedStatement statement, int parameterIndex,
                                   ColumnMetadata column, String raw, int fileRow)
            throws SQLException {
        // Empty / missing CSV cells become SQL NULL.
        if (raw == null || raw.isEmpty()) {
            statement.setNull(parameterIndex, column.dataType());
            return;
        }

        try {
            switch (column.dataType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER ->
                        statement.setInt(parameterIndex, parseIntValue(raw.trim()));
                case Types.BIGINT -> statement.setLong(parameterIndex, parseLongValue(raw.trim()));
                case Types.REAL, Types.FLOAT ->
                        statement.setFloat(parameterIndex, Float.parseFloat(raw.trim()));
                case Types.DOUBLE -> statement.setDouble(parameterIndex, Double.parseDouble(raw.trim()));
                case Types.NUMERIC, Types.DECIMAL ->
                        statement.setBigDecimal(parameterIndex, new BigDecimal(raw.trim()));
                case Types.BIT, Types.BOOLEAN ->
                        statement.setBoolean(parameterIndex, parseCsvBoolean(raw));
                case Types.DATE -> statement.setDate(parameterIndex,
                        java.sql.Date.valueOf(parseCsvDate(raw)));
                case Types.TIME -> statement.setTime(parameterIndex,
                        java.sql.Time.valueOf(LocalTime.parse(raw.trim())));
                case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                        statement.setTimestamp(parameterIndex, parseCsvTimestamp(raw));
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY ->
                        statement.setBytes(parameterIndex, raw.getBytes(StandardCharsets.UTF_8));
                case Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR ->
                        statement.setNString(parameterIndex, raw);
                default -> statement.setString(parameterIndex, raw);
            }
        } catch (RuntimeException parseError) {
            throw new ImportFailureException(fileRow, "Column '" + column.name()
                    + "' cannot accept value \"" + raw + "\": " + parseError.getMessage());
        }
    }

    /** Parses an integer column value; tolerates "45.0"-style text written by Excel generators. */
    private int parseIntValue(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(raw).intValueExact();
            } catch (ArithmeticException alsoIgnored) {
                throw new NumberFormatException("not an integer value");
            }
        }
    }

    /** Parses a bigint column value; tolerates "1.0"-style text written by Excel generators. */
    private long parseLongValue(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            try {
                return new BigDecimal(raw).longValueExact();
            } catch (ArithmeticException alsoIgnored) {
                throw new NumberFormatException("not an integer value");
            }
        }
    }

    private boolean parseCsvBoolean(String raw) {
        String value = raw.trim().toLowerCase();
        return switch (value) {
            case "1", "true", "yes", "y", "t" -> true;
            case "0", "false", "no", "n", "f" -> false;
            default -> throw new IllegalArgumentException("not a boolean value");
        };
    }

    private LocalDate parseCsvDate(String raw) {
        // Accept a date optionally followed by a time part (e.g. "2025-05-02 00:00:00"
        // written by Excel date cells); only the date component is used.
        String value = raw.trim();
        int spaceIndex = value.indexOf(' ');
        if (spaceIndex > 0) {
            value = value.substring(0, spaceIndex);
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ignored) {
            return java.sql.Date.valueOf(value).toLocalDate();
        }
    }

    private Timestamp parseCsvTimestamp(String raw) {
        // Accept ISO 'T' or a space separator, with optional seconds and fraction:
        // yyyy-mm-dd[ T]hh:mm[:ss[.fff...]]; a missing seconds field is padded with zeros.
        String value = raw.trim().replace('T', ' ');
        int spaceIndex = value.indexOf(' ');
        if (spaceIndex > 0) {
            String timePart = value.substring(spaceIndex + 1);
            long colonCount = timePart.chars().filter(ch -> ch == ':').count();
            if (colonCount == 1) {
                timePart = timePart + ":00";
            } else if (colonCount == 2 && timePart.endsWith(":")) {
                timePart = timePart + "00";
            }
            value = value.substring(0, spaceIndex + 1) + timePart;
        }
        return Timestamp.valueOf(value);
    }
}
