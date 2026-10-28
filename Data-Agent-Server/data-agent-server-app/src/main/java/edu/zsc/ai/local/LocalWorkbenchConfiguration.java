package edu.zsc.ai.local;

import edu.zsc.ai.api.controller.db.*;
import edu.zsc.ai.config.db.DbTypeSqlTemplateRegistry;
import edu.zsc.ai.domain.event.ConnectionConfigurationChangedEvent;
import edu.zsc.ai.domain.service.db.impl.*;
import edu.zsc.ai.domain.service.db.transfer.TableDataTransferRegistry;
import edu.zsc.ai.domain.service.db.support.ConnectionAccessSupport;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;

@Configuration(proxyBeanMethods = false)
@Import({DbTypeSqlTemplateRegistry.class, DbTypeController.class, SchemaServiceImpl.class, SchemaController.class,
        ColumnServiceImpl.class, ColumnController.class, IndexServiceImpl.class, IndexController.class,
        PrimaryKeyServiceImpl.class, PrimaryKeyController.class, SqlCompletionMetadataCache.class,
        SqlCompletionServiceImpl.class, SqlCompletionController.class, SqlAnalysisController.class,
        ViewServiceImpl.class, FunctionServiceImpl.class, ProcedureServiceImpl.class, SequenceServiceImpl.class,
        ConstraintServiceImpl.class, TriggerServiceImpl.class, ErDiagramServiceImpl.class, SqlExecutionServiceImpl.class,
        DatabaseController.class, TableController.class, ViewController.class, ConstraintController.class,
        SequenceController.class, FunctionController.class, ProcedureController.class, TriggerController.class,
        LocalHostBootstrap.class, LocalWorkbenchGate.class, LocalSqlController.class, LocalDriverService.class, DriverController.class})
class LocalWorkbenchConfiguration {
    @Bean DatabaseServiceImpl localDatabases(LocalConnectionService connections) { return new DatabaseServiceImpl(connections, connections); }
    @Bean TableServiceImpl localTables(LocalConnectionService connections) {
        return new TableServiceImpl(connections, new TableDataTransferRegistry(java.util.List.of(), java.util.List.of()));
    }
    @Bean Object localConnectionLifecycle(LocalConnectionService connections, SqlCompletionMetadataCache cache, SqlExecutionServiceImpl sql) {
        ConnectionAccessSupport.setDelegate(connections);
        return new Object() {
            @EventListener public void changed(ConnectionConfigurationChangedEvent event) {
                sql.cancelForConnection(event.connectionId()); cache.invalidate(event.connectionId());
            }
            @jakarta.annotation.PreDestroy public void close() {
                if (ConnectionAccessSupport.tryGet().orElse(null) == connections) ConnectionAccessSupport.setDelegate(null);
            }
        };
    }
}
