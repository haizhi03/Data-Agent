package edu.zsc.ai.domain.model.dto.response.db;

import java.util.List;

/**
 * Signature help for the innermost call at the cursor.
 *
 * @param signatures     candidate overloads; empty means nothing to show
 * @param activeSignature index of the overload that best fits the argument count
 * @param activeParameter index of the parameter the cursor is currently editing
 */
public record SqlSignatureHelpResponse(List<SqlSignatureItem> signatures, int activeSignature, int activeParameter) {
    public static SqlSignatureHelpResponse empty() {
        return new SqlSignatureHelpResponse(List.of(), 0, 0);
    }
}
