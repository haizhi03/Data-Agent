package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.metadata.SequenceMetadata;

import java.sql.Connection;
import java.util.List;

/** Optional sequence metadata and DDL capability. */
public interface SequenceManager {

    List<SequenceMetadata> getSequences(Connection connection, String catalog, String schema);

    String getSequenceDdl(Connection connection, String catalog, String schema, String sequenceName);

    void deleteSequence(Connection connection, String catalog, String schema, String sequenceName);
}
