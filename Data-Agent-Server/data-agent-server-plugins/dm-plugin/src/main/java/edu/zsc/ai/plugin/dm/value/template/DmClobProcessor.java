package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Processor for DM CLOB/TEXT/LONGVARCHAR types.
 *
 * <p>Reads via the Clob API so the driver streams the content instead of
 * relying on {@code getString} behaviour for large objects:
 * <ul>
 *   <li>Small CLOBs (&lt; 1MB chars): full content as String</li>
 *   <li>Large CLOBs (&ge; 1MB chars): metadata with size information</li>
 * </ul>
 *
 * <p>The metadata placeholder is size information only, never content;
 * {@code DmRowWriteSupport} refuses to write it back
 * ({@code DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE}).
 * Falls back to {@code getString} if the Clob API fails.
 *
 * @author hhz
 */
public class DmClobProcessor extends DefaultValueProcessor {
    // Size threshold: 1M characters
    private static final long SIZE_THRESHOLD_CHARS = 1024 * 1024;

    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();

        try {
            Clob clob = resultSet.getClob(columnIndex);
            if (clob == null) {
                return null;
            }
            try {
                long clobLength = clob.length();
                if (clobLength >= SIZE_THRESHOLD_CHARS) {
                    return formatClobMetadata(clobLength);
                }
                return clob.getSubString(1, (int) clobLength);
            } finally {
                clob.free();
            }
        } catch (SQLException e) {
            // Fallback: some DM type mappings may not expose a Clob locator
            return resultSet.getString(columnIndex);
        }
    }

    /**
     * Format CLOB metadata for large CLOBs.
     * Format: [CLOB: {size}MB] or [CLOB: {size}KB]
     */
    private String formatClobMetadata(long sizeInChars) {
        if (sizeInChars >= SIZE_THRESHOLD_CHARS) {
            double sizeInMB = sizeInChars / (1024.0 * 1024.0);
            return String.format(Locale.ROOT, "[CLOB: %.2fMB]", sizeInMB);
        } else {
            double sizeInKB = sizeInChars / 1024.0;
            return String.format(Locale.ROOT, "[CLOB: %.2fKB]", sizeInKB);
        }
    }
}
