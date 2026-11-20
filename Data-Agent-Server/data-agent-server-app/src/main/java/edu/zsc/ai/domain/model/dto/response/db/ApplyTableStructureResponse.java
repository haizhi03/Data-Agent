package edu.zsc.ai.domain.model.dto.response.db;

import edu.zsc.ai.plugin.model.structure.StructurePreview;
import edu.zsc.ai.plugin.model.structure.TableStructure;

import java.util.List;

/**
 * Result of applying a structure draft.
 * {@code failedIndex} is -1 when every statement succeeded.
 */
public record ApplyTableStructureResponse(
        TableStructure structure,
        List<String> statements,
        List<StructurePreview.Change> changes,
        int failedIndex,
        String errorMessage
) {
}
