package edu.zsc.ai.agent.subagent.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplorerTaskResult {

    private String taskId;

    private ExplorerTaskStatus status;

    private String summaryText;

    private List<ExploreObject> objects;

    /** Populated by hosts that execute and verify database tools on Explorer's behalf. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ExplorerExecution> executions;

    /** Host-observed compact columns and declared foreign keys, not model-generated facts. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<java.util.Map<String,Object>> structures;

    private String rawResponse;

    private String errorMessage;
}
