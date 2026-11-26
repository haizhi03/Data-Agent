package edu.zsc.ai.plugin.dm.manager;

import edu.zsc.ai.plugin.capability.TableStructureManager;
import edu.zsc.ai.plugin.dm.structure.DmTableStructureDialect;
import edu.zsc.ai.plugin.dm.structure.DmTableStructureLoader;
import edu.zsc.ai.plugin.dm.support.DmMetadataSupport;
import edu.zsc.ai.plugin.dm.support.DmObjectQuerySupport;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.StructureTypeRules;
import edu.zsc.ai.plugin.model.structure.TableStructure;

import java.sql.Connection;

public final class DmTableStructureManager implements TableStructureManager {

    private final DmTableStructureLoader loader;

    public DmTableStructureManager(DmMetadataSupport metadata, DmObjectQuerySupport objects) {
        this.loader = new DmTableStructureLoader(metadata, objects);
    }

    @Override
    public TableStructure load(Connection connection, String catalog, String schema, String tableName) {
        return loader.load(connection, catalog, schema, tableName);
    }

    @Override
    public StructurePreview preview(String catalog, String schema, TableStructure baseline, TableStructure draft) {
        return DmTableStructureDialect.preview(catalog, schema, baseline, draft);
    }

    @Override
    public StructureTypeRules typeRules() {
        return StructureTypeRules.DM;
    }
}
