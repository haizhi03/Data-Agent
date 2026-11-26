package edu.zsc.ai.local;

import com.fasterxml.jackson.databind.*;
import edu.zsc.ai.DataAgentApplication;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.*;

/** Explicit opt-in: real model and existing local MySQL demo, SELECTs only; never creates fixtures or changes configuration. */
@EnabledIfEnvironmentVariable(named="DATA_AGENT_PI_MYSQL_LIVE",matches="true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value=180,unit=TimeUnit.SECONDS)
class LocalPiMySqlLiveTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newHttpClient();
    static final List<Map<String,Object>> evidence=new ArrayList<>();
    static final List<Long> conversations=new ArrayList<>();
    static ServletWebServerApplicationContext context;
    static Path directory;
    static Map<String,String> originalFiles;
    static String base,token,runtime,modelId,modelName;
    static long connectionId,conversation,totalUsers,disabledUsers,totalPairs;

    @BeforeAll static void start() throws Exception {
        directory=Path.of(Objects.requireNonNull(System.getenv("DATA_AGENT_PI_LOCAL_DIRECTORY"),
                "Set DATA_AGENT_PI_LOCAL_DIRECTORY to the existing encrypted local directory")).toAbsolutePath();
        assertThat(directory.resolve("connections.json")).isRegularFile();
        originalFiles=fingerprints();
        Path worker=new LocalPiProperties(true,null,null,0,0,0).resolveWorkerScript();
        assertThat(worker).isRegularFile();
        context=(ServletWebServerApplicationContext)new SpringApplicationBuilder(DataAgentApplication.class).run(
                "--server.port=0","--spring.main.banner-mode=off","--app.local.data-directory="+directory,
                "--app.local.pi.enabled=true",
                "--logging.config=classpath:local-runtime-logback.xml");
        var identity=context.getBean(LocalRuntimeIdentity.class);
        base="http://127.0.0.1:"+context.getWebServer().getPort();
        token=identity.transportTokenForHost(); runtime=identity.runtimeInstanceId().toString();
        var capabilities=call("GET","/api/runtime/capabilities",null);
        assertThat(capabilities.path("features").path("piAssistant").path("state").asText()).isEqualTo("READY");
        for(var connection:call("GET","/api/connections",null)) {
            if(connection.path("dbType").asText().equals("mysql") && connection.path("port").asInt()==3306
                    && Set.of("localhost","127.0.0.1").contains(connection.path("host").asText())) {
                connectionId=connection.path("id").asLong(); break;
            }
        }
        assertThat(connectionId).as("existing local MySQL connection").isPositive();
        for(var binding:call("GET","/api/ai/model-bindings",null))
            if(binding.path("purpose").asText().equals("MAIN_CHAT")) modelId=binding.path("modelConfigId").asText();
        assertThat(modelId).isNotBlank();
        for(var model:call("GET","/api/ai/model-configs",null))
            if(model.path("id").asText().equals(modelId)) modelName=model.path("modelId").asText();
        totalUsers=scalar("SELECT COUNT(*) FROM sys_demo.sys_user");
        disabledUsers=scalar("SELECT COUNT(*) FROM sys_demo.sys_user WHERE status=0");
        totalPairs=scalar("SELECT COUNT(*) FROM sys_demo.sys_user u CROSS JOIN sys_demo.sys_role_permission rp");
        assertThat(totalPairs).as("existing demo must exceed the 200-row query cap").isGreaterThan(200);
        conversation=create();
    }

    @AfterAll static void close() throws Exception {
        try {
            if(context!=null) {
                for(long id:conversations) call("DELETE","/api/local/conversations/"+id,null);
                context.close();
            }
        } finally {
            var summary=new LinkedHashMap<String,Object>();
            summary.put("verifiedAt",java.time.Instant.now().toString()); summary.put("model",modelName);
            summary.put("connectionId",connectionId); summary.put("catalog","sys_demo");
            summary.put("baseline",Map.of("users",totalUsers,"disabledUsers",disabledUsers,"idPairs",totalPairs));
            summary.put("cases",evidence);
            boolean unchanged=originalFiles!=null && originalFiles.equals(fingerprints());
            summary.put("localConfigurationUnchanged",unchanged);
            Files.createDirectories(Path.of("target"));
            boolean explorerOnly=!evidence.isEmpty() && evidence.stream().allMatch(item->item.get("scenario").toString().startsWith("pi-explorer-"));
            String report="pi-mysql-live-summary.json";
            if(explorerOnly)report=evidence.stream().anyMatch(item->item.get("scenario").equals("pi-explorer-query"))
                    ?"pi-explorer-query-cancel-live-summary.json":"pi-explorer-mysql-live-summary.json";
            if(evidence.stream().anyMatch(item->item.get("scenario").equals("pi-explorer-natural")))
                report="pi-explorer-natural-live-summary.json";
            if(!evidence.isEmpty() && evidence.stream().allMatch(item->item.get("scenario").toString().startsWith("discovery-")))
                report="pi-discovery-mysql-live-summary.json";
            if(evidence.stream().anyMatch(item->item.get("scenario").toString().startsWith("comparison-"))) report="pi-readonly-comparison.json";
            Files.writeString(Path.of("target",report),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
            assertThat(unchanged).as("existing connection, credential, key and model files must stay unchanged").isTrue();
        }
    }

    static Map<String,String> fingerprints() throws Exception {
        var files=new TreeMap<String,String>();
        try(var paths=Files.walk(directory)) {
            for(var path:paths.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".lock")).toList())
                files.put(directory.relativize(path).toString(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return files;
    }
    static HttpRequest request(String method,String path,Object body) throws Exception {
        return HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(170))
                .header("Authorization","Bearer "+token).header("X-Local-Runtime-Id",runtime).header("Content-Type","application/json")
                .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
    }
    static JsonNode call(String method,String path,Object body) throws Exception {
        var response=HTTP.send(request(method,path,body),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP status for %s",path).isEqualTo(200);
        return JSON.readTree(response.body()).path("data");
    }
    static long create() throws Exception {
        long id=call("POST","/api/local/conversations",Map.of("connectionId",connectionId,"catalogName","sys_demo")).path("conversationId").asLong();
        conversations.add(id); return id;
    }
    static long scalar(String query) throws Exception {
        var result=call("POST","/api/db/sql/execute",Map.of("connectionId",connectionId,"catalog","sys_demo","sql",query,
                "maxRows",10,"timeoutMs",15000));
        assertThat(result.path("success").asBoolean()).isTrue();
        return result.path("rows").get(0).get(0).asLong();
    }

    @Test @Order(9) void bothEnginesDiscoverSourcesWithoutAnyDefaultTarget() throws Exception {
        for(String engine:List.of("java","pi")) {
            long id=call("POST","/api/local/conversations",Map.of()).path("conversationId").asLong();
            conversations.add(id);
            long started=System.nanoTime();
            String endpoint=engine.equals("pi")?"/api/local/pi/chat/stream":"/api/local/chat/stream";
            var response=HTTP.send(request("POST",endpoint,Map.of("conversationId",id,"message","帮看看当前数据有多少表","modelConfigId",modelId)),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var blocks=new ArrayList<JsonNode>();
            for(String line:response.body().split("\n"))if(line.startsWith("data:"))blocks.add(JSON.readTree(line.substring(5).trim()));
            var done=blocks.stream().filter(block->block.path("done").asBoolean()).toList();
            assertThat(done).hasSize(1);
            assertThat(JSON.readTree(done.getFirst().path("data").asText()).path("status").asText()).isEqualTo("SUCCEEDED");
            var names=new ArrayList<String>();
            for(var block:blocks)if(block.path("type").asText().equals("TOOL_CALL"))
                names.add(JSON.readTree(block.path("data").asText()).path("toolName").asText());
            assertThat(names).as("%s must discover sources instead of refusing an unscoped request",engine).contains("list_connections");
            for(var block:blocks)if(block.path("type").asText().equals("TOOL_RESULT")) {
                var tool=JSON.readTree(block.path("data").asText());
                if(tool.path("toolName").asText().equals("list_tables")) {
                    var result=JSON.readTree(tool.path("result").asText());
                    if(result.has("tables"))assertThat(result.path("target").path("catalogName").asText()).isNotBlank();
                }
            }
            assertThat(call("GET","/api/local/conversations/"+id,null).path("defaultTarget").path("connectionId").isNull()).isTrue();
            String answer=answer(blocks);
            evidence.add(Map.of("scenario","discovery-"+engine,"validated",true,"elapsedMs",(System.nanoTime()-started)/1_000_000,
                    "tools",names,"answer",answer.substring(0,Math.min(answer.length(),1000))));
        }
    }
    static List<JsonNode> turn(long id,String message,String name) throws Exception {
        return turn(id,message,name,"pi");
    }
    static List<JsonNode> turn(long id,String message,String name,String engine) throws Exception {
        long started=System.nanoTime();
        String endpoint=engine.equals("pi")?"/api/local/pi/chat/stream":"/api/local/chat/stream";
        var response=HTTP.send(request("POST",endpoint,Map.of("conversationId",id,"message",message,"modelConfigId",modelId)),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("Pi HTTP status").isEqualTo(200);
        var blocks=new ArrayList<JsonNode>();
        for(String line:response.body().split("\n")) if(line.startsWith("data:")) blocks.add(JSON.readTree(line.substring(5).trim()));
        var terminal=blocks.stream().filter(b -> b.path("done").asBoolean()).toList();
        assertThat(terminal).hasSize(1);
        var done=JSON.readTree(terminal.getFirst().path("data").asText());
        var record=new LinkedHashMap<String,Object>();
        record.put("validated",false);
        record.put("scenario",name); record.put("status",done.path("status").asText()); record.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
        record.put("statistics",done.path("statistics"));
        record.put("engine",engine); record.put("modelConfigId",modelId); record.put("model",modelName);
        var trace=new ArrayList<Map<String,Object>>();
        for(var block:blocks)if(block.path("type").asText().equals("TOOL_CALL")) {
            var tool=JSON.readTree(block.path("data").asText());
            var item=new LinkedHashMap<String,Object>();item.put("tool",tool.path("toolName").asText());
            item.put("nested",block.hasNonNull("parentToolCallId"));
            var args=JSON.readTree(tool.path("arguments").asText("{}"));
            for(String field:List.of("tableName","tableNames","tasks"))if(args.has(field))item.put(field,args.get(field));
            trace.add(item);
        }
        record.put("toolTrace",trace);
        record.put("observedToolCalls",trace.size());
        var sqls=new ArrayList<String>();
        for(var block:blocks) if(block.path("type").asText().equals("TOOL_CALL")) {
            var tool=JSON.readTree(block.path("data").asText());
            if(tool.path("toolName").asText().equals("query_database"))
                sqls.add(JSON.readTree(tool.path("arguments").asText()).path("query").asText());
        }
        record.put("querySql",sqls);
        record.put("answer",answer(blocks).substring(0,Math.min(600,answer(blocks).length())));
        record.put("queryResults",queryResults(blocks).stream().map(r -> {
            var result=new LinkedHashMap<String,Object>();
            for(String field:List.of("executionId","success","statementState","transactionOutcome","truncated")) result.put(field,r.path(field));
            result.put("returnedRows",rows(r).size()); return result;
        }).toList());
        evidence.add(record);
        if(name.equals("follow-up-count")) for(var result:queryResults(blocks)) {
            record.put("aggregateHeaders",result.path("result").path("headers"));
            record.put("aggregateRows",rows(result));
        }
        assertThat(done.path("status").asText()).isEqualTo("SUCCEEDED");
        if(engine.equals("pi")) assertThat(done.path("engine").asText()).isEqualTo("PI");
        return blocks;
    }
    static List<JsonNode> queryResults(List<JsonNode> blocks) throws Exception {
        var results=new ArrayList<JsonNode>();
        for(var block:blocks) if(block.path("type").asText().equals("TOOL_RESULT")) {
            var tool=JSON.readTree(block.path("data").asText());
            if(tool.path("toolName").asText().equals("query_database")) results.add(JSON.readTree(tool.path("result").asText()));
        }
        return results;
    }
    static JsonNode rows(JsonNode result) {
        var response=result.has("result")?result.path("result"):result;
        return response.path("rows").isArray()?response.path("rows"):response.path("resultSet").path("rows");
    }
    static String answer(List<JsonNode> blocks) {
        return blocks.stream().filter(b -> b.path("type").asText().equals("TEXT")).map(b -> b.path("data").asText()).collect(java.util.stream.Collectors.joining());
    }
    static void assertCount(List<JsonNode> blocks,long expected) throws Exception {
        var results=queryResults(blocks); assertThat(results).isNotEmpty();
        var result=results.getLast(); assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(result.path("target").path("catalogName").asText()).isEqualTo("sys_demo");
        assertThat(rows(result).get(0).get(0).asLong()).isEqualTo(expected);
        assertThat(result.path("truncated").asBoolean()).isFalse();
        assertThat(answer(blocks)).containsPattern("(?<!\\d)"+expected+"(?!\\d)");
        evidence.getLast().put("validated",true);
    }

    static long disabledCount(JsonNode result) {
        var data=rows(result); var headers=result.path("result").path("headers");
        assertThat(data.isArray()).isTrue();
        if(data.size()==1 && data.get(0).size()==1) return data.get(0).get(0).asLong();
        int status=-1,conditional=-1;
        for(int i=0;i<headers.size();i++) {
            String header=headers.get(i).asText().toLowerCase(Locale.ROOT);
            if(header.equals("status")) status=i;
            if(header.matches(".*(disabled|inactive|status_?0|停用|禁用).*")) conditional=i;
        }
        if(status>=0 && headers.size()==2) {
            int count=1-status;
            for(var row:data) if(row.get(status).asInt()==0) return row.get(count).asLong();
            throw new AssertionError("status=0 group missing");
        }
        if(conditional>=0 && data.size()==1) return data.get(0).get(conditional).asLong();
        throw new AssertionError("Aggregate shape needs semantic review; do not assume the first cell is the count");
    }


    /** Same main model, data and explicit query. Explorer uses its existing purpose binding. */
    @Test @Order(10) @Timeout(value=360,unit=TimeUnit.SECONDS)
    @EnabledIfEnvironmentVariable(named="DATA_AGENT_PI_COMPARE_LIVE",matches="true")
    void compareReadOnlyEnginesAgainstTheSameIndependentFacts() throws Exception {
        String explorerId=null;
        for(var binding:call("GET","/api/ai/model-bindings",null))
            if(binding.path("purpose").asText().equals("EXPLORER")) explorerId=binding.path("modelConfigId").asText();
        assertThat(explorerId).as("configured Explorer model").isNotBlank();
        // Two repetitions are a smoke comparison. Distribution/cost acceptance needs the full corpus.
        for(int repetition=1;repetition<=2;repetition++) {
            for(String arm:List.of("java","pi-direct","pi-explorer")) {
                String question="请实际执行 SELECT COUNT(*) FROM sys_demo.sys_user，只报告核实的总行数，禁止查询个人明细。";
                if(arm.equals("pi-explorer")) question+="请通过 callingExplorerSubAgent 委派一个 general 模式的任务执行查询。connectionId="+connectionId+", catalogName=sys_demo，timeoutSeconds=45。";
                else question+="请直接使用 query_database 完成，不委派子助手。";
                var events=turn(create(),question,"comparison-"+arm+"-"+repetition,arm.equals("java")?"java":"pi");
                var record=evidence.getLast();
                record.put("validated",false);
                record.put("arm",arm); record.put("repetition",repetition);
                record.put("explorerModelConfigId",explorerId);
                record.put("expectedUsers",totalUsers);
                record.put("queryTimeoutMs",15000); record.put("queryMaxRows",200);
                record.put("comparisonScope","smoke; engine toolsets and Explorer model binding differ");
                if(arm.equals("java")) {
                    var result=queryResults(events).getLast();
                    assertThat(result.path("success").asBoolean()).isTrue();
                    assertThat(rows(result).get(0).get(0).asLong()).isEqualTo(totalUsers);
                    assertThat(answer(events)).containsPattern("(?<!\\d)"+totalUsers+"(?!\\d)");
                    record.put("identityEvidence","request target; Java result omits executionId and target");
                } else assertCount(events,totalUsers);
                record.put("validated",false);
                boolean delegated=events.stream().anyMatch(event->event.path("type").asText().equals("SUB_AGENT_START"));
                assertThat(delegated).as("requested execution arm %s",arm).isEqualTo(arm.equals("pi-explorer"));
                var results=queryResults(events);
                assertThat(results).hasSize(1);
                if(!arm.equals("java")) assertThat(results.getFirst().path("executionId").asText()).isNotBlank();
                record.put("validated",true);
            }
        }
    }

    @Test @Order(11) void anEmptyResultIsReportedWithoutInventingRows() throws Exception {
        var events=turn(create(),"请只执行一次 SELECT id FROM sys_demo.sys_user WHERE 1=0。说明返回行数，不推测任何用户记录。","empty-result");
        var results=queryResults(events);
        assertThat(results).hasSize(1);
        var result=results.getFirst();
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(rows(result)).isEmpty();
        assertThat(result.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(answer(events)).containsPattern("0|空|没有|无记录|未返回");
        evidence.getLast().put("validated",true);
    }

    @Test @Order(12) void ambiguousBusinessMeaningIsClarifiedBeforeCounting() throws Exception {
        var events=turn(create(),"请统计本月活跃用户数量，并说明采用的统计口径。","ambiguous-business-meaning");
        assertThat(queryResults(events)).as("Unconfirmed active-user definition must not become a query").isEmpty();
        assertThat(events.stream().filter(event->event.path("type").asText().equals("TOOL_CALL"))).isEmpty();
        assertThat(answer(events)).contains("活跃").containsPattern("定义|口径|确认|请问|哪种");
        assertThat(answer(events)).containsPattern("年月|日期|时间范围|起止");
        evidence.getLast().put("validated",true);
    }

    @Test @Order(1) void discoversStructureAndCountsUsersAgainstDirectSql() throws Exception {
        var blocks=turn(conversation,"请先列出当前目标库的表并读取 sys_user 的字段定义，再通过只读聚合查询统计 sys_user 总行数。只报告实际总数，不查询个人明细。","discovery-and-count");
        assertThat(blocks.stream().filter(b -> b.path("type").asText().equals("TOOL_CALL")).map(b -> b.path("data").asText()).toList())
                .anyMatch(t -> t.contains("list_tables")).anyMatch(t -> t.contains("describe_table"));
        assertCount(blocks,totalUsers);
    }
    @Test @Order(2) void followUpKeepsTheSameTableAndConnection() throws Exception {
        var blocks=turn(conversation,"其中 status=0 的有多少？沿用刚才的表和目标，通过只读聚合查询核实，不要查明细。","follow-up-count");
        var results=queryResults(blocks); assertThat(results).isNotEmpty();
        var result=results.getLast();
        assertThat(result.path("success").asBoolean()).isTrue(); assertThat(result.path("truncated").asBoolean()).isFalse();
        assertThat(result.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
        assertThat(result.path("target").path("catalogName").asText()).isEqualTo("sys_demo");
        assertThat(disabledCount(result)).isEqualTo(disabledUsers);
        assertThat(answer(blocks)).containsPattern("(?<!\\d)"+disabledUsers+"(?!\\d)");
        evidence.getLast().put("validated",true);
    }
    @Test @Order(3) void realDriverReturnsAtMost200RowsAndReportsTruncation() throws Exception {
        String query="SELECT u.id AS user_id, rp.role_id, rp.permission_id FROM sys_demo.sys_user u CROSS JOIN sys_demo.sys_role_permission rp ORDER BY u.id,rp.role_id,rp.permission_id";
        var results=queryResults(turn(create(),"请仅执行下面这条只读 SQL 一次，不添加或修改 LIMIT，不查询额外明细。完成后只说明返回行数和是否截断，不能把返回行数当成全量行数：\n"+query,"driver-row-limit"));
        assertThat(results).hasSize(1);
        var result=results.getFirst(); assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("truncated").asBoolean()).isTrue();
        assertThat(rows(result).size()).isEqualTo(200);
        evidence.getLast().put("validated",true);
    }
    @Test @Order(4) void missingTableIsARealQueryFailureWithoutRetry() throws Exception {
        String table="pi_live_missing_"+UUID.randomUUID().toString().replace("-","");
        var results=queryResults(turn(create(),"这是只读错误处理验收。请只执行下面的 SELECT 一次，出错后直接解释错误并结束，不改表名、不重试：SELECT COUNT(*) FROM sys_demo."+table,"missing-table"));
        assertThat(results).hasSize(1); assertThat(results.getFirst().path("success").asBoolean()).isFalse();
        assertThat(results.getFirst().path("statementState").asText()).isEqualTo("FAILED");
        evidence.getLast().put("validated",true);
    }
    @Test @Order(6) void piExplorerDiscoversTheRealSchemaUsingItsConfiguredModel() throws Exception {
        String explorerId=null,explorerName=null;
        for(var binding:call("GET","/api/ai/model-bindings",null))if(binding.path("purpose").asText().equals("EXPLORER"))explorerId=binding.path("modelConfigId").asText();
        for(var model:call("GET","/api/ai/model-configs",null))if(model.path("id").asText().equals(explorerId))explorerName=model.path("modelId").asText();
        assertThat(explorerName).isNotBlank();
        String question="请调用 callingExplorerSubAgent，恰好派发一个Explorer任务，connectionId="+connectionId+"，catalogName=sys_demo，timeoutSeconds=45。"
                +"任务：先用list_tables确认sys_user表存在，再用describe_table读取sys_user字段；只读元数据，不执行SQL。"
                +"子任务最后返回summaryText和objects的JSON，objects包含实际读取到的sys_user，objectType=TABLE，objectDdl=null。"
                +"主助手收到taskResults后，简要说明验证了哪些字段。必须实际调用工具，不要直接猜测结构。";
        var events=turn(create(),question,"pi-explorer-metadata");
        var complete=events.stream().filter(event->event.path("type").asText().equals("SUB_AGENT_COMPLETE")).toList();
        assertThat(complete).hasSize(1);
        var data=JSON.readTree(complete.getFirst().path("data").asText());
        var result=JSON.readTree(data.path("resultJson").asText());
        assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("objects").toString()).contains("sys_user","sys_demo");
        var nestedTools=new ArrayList<String>();
        for(var event:events)if(event.hasNonNull("parentToolCallId") && event.path("type").asText().equals("TOOL_RESULT")) {
            var tool=JSON.readTree(event.path("data").asText());assertThat(tool.path("error").asBoolean()).isFalse();
            nestedTools.add(tool.path("toolName").asText());
        }
        assertThat(nestedTools).contains("list_tables","describe_table");assertThat(queryResults(events)).isEmpty();
        evidence.getLast().put("explorerModel",explorerName);evidence.getLast().put("nestedTools",nestedTools);
        evidence.getLast().put("taskStatus",result.path("status").asText());evidence.getLast().put("validated",true);
    }

    @Test @Order(7) void naturalMultiTableQuestionDelegatesAndKeepsExplorationFocused() throws Exception {
        String question="帮我梳理一下 sys_demo 库里的用户和权限相关表，看看用户、角色是怎么关联的。根据实际表结构说明主键和关联字段，区分数据库已定义的外键和推测的关系，最后画一张 Mermaid ER 图。";
        long id=call("POST","/api/local/conversations",Map.of()).path("conversationId").asLong();conversations.add(id);
        var events=turn(id,question,"pi-explorer-natural");
        var delegated=new ArrayList<JsonNode>();
        var described=new HashSet<String>();
        int nestedQueries=0;
        for(var event:events)if(event.path("type").asText().equals("TOOL_CALL")) {
            var tool=JSON.readTree(event.path("data").asText());
            String name=tool.path("toolName").asText();
            boolean nested=event.hasNonNull("parentToolCallId");
            if(name.equals("callingExplorerSubAgent"))delegated.add(tool);
            if(name.equals("describe_table") || name.equals("describe_tables")) {
                assertThat(nested).as("Main assistant must delegate metadata exploration").isTrue();
                var args=JSON.readTree(tool.path("arguments").asText());
                if(name.equals("describe_table"))described.add(args.path("tableName").asText());
                else for(var table:args.path("tableNames"))described.add(table.asText());
            }
            if(name.equals("query_database")) {
                assertThat(nested).as("Relationship verification belongs to Explorer").isTrue();
                nestedQueries++;
            }
        }
        assertThat(delegated).hasSize(1);
        var tasks=JSON.readTree(delegated.getFirst().path("arguments").asText()).path("tasks");
        assertThat(tasks.size()).isEqualTo(1);
        assertThat(tasks.get(0).path("mode").asText()).isEqualTo("schema");
        assertThat(tasks.get(0).path("catalogName").asText()).isEqualTo("sys_demo");
        assertThat(described).contains("sys_user","sys_role");
        assertThat(described.size()).isLessThanOrEqualTo(4);
        assertThat(nestedQueries).isZero();
        var completed=events.stream().filter(event->event.path("type").asText().equals("SUB_AGENT_COMPLETE")).toList();
        assertThat(completed).hasSize(1);
        var completion=JSON.readTree(completed.getFirst().path("data").asText());
        var childResult=JSON.readTree(completion.path("resultJson").asText());
        assertThat(childResult.path("structures").size()).isBetween(2,4);
        for(var structure:childResult.path("structures")) {
            assertThat(structure.path("columns").isArray()).isTrue();
            assertThat(structure.path("foreignKeys").path("available").asBoolean()).isTrue();
        }
        assertThat(events.stream().filter(event->event.path("type").asText().equals("SUB_AGENT_ERROR"))).isEmpty();
        assertThat(answer(events)).contains("erDiagram");
        evidence.getLast().put("describedTables",described);
        evidence.getLast().put("nestedQueries",nestedQueries);
        evidence.getLast().put("delegations",delegated.size());
        evidence.getLast().put("validated",true);
    }

    @Test @Order(8) void piExplorerCountsRealUsersThroughTheSharedReadOnlyQuery() throws Exception {
        String question="请实际调用 callingExplorerSubAgent，派发一个任务，connectionId="+connectionId+"，catalogName=sys_demo，timeoutSeconds=45。"
                +"任务先用describe_table确认sys_user结构，再用query_database执行SELECT COUNT(*) FROM sys_demo.sys_user，"
                +"只查询聚合计数，最后返回含summaryText及objects的JSON；summaryText必须包含实际计数，objects的objectDdl=null。主助手据此简短回答。";
        var events=turn(create(),question,"pi-explorer-query");
        assertThat(events.stream().filter(event->event.path("type").asText().equals("SUB_AGENT_COMPLETE"))).hasSize(1);
        var results=queryResults(events);assertThat(results).isNotEmpty();
        for(var result:results) {
            assertThat(result.path("success").asBoolean()).isTrue();
            assertThat(result.path("target").path("connectionId").asLong()).isEqualTo(connectionId);
            assertThat(result.path("target").path("catalogName").asText()).isEqualTo("sys_demo");
            assertThat(result.path("maxRows").asInt()).isEqualTo(200);assertThat(result.path("timeoutMs").asInt()).isEqualTo(15000);
            assertThat(rows(result).get(0).get(0).asLong()).isEqualTo(totalUsers);
        }
        evidence.getLast().put("actualUsers",totalUsers);evidence.getLast().put("validated",true);
    }

    @Test @Order(9) void cancellingTheParentStopsAnActiveRealPiExplorer() throws Exception {
        long id=create(),started=System.nanoTime();
        String question="请实际调用 callingExplorerSubAgent，派发一个任务，connectionId="+connectionId+"，catalogName=sys_demo，timeoutSeconds=45。"
                +"任务先用list_tables发现sys_user，再describe_table读取字段，然后逐项解释全部字段并返回结构化JSON；不要执行SQL。";
        var response=HTTP.send(request("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"modelConfigId",modelId,"message",question)),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        boolean cancelled=false;String taskId=null;var done=new ArrayList<JsonNode>();
        try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null)if(line.startsWith("data:")) {
                var block=JSON.readTree(line.substring(5).trim());
                if(!cancelled && block.hasNonNull("parentToolCallId") && block.path("type").asText().equals("TOOL_CALL")) {
                    taskId=block.path("subAgentTaskId").asText();
                    assertThat(call("POST","/api/local/conversations/"+id+"/cancel",null).path("status").asText()).isEqualTo("CANCEL_REQUESTED");cancelled=true;
                }
                if(block.path("done").asBoolean())done.add(JSON.readTree(block.path("data").asText()));
            }
        }
        assertThat(cancelled).isTrue();assertThat(taskId).startsWith("pi-explore-");assertThat(done).hasSize(1);
        assertThat(done.getFirst().path("status").asText()).isEqualTo("CANCELLED");
        evidence.add(Map.of("scenario","pi-explorer-parent-cancel","status","CANCELLED","doneBlocks",1,"taskId",taskId,"validated",true,
                "elapsedMs",(System.nanoTime()-started)/1_000_000));
    }

    @Test @Order(5) void cancellationDuringRealModelStreamingProducesOneCancelledDone() throws Exception {
        long started=System.nanoTime();
        long id=create();
        var response=HTTP.send(request("POST","/api/local/pi/chat/stream",Map.of("conversationId",id,"modelConfigId",modelId,
                "message","不要访问数据库。请用不少于2000字逐步讲解 SELECT COUNT(*) FROM sys_demo.sys_user 的语法和统计含义。")),HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        boolean cancelled=false; var done=new ArrayList<JsonNode>();
        try(var reader=new BufferedReader(new InputStreamReader(response.body(),StandardCharsets.UTF_8))) {
            String line;
            while((line=reader.readLine())!=null) if(line.startsWith("data:")) {
                var block=JSON.readTree(line.substring(5).trim());
                if(!cancelled && block.path("type").asText().equals("TEXT")) {
                    assertThat(call("POST","/api/local/conversations/"+id+"/cancel",null).path("status").asText()).isEqualTo("CANCEL_REQUESTED");
                    cancelled=true;
                }
                if(block.path("done").asBoolean()) done.add(JSON.readTree(block.path("data").asText()));
            }
        }
        assertThat(cancelled).isTrue(); assertThat(done).hasSize(1);
        assertThat(done.getFirst().path("status").asText()).isEqualTo("CANCELLED");
        evidence.add(Map.of("scenario","real-model-stream-cancel","status","CANCELLED","doneBlocks",1,"validated",true,
                "elapsedMs",(System.nanoTime()-started)/1_000_000));
    }
}
