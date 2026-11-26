package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.exception.BusinessException;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ApplyTableStructureResponse;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SqlCompletionService;
import edu.zsc.ai.domain.service.db.TableStructureService;
import edu.zsc.ai.plugin.capability.TableStructureManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.structure.StructureDiagnostic;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.StructureTypeRules;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.model.structure.TableStructureEquality;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TableStructureServiceImpl implements TableStructureService {

    private final ConnectionService connectionService;
    private final SqlCompletionService completionService;

    @Override
    public TableStructure load(DbContext db, String tableName) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableStructureManager manager = requireManager(active);
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return manager.load(borrowed.connection(), db.catalog(), db.schema(), tableName);
        }
    }

    @Override
    public StructurePreview preview(DbContext db, TableStructure baseline, TableStructure draft) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableStructureManager manager = requireManager(active);
        return manager.preview(db.catalog(), db.schema(), baseline, draft);
    }

    @Override
    public StructureTypeRules typeRules(DbContext db) {
        connectionService.openConnection(db);
        return requireManager(ActiveConnectionRegistry.getOwnedConnection(db)).typeRules();
    }

    @Override
    public ApplyTableStructureResponse apply(DbContext db, TableStructure baseline, TableStructure draft) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        TableStructureManager manager = requireManager(active);
        String tableName = baseline == null ? null : baseline.name();
        if (StringUtils.isBlank(tableName)) {
            throw BusinessException.badRequest("baseline table name is required");
        }
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            Connection connection = borrowed.connection();
            TableStructure live = reload(manager, connection, db, tableName, null);
            if (!TableStructureEquality.same(baseline, live)) {
                return new ApplyTableStructureResponse(null, List.of(), List.of(), -1, StructureDiagnostic.STRUCTURE_STALE, null);
            }
            StructurePreview preview = manager.preview(db.catalog(), db.schema(), baseline, draft);
            List<String> statements = preview.statements();
            int failedIndex = -1;
            String errorMessage = null;
            for (int i = 0; i < statements.size(); i++) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(statements.get(i));
                } catch (SQLException exception) {
                    failedIndex = i;
                    errorMessage = exception.getMessage();
                    break;
                }
            }
            if (!statements.isEmpty() && failedIndex != 0) {
                completionService.invalidate(db.connectionId());
            }
            TableStructure fresh = reload(manager, connection, db, tableName, errorMessage);
            return new ApplyTableStructureResponse(
                    fresh, statements, preview.changes(), failedIndex, null, errorMessage);
        }
    }

    private TableStructure reload(TableStructureManager manager, Connection connection, DbContext db,
                                  String tableName, String errorMessage) {
        try {
            return manager.load(connection, db.catalog(), db.schema(), tableName);
        } catch (RuntimeException exception) {
            if (errorMessage == null) {
                throw exception;
            }
            throw BusinessException.serverError(errorMessage + "; failed to reload table structure: " + exception.getMessage());
        }
    }

    private TableStructureManager requireManager(ActiveConnectionRegistry.ActiveConnection active) {
        TableStructureManager manager = DefaultPluginManager.getInstance()
                .getTableStructureManagerByPluginId(active.pluginId());
        if (manager == null) {
            throw BusinessException.badRequest("Table structure editor is not supported for this database yet");
        }
        return manager;
    }
}
