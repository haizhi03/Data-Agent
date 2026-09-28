package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.ConstraintService;
import edu.zsc.ai.plugin.capability.ConstraintManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ConstraintMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ConstraintServiceImpl implements ConstraintService {

    private final ConnectionService connectionService;

    @Override
    public List<ConstraintMetadata> getConstraints(DbContext db, String tableName) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ConstraintManager manager = DefaultPluginManager.getInstance().getConstraintManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return manager.getConstraints(borrowed.connection(), db.catalog(), db.schema(), tableName);
        }
    }

    @Override
    public String getConstraintDdl(DbContext db, String constraintName) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ConstraintManager manager = DefaultPluginManager.getInstance().getConstraintManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return manager.getConstraintDdl(borrowed.connection(), db.catalog(), db.schema(), constraintName);
        }
    }

    @Override
    public void deleteConstraint(DbContext db, String tableName, String constraintName) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ConstraintManager manager = DefaultPluginManager.getInstance().getConstraintManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            manager.deleteConstraint(borrowed.connection(), db.catalog(), db.schema(), tableName, constraintName);
        }
    }
}
