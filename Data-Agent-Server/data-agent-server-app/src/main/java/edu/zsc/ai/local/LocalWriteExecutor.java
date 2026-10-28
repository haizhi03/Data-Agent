package edu.zsc.ai.local;

import edu.zsc.ai.domain.service.db.impl.ActiveConnectionRegistry;
import edu.zsc.ai.plugin.manager.DefaultPluginManager;
import edu.zsc.ai.plugin.model.command.sql.*;
import java.sql.Connection;
import org.springframework.stereotype.Component;

/** Uses the existing JDBC plugin executor, but owns this single approved operation's transaction. */
@Component
class LocalWriteExecutor {
    private final LocalConnectionService connections;
    LocalWriteExecutor(LocalConnectionService connections) { this.connections=connections; }
    protected ActiveConnectionRegistry.BorrowedConnection borrow(LocalWriteProposal.Operation operation) {
        connections.openConnection(operation.proposal.target());
        return ActiveConnectionRegistry.borrowOwnedConnection(operation.proposal.target());
    }
    protected SqlCommandResult command(ActiveConnectionRegistry.BorrowedConnection borrowed, SqlCommandRequest request) {
        return DefaultPluginManager.getInstance().getSqlCommandExecutorByPluginId(borrowed.pluginId()).executeCommand(request);
    }
    LocalWriteProposal.Result execute(LocalWriteProposal.Operation operation) {
        long start=System.nanoTime(); var p=operation.proposal;
        LocalWriteSql.validate(p.statements().getFirst().sql(),p.catalogName(),p.schemaName());
        if (operation.cancelRequested.get()) return result("CANCELLED","NONE",null,null,start);
        ActiveConnectionRegistry.BorrowedConnection borrowed;
        try { borrowed=borrow(operation); }
        catch (Exception e) { return result("FAILED","NONE",null,"LOCAL_WRITE_NOT_STARTED",start); }
        var outcome=transaction(operation,borrowed,start);
        Connection connection=borrowed.connection();
        try {
            // Never turn autoCommit on following an uncertain transaction: discard that connection.
            if (outcome.status().equals("UNKNOWN")) discard(borrowed);
            else { connection.setAutoCommit(true); connection.setReadOnly(true); }
        } catch (Exception e) { try { discard(borrowed); } catch (Exception ignored) {} }
        finally { try { borrowed.close(); } catch (Exception ignored) { /* A confirmed commit is still a fact. */ } }
        return outcome;
    }
    private LocalWriteProposal.Result transaction(LocalWriteProposal.Operation operation, ActiveConnectionRegistry.BorrowedConnection borrowed, long start) {
        var p=operation.proposal; Connection connection=borrowed.connection();
        boolean transaction=false,commitAttempted=false;
        try {
            connections.assertReadable(p.connectionId());
            if (operation.cancelRequested.get()) return result("CANCELLED","NONE",null,null,start);
            if (!java.time.Instant.now().isBefore(p.expiresAt())) return result("EXPIRED","NONE",null,"WRITE_PROPOSAL_EXPIRED",start);
            // Local pool baseline stays read-only; only this approved borrower changes it.
            connection.setReadOnly(false); connection.setAutoCommit(false); transaction=true;
            var request=SqlCommandRequest.ofWithoutTransaction(connection,p.statements().getFirst().sql(),p.statements().getFirst().sql(),p.catalogName(),p.schemaName());
            request.setTimeoutMs(p.constraints().timeoutSeconds()*1000); request.setMaxRows(1); request.setExecutionId(operation.executionId);
            if (operation.cancelRequested.get()) { connection.rollback(); return result("CANCELLED","ROLLED_BACK",null,null,start); }
            var executed=command(borrowed,request);
            if (executed==null || !executed.isSuccess() || operation.cancelRequested.get()) {
                connection.rollback();
                return result(operation.cancelRequested.get()?"CANCELLED":"FAILED","ROLLED_BACK",null,"LOCAL_WRITE_FAILED",start);
            }
            commitAttempted=true; connection.commit();
            return result("SUCCEEDED","COMMITTED",executed.getAffectedRows(),null,start);
        } catch (Exception e) {
            boolean rolledBack=false;
            if (transaction) try { connection.rollback(); rolledBack=true; } catch (Exception ignored) { }
            // A successful rollback after a lost commit response does not prove that commit failed.
            return commitAttempted || (transaction && !rolledBack)
                    ?result("UNKNOWN","UNKNOWN",null,"LOCAL_WRITE_OUTCOME_UNKNOWN",start)
                    :result(operation.cancelRequested.get()?"CANCELLED":"FAILED",rolledBack?"ROLLED_BACK":"NONE",null,"LOCAL_WRITE_FAILED",start);
        }
    }
    private void discard(ActiveConnectionRegistry.BorrowedConnection borrowed) throws java.sql.SQLException {
        if (borrowed.activeConnection().dataSource() instanceof com.zaxxer.hikari.HikariDataSource hikari) hikari.evictConnection(borrowed.connection());
        else borrowed.connection().close();
    }
    private static LocalWriteProposal.Result result(String status,String transaction,Integer affected,String code,long start) {
        return new LocalWriteProposal.Result(status,transaction,affected,code,(System.nanoTime()-start)/1_000_000);
    }
}
