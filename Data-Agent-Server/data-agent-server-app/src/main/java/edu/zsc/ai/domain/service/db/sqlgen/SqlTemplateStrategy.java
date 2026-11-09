package edu.zsc.ai.domain.service.db.sqlgen;

/**
 * One SQL statement template (SELECT / INSERT / UPDATE / DELETE). Implementations
 * are Spring beans resolved by {@link SqlTemplateRegistry}, so adding a new
 * statement type never touches the existing ones.
 */
public interface SqlTemplateStrategy {

    /** Statement type key, e.g. {@code SELECT}. */
    String statementType();

    String generate(SqlTemplateContext context);
}
