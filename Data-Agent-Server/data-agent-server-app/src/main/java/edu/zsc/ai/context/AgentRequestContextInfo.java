package edu.zsc.ai.context;

import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequestContextInfo {

    private String agentMode;

    private String agentType;

    private List<Long> allowedConnectionIds;

    /** Main-agent snapshot: connections visible to the user for this chat session. */
    private List<Long> readableConnectionIds;

    private String modelName;

    private String language;

    /** Immutable purpose bindings shared by the main agent, tool threads and continuations. */
    private ModelRunSnapshot modelRunSnapshot;
}
