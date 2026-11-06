# Pi 多表探索与系统提示词

## 问题与处理

自然提问涉及用户、角色和权限表及 ER 图时，原来的主助手自行逐表读取、反复查询系统目录，然后生成长答案。一次实测耗时约 209 秒，8 轮模型调用、17 次工具调用，没有委派 Explorer。

现在由主助手发现数据源和命名空间，再自动委派一个 `mode: "schema"` 任务。结构探索只开放 `list_tables`、`describe_tables`；批量读取成功后切换到无工具的 JSON 摘要阶段。主助手收到完整的成功结构证据后也切到无工具的最终回答阶段。下一轮对话恢复完整工具集。

`general` 模式（也是省略 `mode` 时的兼容默认值）保留原来的只读查询工具，支持数据统计和混合任务。结构模式在 Java 边界也拒绝 Worker 发出的业务查询，不仅依赖提示词。

## 结构证据

- `describe_tables` 在同一连接和命名空间内批量读取 1–4 张指定表，先验证完整参数，再读取元数据；仍执行访问校验、连接版本固定和运行取消检查。
- 字段只携带名称、类型、主键标记和简短注释。字段为空时不视为已验证表。
- 外键通过 JDBC `getImportedKeys` 读取，保留复合外键顺序、引用目标和截断标记。不让模型生成数据库专用系统目录 SQL。
- 外键读取失败返回 `available: false`，不等同于已确认无外键；单表最多返回 100 条外键字段记录。
- 宿主把真实结构附到 Explorer 结果的 `structures` 中，主助手直接复用。超长任务结果仍保留截断标记，不能宣称完整覆盖。

## 提示词位置

- `Data-Agent-Server/data-agent-server-app/src/main/resources/prompt/pi-main-agent_zh.md`
- `Data-Agent-Server/data-agent-server-app/src/main/resources/prompt/pi-explorer_zh.md`

复用 `PromptConfig.loadClassPathResource` 按 UTF-8 加载，资源随应用打包。修改后重启后端生效；没有把数据库身份、预算和工具权限等宿主校验移到提示词中。

## 验证

真实 Qwen + 既有 MySQL `sys_demo`，新建无默认连接的会话，使用同一个自然问题，没有要求用户说出 Explorer 或工具名。

- 约 67.8 秒完成，聚合统计为 7 轮模型调用、5 次工具调用、4030 输出 tokens。
- 调用链：`list_connections` → `list_namespaces` → `callingExplorerSubAgent` → 子任务 `list_tables` → `describe_tables` → 汇总。
- 一个 Explorer 任务成功读取 4 张核心表，子任务约 20.1 秒完成；没有执行 SQL，没有主助手重复读取结构，最终包含 Mermaid ER 图。
- 本地模型、凭证和连接配置文件指纹保持不变。
- 证据：`data-agent-server-app/target/pi-explorer-natural-live-summary.json`，测试方法 `LocalPiMySqlLiveTest#naturalMultiTableQuestionDelegatesAndKeepsExplorationFocused`。

这是一次相同问题的对比实测，不是所有模型和问题的固定延迟承诺。确定性测试还覆盖结构阶段工具收敛、下一轮工具恢复、Java 拒绝结构模式 SQL、批量参数边界及 JDBC 外键不可用/复合键/截断。
