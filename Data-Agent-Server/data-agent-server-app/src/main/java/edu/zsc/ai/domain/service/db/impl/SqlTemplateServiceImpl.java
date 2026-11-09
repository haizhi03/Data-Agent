package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.domain.service.db.SqlTemplateService;
import edu.zsc.ai.domain.service.db.sqlgen.SqlTemplateContext;
import edu.zsc.ai.domain.service.db.sqlgen.SqlTemplateRegistry;
import edu.zsc.ai.domain.service.db.transfer.ImportPrepareSupport;
import edu.zsc.ai.plugin.capability.ColumnManager;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.metadata.ColumnMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class SqlTemplateServiceImpl implements SqlTemplateService {

    private final ConnectionService connectionService;
    private final SqlTemplateRegistry registry;

    @Override
    public String generate(DbContext db, String tableName, String statementType) {
        connectionService.openConnection(db);
        ActiveConnectionRegistry.ActiveConnection active = ActiveConnectionRegistry.getOwnedConnection(db);
        ColumnManager columnManager = DefaultPluginManager.getInstance()
                .getColumnManagerByPluginId(active.pluginId());
        try (ActiveConnectionRegistry.BorrowedConnection borrowed = active.borrowConnection()) {
            String quote = ImportPrepareSupport.resolveIdentifierQuote(borrowed.connection());
            List<ColumnMetadata> columns = columnManager.getColumns(
                    borrowed.connection(), db.catalog(), db.schema(), tableName);
            return registry.resolve(statementType).generate(buildContext(db, tableName, quote, columns));
        }
    }

    private SqlTemplateContext buildContext(DbContext db, String tableName, String quote,
                                            List<ColumnMetadata> columns) {
        String qualifiedTable = Stream.of(db.catalog(), db.schema(), tableName)
                .filter(part -> part != null && !part.isBlank())
                .map(part -> ImportPrepareSupport.quoteIdentifier(quote, part))
                .collect(Collectors.joining("."));
        List<String> quotedColumns = columns.stream()
                .map(column -> ImportPrepareSupport.quoteIdentifier(quote, column.name()))
                .toList();
        List<String> quotedPrimaryKeys = columns.stream()
                .filter(ColumnMetadata::isPrimaryKeyPart)
                .map(column -> ImportPrepareSupport.quoteIdentifier(quote, column.name()))
                .toList();
        return new SqlTemplateContext(qualifiedTable, quotedColumns, quotedPrimaryKeys);
    }
}
