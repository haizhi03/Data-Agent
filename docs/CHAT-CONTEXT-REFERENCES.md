# desktop-pi 当前交互（2026-11-04）

- 移除输入区常驻的“默认目标：未指定 / 设置默认目标”栏及手动选择弹窗。
- 首次发送前，连接/catalog/Schema 自动跟随当前 SQL 或表数据标签页；发送时复制并固定为会话目标。Plan、子代理控制台及 ER 页不提供隐式目标。
- 表数据页使用实际 catalog/schema 字段，避免把 DM 的展示用 databaseName 当成独立数据库；MySQL 不制造独立 Schema。
- 已有对话不随工作区标签切换而更换数据库；新建对话关闭旧会话、清空草稿和本轮引用，并重新继承当前工作区目标。创建请求等待期间也固定已提交目标，失败后允许重新继承。
- 有目标时仅显示简短的连接及库名标签，无目标不显示占位栏。各个 `@` 引用仍保留完整身份；删除 token 后不再随请求发送。不增加数据库连接或修改业务数据。
- 无目标也可访问发现工具：Java / Pi 主助手先用 `list_connections` 列出已配置数据源，再用 `list_namespaces` 发现数据库或 Schema。唯一候选或用户点名的候选可继续；多个无法判断的候选按名称询问，无需提供连接 ID 或先设置默认目标。列表和字段读取必须具有完整数据库/Schema 范围，避免 JDBC 空 catalog 混合多个库。发现结果不更改会话默认目标。
- 后端会话目标和请求快照契约保留；以下合入记录及主分支实现说明为历史记录，其中手动目标选择交互已由上述规则替代。

# desktop-pi 合入记录

2026-10-29：已将本次修复适配并合入 `feature/desktop-pi` 工作区，完成验证后按用户要求提交至本地分支，不推送远程。下文主分支实现记录保留为来源说明；以下为当前实际生效的差异：

- 实际入口是 `LocalAssistant`，默认目标保存在本地内存会话；新增 `GET /api/local/conversations/{id}` 和 `PUT /api/local/conversations/{id}/default-target`。首次创建接受规范字段和旧 catalog/schema 别名。
- 实际聊天请求携带默认目标及引用快照，本地模型收到相同的版本化 UserMessage。关闭、重启后不恢复完整聊天，继续遵循 desktop-pi 的现有产品规则；没有重新引入账号、旧历史页面或应用 PostgreSQL。
- 共享候选选择、完整引用展示、DTO、编码与服务端校验已经迁入。本地只读工具支持明确的连接/catalog/Schema 参数，默认目标只补全缺省字段。执行继续校验本地连接访问与配置版本，并保持只读 SQL、超时和行数限制。
- 写入提议仍绑定不可变 SQL、目标和连接版本，仍需原有一次性审批。更改默认目标不重定向已有提议；连接变更按提议自身目标失效。
- 主分支已经被 desktop-pi 删除的历史页面、运行时 Hook 和 ORM Service 实现没有恢复。V9 源脚本仅保留作主分支参考，当前本地运行不需要执行它，也不需要应用数据库。
- 合入后验证：前端 50 个文件 / 239 项通过，TypeScript 检查与生产构建通过；后端定向回归 77 项通过（含真实 HTTP/SSE、本地模型请求捕获、跨连接工具参数、目标隔离和审批绑定），后端打包通过。没有调用真实模型厂商或业务数据库。
- 本批原始主分支改动另存 `/private/tmp/data-agent-chat-context-original-backup.tar.gz`，原始 tracked diff 为 `/private/tmp/data-agent-chat-context-original.patch`。仅在代码已迁移和验证后删除临时 worktree 与 `fix/chat-target-mentions` 分支。

后端合入验收命令：

```sh
mvn -pl data-agent-server-app -am test \
  -Dtest=LocalAssistantHttpTest,LocalAssistantPolicyTest,LocalWritePolicyTest,LocalWriteExecutorTest,ChatContextChainTest,ChatSessionModelSnapshotTest,SearchObjectsToolTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:/path/to/mockito-core-5.20.0.jar
```

日志：`/private/tmp/desktop-chat-frontend.log`、`/private/tmp/desktop-chat-tests.log`、`/private/tmp/desktop-chat-client-build.log`、`/private/tmp/desktop-chat-package.log`。

---

# 聊天默认目标与本轮引用改造

日期：2026-10-29。基线为最新 `origin/main` 的 `cc806751412f5a6289a407bbac225d9f959e319c`，实施前和收尾均 fetch 核对。修复保留在 `fix/chat-target-mentions` 的独立工作目录 `/private/tmp/data-agent-chat-target-mentions`，没有暂存、提交、推送或创建 PR；原 `feature/desktop-pi` 工作区保留。

## 数据和接口

- 默认目标只含 `connectionId / catalogName / schemaName`，不绑定表，不构成授权或范围限制。
- 现有 `ai_conversation` 增加 `default_connection_id / default_catalog_name / default_schema_name` 三列，详情和列表响应增加 `defaultTarget`。旧会话不推测回填。
- 新增 `PUT /api/conversations/{id}/default-target`，明确设置时才更新；`{}` 清空目标。保留现有会话和连接访问检查，原标题更新接口不变。
- 延续主分支首次发消息时创建会话的方式。未发送草稿只在内存；首次请求保存所选目标，后续聊天的目标是本轮快照，不覆盖已保存目标，避免旧排队消息回写目标。
- 聊天规范字段为 `catalogName / schemaName`，兼容 `catalog / schema` 输入，规范字段优先。新客户端发送 `defaultTarget` 完整快照；省略时兼容旧平铺字段或读取会话保存值。
- 根据插件能力处理不适用层级：MySQL 不制造独立 Schema，DM 不制造独立数据库；对象搜索也支持无 catalog 的 Schema。显式选择其他数据库时不继承原数据库的 Schema。

