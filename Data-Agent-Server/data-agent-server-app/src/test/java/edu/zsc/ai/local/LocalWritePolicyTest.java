package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.domain.model.context.DbContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class LocalWritePolicyTest {
    @ParameterizedTest @ValueSource(strings={"UPDATE t SET name = 'a  b' WHERE id = 1", "INSERT INTO t (id, name) VALUES (1, 'x')", "DELETE FROM db.t WHERE id IN (1,2)", "UPDATE \"db\".\"t\" SET name='--literal' WHERE id=2"})
    void supportedDmlKeepsExactLiteralSemantics(String sql) { assertThatCode(() -> LocalWriteSql.validate(sql,"db",null)).doesNotThrowAnyException(); }
    @ParameterizedTest @ValueSource(strings={"UPDATE other.t SET x=1", "UPDATE t SET x=evil()", "INSERT INTO t(id) VALUES (evil())", "UPDATE t SET x=1; DELETE FROM t", "DROP TABLE t", "UPDATE t SET x=(SELECT 1)", "UPDATE t SET x=1 /* hidden */", "UPDATE t SET x=?", "UPDATE t SET x=@x", "DELETE FROM other.db.t", "UPDATE t JOIN t b ON t.id=b.id SET t.x=1", "INSERT INTO t VALUES evil()", "UPDATE t,t b SET t.x=1", "UPDATE t SET x=db.VALUES(1)", "DELETE t,b FROM t,t b", "UPDATE t SET x=NEXT VALUE FOR other.seq", "UPDATE t SET x=VALUES(1)"})
    void unsupportedOrCrossTargetContentNeverBecomesAProposal(String sql) { assertThatThrownBy(() -> LocalWriteSql.validate(sql,"db",null)).isInstanceOf(LocalRequestException.class); }
    static LocalWriteProposal proposal(LocalConversationRegistry.Session s, ModelRunSnapshot run, Instant now, String sql) {
        return new LocalWriteProposal(UUID.randomUUID().toString(),UUID.randomUUID().toString(),s.id,run.runId(),1,1,"fixture","mysql","fixture.invalid",3306,"db",null,
                sql,List.of(new LocalWriteProposal.Statement(sql,List.of())),new LocalWriteProposal.Constraints(30),now,now.plusSeconds(300));
    }
    @Test void concurrentApprovalCanBeConsumedOnlyOnceAndSqlIsNeverNormalized() throws Exception {
        var registry=new LocalConversationRegistry(); var s=registry.create(new DbContext(1L,"db",null),1L); var run=new ModelRunSnapshot(Map.of(),16); registry.begin(s,run);
        String sql="UPDATE t SET x='a  b' WHERE id=1"; var p=proposal(s,run,registry.now(),sql); registry.propose(s,run,p); registry.finish(s,run,List.of());
        var start=new CountDownLatch(1); var consumed=new AtomicInteger();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var futures=new ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(() -> { try { start.await(); var op=registry.claim(s.id,p.proposalId(),true); assertThat(op.proposal.statements().getFirst().sql()).isEqualTo(sql); consumed.incrementAndGet(); } catch(LocalRequestException expected) {} catch(InterruptedException e) { throw new RuntimeException(e); } }));
            start.countDown(); for(var f:futures)f.get(5,TimeUnit.SECONDS);
        }
        assertThat(consumed).hasValue(1);
        registry.cancel(s.id); assertThat(s.writeActive.cancelRequested).isTrue();
        registry.writeFinished(s.id,s.writeActive,new LocalWriteProposal.Result("UNKNOWN","UNKNOWN",null,null,1));
        assertThatThrownBy(() -> registry.claim(s.id,p.proposalId(),true)).isInstanceOf(LocalRequestException.class);
    }
    @Test void expiryRejectCancelFailureAndCloseNeverLeaveReusableAuthorizations() {
        var instant=new AtomicReference<>(Instant.parse("2026-10-27T00:00:00Z"));
        var clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return instant.get();}};
        for(String action:List.of("expiry","reject","cancel","failed","close","connection")) {
            var registry=new LocalConversationRegistry(clock); var s=registry.create(new DbContext(1L,"db",null),1L); var run=new ModelRunSnapshot(Map.of(),16);registry.begin(s,run);
            var p=proposal(s,run,instant.get(),"DELETE FROM t WHERE id=1");registry.propose(s,run,p);
            registry.finish(s,run,action.equals("failed")?null:List.of());
            switch(action) {
                case "expiry" -> instant.set(instant.get().plusSeconds(300));
                case "reject" -> registry.claim(s.id,p.proposalId(),false);
                case "cancel" -> registry.cancel(s.id);
                case "close" -> registry.close(s.id);
                case "connection" -> registry.connectionChanged(new edu.zsc.ai.domain.event.ConnectionConfigurationChangedEvent(1L,2L,"UPDATED"));
            }
            assertThatThrownBy(() -> registry.claim(s.id,p.proposalId(),true)).as(action).isInstanceOf(LocalRequestException.class);
        }
    }
    @Test void pendingApprovalBlocksAnotherRunButExpiredApprovalDoesNot() {
        var registry=new LocalConversationRegistry();var s=registry.create(new DbContext(1L,"db",null),1L);var run=new ModelRunSnapshot(Map.of(),16);registry.begin(s,run);
        registry.propose(s,run,proposal(s,run,registry.now(),"DELETE FROM t"));registry.finish(s,run,List.of());
        assertThatThrownBy(() -> registry.begin(s,new ModelRunSnapshot(Map.of(),16))).isInstanceOf(LocalRequestException.class);
        registry.cancel(s.id);assertThatCode(() -> registry.begin(s,new ModelRunSnapshot(Map.of(),16))).doesNotThrowAnyException();
    }
}
