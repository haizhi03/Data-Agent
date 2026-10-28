package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.entity.db.DbConnection;

/** Minimum saved-definition port used by the JDBC connection service; contains no ORM operations. */
public interface ConnectionDefinitionReader {
    DbConnection getById(Long id);
    DbConnection getOwnedById(Long id);
}
