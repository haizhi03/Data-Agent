package edu.zsc.ai.plugin.dm.value;

import java.util.regex.Pattern;

/**
 * Detects truncated LOB preview placeholders produced by the DM value processors
 * ({@code DmBlobProcessor} / {@code DmClobProcessor}): strings of the form
 * {@code [BLOB: 1.17MB]} or {@code [CLOB: 1.15MB]}.
 *
 * <p>These placeholders are size metadata for large objects that exceed the
 * inline preview threshold — they are NOT the column content. Live verification
 * showed that writing such a string back through the row-write path silently
 * overwrites the LOB with the placeholder text itself. Any write-back of a
 * truncated preview must therefore be rejected (see
 * {@code DmRowWriteSupport}); this class is the single source of truth for the
 * placeholder shape.
 *
 * @author hhz
 */
public final class DmLobPreviewValues {

    /** Error code attached to a write-back that was rejected as a truncated LOB preview. */
    public static final String PREVIEW_NOT_WRITABLE_CODE = "LOB_PREVIEW_NOT_WRITABLE";

    private static final Pattern TRUNCATED_LOB_PREVIEW =
            Pattern.compile("^\\[(BLOB|CLOB): \\d+(\\.\\d+)?(KB|MB)]$");

    private DmLobPreviewValues() {
    }

    /**
     * Whether the given value is a truncated LOB preview placeholder that must
     * never be written back to the database.
     *
     * @param value candidate write-back value (may be null)
     * @return true when the value matches the LOB preview placeholder shape
     */
    public static boolean isTruncatedLobPreview(Object value) {
        return value instanceof CharSequence charSequence
                && TRUNCATED_LOB_PREVIEW.matcher(charSequence).matches();
    }

    /**
     * Build the rejection message for a refused write-back.
     *
     * @param columnName   target column
     * @param previewValue the placeholder value that was refused
     * @return human-readable explanation (never contains credentials)
     */
    public static String rejectionMessage(String columnName, Object previewValue) {
        return String.format(
                "Refusing to write truncated LOB preview \"%s\" to column \"%s\": the preview is size"
                        + " metadata, not the column content. Writing it back would corrupt the stored"
                        + " LOB. Re-fetch the full value or leave the column unmodified.",
                previewValue, columnName);
    }
}
