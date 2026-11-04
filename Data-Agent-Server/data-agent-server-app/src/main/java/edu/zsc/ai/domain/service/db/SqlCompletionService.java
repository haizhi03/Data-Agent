package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.SqlCompletionResponse;
import edu.zsc.ai.domain.model.dto.response.db.SqlHoverResponse;
import edu.zsc.ai.domain.model.dto.response.db.SqlSignatureHelpResponse;

public interface SqlCompletionService {

    SqlCompletionResponse complete(DbContext db, String sql, int cursorOffset);

    SqlHoverResponse hover(DbContext db, String sql, int cursorOffset);

    SqlSignatureHelpResponse signature(DbContext db, String sql, int cursorOffset);

    SqlCompletionResponse dictionary(Long connectionId);
    SqlCompletionResponse dictionary(DbContext db, boolean resolveEnvironment);

    void invalidate(Long connectionId);
}
