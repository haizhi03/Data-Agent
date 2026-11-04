package edu.zsc.ai.domain.model.dto.response.db;

import java.util.List;

/**
 * One callable overload.
 *
 * @param label      full signature text, e.g. {@code NVL(value:ANY, replacement:ANY) → ANY}
 * @param parameters parameter labels in declaration order; each must occur in {@code label}
 */
public record SqlSignatureItem(String label, List<SqlSignatureParameter> parameters) {
}
