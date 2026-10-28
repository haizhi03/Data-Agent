package edu.zsc.ai.local;

import edu.zsc.ai.domain.model.context.DbContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact executable content stays in process memory. A preview never becomes executable input. */
record LocalWriteProposal(String proposalId, String runtimeInstanceId, long conversationId, String originRunId,
        long connectionId, long connectionVersion, String connectionName, String dbType, String host, Integer port,
        String catalogName, String schemaName, String sqlPreview, List<Statement> statements,
        Constraints constraints, Instant createdAt, Instant expiresAt) {
    record Statement(String sql, List<Object> parameters) { Statement { parameters=List.copyOf(parameters); } }
    record Constraints(int timeoutSeconds) { }
    LocalWriteProposal { statements=List.copyOf(statements); }
    DbContext target() { return new DbContext(connectionId,catalogName,schemaName); }
    static final class Operation {
        final LocalWriteProposal proposal;
        final String executionId=UUID.randomUUID().toString();
        final AtomicBoolean cancelRequested=new AtomicBoolean();
        volatile String status="PENDING";
        volatile Result result;
        Operation(LocalWriteProposal proposal) { this.proposal=proposal; }
        void cancel() {
            cancelRequested.set(true);
            edu.zsc.ai.plugin.execution.StatementCancellationRegistry.getInstance().cancel(executionId);
        }
    }
    record Result(String status, String transactionOutcome, Integer affectedRows, String errorCode, long elapsedMs) { }
    record View(LocalWriteProposal proposal, String status, boolean cancelRequested, Result result) { }
    static View view(Operation operation) { return new View(operation.proposal,operation.status,operation.cancelRequested.get(),operation.result); }
}
