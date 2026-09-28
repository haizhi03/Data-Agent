package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.request.db.AgentExecuteSqlRequest;
import edu.zsc.ai.domain.model.dto.response.db.ExecuteSqlResponse;
import edu.zsc.ai.domain.service.db.ConnectionAccessService;
import edu.zsc.ai.domain.service.db.ConnectionService;
import edu.zsc.ai.plugin.capability.CommandExecutor;
import edu.zsc.ai.plugin.capability.SqlValidator;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandRequest;
import edu.zsc.ai.plugin.model.command.sql.SqlCommandResult;
import edu.zsc.ai.plugin.model.sql.SqlType;
import edu.zsc.ai.plugin.model.sql.SqlValidationResult;
import edu.zsc.ai.plugin.model.transaction.BatchExecutionState;
import edu.zsc.ai.plugin.model.transaction.BatchMode;
import edu.zsc.ai.plugin.model.transaction.StatementExecutionState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqlExecutionServiceImplTest {

    @Test
    void writeStatementKeepsExecutorTransaction() throws Exception {
        TestContext context = context();
        when(context.validator.validate("UPDATE T SET C = 1"))
                .thenReturn(SqlValidationResult.valid(SqlType.UPDATE, List.of(), List.of()));
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));

        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("UPDATE T SET C = 1")
                    .build());
        }

        ArgumentCaptor<SqlCommandRequest> request = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(request.capture());
        assertTrue(request.getValue().isNeedTransaction());
    }

    @Test
    void readOnlyStatementSkipsTransaction() throws Exception {
        TestContext context = context();
        when(context.validator.validate("SELECT 1"))
                .thenReturn(SqlValidationResult.valid(SqlType.SELECT, List.of(), List.of()));
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));

        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("SELECT 1")
                    .build());
        }

        ArgumentCaptor<SqlCommandRequest> request = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(request.capture());
        assertFalse(request.getValue().isNeedTransaction());
    }

    @Test
    void unclassifiableStatementKeepsTransaction() throws Exception {
        TestContext context = context();
        when(context.validator.validate("STRANGE SQL"))
                .thenReturn(SqlValidationResult.invalid(SqlType.UNKNOWN, List.of()));
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));

        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("STRANGE SQL")
                    .build());
        }

        ArgumentCaptor<SqlCommandRequest> request = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor).executeCommand(request.capture());
        assertTrue(request.getValue().isNeedTransaction());
    }

    @Test
    void rejectsTransactionControlViaPluginValidator() throws Exception {
        TestContext context = context();
        when(context.validator.isTransactionControl("BEGIN")).thenReturn(true);

        ExecuteSqlResponse response;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            response = context.service.executeSql(AgentExecuteSqlRequest.builder()
                    .connectionId(context.db.connectionId())
                    .catalog(context.db.catalog())
                    .schema(context.db.schema())
                    .sql("BEGIN")
                    .build());
        }

        assertFalse(response.isSuccess());
        assertTrue(response.getErrorMessage().contains("one batch"));
        assertEquals(StatementExecutionState.NOT_EXECUTED, response.getStatementState());
        verify(context.executor, never()).executeCommand(any());
    }

    @Test
    void atomicBatchFailureMarksStatesWithoutRewritingFacts() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        AtomicInteger invocation = new AtomicInteger();
        when(context.executor.executeCommand(any())).thenAnswer(ignored ->
                invocation.getAndIncrement() == 0 ? result(true, null) : failedResult("broken statement"));

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A", "UPDATE B", "UPDATE C"));
        }

        verify(context.connection).setAutoCommit(false);
        verify(context.connection).rollback();
        verify(context.connection, never()).commit();
        verify(context.connection).setAutoCommit(true);
        // the successfully executed statement keeps its factual success flag and original message
        assertTrue(responses.get(0).isSuccess());
        assertEquals(StatementExecutionState.ROLLED_BACK, responses.get(0).getStatementState());
        assertNull(responses.get(0).getErrorMessage());
        // the failed statement keeps its own error
        assertFalse(responses.get(1).isSuccess());
        assertEquals(StatementExecutionState.FAILED, responses.get(1).getStatementState());
        assertTrue(responses.get(1).getErrorMessage().contains("broken statement"));
        // the skipped statement was never submitted
        assertFalse(responses.get(2).isSuccess());
        assertEquals(StatementExecutionState.NOT_EXECUTED, responses.get(2).getStatementState());
        responses.forEach(r -> assertEquals(BatchExecutionState.ROLLED_BACK, r.getBatchState()));
    }

    @Test
    void atomicBatchWithExecutedDdlCannotClaimFullRollback() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        when(context.validator.classifySql("CREATE TABLE X (ID INT)")).thenReturn(SqlType.CREATE);
        when(context.validator.classifySql("INSERT INTO X VALUES (1)")).thenReturn(SqlType.INSERT);
        AtomicInteger invocation = new AtomicInteger();
        when(context.executor.executeCommand(any())).thenAnswer(ignored ->
                invocation.getAndIncrement() < 2 ? result(true, null) : failedResult("broken statement"));

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db,
                    List.of("CREATE TABLE X (ID INT)", "INSERT INTO X VALUES (1)", "INSERT INTO NOPE VALUES (1)"));
        }

        verify(context.connection).rollback();
        // DDL implicitly commits on DM/Oracle/MySQL: up to the DDL the outcome is undecidable
        assertEquals(StatementExecutionState.UNKNOWN, responses.get(0).getStatementState());
        assertEquals(StatementExecutionState.ROLLED_BACK, responses.get(1).getStatementState());
        assertEquals(StatementExecutionState.FAILED, responses.get(2).getStatementState());
        responses.forEach(r -> assertEquals(BatchExecutionState.UNKNOWN, r.getBatchState()));
    }

    @Test
    void atomicBatchCommitFailureIsNotReportedAsCommitted() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));
        doThrow(new SQLException("commit lost")).when(context.connection).commit();

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A", "UPDATE B"));
        }

        // commit failed -> rollback succeeded -> ROLLED_BACK, never COMMITTED
        responses.forEach(r -> {
            assertEquals(StatementExecutionState.ROLLED_BACK, r.getStatementState());
            assertEquals(BatchExecutionState.ROLLED_BACK, r.getBatchState());
        });
    }

    @Test
    void atomicBatchCommitAndRollbackFailureIsUnknown() throws Exception {
        TestContext context = context();
        when(context.connection.getAutoCommit()).thenReturn(true);
        when(context.executor.executeCommand(any())).thenReturn(result(true, null));
        doThrow(new SQLException("commit lost")).when(context.connection).commit();
        doThrow(new SQLException("rollback lost")).when(context.connection).rollback();

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A"));
        }

        assertEquals(StatementExecutionState.UNKNOWN, responses.get(0).getStatementState());
        assertEquals(BatchExecutionState.UNKNOWN, responses.get(0).getBatchState());
    }

    @Test
    void stepwiseBatchCommitsIndependentlyAndContinues() throws Exception {
        TestContext context = context();
        AtomicInteger invocation = new AtomicInteger();
        when(context.executor.executeCommand(any())).thenAnswer(ignored -> {
            int index = invocation.getAndIncrement();
            if (index == 1) {
                return failedResult("broken statement");
            }
            SqlCommandResult committed = result(true, null);
            committed.setStatementState(StatementExecutionState.COMMITTED);
            committed.setTransactionOutcome(edu.zsc.ai.plugin.model.transaction.TransactionOutcome.COMMITTED);
            return committed;
        });

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db,
                    List.of("UPDATE A", "UPDATE B", "UPDATE C"), BatchMode.STEPWISE);
        }

        assertEquals(3, responses.size());
        verify(context.executor, org.mockito.Mockito.times(3)).executeCommand(any());
        verify(context.connection, never()).commit();
        verify(context.connection, never()).rollback();
        assertEquals(StatementExecutionState.COMMITTED, responses.get(0).getStatementState());
        assertEquals(StatementExecutionState.FAILED, responses.get(1).getStatementState());
        assertEquals(StatementExecutionState.COMMITTED, responses.get(2).getStatementState());
        responses.forEach(r -> assertEquals(BatchExecutionState.PARTIAL, r.getBatchState()));
        // per-statement requests are independently transactional
        ArgumentCaptor<SqlCommandRequest> requests = ArgumentCaptor.forClass(SqlCommandRequest.class);
        verify(context.executor, org.mockito.Mockito.times(3)).executeCommand(requests.capture());
        requests.getAllValues().forEach(r -> assertTrue(r.isNeedTransaction()));
    }

    @Test
    void batchWithTransactionControlIsRejectedBeforeExecution() throws Exception {
        TestContext context = context();
        when(context.validator.isTransactionControl("COMMIT")).thenReturn(true);

        List<ExecuteSqlResponse> responses;
        try (MockedStatic<ActiveConnectionRegistry> registry = mockStatic(ActiveConnectionRegistry.class);
             MockedStatic<DefaultPluginManager> plugins = mockStatic(DefaultPluginManager.class)) {
            registry.when(() -> ActiveConnectionRegistry.getOwnedConnection(context.db)).thenReturn(context.active);
            plugins.when(DefaultPluginManager::getInstance).thenReturn(context.pluginManager);

            responses = context.service.executeBatchSql(context.db, List.of("UPDATE A", "COMMIT"));
        }

        assertEquals(2, responses.size());
        responses.forEach(r -> {
            assertFalse(r.isSuccess());
            assertEquals(StatementExecutionState.NOT_EXECUTED, r.getStatementState());
            assertEquals(BatchExecutionState.FAILED, r.getBatchState());
            assertTrue(r.getErrorMessage().contains("already transactional"));
        });
        verify(context.executor, never()).executeCommand(any());
    }

    @SuppressWarnings("unchecked")
    private TestContext context() throws Exception {
        ConnectionService connectionService = mock(ConnectionService.class);
        ConnectionAccessService accessService = mock(ConnectionAccessService.class);
        SqlExecutionServiceImpl service = new SqlExecutionServiceImpl(connectionService, accessService);
        DbContext db = new DbContext(1L, "DB", "SCHEMA");
        Connection connection = mock(Connection.class);
        ActiveConnectionRegistry.ActiveConnection active = mock(ActiveConnectionRegistry.ActiveConnection.class);
        when(active.pluginId()).thenReturn("test-plugin");
        when(active.borrowConnection()).thenReturn(new ActiveConnectionRegistry.BorrowedConnection(connection, active));
        CommandExecutor<SqlCommandRequest, SqlCommandResult> executor = mock(CommandExecutor.class);
        SqlValidator validator = mock(SqlValidator.class);
        DefaultPluginManager pluginManager = mock(DefaultPluginManager.class);
        when(pluginManager.getSqlCommandExecutorByPluginId("test-plugin")).thenReturn(executor);
        when(pluginManager.getSqlValidatorByPluginId("test-plugin")).thenReturn(validator);
        return new TestContext(service, db, connection, active, executor, validator, pluginManager);
    }

    private SqlCommandResult result(boolean success, String error) {
        SqlCommandResult result = new SqlCommandResult();
        result.setSuccess(success);
        result.setErrorMessage(error);
        return result;
    }

    private SqlCommandResult failedResult(String error) {
        SqlCommandResult result = result(false, error);
        result.setStatementState(StatementExecutionState.FAILED);
        result.setTransactionOutcome(edu.zsc.ai.plugin.model.transaction.TransactionOutcome.NONE);
        return result;
    }

    private record TestContext(
            SqlExecutionServiceImpl service,
            DbContext db,
            Connection connection,
            ActiveConnectionRegistry.ActiveConnection active,
            CommandExecutor<SqlCommandRequest, SqlCommandResult> executor,
            SqlValidator validator,
            DefaultPluginManager pluginManager) {
    }
}
