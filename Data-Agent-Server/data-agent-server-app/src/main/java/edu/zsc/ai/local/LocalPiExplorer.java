package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.zsc.ai.agent.subagent.contract.*;
import edu.zsc.ai.agent.subagent.explorer.ExplorerResponseParser;
import edu.zsc.ai.config.ai.model.*;
import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.agent.ChatResponseBlock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Bounded, isolated Pi Explorer tasks. No legacy agent graph or chat persistence. */
final class LocalPiExplorer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalPiExplorer.class);
    private static final String PROMPT = edu.zsc.ai.config.ai.PromptConfig.loadClassPathResource("prompt/pi-explorer_zh.md");
    private final LocalPiProperties properties;
    private final ObjectMapper json;
    private final CredentialService credentials;
    private final ModelConfigResolver resolver;
    private final LocalMetadataTools metadata;

    @FunctionalInterface interface ReadTool {
        LocalPiChatService.ToolResult execute(ModelRunSnapshot child, DbContext target, String name, String arguments) throws Exception;
    }
    private record Task(DbContext target, String instruction, String context, long timeoutSeconds, String mode) { }

    LocalPiExplorer(LocalPiProperties properties, ObjectMapper json, CredentialService credentials,
                    ModelConfigResolver resolver, LocalMetadataTools metadata) {
        this.properties=properties; this.json=json; this.credentials=credentials; this.resolver=resolver; this.metadata=metadata;
    }

    LocalPiChatService.ToolResult execute(long conversationId, ModelRunSnapshot parent, DbContext defaultTarget,
            Map<Long,Long> versions, String arguments, String parentToolId, AtomicInteger taskBudget,
            ReadTool tools, Consumer<ChatResponseBlock> events, Consumer<JsonNode> usage) throws Exception {
        var tasks=parse(arguments,defaultTarget,versions);
        if (taskBudget.addAndGet(tasks.size())>4) throw failure("AGENT_BUDGET_EXCEEDED");
        var results=new ArrayList<ExplorerTaskResult>();
        for (var task:tasks) {
            parent.ensureActive();
            results.add(executeTask(conversationId,parent,task,parentToolId,tools,events,usage));
        }
        var body=new LinkedHashMap<String,Object>();body.put("taskResults",results);body.put("truncated",false);
        String text=json.writeValueAsString(body);
        if(text.length()>12000) {
            body.put("truncated",true);
            results.forEach(result->result.setRawResponse(null));
            text=json.writeValueAsString(body);
            while(text.length()>12000) {
                for(var result:results) {
                    result.setSummaryText(limit(result.getSummaryText(),500));
                    if(!result.getObjects().isEmpty())result.setObjects(result.getObjects().subList(0,result.getObjects().size()/2));
                    if(result.getStructures()!=null && !result.getStructures().isEmpty())
                        result.setStructures(result.getStructures().subList(0,result.getStructures().size()/2));
                }
                text=json.writeValueAsString(body);
            }
        }
        return new LocalPiChatService.ToolResult(text,results.stream().anyMatch(result->result.getStatus()==ExplorerTaskStatus.ERROR));
    }

    private List<Task> parse(String arguments, DbContext fallback, Map<Long,Long> versions) throws Exception {
        JsonNode root=json.readTree(arguments);
        keys(root,Set.of("tasks"));
        JsonNode values=root.path("tasks");
        if(!values.isArray() || values.isEmpty() || values.size()>2)throw LocalRequestException.invalid();
        var result=new ArrayList<Task>();
        for(var value:values) {
            keys(value,Set.of("connectionId","catalogName","schemaName","instruction","context","timeoutSeconds","mode"));
            var id=value.path("connectionId");
            if(!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong()<1 || id.asLong()>9007199254740991L)throw LocalRequestException.invalid();
            String instruction=string(value,"instruction",4000,true), context=string(value,"context",4000,false);
            String mode=string(value,"mode",20,false);
            if(mode==null)mode="general"; // Existing callers retain the original read-query tool set.
            if(!Set.of("schema","general").contains(mode))throw LocalRequestException.invalid();
            long timeout=30;
            if(value.hasNonNull("timeoutSeconds")) {
                if(!value.path("timeoutSeconds").isIntegralNumber() || !value.path("timeoutSeconds").canConvertToLong())throw LocalRequestException.invalid();
                timeout=value.path("timeoutSeconds").asLong();
                if(timeout<1 || timeout>45)throw LocalRequestException.invalid();
            }
            var probe=LocalMetadataTools.fillDefaults(id.asLong(),string(value,"catalogName",200,false),string(value,"schemaName",200,false),fallback);
            var scope=metadata.resolveValidated(probe,versions);
            result.add(new Task(scope.target(),instruction,context,timeout,mode));
        }
        return result;
    }

    private ExplorerTaskResult executeTask(long conversationId, ModelRunSnapshot parent, Task task, String parentToolId,
            ReadTool tools, Consumer<ChatResponseBlock> events, Consumer<JsonNode> usage) {
        String taskId="pi-explore-"+UUID.randomUUID();
        long startedNanos=System.nanoTime();
        log.info("Pi Explorer started: conversationId={}, parentRunId={}, taskId={}, connectionId={}, timeoutSeconds={}",
                conversationId,parent.runId(),taskId,task.target().connectionId(),task.timeoutSeconds());
        var result=ExplorerTaskResult.builder().taskId(taskId).status(ExplorerTaskStatus.ERROR).objects(List.of()).executions(List.of()).build();
        events.accept(ChatResponseBlock.subAgentStart("explorer",parentToolId,taskId,task.target().connectionId(),task.timeoutSeconds()));
        var models=new EnumMap<ModelPurpose,ResolvedModelConfig>(ModelPurpose.class);
        for(var purpose:ModelPurpose.values())models.put(purpose,parent.model(purpose));
        var child=new ModelRunSnapshot(models,6);
        var process=new AtomicReference<PiWorkerProcess>();
        var messages=new LinkedBlockingQueue<JsonNode>(128);
        var overflow=new AtomicBoolean();
        var counts=new LinkedHashMap<String,Integer>();
        var evidence=new HashSet<String>();
        var claimed=new HashSet<String>();
        var executions=new ArrayList<ExplorerExecution>();
        var structures=new ArrayList<Map<String,Object>>();
        var output=new StringBuilder();
        var timedOut=new AtomicBoolean();
        var reportedUsage=new AtomicBoolean();
        var timer=new Timer("pi-explorer-timeout",true);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(task.timeoutSeconds());
        timer.schedule(new TimerTask(){public void run(){timedOut.set(true);child.cancel();var worker=process.get();if(worker!=null)worker.close();}},TimeUnit.SECONDS.toMillis(task.timeoutSeconds()));
        try(var registration=parent.track(()->{
            child.cancel();
            var worker=process.get();if(worker!=null)worker.close();
        })) {
            var config=parent.model(ModelPurpose.EXPLORER);
            log.info("Pi Explorer model selected: conversationId={}, taskId={}, modelConfigId={}, modelId={}",
                    conversationId,taskId,config.modelConfigId(),config.modelId());
            if(!Set.of(ModelApiStyle.OPENAI_CHAT_COMPLETIONS,ModelApiStyle.ANTHROPIC_MESSAGES).contains(config.apiStyle()))
                throw failure("PI_UNSUPPORTED_API_STYLE");
            resolver.reauthorize(config);
            var worker=PiWorkerProcess.start(conversationId,properties,json,new PiWorkerProcess.Listener(){
                public void onMessage(JsonNode message){if(!messages.offer(message))overflow.set(true);}
                public void onGone(String reason){messages.offer(json.valueToTree(Map.of("type","finished","runId",child.runId(),"status","FAILED","errorCode",reason)));}
            });
            process.set(worker);parent.ensureActive();child.ensureActive();
            var start=credentials.withSecret(config,secret->start(child,config,secret,task,taskId));
            worker.send(start);
            for(;;) {
                parent.ensureActive();child.ensureActive();
                if(System.nanoTime()>deadline)throw failure("PI_EXPLORER_TIMEOUT");
                if(overflow.get())throw failure("PI_PROTOCOL_ERROR");
                var message=messages.poll(100,TimeUnit.MILLISECONDS);
                if(message==null)continue;
                if(!child.runId().equals(message.path("runId").asText()))continue;
                switch(message.path("type").asText()) {
                    case "text_delta" -> {
                        output.append(message.path("delta").asText());
                        if(output.length()>16000)throw failure("AGENT_BUDGET_EXCEEDED");
                    }
                    case "tool_call" -> {
                        String callId=message.path("toolCallId").asText(), name=message.path("name").asText();
                        if(callId.isBlank() || callId.length()>200 || name.length()>100 || !claimed.add(callId))throw failure("PI_PROTOCOL_ERROR");
                        if(claimed.size()>12)throw failure("AGENT_BUDGET_EXCEEDED");
                        String arguments=message.path("arguments").asText("{}");
                        if(arguments.length()>65536)throw failure("PI_PROTOCOL_ERROR");
                        String loggedName=name.replaceAll("[^A-Za-z0-9_]","_");
                        long toolStarted=System.nanoTime();
                        log.info("Pi Explorer tool started: conversationId={}, taskId={}, tool={}",conversationId,taskId,loggedName);
                        events.accept(nest(ChatResponseBlock.toolCall(taskId+"/"+callId,name,arguments),parentToolId,taskId));
                        events.accept(ChatResponseBlock.subAgentProgress("explorer","正在调用 "+name,parentToolId,taskId,task.target().connectionId()));
                        LocalPiChatService.ToolResult outcome;
                        restrict(arguments,name,task.target(),task.mode());
                        try {
                            outcome=tools.execute(child,task.target(),name,arguments);
                            if(!outcome.isError()) {
                                collectEvidence(outcome.text(),evidence);
                                if(name.equals("describe_tables")) {
                                    var body=json.readTree(outcome.text());
                                    for(var table:body.path("tables"))if(table.isObject() && structures.size()<20) {
                                        Map<String,Object> structure=json.convertValue(table,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});
                                        structure.put("target",LocalMetadataTools.identity(task.target()));
                                        structures.add(structure);
                                    }
                                }
                            }
                        } catch(Exception e) { outcome=new LocalPiChatService.ToolResult(json.writeValueAsString(Map.of("error",LocalPiChatService.errorCode(e))),true); }
                        collectExecution(name,outcome.text(),task.target(),executions);
                        log.info("Pi Explorer tool finished: conversationId={}, taskId={}, tool={}, isError={}, elapsedMs={}",
                                conversationId,taskId,loggedName,outcome.isError(),(System.nanoTime()-toolStarted)/1_000_000);
                        counts.merge(name,1,Integer::sum);
                        parent.ensureActive();child.ensureActive();
                        if(System.nanoTime()>deadline)throw failure("PI_EXPLORER_TIMEOUT");
                        events.accept(nest(ChatResponseBlock.toolResult(taskId+"/"+callId,name,outcome.text(),outcome.isError()),parentToolId,taskId));
                        worker.send(Map.of("type","tool_result","runId",child.runId(),"toolCallId",callId,"isError",outcome.isError(),"result",outcome.text()));
                    }
                    case "finished" -> {
                        usage.accept(message.path("usage"));reportedUsage.set(true);
                        if(!message.path("status").asText().equals("SUCCEEDED"))throw failure(message.path("errorCode").asText("PI_WORKER_ERROR"));
                        edu.zsc.ai.agent.subagent.contract.SchemaSummary summary;
                        try { summary=ExplorerResponseParser.parse(output.toString()); }
                        catch(RuntimeException invalid) { throw failure("PI_EXPLORER_RESULT_INVALID"); }
                        var objects=summary.getObjects()==null?List.<ExploreObject>of():summary.getObjects();
                        if(objects.size()>20)throw failure("PI_EXPLORER_RESULT_INVALID");
                        for(var object:objects) {
                            if(object==null || !evidence.contains(object.getObjectName()) || !"TABLE".equalsIgnoreCase(object.getObjectType())
                                    || object.getObjectDdl()!=null && !object.getObjectDdl().isBlank()
                                    || object.getCatalog()!=null && !Objects.equals(object.getCatalog(),task.target().catalog())
                                    || object.getSchema()!=null && !Objects.equals(object.getSchema(),task.target().schema()))throw failure("PI_EXPLORER_RESULT_INVALID");
                            object.setCatalog(task.target().catalog());object.setSchema(task.target().schema());object.setObjectType("TABLE");
                        }
                        result.setStatus(ExplorerTaskStatus.SUCCESS);result.setSummaryText(limit(summary.getSummaryText(),1500));
                        result.setObjects(objects);result.setExecutions(List.copyOf(executions));result.setStructures(List.copyOf(structures));
                        result.setRawResponse(structures.isEmpty()?output.toString():null);
                        events.accept(ChatResponseBlock.subAgentComplete("explorer",parentToolId,taskId,claimed.size(),counts,
                                result.getSummaryText(),json.writeValueAsString(result),task.target().connectionId(),task.timeoutSeconds()));
                        return result;
                    }
                    default -> throw failure("PI_PROTOCOL_ERROR");
                }
            }
        } catch(Exception e) {
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            String code=timedOut.get()?"PI_EXPLORER_TIMEOUT":e instanceof IllegalArgumentException?"PI_EXPLORER_RESULT_INVALID":LocalPiChatService.errorCode(e);
            if(!reportedUsage.get())usage.accept(json.createObjectNode());
            result.setExecutions(List.copyOf(executions));
            result.setStructures(List.copyOf(structures));
            result.setErrorMessage(code);result.setSummaryText("Explorer task failed: "+code);
            log.warn("Pi Explorer failed: conversationId={}, taskId={}, errorCode={}, exceptionType={}, location={}",
                    conversationId,taskId,code,e.getClass().getSimpleName(),e.getStackTrace().length==0?null:e.getStackTrace()[0]);
            events.accept(ChatResponseBlock.subAgentError("explorer",code,parentToolId,taskId,task.target().connectionId(),task.timeoutSeconds()));
            return result;
        } finally {
            log.info("Pi Explorer finished: conversationId={}, taskId={}, status={}, errorCode={}, elapsedMs={}, toolCalls={}, executions={}",
                    conversationId,taskId,result.getStatus(),result.getErrorMessage(),(System.nanoTime()-startedNanos)/1_000_000,claimed.size(),executions.size());
            timer.cancel();child.cancel();var worker=process.get();if(worker!=null)worker.close();
        }
    }

    private Map<String,Object> start(ModelRunSnapshot child, ResolvedModelConfig config, String secret, Task task, String taskId) {
        var model=new LinkedHashMap<String,Object>();model.put("apiStyle",config.apiStyle().name());model.put("baseUrl",config.endpoint().toString());
        model.put("modelId",config.modelId());model.put("contextWindow",config.maxContextTokens());
        model.put("maxOutputTokens",config.options().maxOutputTokens()==null?4096:Math.min(4096,config.options().maxOutputTokens()));
        var message=new LinkedHashMap<String,Object>();message.put("type","start");message.put("role","explorer");message.put("runId",child.runId());
        message.put("toolProfile",task.mode());
        message.put("sessionId",taskId);message.put("systemPrompt",PROMPT);message.put("model",model);message.put("apiKey",secret);
        message.put("message",edu.zsc.ai.util.JsonUtil.object2json(Map.of("instruction",task.instruction(),"context",task.context()==null?"":task.context(),"target",LocalMetadataTools.identity(task.target()))));
        message.put("context",Map.of("defaultTarget",LocalMetadataTools.identity(task.target()),"mentions",List.of()));
        message.put("limits",Map.of("maxModelCalls",6,"maxToolCalls",12));return message;
    }

    private void restrict(String arguments,String name,DbContext target,String mode) throws Exception {
        if(!Set.of("list_tables","describe_table","describe_tables","query_database").contains(name))throw LocalRequestException.forbidden();
        if(mode.equals("schema") && !Set.of("list_tables","describe_tables").contains(name))throw LocalRequestException.forbidden();
        var args=json.readTree(arguments);if(args==null || !args.isObject())throw LocalRequestException.invalid();
        if(args.hasNonNull("connectionId") && (!args.path("connectionId").isIntegralNumber() || !args.path("connectionId").canConvertToLong()
                || args.path("connectionId").asLong()!=target.connectionId()))throw LocalRequestException.forbidden();
        for(var entry:LocalMetadataTools.identity(target).entrySet()) {
            if(entry.getKey().equals("connectionId") || !args.hasNonNull(entry.getKey()))continue;
            if(!args.path(entry.getKey()).isTextual() || !Objects.equals(args.path(entry.getKey()).asText(),entry.getValue()))throw LocalRequestException.forbidden();
        }
    }
    private void collectEvidence(String text,Set<String> evidence) throws Exception {
        var body=json.readTree(text);
        if(body.path("tables").isArray())for(var table:body.path("tables")) {
            if(table.isTextual())evidence.add(table.asText());
            else if(table.path("table").isTextual())evidence.add(table.path("table").asText());
        }
        if(body.path("table").isTextual())evidence.add(body.path("table").asText());
    }
    private void collectExecution(String name,String text,DbContext expectedTarget,List<ExplorerExecution> executions) throws Exception {
        if(!"query_database".equals(name))return;
        var body=json.readTree(text);
        if(!body.path("executionId").isTextual())return; // Validation failures never reached JDBC execution.
        if(body.path("executionId").asText().isBlank() || !body.path("target").isObject()
                || !body.path("success").isBoolean() || !body.path("statementState").isTextual()
                || !body.path("truncated").isBoolean())throw failure("PI_PROTOCOL_ERROR");
        var wireTarget=body.path("target");
        var connectionId=wireTarget.path("connectionId");
        if(!connectionId.isIntegralNumber() || !connectionId.canConvertToLong()
                || !wireTarget.path("catalogName").isTextual() && !wireTarget.path("catalogName").isNull()
                || !wireTarget.path("schemaName").isTextual() && !wireTarget.path("schemaName").isNull()) {
            throw failure("PI_PROTOCOL_ERROR");
        }
        var target=new DbContext(connectionId.asLong(),wireTarget.path("catalogName").isNull()?null:wireTarget.path("catalogName").asText(),
                wireTarget.path("schemaName").isNull()?null:wireTarget.path("schemaName").asText());
        if(!expectedTarget.equals(target))throw failure("PI_EXPLORER_RESULT_INVALID");
        executions.add(ExplorerExecution.builder().executionId(body.path("executionId").asText()).target(target)
                .success(body.path("success").asBoolean()).statementState(body.path("statementState").asText())
                .truncated(body.path("truncated").asBoolean()).build());
    }
    private static ChatResponseBlock nest(ChatResponseBlock block,String parent,String task){block.setParentToolCallId(parent);block.setSubAgentTaskId(task);return block;}
    private static void keys(JsonNode value,Set<String> allowed){if(value==null || !value.isObject())throw LocalRequestException.invalid();value.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw LocalRequestException.invalid();});}
    private static String string(JsonNode value,String field,int max,boolean required){var item=value.get(field);if(item==null || item.isNull()){if(required)throw LocalRequestException.invalid();return null;}if(!item.isTextual() || item.asText().length()>max || required && item.asText().isBlank())throw LocalRequestException.invalid();return item.asText();}
    private static String limit(String value,int max){return value==null?null:value.substring(0,Math.min(value.length(),max));}
    private static LocalRequestException failure(String code){return new LocalRequestException(409,code,code);}
}
