package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.sql.SqlType;
import edu.zsc.ai.plugin.model.sql.SqlValidationResult;

/**
 * Capability interface for validating and classifying SQL statements.
 * Plugins may implement this to provide dialect-specific syntax validation
 * using ANTLR or similar parsing tools.
 * If a plugin does not implement this interface, DefaultPluginManager falls back to DefaultSqlValidator.
 */
public interface SqlValidator {

    /**
     * Validate the given SQL statement: check syntax, classify type, extract referenced tables and columns.
     *
     * @param sql the SQL statement to validate
     * @return validation result with type, errors, tables, and columns
     */
    SqlValidationResult validate(String sql);

    /**
     * Quick classification of the SQL statement type without full parsing.
     *
     * @param sql the SQL statement to classify
     * @return the SQL type
     */
    SqlType classifySql(String sql);

    /**
     * Whether the single SQL statement is an explicit transaction control statement
     * (BEGIN / START TRANSACTION / COMMIT / ROLLBACK and dialect variants).
     * Implementations should use their own parser so that anonymous blocks
     * ({@code BEGIN ... END;}), string literals and comments are not misclassified.
     *
     * @param sql the SQL statement to check
     * @return true if executing it would implicitly begin/end a transaction
     */
    default boolean isTransactionControl(String sql) {
        return switch (classifySql(sql)) {
            case BEGIN, COMMIT, ROLLBACK -> true;
            default -> false;
        };
    }
}
