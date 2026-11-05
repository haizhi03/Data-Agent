# Pi 最小接入验证（PI-M1）实施记录

日期：2026-10-30
分支：feature/desktop-pi
状态：已实现并通过两层验证；默认关闭，现有 Java Agent 仍是默认引擎。

## 1. 任务卡

| 字段 | 内容 |
| --- | --- |
| 目标与范围 | 让 Pi 在当前项目完成「用户问题 → 模型 → Java 元数据工具 → 模型 → 流式回答 → 明确结束」的最小闭环；只交付一个可运行、可检查的实验入口，不重写 Agent 系统 |
| 输入与输出 | 新增 `POST /api/local/pi/chat/stream`，请求格式与 `/api/local/chat/stream` 完全一致（conversationId/message/modelConfigId/defaultTarget/userMentions），SSE 事件复用现有 TEXT / TOOL_CALL / TOOL_RESULT / STATUS / DONE 格式，DONE 元数据增加 `engine:"PI"` |
| 复用与改动 | 复用 ChatReferenceService / ChatTurnContext / ExplicitReferencesStrategy / LocalConversationRegistry / 连接版本与访问校验 / 模型配置与凭证。从 LocalReadTools 提取 `LocalMetadataTools`（范围解析 + 有界 list/describe），Java ReAct 工具与 Pi 桥共用，不复制整套 LocalReadTools，不建通用工具网关 |
| 约束 | 不保存聊天历史（worker 进程内存持有临时会话）；凭证只走 stdin 管道；stdout 仅协议消息；只注册 list_tables / describe_table；不注册 query_database / propose_write；已启动的运行不切换到 Java Agent 重跑 |
| 撤回 / 恢复 | `app.local.pi.enabled=false`（默认值）即完全回到现有行为；未新增持久化格式 |

## 2. 锁定的版本与核实过的 API

- `@earendil-works/pi-agent-core@1.4.0`、`@earendil-works/pi-ai@1.4.0`、`typebox@1.3.27`（package.json 固定版本，非 latest）；Node `>=22.19.0`（engines），本机验证用 Node 22.23.2。
- 核实依据为锁定版本内的 `dist/*.d.ts` 与 README：`Agent` 事件流（`text_delta` 经 `message_update.assistantMessageEvent`；`tool_execution_start/end`；`agent_end` 为一次运行的最终事件，且 `prompt()`/`waitForIdle()` 在 `agent_end` 监听器结束后才 settle）；`getApiKey` 动态取钥；`stopReason` 为 `error`/`aborted` 时分别映射 FAILED/CANCELLED。
- 工具完成 ≠ 任务完成：`tool_execution_end` 之后循环继续发起下一次模型请求，只有 `agent_end` 才产生终态；Java 侧每次运行只发一个 DONE 块（`AtomicBoolean` 守卫）。

## 3. 架构与协议

```
前端 LocalAssistant ──SSE──> LocalAssistantController /api/local/pi/chat/stream
                                │  LocalPiChatService（目标/引用校验、凭证快照、事件映射、取消、终态）
                                │  stdin/stdout 每行一个 JSON（≤256KB/行）
                                ▼
                          pi-worker（Node，pi-agent-core Agent）
                                │  tool_call → Java 执行 list_tables/describe_table → tool_result
                                ▼
                          模型厂商（worker 直接调用，Java 不参与模型协议）
```

协议消息（均带 runId；工具请求/结果以 toolCallId 对应）：

- 入：`start`（runId、sessionId、systemPrompt、ChatTurnContext 编码后的 message、model 快照、apiKey、context）、`tool_result`、`cancel`
- 出：`hello`（protocol=1）、`text_delta`、`tool_call`、`finished`（SUCCEEDED/FAILED/CANCELLED + usage）、`error`（协议级）

要点：

- 每个临时会话一个 worker 进程；同一会话同一时刻只允许一个活动运行（Java `sessions.begin` 与 worker 双重拒绝 `PI_SESSION_BUSY`）。
- 会话消息只在 worker 内存中；会话关闭/驱逐/JVM 退出时 Java 销毁 worker（LocalConversationRegistry 新增 sessionGone 钩子）。
- 凭证不进命令行、日志、协议调试输出；worker 诊断只写 stderr 且做了 key 脱敏。
- 跨进程到达的 connectionId/catalog/schema 全部重新校验：`LocalMetadataTools.resolveValidated`（assertReadable + normalizeTarget + 连接版本钉住 + `withExpectedVersion` 包裹实际读取）。
- 工具结果带 `target`（实际解析身份）与 `truncated`；结果上限沿用既有约定（200 表 / 100 列 / 12000 字符，超长返回 preview+truncated）。
- 输出文本上限 64KB、每轮工具上限 32、整体 150s 超时；取消经 `ModelRunSnapshot.track` 发送 cancel，worker 10s 内不收尾则强杀（cancelGraceSeconds）。
- 迟到事件按 runId 过滤丢弃；worker 异常退出 → `FAILED/PI_WORKER_EXIT`，下一轮自动重建 worker。

## 4. 模型调用归属

- Pi worker 直接调用模型厂商；Java 用 `models.prepare` + `credentials.withSecret` 生成本轮不可变快照与必要凭证，经 stdin 传入。worker 不保存任何长期模型配置或凭证。
- 支持的接口类型：`OPENAI_CHAT_COMPLETIONS`（已实测，见第 6 节）与 `ANTHROPIC_MESSAGES`（已接线，未实测，使用前需单独验证）。其余接口类型明确报 `PI_UNSUPPORTED_API_STYLE`，不伪造兼容。
- 不实现「Java 代理模型协议」第二条路径。

