package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;

public interface SqlTemplateService {

    /**
     * Builds a SQL statement template for a table. Values are NULL placeholders so
     * the generated text stays executable-shaped without touching real data.
     */
    String generate(DbContext db, String tableName, String statementType);
}
