package edu.zsc.ai.local;

import dev.langchain4j.data.message.ChatMessage;
import edu.zsc.ai.config.ai.model.ModelRunSnapshot;
import edu.zsc.ai.domain.model.context.DbContext;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Bounded, process-only conversations. Active sessions are never silently evicted. */
@Component
final class LocalConversationRegistry {
    private final Map<Long, Session> sessions = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(new java.security.SecureRandom().nextLong(1, 1L << 48));
    private final Clock clock;
    /** Notified after a session is closed, evicted or shut down; listeners must not call back into the registry. */
    private volatile java.util.function.LongConsumer sessionGoneListener = id -> { };
    void setSessionGoneListener(java.util.function.LongConsumer listener) { sessionGoneListener = listener; }
    LocalConversationRegistry() { this(Clock.systemUTC()); }
    LocalConversationRegistry(Clock clock) { this.clock = clock; }
    synchronized Session create(DbContext target, Long connectionVersion) {
        evictIdle();
        if (sessions.size() >= 32) throw new LocalRequestException(429, "LOCAL_SESSION_CAPACITY", "临时会话已达上限，请关闭不用的会话");
        long id = ids.incrementAndGet();
        if (id > 9007199254740991L) throw new LocalRequestException(503, "LOCAL_SESSION_CAPACITY", "会话编号不可用");
        var session = new Session(id, target, connectionVersion, clock.instant());
        sessions.put(id, session); return session;
    }
    synchronized Session require(long id) {
        evictIdle(); var session = sessions.get(id);
        if (session == null) throw new LocalRequestException(410, "LOCAL_SESSION_EXPIRED", "临时会话已失效，请新建对话");
        session.touched = clock.instant(); return session;
    }
    record TargetSnapshot(DbContext target, Long version) { }
    synchronized TargetSnapshot target(long id) {
        var session=require(id); return new TargetSnapshot(session.target, session.connectionVersion);
    }
    synchronized void updateTarget(long id, DbContext target, Long version) {
        var session=require(id); session.target=target; session.connectionVersion=version;
    }
    synchronized void close(long id) {
        Session session = require(id);
        session.closed = true; session.messages = List.of(); invalidateWrites(session,"CANCELLED");
        if (session.active != null) session.active.cancel();
        sessions.remove(id);
        sessionGoneListener.accept(id);
    }
    synchronized void begin(Session session, ModelRunSnapshot run) {
        require(session.id);
        if (session.active != null) throw new LocalRequestException(409, "LOCAL_SESSION_BUSY", "当前会话仍在运行");
        expireWrites(session);
        if (session.writeActive!=null || session.writes.values().stream().anyMatch(w -> w.status.equals("PENDING")))
            throw new LocalRequestException(409,"WRITE_APPROVAL_REQUIRED","请先确认或拒绝待处理写入");
        session.active = run;
    }
    synchronized boolean finish(Session session, ModelRunSnapshot run, List<ChatMessage> committed) {
        if (session.active != run) return false;
        boolean accepted=!session.closed && committed!=null;
        if (accepted) {
            try { run.ensureActive(); } catch (edu.zsc.ai.config.ai.model.ModelIntegrationException e) { accepted=false; }
        }
        if (accepted) session.messages = List.copyOf(committed);
        if (!accepted) session.writes.values().stream().filter(w -> w.proposal.originRunId().equals(run.runId()) && w.status.equals("PENDING"))
                .forEach(w -> w.status="CANCELLED");
        session.active = null; session.touched = clock.instant();
        return accepted;
    }
    synchronized void cancel(long id) {
        Session session = require(id); invalidateWrites(session,"CANCELLED");
        if (session.active != null) session.active.cancel();
    }
    synchronized LocalWriteProposal.View propose(Session session, ModelRunSnapshot run, LocalWriteProposal proposal) {
        require(session.id); run.ensureActive();
        if (session.closed || session.active!=run) throw LocalRequestException.forbidden();
        if (session.writes.values().stream().anyMatch(w -> w.status.equals("PENDING")))
            throw new LocalRequestException(409,"WRITE_APPROVAL_REQUIRED","已有待确认写入");
        if (session.writes.size()>=16) {
            String oldest=session.writes.keySet().iterator().next(); session.writes.remove(oldest);
        }
        var operation=new LocalWriteProposal.Operation(proposal); session.writes.put(proposal.proposalId(),operation);
        return LocalWriteProposal.view(operation);
    }
    synchronized List<LocalWriteProposal.View> proposals(long id) {
        var session=require(id); expireWrites(session);
        return session.writes.values().stream().map(LocalWriteProposal::view).toList();
    }
    synchronized LocalWriteProposal.Operation claim(long id, String proposalId, boolean approve) {
        var session=require(id); expireWrites(session);
        var operation=session.writes.get(proposalId);
        if (operation==null) throw new LocalRequestException(404,"WRITE_PROPOSAL_NOT_FOUND","写入提议不存在");
        if (operation.status.equals("EXPIRED")) throw new LocalRequestException(410,"WRITE_PROPOSAL_EXPIRED","写入提议已过期");
        if (!operation.status.equals("PENDING")) throw new LocalRequestException(409,"WRITE_APPROVAL_ALREADY_CONSUMED","该提议已处理，不能重复执行");
        if (session.active!=null || session.writeActive!=null) throw new LocalRequestException(409,"LOCAL_SESSION_BUSY","会话仍在运行");
        operation.status=approve?"EXECUTING":"REJECTED";
        if (approve) session.writeActive=operation;
        return operation;
    }
    synchronized void writeFinished(long id, LocalWriteProposal.Operation operation, LocalWriteProposal.Result result) {
        operation.result=result; operation.status=result.status();
        var session=sessions.get(id);
        if (session!=null && session.writeActive==operation) { session.writeActive=null; session.touched=clock.instant(); }
    }
    synchronized boolean waiting(long id) { var session=sessions.get(id); if (session==null) return false; expireWrites(session); return session.writes.values().stream().anyMatch(w -> w.status.equals("PENDING")); }
    @org.springframework.context.event.EventListener synchronized void connectionChanged(edu.zsc.ai.domain.event.ConnectionConfigurationChangedEvent event) {
        sessions.values().forEach(session -> {
            session.writes.values().stream().filter(w -> Objects.equals(w.proposal.connectionId(), event.connectionId()) && w.status.equals("PENDING"))
                    .forEach(w -> w.status="INVALIDATED");
            if (session.writeActive!=null && Objects.equals(session.writeActive.proposal.connectionId(),event.connectionId())) session.writeActive.cancel();
        });
    }
    private void expireWrites(Session session) {
        session.writes.values().stream().filter(w -> w.status.equals("PENDING") && !clock.instant().isBefore(w.proposal.expiresAt())).forEach(w -> w.status="EXPIRED");
    }
    private void invalidateWrites(Session session,String status) {
        session.writes.values().stream().filter(w -> w.status.equals("PENDING")).forEach(w -> w.status=status);
        if (session.writeActive!=null) session.writeActive.cancel();
    }
    Instant now() { return clock.instant(); }
    private void evictIdle() {
        var deadline = clock.instant().minus(Duration.ofMinutes(30));
        var iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var session = entry.getValue();
            if (session.active == null && session.writeActive == null && session.touched.isBefore(deadline)) {
                iterator.remove();
                sessionGoneListener.accept(entry.getKey());
            }
        }
    }
    @jakarta.annotation.PreDestroy synchronized void shutdown() {
        sessions.values().forEach(s -> { s.closed = true; s.messages = List.of(); invalidateWrites(s,"CANCELLED"); if (s.active != null) s.active.cancel(); });
        sessions.keySet().forEach(id -> sessionGoneListener.accept(id));
        sessions.clear();
    }
    static final class Session {
        final long id;
        volatile DbContext target;
        volatile Long connectionVersion;
        volatile List<ChatMessage> messages = List.of();
        volatile ModelRunSnapshot active;
        volatile boolean closed;
        volatile String modelConfigId;
        final Map<String,LocalWriteProposal.Operation> writes=new LinkedHashMap<>();
        volatile LocalWriteProposal.Operation writeActive;
        Instant touched;
        Session(long id, DbContext target, Long version, Instant touched) {
            this.id = id; this.target = target; this.connectionVersion = version; this.touched = touched;
        }
    }
}
