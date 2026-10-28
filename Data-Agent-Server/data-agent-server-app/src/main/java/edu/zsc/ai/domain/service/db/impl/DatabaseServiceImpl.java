package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.entity.db.DbConnection;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.DatabaseService;
import edu.zsc.ai.domain.service.db.ConnectionDefinitionReader;
import edu.zsc.ai.plugin.capability.DatabaseManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseServiceImpl implements DatabaseService {

    private final ConnectionService connectionService;
    private final ConnectionDefinitionReader dbConnectionService;

    @Override
    public List<String> getDatabases(Long connectionId) {
        connectionService.openConnection(connectionId);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getAnyOwnedActiveConnection(connectionId);
        DefaultPluginManager pluginManager = DefaultPluginManager.getInstance();

        // Plugins without a database concept (e.g. DM, schema-only): expose a single
        // pseudo catalog so the explorer tree can drill into schemas beneath it.
        if (!pluginManager.supportsDatabaseByPluginId(active.pluginId())) {
            DbConnection dbConnection = dbConnectionService.getById(connectionId);
            String catalog = StringUtils.isNotBlank(dbConnection.getDatabase())
                    ? dbConnection.getDatabase()
                    : dbConnection.getName();
            return List.of(catalog);
        }

        DatabaseManager provider = pluginManager.getDatabaseManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            return provider.getDatabases(borrowed.connection());
        }
    }

    @Override
    public void deleteDatabase(Long connectionId, String databaseName) {
        connectionService.openConnection(connectionId);

        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getAnyOwnedActiveConnection(connectionId);
        DatabaseManager provider = DefaultPluginManager.getInstance().getDatabaseManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            provider.deleteDatabase(borrowed.connection(), databaseName);
        }

        log.info("Database deleted successfully: connectionId={}, databaseName={}", connectionId, databaseName);
    }
}
