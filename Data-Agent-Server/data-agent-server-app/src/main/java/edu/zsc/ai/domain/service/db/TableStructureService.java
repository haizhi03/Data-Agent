package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.ApplyTableStructureResponse;
import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.StructureTypeRules;
import edu.zsc.ai.plugin.model.structure.TableStructure;

public interface TableStructureService {

    TableStructure load(DbContext db, String tableName);

    StructurePreview preview(DbContext db, TableStructure baseline, TableStructure draft);

    StructureTypeRules typeRules(DbContext db);

    ApplyTableStructureResponse apply(DbContext db, TableStructure baseline, TableStructure draft);
}
