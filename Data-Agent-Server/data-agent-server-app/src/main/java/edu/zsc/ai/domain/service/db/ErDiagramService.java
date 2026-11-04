package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;

import java.util.List;

public interface ErDiagramService {

    /** First tables in the schema, capped at the default limit. */
    ErDiagramResponse load(DbContext db);

    /**
     * @param allTables every table in the schema; {@code tableNames} is ignored
     * @param tableNames selected names, or null for the default cap. Unknown names are dropped
     *                   and duplicates collapse onto the metadata name.
     */
    ErDiagramResponse load(DbContext db, boolean allTables, List<String> tableNames);
}
