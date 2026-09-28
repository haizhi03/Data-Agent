package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;

import java.util.List;

public interface ConstraintService {

    List<ConstraintMetadata> getConstraints(DbContext db, String tableName);

    String getConstraintDdl(DbContext db, String constraintName);

    void deleteConstraint(DbContext db, String tableName, String constraintName);
}
