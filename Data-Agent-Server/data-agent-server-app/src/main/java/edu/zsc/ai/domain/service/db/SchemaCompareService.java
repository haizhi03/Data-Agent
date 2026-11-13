package edu.zsc.ai.domain.service.db;

import edu.zsc.ai.domain.model.dto.request.db.SchemaCompareRequest;
import edu.zsc.ai.domain.service.db.compare.diff.SchemaCompareResult;

/** 同库结构比较，只读，不执行任何 DDL。 */
public interface SchemaCompareService {

    SchemaCompareResult compare(SchemaCompareRequest request);
}
