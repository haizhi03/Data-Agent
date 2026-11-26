# Data-Agent-Pi-Worker

Data-Agent 本地助手的实验性 Pi（pi-agent-core）Worker。由 Java 宿主按临时会话启动，
通过 stdio 传输每行一个 JSON 消息；stdout 只输出协议消息，诊断日志只写 stderr。
会话消息只在进程内存中，不落盘。

本模块与 `Data-Agent-Client`、`Data-Agent-Server` 平级，Java 后端负责启动、取消和关闭 Worker。

## 开发准备

在本目录运行 `npm ci` 安装依赖，然后正常启动 Java 后端，无需手动启动 Worker。
从仓库根目录、`Data-Agent-Server` 或 `data-agent-server-app` 启动后端时，均自动查找本模块的 `src/worker.js`。
打包部署可通过 `app.local.pi.worker-script` 指定脚本路径。

## 协议（协议版本 1）

入（Java → Worker）：`start` / `tool_result` / `cancel`
出（Worker → Java）：`hello` / `text_delta` / `tool_call` / `finished` / `error`

- 所有运行消息带 `runId`；工具请求与结果以 `toolCallId` 对应。
- `start.role` 为 `assistant`（缺省）或 `explorer`；`limits.maxModelCalls/maxToolCalls` 在调用前执行预算检查。
- 每次运行恰好一条 `finished`：`SUCCEEDED` / `FAILED(errorCode)` / `CANCELLED`。
- 入站行上限 256KB；工具结果入模型前上限 64KB；单次运行文本上限 256KB。
- 模型凭证只在 `start.apiKey` 内经 stdin 传递；不进命令行、日志或 stdout。
- 两种角色均注册 `list_tables` / `describe_table` / `describe_tables` / `query_database`，由 Java 校验目标与连接版本后执行；主助手额外注册 `callingExplorerSubAgent`，Explorer 不开放递归委派。
- `describe_tables` 一次读取 1–4 张指定表的简明字段和 JDBC 已定义外键，不生成系统目录 SQL；外键不可读与已确认无外键分别标记。批量结构证据由宿主附在 Explorer 的 `structures` 返回值中，避免主助手重复探索。
- Pi 系统提示词位于服务端资源目录 `prompt/pi-main-agent_zh.md` 和 `prompt/pi-explorer_zh.md`，随应用打包，在启动时按 UTF-8 加载；修改后重启后端生效。
- 本地运行日志直接输出到启动后端的 IDEA Run 控制台，不写日志文件。可查看 `Pi chat started`、工具调用、Explorer 任务、耗时及最终状态。
- 纯结构探索使用任务 `mode: "schema"`：子助手仅可列表和批量读取结构，成功读取后切到无工具的摘要阶段；主助手收到完整结构证据后也直接汇总。下一轮对话恢复完整工具集。省略模式或使用 `general` 保留只读数据查询能力。
- 主助手另有 `list_connections` / `list_namespaces`：无需默认目标即可先发现数据源，再逐级发现库或 Schema。多个候选无法判断时按名称询问；不会要求用户手填连接 ID。数据源列表只包含 ID、名称和类型，不打开数据库；表与字段读取要求完整的数据库/Schema 范围。
- Explorer 使用独立 worker 和该轮固定的 EXPLORER 模型配置，返回经过元数据证据校验的结构化任务结果；任务结束即释放进程，不保存子任务历史。
- 查询仅接受受支持的单条只读 SELECT/WITH，超时 15 秒、最多返回 200 行；禁止写入、DDL、批量语句、锁、序列及未知函数。查询结果保留执行编号、目标、状态与截断标记。
- 写入与对话压缩暂不支持。会话固定引擎，不在失败时切换到 Java 重跑。

## 模型接口

- `OPENAI_CHAT_COMPLETIONS`：已实测（DashScope 兼容模式，qwen3.8-max）。
- `ANTHROPIC_MESSAGES`：已接线，未实测。
- 其余接口类型返回 `PI_UNSUPPORTED_API_STYLE`。

## 测试

```sh
npm test    # node:test，真实子进程 + 脚本化 OpenAI 兼容夹具，无真实模型费用
```

接入基线见 [PI-M1](../docs/PI-M1-IMPLEMENTATION-2026-10-30.md)；只读查询及本轮验证见 [PI-M2](../docs/PI-M2-READONLY-QUERY-2026-11-04.md)。
Explorer 任务、预算、取消及完整链路验证见 [PI-M3](../docs/PI-M3-EXPLORER-2026-11-04.md)。

阶段 A 的固定任务集、验证脚本和阶段 B 的 Core / Durable 选型记录见
[A/B 交付记录](../docs/PI-STAGES-AB-2026-11-26.md)。从仓库根目录执行
`python3 scripts/validation/pi_stages_ab.py` 可生成本次合同验证报告。
当前选型继续使用 Core 1.5.0，`durable-prototype` 保留为恢复机制原型。
