package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ErDiagramResponse;

public interface ErDiagramService {

    ErDiagramResponse load(DbContext db);
}
