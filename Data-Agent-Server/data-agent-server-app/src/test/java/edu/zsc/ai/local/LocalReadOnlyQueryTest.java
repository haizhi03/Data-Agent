package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.model.ModelIntegrationException;
import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.impl.SqlExecutionServiceImpl;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LocalReadOnlyQueryTest {
    @Test void backgroundCancellationRestoresIdentityAndCannotReturnALateSuccess() {
        var sql=mock(SqlExecutionServiceImpl.class);
        var run=new ModelRunSnapshot(Map.of(),1);
        var target=new DbContext(7L,"sales",null);
        when(sql.cancelExecution(eq(target),anyString())).thenAnswer(invocation -> {
            assertThat(RequestContext.getUserId()).isEqualTo(42L);
            assertThat(RequestContext.getConnectionId()).isEqualTo(7L);
            assertThat(AgentRequestContext.get().getModelRunSnapshot()).isSameAs(run);
            return null;
        });
        when(sql.executeSql(any())).thenAnswer(invocation -> {
            CompletableFuture.runAsync(() -> {
                assertThat(RequestContext.snapshot()).isNull();
                run.cancel();
                assertThat(RequestContext.snapshot()).isNull();
                assertThat(AgentRequestContext.snapshot()).isNull();
            }).get(5,TimeUnit.SECONDS);
            return ExecuteSqlResponse.builder().success(true).query(true).build();
        });
        try(var ignored=new ModelContextScope(RequestContextInfo.builder().userId(42L).connectionId(7L).build(),
                AgentRequestContextInfo.builder().modelRunSnapshot(run).build())) {
            assertThatThrownBy(() -> new LocalReadOnlyQuery(sql).execute(1L,run,target,"SELECT 1"))
                    .isInstanceOfSatisfying(ModelIntegrationException.class,e -> assertThat(e.getReason()).isEqualTo("AGENT_RUN_CANCELLED"));
            var request=org.mockito.ArgumentCaptor.forClass(AgentExecuteSqlRequest.class); verify(sql).executeSql(request.capture());
            verify(sql).cancelExecution(target,request.getValue().getExecutionId());
        }
    }

    @Test void completedQueryUnregistersCancellationAndPreservesSqlLiterals() {
        var sql=mock(SqlExecutionServiceImpl.class);
        when(sql.executeSql(any())).thenReturn(ExecuteSqlResponse.builder().success(true).query(true).build());
        var run=new ModelRunSnapshot(Map.of(),1);
        String query="SELECT 'a  b' AS text";
        var result=new LocalReadOnlyQuery(sql).execute(1L,run,new DbContext(7L,"sales",null),query);
        var request=org.mockito.ArgumentCaptor.forClass(AgentExecuteSqlRequest.class); verify(sql).executeSql(request.capture());
        assertThat(request.getValue().getSql()).isEqualTo(query);
        assertThat(request.getValue().getExecutionId()).isEqualTo(result.executionId());
        run.cancel(); verify(sql,never()).cancelExecution(any(),anyString());
    }
}
