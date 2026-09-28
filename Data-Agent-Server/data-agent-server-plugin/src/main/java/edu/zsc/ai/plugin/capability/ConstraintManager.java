package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;

import java.sql.Connection;
import java.util.List;

/** Optional table constraint metadata and DDL capability. */
public interface ConstraintManager {

    List<ConstraintMetadata> getConstraints(Connection connection, String catalog, String schema, String tableName);

    String getConstraintDdl(Connection connection, String catalog, String schema, String constraintName);

    void deleteConstraint(Connection connection, String catalog, String schema, String tableName, String constraintName);
}
