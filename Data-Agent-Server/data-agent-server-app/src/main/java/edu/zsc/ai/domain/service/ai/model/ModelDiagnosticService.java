package edu.zsc.ai.domain.service.ai.model;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.context.*;
import edu.zsc.ai.domain.model.dto.response.ai.ModelManagementResponses.Test;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.github.benmanes.caffeine.cache.*;
import java.time.*;
import org.springframework.stereotype.Service;

/** Explicit, bounded and billable diagnostics. No application tools or databases are exposed to the model. */
@Service
public class ModelDiagnosticService {
    public static final String SDK = "langchain4j-1.12.2/community-1.12.2-beta22";
    private final ModelManagementService management;
    private final ModelManagementAccess access;
    private final ModelClientRegistry registry;
    private final ModelConfigRepository repository;
    private final ExecutorService workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"model-diagnostic");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"model-diagnostic-timeout");t.setDaemon(true);return t;});

    private final Cache<UUID,Entry> records=Caffeine.newBuilder().maximumSize(1024).expireAfterWrite(Duration.ofMinutes(30)).build();
    private final Cache<String,Deque<Instant>> rates=Caffeine.newBuilder().maximumSize(4096).expireAfterAccess(Duration.ofMinutes(2)).build();
    private final Clock clock;
    private static final class Entry {
        final ResolvedModelConfig config;final String level;final Instant created;
        String status="RUNNING",result="[]",history;Instant finished;
        Entry(ResolvedModelConfig config,String level,Instant created){this.config=config;this.level=level;this.created=created;}
        @Override public String toString(){return "ModelDiagnostic[redacted]";}
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ModelDiagnosticService(ModelManagementService management,ModelManagementAccess access,ModelClientRegistry registry,ModelConfigRepository repository){this(management,access,registry,repository,Clock.systemUTC());}
    public ModelDiagnosticService(ModelManagementService management,ModelManagementAccess access,ModelClientRegistry registry,ModelConfigRepository repository,Clock clock){this.management=management;this.access=access;this.registry=registry;this.repository=repository;this.clock=clock;}
    public Test start(String modelId,String level){
        if(level==null || !List.of("L1","L2","L3","L4").contains(level))throw ModelManagementException.invalid();
        var c=management.diagnosticModel(modelId);var a=c.access();Instant now=clock.instant();
        synchronized(rates){
            var recent=rates.get(a.workspaceType()+"/"+a.ownerId(),ignored->new ArrayDeque<>());
            while(!recent.isEmpty() && !recent.peekFirst().isAfter(now.minusSeconds(60)))recent.removeFirst();
            if(recent.size()>=3)throw new ModelManagementException("MODEL_TEST_RATE_LIMITED",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS);
            recent.addLast(now);
        }
        UUID id=UUID.randomUUID();records.put(id,new Entry(c,level,now));var context=RequestContext.snapshot();
        try{workers.execute(()->execute(id,c,level,context));}
        catch(RejectedExecutionException e){finish(id,"FAILED",List.of(Map.of("stage","QUEUE","status","FAILED","code","MODEL_TEST_BUSY")));}
        return read(id.toString());
    }
    public Test read(String id){
        var a=management.scope(false);UUID key;
        try{key=UUID.fromString(id);}catch(RuntimeException e){throw missingResult();}
        var entry=records.getIfPresent(key);
        if(entry==null || !StartupModelConfigRepository.sameScope(a,entry.config.access()) || (a.userId()!=entry.config.access().userId() && !access.canManage()))throw missingResult();
        if(!entry.created.isAfter(clock.instant().minus(Duration.ofMinutes(30)))){records.invalidate(key);throw missingResult();}
        var c=entry.config;var current=repository.find(c.modelConfigId());
        boolean fresh=current!=null && current.configVersion()==c.configVersion() && current.connectionVersion()==c.connectionVersion() && current.credentialVersion()==c.credentialVersion()
                && current.capabilityProfile().equals(c.capabilityProfile()) && current.compatibilityProfile().equals(c.compatibilityProfile());
        synchronized(entry){
            if(entry.status.equals("RUNNING") && !entry.created.isAfter(clock.instant().minusSeconds(90))){entry.status="INTERRUPTED";entry.result=ModelManagementJson.write(List.of(Map.of("stage","RUN","status","FAILED","code","MODEL_TEST_INTERRUPTED")));entry.finished=clock.instant();entry.history=null;}
            return new Test(id,c.modelConfigId(),c.configVersion(),c.connectionVersion(),c.credentialVersion(),c.capabilityProfile(),c.compatibilityProfile(),SDK,entry.level,entry.status,entry.result,entry.created.toString(),entry.finished==null?null:entry.finished.toString(),fresh);
        }
    }
    public List<Test> latest(String modelId){
        var a=management.scope(true);management.model(modelId);
        return records.asMap().entrySet().stream().filter(e->StartupModelConfigRepository.sameScope(a,e.getValue().config.access()) && e.getValue().config.modelConfigId().equals(modelId) && e.getValue().created.isAfter(clock.instant().minus(Duration.ofMinutes(30))))
                .sorted((x,y)->y.getValue().created.compareTo(x.getValue().created)).limit(10).map(e->read(e.getKey().toString())).toList();
    }
    private void execute(UUID id,ResolvedModelConfig c,String level,RequestContextInfo context) {
        var run=new ModelRunSnapshot(Map.of(ModelPurpose.MAIN_CHAT,c),8);
        var active=new CopyOnWriteArrayList<ModelRunSnapshot>(); active.add(run);
        var guard=deadlines.schedule(()->active.forEach(ModelRunSnapshot::cancel),35,TimeUnit.SECONDS);
        List<Map<String,String>> stages=new ArrayList<>(); String stage="L1";
        try(var ignored=new ModelContextScope(context,AgentRequestContextInfo.builder().modelRunSnapshot(run).build())) {
            management.scope(true); var clients=registry.clients(run,ModelPurpose.MAIN_CHAT);
            var text=clients.chatModel().chat("Reply with OK only.");
            require(text!=null && !text.isBlank()); pass(stages,"L1");
            if(level.equals("L1")) { finish(id,"SUCCEEDED",stages); return; }
            stage="L2"; management.scope(true);
            CompletableFuture<ChatResponse> streamed=new CompletableFuture<>(); AtomicInteger deltas=new AtomicInteger();
            clients.streamingChatModel().chat(ChatRequest.builder().messages(UserMessage.from("Reply with READY only.")).build(),new StreamingChatResponseHandler(){
                public void onPartialResponse(String text){if(text!=null && !text.isEmpty()) deltas.incrementAndGet();}
                public void onCompleteResponse(ChatResponse r){streamed.complete(r);} public void onError(Throwable e){streamed.completeExceptionally(e);}
            });
            var streamedResult=streamed.get(20,TimeUnit.SECONDS); require(deltas.get()>0 && streamedResult.aiMessage().text()!=null && !streamedResult.aiMessage().text().isBlank()); pass(stages,"L2");
            cancelProbe(c,context,active,stages);
            if(level.equals("L2")) { finish(id,"SUCCEEDED",stages); return; }
            stage="L3"; management.scope(true);
            String nonce=UUID.randomUUID().toString();
            var tool=ToolSpecification.builder().name("echo_probe").description("Return the provided nonce exactly without external effects")
                    .parameters(JsonObjectSchema.builder().addStringProperty("nonce").required("nonce").additionalProperties(false).build()).build();
            List<ChatMessage> history=new ArrayList<>(); history.add(UserMessage.from("Call echo_probe exactly once with nonce "+nonce+", then include its returned nonce in your answer."));
            boolean thinking=c.options().thinkingMode()!=MultiProviderProperties.ThinkingMode.DISABLED;
            ToolChoice choice=thinking ? ToolChoice.AUTO : ToolChoice.REQUIRED;
            var result=clients.chatModel().chat(ChatRequest.builder().messages(history).toolSpecifications(tool).toolChoice(choice).build());
            var calls=result.aiMessage().toolExecutionRequests(); require(calls.size()==1);
            var call=calls.get(0); require("echo_probe".equals(call.name()));
            var arguments=ModelManagementJson.read(call.arguments(),com.fasterxml.jackson.databind.JsonNode.class);
            require(arguments.isObject() && arguments.size()==1 && nonce.equals(arguments.path("nonce").asText()));
            history.add(result.aiMessage()); history.add(ToolExecutionResultMessage.from(call.id(),call.name(),nonce));
            var answer=clients.chatModel().chat(ChatRequest.builder().messages(history).toolSpecifications(tool).toolChoice(ToolChoice.NONE).build());
            require(answer.aiMessage().text()!=null && answer.aiMessage().text().contains(nonce)); history.add(answer.aiMessage()); pass(stages,"L3");
            if(level.equals("L3")) { finish(id,"SUCCEEDED",stages); return; }
            stage="L4"; management.scope(true);
            var entry=records.getIfPresent(id);if(entry==null)throw ModelManagementException.missing();
            String stored;
            synchronized(entry){entry.history=ChatMessageSerializer.messagesToJson(history);stored=entry.history;entry.history=null;}
            List<ChatMessage> restored=new ArrayList<>(ChatMessageDeserializer.messagesFromJson(stored));
            restored.add(UserMessage.from("From the saved echo result, repeat the nonce exactly. Do not call tools."));
            // Rebuild the invocation wrapper after a temporary JSON and SDK codec round-trip. Only the harmless local probe was executed.
            var resumed=registry.clients(run,ModelPurpose.MAIN_CHAT).chatModel().chat(ChatRequest.builder().messages(restored).toolSpecifications(tool).toolChoice(ToolChoice.NONE).build());
            require(resumed.aiMessage().text()!=null && resumed.aiMessage().text().contains(nonce)); pass(stages,"L4"); finish(id,"SUCCEEDED",stages);
        } catch(Exception e) {
            String code=e instanceof ModelIntegrationException m ? m.getReason() : e instanceof ModelManagementException m ? m.getMessage() : "MODEL_TEST_FAILED";
            stages.add(Map.of("stage",stage,"status","FAILED","code",code)); finish(id,"FAILED",stages);
        } finally { guard.cancel(false); active.forEach(ModelRunSnapshot::cancel); }
    }
    private void cancelProbe(ResolvedModelConfig c,RequestContextInfo context,List<ModelRunSnapshot> active,List<Map<String,String>> stages) throws Exception {
        management.scope(true); var probe=new ModelRunSnapshot(Map.of(ModelPurpose.MAIN_CHAT,c),1); active.add(probe);
        CompletableFuture<ChatResponse> result=new CompletableFuture<>();
        try(var ignored=new ModelContextScope(context,AgentRequestContextInfo.builder().modelRunSnapshot(probe).build())) {
            registry.clients(probe,ModelPurpose.MAIN_CHAT).streamingChatModel().chat(ChatRequest.builder().messages(UserMessage.from("Count slowly from one to one hundred.")).build(),new StreamingChatResponseHandler(){
                public void onCompleteResponse(ChatResponse r){result.complete(r);} public void onError(Throwable e){result.completeExceptionally(e);}
            });
            boolean completed=result.isDone(); probe.cancel(); String outcome=completed ? "COMPLETED_BEFORE_CANCEL" : "REQUESTED";
            if(!completed) try { result.get(2,TimeUnit.SECONDS); } catch(ExecutionException e) { outcome="TERMINATED_AFTER_CANCEL"; } catch(TimeoutException e) { /* No upstream cancellation acknowledgement is claimed. */ }
            stages.add(Map.of("stage","L2_CANCEL","status",outcome));
        } finally { probe.cancel(); }
    }
    private void finish(UUID id,String status,List<Map<String,String>> stages){
        var entry=records.getIfPresent(id);if(entry==null)return;
        synchronized(entry){if(!entry.status.equals("RUNNING"))return;entry.status=status;entry.result=ModelManagementJson.write(stages);entry.history=null;entry.finished=clock.instant();}
    }
    private static ModelManagementException missingResult(){return new ModelManagementException("MODEL_TEST_NOT_FOUND",org.springframework.http.HttpStatus.NOT_FOUND);}
    private static void require(boolean condition) { if(!condition) throw new ModelManagementException("MODEL_TEST_ASSERTION_FAILED",org.springframework.http.HttpStatus.BAD_GATEWAY); }
    private static void pass(List<Map<String,String>> stages,String name) { stages.add(Map.of("stage",name,"status","PASSED")); }
    @PreDestroy public void close() {
        workers.shutdownNow();
        try { workers.awaitTermination(3,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        deadlines.shutdownNow();records.invalidateAll();rates.invalidateAll();
    }
}
