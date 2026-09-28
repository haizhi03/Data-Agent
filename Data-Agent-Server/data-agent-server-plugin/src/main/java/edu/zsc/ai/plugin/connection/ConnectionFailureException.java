package edu.zsc.ai.plugin.connection;

/**
 * Connection establishment failure with a classified root cause.
 *
 * <p>Carries the JDBC sqlState/vendor errorCode when available so callers can log or
 * react without parsing message text. Messages must never contain credentials.
 */
public class ConnectionFailureException extends RuntimeException {

    public enum Category {
        DRIVER_LOAD,
        NETWORK,
        AUTHENTICATION,
        TIMEOUT,
        SCHEMA_SWITCH,
        POOL_EXHAUSTED,
        UNKNOWN
    }

    private final Category category;
    private final String sqlState;
    private final Integer errorCode;

    public ConnectionFailureException(Category category, String message, Throwable cause) {
        this(category, null, null, message, cause);
    }

    public ConnectionFailureException(Category category, String sqlState, Integer errorCode,
                                      String message, Throwable cause) {
        super(message, cause);
        this.category = category == null ? Category.UNKNOWN : category;
        this.sqlState = sqlState;
        this.errorCode = errorCode;
    }

    public Category getCategory() {
        return category;
    }

    public String getSqlState() {
        return sqlState;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    @Override
    public String toString() {
        return "ConnectionFailureException{category=" + category
                + ", sqlState=" + sqlState + ", errorCode=" + errorCode + ", message=" + getMessage() + "}";
    }
}
