# PI-M2：Pi 受限只读查询

日期：2026-11-04。承接 PI-M1 与会话引擎 / 能力 / 模型兼容性收口。Pi 仍为默认关闭的实验入口，Java 仍是默认助手。

## 范围

将「找表 → 查看字段 → 执行受限查询 → 根据真实结果回答」接入现有 Pi 工具闭环。不新增写入、DDL、批量执行、长期记忆或 Pi 压缩能力；不保存聊天历史。

### 共用执行链路

- 新增 `LocalReadOnlyQuery`，从 Java `LocalReadTools.query_database` 提取校验、请求构造与取消注册，Java 和 Pi 共用。
- 复用 `LocalReadOnlySql` 的保守语法与函数校验，并额外限制为单条语句。禁止 DML / DDL、锁、序列、未知函数和多条 SELECT。
- 执行继续进入 `SqlExecutionServiceImpl` 和现有 JDBC / 插件链路，固定 `timeoutMs=15000`、`maxRows=200`，保留原 SQL 字面量与空白，不进行改写。
- 由 Java 恢复可信请求上下文，沿用访问检查、插件范围规范化、连接版本钉住；显式引用身份优先于默认目标。默认目标不是命名空间权限锁。
- 取消注册保存原请求与 Agent 上下文，后台线程触发时恢复原身份，执行后清理线程上下文。取消后不交付迟到成功结果；worker 异常终止也会取消在途 SQL，不换引擎重放。

### 工具与结果契约

worker 增加 `query_database`，参数为 `query` 和可选 `connectionId` / `catalogName` / `schemaName`。SQL 参数上限 65536 字符。Java 拒绝非对象、未知字段、非字符串 SQL / 名称、非正整数或超出前端安全整数范围的连接 ID，不把非法目标回退成默认连接。

Pi 查询结果包含：

- `target`：实际解析的连接、catalog 和 schema。
- `executionId`、`success`、`statementState`、`transactionOutcome`、`batchState`。
- `maxRows`、`timeoutMs`、`truncated`。
- `result`：原执行响应，含结果行、列、错误及执行信息。

数据库返回的截断标记继续保留。序列化结果超过 12000 字符时，保留外层目标、执行编号与状态，将完整结果替换成有界 `preview` 并明确 `truncated=true`；JSON 转义后仍检查最终长度。查询执行失败时 TOOL_RESULT 标记 `error=true`，不把超时或 UNKNOWN 当作查询成功。Agent 在解释工具失败后正常结束回答，与 SQL 执行成功是两个独立事实。

Java 助手原查询工具继续返回原执行响应，保持现有调用方格式。

### 界面与提示词

Pi 固定提示更新为「Pi 实验引擎 · 只读查询」，说明 15 秒 / 200 行限制及写入、压缩尚未支持。中英文同步。系统提示要求根据执行事实回答，不以部分行结果推断全量统计；查询工具完成后继续模型生成，只有运行终态才发送 DONE。

## 验证方法与边界

使用本机 Node 22.23.2 / JDK 21.0.13.0.2。未调用真实厂商或业务数据库，未验收 Windows、桌面安装包或 Anthropic 实际服务。

```sh
cd Data-Agent-Pi-Worker
npm test
```

真实 worker 与脚本化 OpenAI 兼容模型夹具验证：三个工具注册范围、元数据到查询的四次模型调用、目标身份与查询结果回传、工具结果后继续生成、唯一终态、等查询结果时取消，以及原有多轮、错误、崩溃与协议回归。

结果：13 项全部通过。

```sh
cd Data-Agent-Server
mvn -pl data-agent-server-app -am '-Dtest=Local*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false clean test
```

首次增量构建发现 target/classes 残留已删除的旧账号类，导致运行时隔离断言失败；干净构建后原断言通过，未放宽测试。随后补充后台取消身份恢复与 worker 查询中崩溃的处理，并再次运行同组测试。

HTTP/SSE 与确定性 Node 协议夹具验证查询链路、SQL 请求限额、写入 / DDL / 批量 / 副作用拒绝、畸形参数、无目标、跨连接 Schema 引用、连接版本变化、超时 / UNKNOWN、数据库与内容截断、取消、worker 在查询中崩溃后重建与唯一终态。SQL 执行边界使用模拟服务，不能替代真实数据库的性能、取消响应和驱动兼容性验收。单元测试补充跨线程取消身份恢复、迟到结果拒绝、完成后取消注销及 SQL 字面量保留。

最终同组回归：203 项中 202 项通过、1 项 `LocalWorkbenchLiveTest` 因未配置实库而跳过，0 失败、0 错误；其中 Pi HTTP 测试 23 项、共用查询单元测试 2 项全部通过。最终 Maven 返回 BUILD SUCCESS。

```sh
cd Data-Agent-Client
npm test -- src/components/ai/LocalAssistant.context.test.tsx src/services/localAssistant.service.test.ts --maxWorkers=1
npm run build
```

前端继续回归会话引擎固定、命令边界、能力提示、模型接口校验、默认目标与引用。

结果：相关测试 21 项通过，TypeScript 检查与生产构建通过；Browserslist 数据过期与产物体积提示仍为已有构建提示，本批未调整依赖或拆包配置。`git diff --check` 通过。

## 后续

同日已使用现有 MySQL `sys_demo` 和真实 `qwen3.8-max` 完成五个只读场景验收，见 [实库验收记录](PI-M2-MYSQL-LIVE-2026-11-04.md)。以上夹具阶段的「未调用真实厂商或业务数据库」是该阶段的历史边界。

后续评估查询结果展示与上下文预算。写入和 Pi 压缩另行实现与验收。
