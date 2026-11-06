# PI-M3：Pi Explorer 任务闭环

日期：2026-11-04。承接 [只读查询](PI-M2-READONLY-QUERY-2026-11-04.md)。本批完成本地 Pi 主助手派发 Explorer 子任务的第一版；Java 助手仍是默认入口，Pi 仍需 `app.local.pi.enabled=true`。

## 调用链

主 worker 新增 `callingExplorerSubAgent`：`tasks` 包含显式 `connectionId`、聚焦的 `instruction`，可带 `catalogName`、`schemaName`、`context` 和 `timeoutSeconds`。默认目标仅补全同一连接的缺省字段。

Java 验证整批任务、规范化目标并固定连接配置版本，再为每项任务创建独立的短生命周期 Pi worker。子任务使用该轮开始时固定的 `ModelPurpose.EXPLORER` 模型及其凭证，而非当前主对话模型；不会改动用途绑定或保存新的模型配置。子 worker 只有本任务上下文，不继承主 worker 的完整消息历史。

子 worker 以 `role=explorer` 启动，只注册 `list_tables`、`describe_table`、`query_database`；不开放递归委派、写入或任意进程工具。底层继续复用 Java 的连接访问检查、版本校验、元数据服务及 `LocalReadOnlyQuery`。子任务的工具目标字段不能切换连接、catalog 或 Schema。执行连接限制不等同于 SQL 对象权限沙箱；数据库本身的账号权限仍然适用。

## 结果和界面

- 复用 `ExplorerTaskResult` / `ExploreObject` 结构，返回 `taskResults`，每项有独立 `taskId`、`SUCCESS/ERROR`、`summaryText`、`objects`、`rawResponse` 或稳定错误码。
- 模型最终输出经现有 Explorer JSON 解析器处理；返回的表必须实际出现在本任务成功的元数据读取中，catalog/Schema 必须符合任务目标。未观察到的表及伪造 DDL 会使任务失败。当前只交付表对象，`objectDdl` 不提供生成内容。
- 父工具结果最多 12000 字符；需要缩减原始响应或对象列表时保留合法 JSON，并设置外层 `truncated=true`。主助手拿到结果后继续生成，不将子代理结束当作整轮结束。
- 生命周期复用 `SUB_AGENT_START/PROGRESS/COMPLETE/ERROR`，嵌套工具事件携带 `parentToolCallId` 和 `subAgentTaskId`。本地助手保留这些事件供现有子代理视图展示。
- 修复本地启动握手丢弃 `piAssistant` 能力的问题：保留并验证服务端返回的 Pi 状态，兼容未提供该可选字段的旧服务。
- 主助手提示词同步移除对已删除“默认目标”控件的引导。

## 预算和生命周期

- 每次委派 1–2 项任务，当前顺序执行；同一主运行累计最多 4 项。尚未实现并行 Explorer 调度。
- 主 worker 最多 8 次模型请求、32 次工具请求；每个子任务最多 6 次模型请求、12 次工具请求。模型预算在真正发起上游请求前检查，工具预算在发给 Java 前检查；超额后不再发起模型请求。
- 子任务默认 30 秒，可显式指定 1–45 秒；仍受整个前台运行 150 秒超时约束。只读 SQL 保持 15 秒 / 200 行限制。
- 子任务超时停止该子 worker，并取消其正在执行的 SQL，将任务错误交回父助手。父对话取消、关闭或服务销毁则取消所有在途子任务和查询。终态后不交付迟到结果，不换到 Java 引擎重放。
- 主运行统计合并子任务的模型调用、工具调用和 token 用量；未收到完整子任务统计时 `usageComplete=false`。

## 验证

```sh
cd Data-Agent-Pi-Worker
npm test

cd ..
mvn -pl data-agent-server-app -am test \
  -Dtest=LocalPiChatHttpTest,LocalPiExplorerWorkerHttpTest \
  -Dsurefire.failIfNoSpecifiedTests=false

cd ../Data-Agent-Client
npm test -- src/lib/localRuntime.test.ts \
  src/components/ai/LocalAssistant.context.test.tsx \
  src/components/ai/blocks/subAgentDataHelpers.test.ts
npm run build
```

- Node 17 项通过：真实 worker、脚本化模型接口，覆盖委派结果后继续回答、Explorer 工具集合、模型/工具预算、取消、多轮和协议边界。
- HTTP 31 项通过：包含真实生产 worker 的完整主助手 → Explorer → Java 元数据 → 父助手闭环。两个模型使用不同配置及测试凭证，本地 TLS 模型夹具校验请求使用正确模型和凭证，保持证书验证开启。该夹具的 Node 启动包装使用 POSIX 权限，Windows 适配另行验收。
- HTTP 协议夹具还覆盖两个任务的目标隔离、越界工具目标、非法整批输入、伪造对象、子 worker 崩溃、独立模型接口不兼容、子任务超时取消 SQL、父对话取消及唯一 DONE。
- 前端 41 项通过；TypeScript 检查及生产构建通过。构建仍有已有的 Browserslist 数据过期和产物体积提示。

## 真实 MySQL 与模型验收

复用现有本地连接 `1` 的 `sys_demo`，主模型 `qwen3.8-max`，EXPLORER 用途配置 `qwen3.6-flash`。测试使用独立临时后端实例及真实生产 worker，结束后释放临时会话与进程，不重启用户正在使用的后端。

| 场景 | 结果 | 耗时 |
| --- | --- | --- |
| 主助手委派单个 Explorer，列出表并读取 `sys_user` 字段 | 子任务 `SUCCESS`，确认 10 个字段，主助手继续回答；没有执行 SQL | 27.429 秒 |
| Explorer 读取结构并执行聚合查询 | `SELECT COUNT(*) FROM sys_demo.sys_user` 返回 12，与直接 SQL 基线一致；保留执行编号、真实目标、200 行 / 15 秒限制 | 24.203 秒 |
| 子代理开始调用工具后取消父对话 | 恰好一个 `CANCELLED` DONE，无跨引擎重跑 | 26.140 秒（含等待真实模型委派与首次工具调用） |

元数据场景模型调用 5 次、工具调用 3 次，统计字段完整。后两个场景在独立一次验收中执行，两项全部通过。连接、凭证、密钥及模型文件的前后 SHA-256 指纹一致；未进行业务数据写入。真实 SQL 在途取消另由共享查询与确定性 HTTP 夹具覆盖，此处真实取消场景验证的是已启动的 Explorer 工具阶段。

结果文件：

- `Data-Agent-Server/data-agent-server-app/target/pi-explorer-mysql-live-summary.json`
- `Data-Agent-Server/data-agent-server-app/target/pi-explorer-query-cancel-live-summary.json`

显式复跑三项 Explorer 实测：

```sh
cd Data-Agent-Server
DATA_AGENT_PI_MYSQL_LIVE=true \
DATA_AGENT_PI_LOCAL_DIRECTORY=/absolute/path/to/existing/local-directory \
mvn -pl data-agent-server-app -am test \
  '-Dtest=LocalPiMySqlLiveTest#piExplorer*+cancellingTheParentStopsAnActiveRealPiExplorer' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

不宣称与旧 Java Explorer 的搜索、视图、DDL、并行探索完全等价；Planner、写入、Pi 压缩和长期记忆不在本批范围。Anthropic 适配仍未做真实厂商验收。
