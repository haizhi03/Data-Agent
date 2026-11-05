# PI-M1 后续：会话引擎绑定与压缩入口修正

日期：2026-11-04。

## 本批范围

修复 Pi 会话仍展示 `/compact`、实际却调用 Java 会话压缩的问题。Pi 对话保存在 Node worker 内存中，本批不实现 Pi 压缩，也不新增查询或写入工具。

- 前端在创建临时会话后固定本次选用的引擎。能力刷新不改变已有会话；新建对话后重新依据能力选择。
- 后端在首次接纳聊天运行时绑定 JAVA / PI；Java 压缩也是 Java 引擎操作。绑定与运行占用检查在注册表锁内完成，取消、失败和 worker 重启不解除绑定。
- 已绑定的会话不能通过另一引擎入口续聊，返回 HTTP 409 / `LOCAL_SESSION_ENGINE_MISMATCH`。
- Pi 模式的命令列表只提供 `/new`；直接触发压缩回调或发送独立的 `/compact` 文本时，前端明确提示不支持，不发送聊天或压缩请求。
- 直接请求 Pi 会话的压缩接口返回 HTTP 409 / `PI_COMPACTION_NOT_SUPPORTED`，不读取或修改 worker 历史。
- Java 会话保留现有压缩流程。所有绑定状态仅保存在内存，无文件格式或用户数据迁移。

## 验证

本机 Node 22.23.2、JDK 21.0.13.0.2。

前端原有本地依赖缺少 jsdom，已按现有锁文件执行 `npm ci --no-audit --no-fund` 恢复；未修改 package.json 或锁文件。

```sh
cd Data-Agent-Client
npm test -- src/components/ai/LocalAssistant.context.test.tsx src/services/localAssistant.service.test.ts --maxWorkers=1
```

2 个文件、12 项测试通过。覆盖 Pi 压缩命令过滤与拒绝、Java 压缩、双向能力状态刷新后的引擎固定、新建会话重新选择，以及原有目标和引用行为。

`npm run build` 通过（TypeScript 检查和 Vite 生产构建）；构建提示 Browserslist 数据过期及部分产物超过 600 kB，本批未调整依赖或拆包配置。

```sh
cd Data-Agent-Server
mvn -pl data-agent-server-app -am \
  -Dtest=LocalAssistantPolicyTest,LocalAssistantHttpTest,LocalPiChatHttpTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

40 项测试通过，无失败、错误或跳过。包括真实 HTTP/SSE 与确定性 Node worker 夹具：Pi 压缩被拒绝后仍复用原 worker 多轮上下文、跨引擎续聊被拒绝，以及 Java 原有压缩、审批和取消回归。注册表测试覆盖两种引擎在取消后仍保持绑定。

本批未调用真实模型或业务数据库，未验收 Windows 或桌面安装包。

## 后续

### 同日第二小步：Pi 能力范围可见

- 助手头部下方持续显示「Pi 实验引擎 · 仅表结构」，首条消息发送前即可看见。
- 显示可列出表、查看字段并追问结构，同时说明查询执行、写入与对话压缩尚未支持。中英文同步。
- 提示依据当前会话固定的引擎展示；能力刷新不改变已有会话的提示，新建对话后重新选择。Java 会话不显示 Pi 提示。
- Pi 的面板提示只描述内存对话，不再沿用 Java 的「数据库写入须单独确认」文案。状态行保留运行状态，移除重复的引擎标签。
- 本批复跑上述前端两个测试文件，12 项通过；`./node_modules/.bin/tsc --noEmit` 通过。已有用例补充提示在首轮前可见、随固定引擎保持及新建后变化的断言。本小步未重跑生产构建、后端或实库验收。

### 同日第三小步：发送前校验模型接口

- Pi 根据当前选中模型关联的连接接口校验，仅接受 `OPENAI_CHAT_COMPLETIONS` / `ANTHROPIC_MESSAGES`，与 Java 宿主及 worker 的接线范围一致。Anthropic 仍沿用 PI-M1「已接线、未实测」的边界，本批不声称完成厂商实测。
- 不支持或未知的接口立即显示换用兼容模型的提示并禁用发送；发送回调也做同样校验，保留输入，不创建会话或发起聊天请求。缺失模型连接时提示配置不可用。
- 模型列表仍可选择其他接口，以便看清原因；选回兼容模型后即时恢复发送，已有会话保持原引擎和上下文。Java 会话不受这项 Pi 校验限制。
- 中英文错误提示同步，Java 后端原有接口校验继续作为最终边界。
- 本小步前端两个测试文件共 21 项通过，`./node_modules/.bin/tsc --noEmit` 通过。覆盖两种接线接口、Responses / DashScope / Gemini / 未知接口、缺失连接、切换模型后继续原会话及 Java 回归。
- `mvn -pl data-agent-server-app -am -Dtest=LocalPiChatHttpTest -Dsurefire.failIfNoSpecifiedTests=false test` 通过，14 项无失败、错误或跳过。新增 HTTP 验证：使用有效的 Responses 模型配置直接调用 Pi 返回 409 / `PI_UNSUPPORTED_API_STYLE`，随后同一会话改用兼容模型仍从 worker 第一轮正常开始。使用确定性 worker 夹具，未调用真实厂商或业务数据库。本小步未重跑生产构建。

同日已实现 Pi 受限只读查询，见 [PI-M2 实施记录](PI-M2-READONLY-QUERY-2026-11-04.md)。前述「仅表结构」为第二小步的历史边界；当前提示更新为只读查询。Pi 压缩和写入工具各自验收。