## 5. 第一层验证（模拟模型 + 协议夹具，无真实模型费用）

- `Data-Agent-Server/pi-worker`：`node --test` 12/12 通过（真实 worker 进程 + 脚本化 OpenAI 兼容夹具服务器）：文本流、工具闭环、工具失败、流式中取消、等工具结果时取消、会话忙拒绝、多轮临时会话、不支持的接口类型、厂商 HTTP 401→`PI_MODEL_ERROR`、迟到 tool_result 丢弃、坏行/超大行、stdin EOF 退出。
- `LocalPiChatHttpTest`（真 HTTP/SSE + 真 Node 子进程跑确定性协议夹具）11/11 通过：@ 表完整身份到达 describe_table、同名表跨连接用各自身份、默认目标发现→读取、未知工具回错且运行完成、多轮复用同一 worker、等工具结果时取消→CANCELLED、worker 崩溃→FAILED/PI_WORKER_EXIT 且下一轮重启成功、终态后迟到事件被丢弃、会话忙 409、能力 READY 且不含凭证。
- 回归：`Local*Test` 全部 178 个用例通过（含 LocalAssistantHttpTest 16 个，证明 LocalReadTools 提取后行为不变；LocalRuntimeHttpTest 契约更新为 5 个能力键）。
- 前端：`tsc --noEmit` 通过；LocalAssistant 相关 vitest 8/8 通过；`npm run build` 通过。

## 6. 第二层验证（真实模型 + 专用测试库）

- 模型：本机已配置的 DashScope `qwen3.8-max`，接口类型 `OPENAI_CHAT_COMPLETIONS`（`https://dashscope.aliyuncs.com/compatible-mode/v1`）。
- 数据库：本机 Homebrew MySQL 8.4（127.0.0.1:3306），复用用户已有连接 id=1；未创建任何数据库、账号或表。验证期间曾临时建过 docker 容器与两条测试连接，均已删除/移除。
- 后端：`--server.port=8082 --app.local.pi.enabled=true`（不动 8081 上既有实例）；`piAssistant` 能力 READY。

| 场景 | 结果 |
| --- | --- |
| 1. @user（TABLE 引用） | ✅ 模型以引用完整身份（conn=1/catalog=mysql/table=user）调 describe_table，逐列解释并明确说明 remarks 为空不编造 |
| 2. 无 @、默认目标 sys_demo | ✅ 先 list_tables（空参 → 服务端补默认目标，返回 10 表未截断），再 describe 三张用户相关表，含类型/注释/主键 |
| 3. 引用身份覆盖默认目标 | ✅ 默认目标 sys_demo 下 @user（catalog=mysql）正确读到 mysql.user 复合主键（Host+User）；两连接同名表由第一层夹具覆盖 |
| 4. 连续追问 | ✅「手机号在哪列」0 次工具调用，直接引用上一轮读到的 sys_user_profile.phone varchar(30) |
| 5. 候选不存在 | ✅「orders 有哪些列」→ 实际 list 后明确说明不存在，不编造列 |
| 6. 无连接范围 | ✅ 一般 SQL 问题直接回答（0 工具）；「数据库里有哪些表」→ 0 工具调用并引导使用默认目标控件，不自行选连接 |
| 7. 工具失败 | ✅ 不存在的 catalog → 两次 `LOCAL_CONNECTION_FAILED` 以 isError 回传，回答明确说明失败 |
| 8. 取消 | ✅ 模型生成中取消：CANCEL_REQUESTED → 唯一终态 CANCELLED；等工具结果时取消由第一层覆盖 |
| 9. worker 异常退出 | ✅ kill -9 运行中的 worker → 立即 FAILED/PI_WORKER_EXIT；下一轮自动重建 worker 并成功（还意外验证了多轮记忆随 worker 进程释放） |
| 10. 工具结果后继续生成 | ✅ 所有工具场景均为 工具结果 → 继续模型调用 → 文本回答 → 唯一 DONE，未提前结束 |

附记（既有行为，非本次改动引入）：MySQL 系统 catalog `mysql` 的 `getTables` 枚举返回空（workbench 端点同样为空），模型在系统提示约束下如实说明了「清单为空但按名字可读」的矛盾，没有宣称已查看全部表。

## 7. 使用方式

```sh
# 后端（开发机，默认数据目录）
java -jar data-agent-server-app-0.0.1-SNAPSHOT.jar --app.local.pi.enabled=true
# 前端照常 npm run dev；能力上报 piAssistant=READY 时 LocalAssistant 自动走 Pi 入口，
# 否则保持 Java Agent。状态行显示「Pi 引擎（实验）」。
```

配置项：`app.local.pi.enabled`（默认 false）、`node-binary`（默认 node）、`worker-script`（默认按开发目录自动解析 `Data-Agent-Server/pi-worker/src/worker.js`）、`startup-timeout-ms`、`max-line-bytes`、`cancel-grace-seconds`。

## 8. 未验证项与边界

- ANTHROPIC_MESSAGES 接口类型未实测；DASHSCOPE_CHAT / GEMINI / OPENAI_RESPONSES 明确不支持。
- Windows 平台未验证（worker 路径解析、进程信号语义可能不同）。
- 桌面打包形态下 worker 与 Node 运行时的分发方式未定（当前按开发目录解析脚本）。
- Pi 入口本轮不含写入提议、查询执行、压缩与长期记忆；这些仍由 Java 助手承担。
