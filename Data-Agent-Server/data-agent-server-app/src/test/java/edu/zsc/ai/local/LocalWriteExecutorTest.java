package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.plugin.model.command.sql.*;
import java.sql.*;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** JDBC/driver boundary fixtures only: no network or real database write permission is needed. */
class LocalWriteExecutorTest {
    final LocalConnectionService connections=mock(LocalConnectionService.class);
    final Connection jdbc=mock(Connection.class);
    final ActiveConnectionRegistry.BorrowedConnection borrowed=mock(ActiveConnectionRegistry.BorrowedConnection.class);
    final ActiveConnectionRegistry.ActiveConnection active=mock(ActiveConnectionRegistry.ActiveConnection.class);
    final AtomicInteger commands=new AtomicInteger();
    final LocalWriteProposal.Operation operation;
    LocalWriteExecutorTest() {
        var registry=new LocalConversationRegistry();var s=registry.create(new DbContext(1L,"db",null),1L);var run=new ModelRunSnapshot(Map.of(),16);
        operation=new LocalWriteProposal.Operation(LocalWritePolicyTest.proposal(s,run,Instant.now(),"UPDATE t SET name='a  b' WHERE id=1"));
        when(borrowed.connection()).thenReturn(jdbc);when(borrowed.activeConnection()).thenReturn(active);
    }
    LocalWriteExecutor executor(boolean success,Runnable during) {
        return new LocalWriteExecutor(connections) {
            @Override protected ActiveConnectionRegistry.BorrowedConnection borrow(LocalWriteProposal.Operation op) {return borrowed;}
            @Override protected SqlCommandResult command(ActiveConnectionRegistry.BorrowedConnection b,SqlCommandRequest request) {
                commands.incrementAndGet();assertThat(request.getOriginalSql()).isEqualTo("UPDATE t SET name='a  b' WHERE id=1");
                assertThat(request.isNeedTransaction()).isFalse();assertThat(request.getTimeoutMs()).isEqualTo(30000);
                during.run();var result=new SqlCommandResult();result.setSuccess(success);result.setAffectedRows(3);return result;
            }
        };
    }
    @Test void onlyApprovedBorrowerTemporarilyBecomesWritableAndConfirmedCommitIsRetainedOnCloseFailure() throws Exception {
        doThrow(new RuntimeException("fixture cleanup failed")).when(borrowed).close();
        var result=executor(true,() -> {}).execute(operation);
        assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.affectedRows()).isEqualTo(3);
        var order=inOrder(jdbc);order.verify(jdbc).setReadOnly(false);order.verify(jdbc).setAutoCommit(false);order.verify(jdbc).commit();order.verify(jdbc).setAutoCommit(true);order.verify(jdbc).setReadOnly(true);
        verify(jdbc,never()).rollback();assertThat(commands).hasValue(1);
    }
    @Test void failedStatementRollsBackWithoutCommitAndCancelBeforeExecutionNeverDispatchesSql() throws Exception {
        assertThat(executor(false,() -> {}).execute(operation).transactionOutcome()).isEqualTo("ROLLED_BACK");
        verify(jdbc).rollback();verify(jdbc,never()).commit();
        operation.cancelRequested.set(true);
        assertThat(executor(true,() -> {}).execute(operation).status()).isEqualTo("CANCELLED");assertThat(commands).hasValue(1);
    }
    @Test void lostCommitResponseRemainsUnknownEvenWhenRollbackSucceedsAndConnectionIsDiscarded() throws Exception {
        doThrow(new SQLException("fixture lost commit response")).when(jdbc).commit();
        var result=executor(true,() -> {}).execute(operation);
        assertThat(result.status()).isEqualTo("UNKNOWN");assertThat(result.transactionOutcome()).isEqualTo("UNKNOWN");assertThat(result.affectedRows()).isNull();
        verify(jdbc).rollback();verify(jdbc).close();verify(jdbc,never()).setAutoCommit(true);
    }
    @Test void rollbackFailureIsUnknownAndCancellationDoesNotClaimSuccess() throws Exception {
        doThrow(new SQLException("fixture lost rollback")).when(jdbc).rollback();
        assertThat(executor(false,() -> {}).execute(operation).status()).isEqualTo("UNKNOWN");
        verify(jdbc,never()).commit();verify(jdbc).close();
    }
    @Test void cancelDuringExecutionRollsBackBeforeCommit() throws Exception {
        var result=executor(true,() -> operation.cancelRequested.set(true)).execute(operation);
        assertThat(result.status()).isEqualTo("CANCELLED");verify(jdbc).rollback();verify(jdbc,never()).commit();
    }
}
