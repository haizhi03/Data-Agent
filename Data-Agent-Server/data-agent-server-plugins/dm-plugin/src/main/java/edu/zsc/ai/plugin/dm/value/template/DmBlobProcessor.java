package edu.zsc.ai.plugin.dm.value.template;

import edu.zsc.ai.plugin.value.DefaultValueProcessor;
import edu.zsc.ai.plugin.value.JdbcValueContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Processor for DM BLOB/BFILE/IMAGE types.
 *
 * <p>Smart encoding strategy:
 * <ul>
 *   <li>Small BLOBs (&lt; 1MB): Convert to Base64 data URI format</li>
 *   <li>Large BLOBs (&ge; 1MB): Return metadata with size information</li>
 * </ul>
 *
 * <p>The metadata placeholder is size information only, never content;
 * {@code DmRowWriteSupport} refuses to write it back
 * ({@code DmLobPreviewValues.PREVIEW_NOT_WRITABLE_CODE}).
 *
 * @author hhz
 */
public class DmBlobProcessor extends DefaultValueProcessor {
    // Size threshold: 1MB
    private static final long SIZE_THRESHOLD_BYTES = 1024 * 1024;

    @Override
    public Object convertJdbcValueByType(JdbcValueContext context) throws SQLException {
        ResultSet resultSet = context.getResultSet();
        int columnIndex = context.getColumnIndex();
        java.sql.Blob blob = resultSet.getBlob(columnIndex);

        if (blob == null) {
            return null;
        }

        try {
            long blobLength = blob.length();

            // For large BLOBs (>= 1MB), return metadata instead of full content
            if (blobLength >= SIZE_THRESHOLD_BYTES) {
                return formatBlobMetadata(blobLength);
            }

            // For small BLOBs (< 1MB), convert to Base64
            byte[] blobBytes = blob.getBytes(1, (int) blobLength);
            return bytesToBase64(blobBytes);
        } finally {
            blob.free();
        }
    }

    /**
     * Convert bytes to Base64 data URI format.
     * Format: data:application/octet-stream;base64,{encoded_data}
     */
    private String bytesToBase64(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "data:application/octet-stream;base64,";
        }
        String base64 = java.util.Base64.getEncoder().encodeToString(bytes);
        return "data:application/octet-stream;base64," + base64;
    }

    /**
     * Format BLOB metadata for large BLOBs.
     * Format: [BLOB: {size}MB] or [BLOB: {size}KB]
     */
    private String formatBlobMetadata(long sizeInBytes) {
        if (sizeInBytes >= SIZE_THRESHOLD_BYTES) {
            double sizeInMB = sizeInBytes / (1024.0 * 1024.0);
            return String.format(Locale.ROOT, "[BLOB: %.2fMB]", sizeInMB);
        } else {
            double sizeInKB = sizeInBytes / 1024.0;
            return String.format(Locale.ROOT, "[BLOB: %.2fKB]", sizeInKB);
        }
    }
}