## 选择、传递和展示

`useMention` 的浏览、返回、取消和确认只影响候选导航及本轮引用。点击名称或右方向键展开；Enter 或“引用”按钮确认当前层级。连接、数据库、Schema、对象都可形成引用；同名对象必要时使用完整路径或类型/连接 ID 区分。

`AIAssistant` 发送时过滤已删除 token；现有 `useConversationRuntime` 按会话保存目标，在提交/排队时复制目标和引用。历史目标通过现有详情 API 恢复；晚到响应不会改动其他会话。默认目标在输入区域持续展示，设置弹窗仅保存草稿，明确点击应用或新建才生效。普通新会话继承目标并清空输入及本轮引用。

服务端再次过滤已删除 token，检查类型、长度、必要对象范围、重复 token 和连接权限，并使用服务端连接名称。`ChatSessionFactory` 直接复用 `ExplicitReferencesStrategy`，把目标、引用和原问题放入真实 UserMessage；规范化目标同时进入已有 RequestContext 和 InvocationParameters。没有重新启用 RuntimeContextManager、旧记忆链或预先注入整库 DDL。

UserMessage 以 `[Data-Agent turn context v1]` 开头，后接 JSON，以下为字段摘录：

```json
{
  "defaultTarget": {"connectionId": 1, "catalogName": "sales", "schemaName": null},
  "userMentions": [
    {"token": "@orders", "connectionId": 1, "connectionName": "development", "catalogName": "sales", "schemaName": null, "objectType": "TABLE", "objectName": "orders"},
    {"token": "@warehouse/ARCHIVE/orders", "connectionId": 2, "connectionName": "warehouse", "catalogName": null, "schemaName": "ARCHIVE", "objectType": "TABLE", "objectName": "orders"}
  ],
  "message": "compare @orders and @warehouse/ARCHIVE/orders"
}
```

完整消息还包含解析规则和已有策略生成的引用段。默认目标仅补全未指定范围，各引用按自己的完整身份解析。名称、引用和消息属于数据，不是系统指令或授权；结构详情由模型按任务调用原工具取得。SQL 写入审批和授权机制没有修改。

## 消息历史

当前轮信息随实际 UserMessage 进入现有 JSON 消息存储，无需新消息表。新格式不被 MemoryUtil 剥离；历史转换器解码后返回原短文本、userMentions 和该轮目标快照。前端历史 API 映射保留元数据，草稿与用户消息显示完整路径，提示还包含对象类型和连接 ID。后续模型轮次从原消息历史继续读取身份。

旧消息若从未保存身份，无法可靠恢复引用归属；保持原文本，不猜测。

## 部署前变更

按现有 SQL 脚本管理方式，先对应用数据库执行：

`Data-Agent-Server/data-agent-server-app/src/main/resources/db/V9__conversation_default_target.sql`

脚本幂等增加三列，不推测回填、不删除数据。本次未连接数据库或执行此脚本；前后端需配套更新。

## 验证结果

- `npm test`：55 个文件，258 项通过；含真实 Hook、助手发送处理器、目标控件、历史 API 映射和引用展示测试。
- `npx tsc --noEmit`：通过。
- 后端定向回归：Maven 汇总 55 项通过，含新增 10 项链路及边界用例。使用真实 LangChain4j AiServices 捕获 StreamingChatModel 实际请求，触发工具调用并检查 GetObjectDetailTool 到 DiscoveryService 的参数，消息经过真实序列化/反序列化。仓储、权限与数据库服务使用 mock。
- `mvn -pl data-agent-server-app -am package -Dmaven.test.skip=true`：通过。
- `git diff --check`：通过，暂存区为空。

后端复跑命令（在 Data-Agent-Server 下）：

```sh
mvn -pl data-agent-server-app -am test \
  -Dtest=ChatContextChainTest,ChatSessionModelSnapshotTest,ChatServiceImplTest,AgentManagerTest,RuntimeContextManagerTest,MemoryUtilTest,AiMessageServiceImplTest,SearchObjectsToolTest,RequestContextTest,AgentToolConfigTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:/path/to/mockito-core-5.20.0.jar
```

本机显式代理位于 `/Users/huanghaizhi/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar`，避免 Mockito 自动附加受限。总数按 Maven 汇总，包含嵌套测试；现有父 XML 会覆盖部分单项计数。

覆盖跨连接/Schema 同名表、同轮多对象、浏览返回取消、无自动新会话、会话隔离和重新挂载恢复、Schema 新旧字段、历史身份、显式新目标会话、删除引用、队列快照、晚到候选、DM 无 catalog 搜索、跨数据库不继承旧 Schema、Plan 继续保留身份及权限拒绝。

### 原有基线问题和验证边界

- 前端生产构建缺少 `src/assets/fonts/NotoSansSC-Regular.ttf`，由 erDiagramPdf.ts 引用。在未修改的 origin/main 独立快照上构建同样失败，本次没有用替代字体或修改 PDF 功能。
- 扩大后端回归时，ExecuteSqlToolTest/GetObjectDetailToolTest 有 7 项英文预期与中文提示不符。未修改主分支快照运行同样 13 项，复现完全相同的 7 项失败；没有修改业务提示或放宽断言。基线日志 `/private/tmp/chat-context-backend-baseline.log`。
- 没有真实厂商、业务库、浏览器端到端或生产部署验证；mock 通过不能替代这些环境验收。
