package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.plugin.model.metadata.SequenceMetadata;

import java.util.List;

public interface SequenceService {

    List<SequenceMetadata> getSequences(DbContext db);

    String getSequenceDdl(DbContext db, String sequenceName);

    void deleteSequence(DbContext db, String sequenceName);
}
