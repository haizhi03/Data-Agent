package edu.zsc.ai.agent.subagent.contract;

import edu.zsc.ai.domain.model.context.DbContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Host-observed read-only query facts attached to an Explorer task result. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplorerExecution {

    private String executionId;

    private DbContext target;

    private Boolean success;

    private String statementState;

    private Boolean truncated;
}
