package edu.zsc.ai.plugin.mysql.manager;

import edu.zsc.ai.plugin.capability.TableStructureManager;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.StructureTypeRules;
import edu.zsc.ai.plugin.model.structure.TableStructure;
import edu.zsc.ai.plugin.mysql.structure.MysqlTableStructureDialect;
import edu.zsc.ai.plugin.mysql.structure.MysqlTableStructureLoader;

import java.sql.Connection;

public final class MysqlTableStructureManager implements TableStructureManager {

    private final MysqlTableStructureLoader loader = new MysqlTableStructureLoader();

    @Override
    public TableStructure load(Connection connection, String catalog, String schema, String tableName) {
        return loader.load(connection, catalog, schema, tableName);
    }

    @Override
    public StructurePreview preview(String catalog, String schema, TableStructure baseline, TableStructure draft) {
        return MysqlTableStructureDialect.preview(catalog, schema, baseline, draft);
    }

    @Override
    public StructureTypeRules typeRules() {
        return StructureTypeRules.MYSQL;
    }
}
