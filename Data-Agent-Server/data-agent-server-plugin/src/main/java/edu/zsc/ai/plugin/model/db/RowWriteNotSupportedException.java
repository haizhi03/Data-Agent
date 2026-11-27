package edu.zsc.ai.plugin.model.db;

/**
 * A row write rejected before execution, e.g. a truncated LOB preview placeholder
 * that is size metadata rather than content. Carries the error code the client
 * would see from EXECUTE, so a PLAN fails with the same code.
 */
public class RowWriteNotSupportedException extends RuntimeException {

    private final String code;

    public RowWriteNotSupportedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
