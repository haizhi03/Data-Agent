package edu.zsc.ai.local;

import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
final class LocalWriteService {
    private final LocalConversationRegistry sessions;
    private final LocalConnectionService connections;
    private final LocalRuntimeIdentity identity;
    private final LocalWriteExecutor executor;
    LocalWriteService(LocalConversationRegistry sessions, LocalConnectionService connections, LocalRuntimeIdentity identity, LocalWriteExecutor executor) {
        this.sessions=sessions; this.connections=connections; this.identity=identity; this.executor=executor;
    }
    LocalWriteProposal.View propose(LocalConversationRegistry.Session session, ModelRunSnapshot run, String statement) {
        run.ensureActive();
        if (session.target.connectionId()==null || (session.target.catalog()==null && session.target.schema()==null))
            throw new LocalRequestException(409,"LOCAL_WRITE_TARGET_REQUIRED","写入需要明确选择数据库或 Schema");
        LocalWriteSql.validate(statement,session.target.catalog(),session.target.schema());
        return connections.withExpectedVersion(session.target.connectionId(),session.connectionVersion,() -> {
            var connection=connections.get(session.target.connectionId());
            var created=sessions.now();
            var proposal=new LocalWriteProposal(UUID.randomUUID().toString(),identity.runtimeInstanceId().toString(),session.id,run.runId(),
                    connection.id(),connection.version(),connection.name(),connection.dbType(),connection.host(),connection.port(),
                    session.target.catalog(),session.target.schema(),statement,List.of(new LocalWriteProposal.Statement(statement,List.of())),
                    new LocalWriteProposal.Constraints(30),created,created.plusSeconds(300));
            return sessions.propose(session,run,proposal);
        });
    }
    LocalWriteProposal.View decide(long id, String proposalId, boolean approve) {
        connections.assertWorkbenchApiAllowed();
        var operation=sessions.claim(id,proposalId,approve);
        if (!approve) return LocalWriteProposal.view(operation);
        LocalWriteProposal.Result result;
        var observed=new java.util.concurrent.atomic.AtomicReference<LocalWriteProposal.Result>();
        try {
            var p=operation.proposal;
            // Already consumed before any JDBC work. Version changes never refund an approval.
            result=connections.withExpectedVersion(p.connectionId(),p.connectionVersion(),() -> { var actual=executor.execute(operation); observed.set(actual); return actual; });
        } catch (Exception e) {
            result=observed.get()!=null?observed.get():e instanceof LocalRequestException
                    ?new LocalWriteProposal.Result("FAILED","NONE",null,"LOCAL_WRITE_NOT_STARTED",0)
                    :new LocalWriteProposal.Result("UNKNOWN","UNKNOWN",null,"LOCAL_WRITE_OUTCOME_UNKNOWN",0);
        }
        sessions.writeFinished(id,operation,result);
        return LocalWriteProposal.view(operation);
    }
}
