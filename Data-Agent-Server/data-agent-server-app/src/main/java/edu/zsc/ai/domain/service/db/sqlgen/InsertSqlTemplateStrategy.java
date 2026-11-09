package edu.zsc.ai.domain.service.db.sqlgen;

import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class InsertSqlTemplateStrategy implements SqlTemplateStrategy {

    @Override
    public String statementType() {
        return "INSERT";
    }

    @Override
    public String generate(SqlTemplateContext context) {
        return "INSERT INTO " + context.tableName()
                + " (" + String.join(", ", context.columns()) + ")"
                + "\nVALUES (" + String.join(", ", Collections.nCopies(context.columns().size(), "NULL")) + ");";
    }
}
