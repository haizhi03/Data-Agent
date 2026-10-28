package edu.zsc.ai.aspect;

import dev.langchain4j.invocation.InvocationParameters;
import edu.zsc.ai.agent.annotation.AgentTool;
import edu.zsc.ai.agent.annotation.DisallowInPlanMode;
import edu.zsc.ai.agent.tool.error.ToolErrorMapper;
import edu.zsc.ai.agent.tool.sql.model.AgentSqlResult;
import edu.zsc.ai.common.constant.InvocationContextConstant;
import edu.zsc.ai.common.enums.ai.ToolNameEnum;
import edu.zsc.ai.context.AgentRequestContext;
import edu.zsc.ai.context.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentModeGuardAspectTest {

    @AfterEach
    void tearDown() {
        AgentRequestContext.clear();
        RequestContext.clear();
    }

    @Test
    void disallowInPlanMode_blocksExecutionInPlanMode() {
        GuardedService target = new GuardedService();
        GuardedService proxy = proxy(target);

        AgentSqlResult result = proxy.guardedCall(InvocationParameters.from(Map.of(
                InvocationContextConstant.AGENT_MODE, "plan"
        )));
        assertFalse(result.isSuccess());
        assertEquals(0, target.invocationCount);
        // Tool diagnostics currently follow the Chinese main-agent prompt contract.
        assertEquals(
                "executeSelectSql 在 Plan 模式下不可用，规划期间不能运行执行类工具。"
                        + "把 SQL 保留在计划答复中，不要执行它。",
                result.getError()
        );
    }

    @Test
    void disallowInPlanMode_allowsExecutionOutsidePlanMode() {
        GuardedService target = new GuardedService();
        GuardedService proxy = proxy(target);

        AgentSqlResult result = proxy.guardedCall(InvocationParameters.from(Map.of(
                InvocationContextConstant.AGENT_MODE, "agent"
        )));
        assertTrue(result.isSuccess());
        assertEquals(1, target.invocationCount);
        assertEquals("RESULT_SET", result.getType());
    }

    private static GuardedService proxy(GuardedService target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new AgentToolContextAspect(new ToolErrorMapper()));
        factory.addAspect(new AgentModeGuardAspect());
        return factory.getProxy();
    }

    @AgentTool
    static class GuardedService {

        private int invocationCount;

        @DisallowInPlanMode(ToolNameEnum.EXECUTE_SELECT_SQL)
        AgentSqlResult guardedCall(InvocationParameters parameters) {
            invocationCount++;
            return AgentSqlResult.builder()
                    .success(true)
                    .type("RESULT_SET")
                    .build();
        }
    }
}
