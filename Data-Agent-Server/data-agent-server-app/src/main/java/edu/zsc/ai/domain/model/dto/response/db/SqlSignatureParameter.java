package edu.zsc.ai.domain.model.dto.response.db;

/**
 * One parameter of a callable signature.
 *
 * @param label parameter text exactly as it appears inside the signature label
 */
public record SqlSignatureParameter(String label) {
}
