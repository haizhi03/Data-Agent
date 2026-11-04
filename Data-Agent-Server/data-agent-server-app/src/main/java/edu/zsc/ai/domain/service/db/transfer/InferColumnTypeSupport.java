package edu.zsc.ai.domain.service.db.transfer;

import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Infers a {@link ColumnMetadata} for each file header by sampling the first N
 * data rows. Used when the target table does not yet exist and must be created
 * before data is inserted.
 *
 * <p>Inference rules (first-match-wins per column):
 * <ol>
 *   <li>All non-null samples are integers → {@code BIGINT}</li>
 *   <li>All non-null samples are numeric (decimal or scientific) → {@code DECIMAL(18,4)}</li>
 *   <li>All non-null samples are boolean literals → {@code BIT}</li>
 *   <li>All non-null samples match {@code yyyy-MM-dd} → {@code DATE}</li>
 *   <li>All non-null samples match {@code yyyy-MM-dd HH:mm:ss[.SSS]} → {@code TIMESTAMP}</li>
 *   <li>Otherwise → {@code VARCHAR(n)} where n = max(length)*2 clamped to [32, 4096]</li>
 * </ol>
 *
 * <p>Columns whose every sample is null fall through to VARCHAR(255).
 */
@Component
public class InferColumnTypeSupport {

    private static final Logger log = LoggerFactory.getLogger(InferColumnTypeSupport.class);

    /** Max rows scanned when inferring column types from file data. */
    public static final int SAMPLE_ROW_LIMIT = 50;

    /** Lower/upper clamp for VARCHAR column size. */
    private static final int VARCHAR_MIN = 32;
    private static final int VARCHAR_MAX = 4096;
    private static final int VARCHAR_FALLBACK = 255;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATETIME_MS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * Infers column metadata from the file header names and sample rows.
     *
     * @param headerNames  file header names (e.g. CSV first row, JSON first object keys)
     * @param sampleRows   up to {@link #SAMPLE_ROW_LIMIT} data rows in file order;
     *                     each row has one String per column, {@code null} for empty cells
     * @return one {@link ColumnMetadata} per header name, ordered by header position
     */
    public List<ColumnMetadata> inferColumnTypes(List<String> headerNames,
                                                  List<String[]> sampleRows) {
        List<ColumnMetadata> result = new ArrayList<>(headerNames.size());
        int numCols = headerNames.size();
        for (int colIdx = 0; colIdx < numCols; colIdx++) {
            String name = headerNames.get(colIdx) == null
                    ? "col" + (colIdx + 1)
                    : headerNames.get(colIdx).trim();
            if (name.isEmpty()) {
                name = "col" + (colIdx + 1);
            }
            List<String> nonNullSamples = new ArrayList<>();
            int maxLen = 0;
            for (String[] row : sampleRows) {
                if (colIdx < row.length) {
                    String v = row[colIdx];
                    if (v != null) {
                        String trimmed = v.trim();
                        if (!trimmed.isEmpty()) {
                            nonNullSamples.add(trimmed);
                            if (trimmed.length() > maxLen) {
                                maxLen = trimmed.length();
                            }
                        }
                    }
                }
            }
            ColumnMetadata inferred = inferOneColumn(name, colIdx, nonNullSamples, maxLen);
            result.add(inferred);
        }
        return result;
    }

    private ColumnMetadata inferOneColumn(String name, int ordinal,
                                           List<String> nonNullSamples, int maxLen) {
        if (nonNullSamples.isEmpty()) {
            return varcharColumn(name, ordinal, VARCHAR_FALLBACK);
        }

        // 1) Integer check
        if (allMatch(nonNullSamples, InferColumnTypeSupport::isInteger)) {
            return numericColumn(name, ordinal, Types.BIGINT, "BIGINT", 19, 0);
        }

        // 2) Decimal / scientific
        if (allMatch(nonNullSamples, InferColumnTypeSupport::isDecimal)) {
            return numericColumn(name, ordinal, Types.DECIMAL, "DECIMAL", 18, 4);
        }

        // 3) Boolean
        if (allMatch(nonNullSamples, InferColumnTypeSupport::isBooleanLiteral)) {
            return numericColumn(name, ordinal, Types.BIT, "BIT", 1, 0);
        }

        // 4) Date yyyy-MM-dd
        if (allMatch(nonNullSamples, InferColumnTypeSupport::isDate)) {
            return new ColumnMetadata(name, Types.DATE, "DATE", 10, 0,
                    true, ordinal, null);
        }

        // 5) Datetime yyyy-MM-dd HH:mm:ss[.SSS]
        if (allMatch(nonNullSamples, InferColumnTypeSupport::isDateTime)) {
            return new ColumnMetadata(name, Types.TIMESTAMP, "DATETIME", 19, 3,
                    true, ordinal, null);
        }

        // 6) VARCHAR fallback
        int size = Math.max(VARCHAR_MIN, Math.min(VARCHAR_MAX, maxLen * 2));
        return varcharColumn(name, ordinal, size);
    }

    private ColumnMetadata varcharColumn(String name, int ordinal, int size) {
        return new ColumnMetadata(name, Types.VARCHAR, "VARCHAR", size, 0,
                true, ordinal, null);
    }

    private ColumnMetadata numericColumn(String name, int ordinal, int jdbcType,
                                          String typeName, int size, int decimals) {
        return new ColumnMetadata(name, jdbcType, typeName, size, decimals,
                true, ordinal, null);
    }

    // --- Matchers ---

    private static boolean allMatch(List<String> samples, java.util.function.Predicate<String> pred) {
        for (String s : samples) {
            if (!pred.test(s)) return false;
        }
        return true;
    }

    static boolean isInteger(String s) {
        // Plain integer only, and only if it fits in a signed 64-bit long: reject
        // decimals (3.14), scientific notation (1e3) and digit strings too large
        // for BIGINT (IDs, phone numbers, ...) so those fall through to VARCHAR.
        if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
            return false;
        }
        try {
            Long.parseLong(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean isDecimal(String s) {
        try {
            BigDecimal d = new BigDecimal(s);
            // Must fit DECIMAL(18,4): at most 14 integer digits and 4 fractional
            // digits, so over-precise values fall through to VARCHAR instead of
            // overflowing the column on insert.
            return d.scale() <= 4 && d.precision() - d.scale() <= 14;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean isBooleanLiteral(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.equals("true") || lower.equals("false")
                || lower.equals("1") || lower.equals("0")
                || lower.equals("yes") || lower.equals("no")
                || lower.equals("y") || lower.equals("n");
    }

    static boolean isDate(String s) {
        try {
            LocalDate.parse(s, DATE_FMT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    static boolean isDateTime(String s) {
        try {
            LocalDateTime.parse(s, DATETIME_FMT);
            return true;
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDateTime.parse(s, DATETIME_MS_FMT);
            return true;
        } catch (DateTimeParseException ignored) {
        }
        // also accept ISO-like "yyyy-MM-ddTHH:mm:ss[.SSS]"
        try {
            LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
