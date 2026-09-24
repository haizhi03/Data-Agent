package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SequenceService;
import edu.zsc.ai.plugin.capability.SequenceManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.SequenceMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SequenceServiceImpl implements SequenceService {

    private final ConnectionService connectionService;

    @Override
    public List<SequenceMetadata> getSequences(DbContext db) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        SequenceManager manager = DefaultPluginManager.getInstance().getSequenceManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return manager.getSequences(borrowed.connection(), db.catalog(), db.schema());
        }
    }

    @Override
    public String getSequenceDdl(DbContext db, String sequenceName) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        SequenceManager manager = DefaultPluginManager.getInstance().getSequenceManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return manager.getSequenceDdl(borrowed.connection(), db.catalog(), db.schema(), sequenceName);
        }
    }

    @Override
    public void deleteSequence(DbContext db, String sequenceName) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        SequenceManager manager = DefaultPluginManager.getInstance().getSequenceManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            manager.deleteSequence(borrowed.connection(), db.catalog(), db.schema(), sequenceName);
        }
    }
}
