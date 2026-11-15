package edu.zsc.ai.plugin.capability;

import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.TableStructure;

import java.sql.Connection;

/**
 * Loads a table structure and renders the dialect SQL for a draft edit.
 * Preview does not touch the database.
 */
public interface TableStructureManager {

    TableStructure load(Connection connection, String catalog, String schema, String tableName);

    StructurePreview preview(String catalog, String schema, TableStructure baseline, TableStructure draft);
}
