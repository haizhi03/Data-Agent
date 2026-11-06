# 无默认目标的数据源发现

日期：2026-11-04。

此前 Java 本地助手仅在有默认连接或显式引用时注册工具，Pi 提示词也要求无目标时先让用户指定连接。现在两个主助手均可以自主发现已配置的数据源，不要求用户提供连接 ID 或先设置默认目标。

## 行为

- `list_connections {}` 返回 `connections: [{connectionId, name, dbType}]`、`total`、`truncated`。最多 50 项，只读本地配置，不打开数据库，不向模型发送地址、用户名、驱动路径、连接属性或凭证。发现时固定连接配置版本，不覆盖本轮已有版本。
- `list_namespaces {connectionId, catalogName?, schemaName?}` 在单个经过校验的连接上逐级发现数据库或 Schema。支持 catalog 的插件先列 catalog，传入选定 catalog 后再列 Schema；DM 直接列 Schema，不制造伪 catalog。两类名称各最多 200 项并标记截断。
- 唯一候选或用户已明确名称时继续，存在多个无法判断的候选时展示名称请用户选择。数据源为空时引导添加连接。一般问题无需调用发现工具。
- 表列表及字段读取要求插件支持的数据库/Schema 范围完整，否则返回 `LOCAL_NAMESPACE_REQUIRED`，引导调用发现工具。避免空 JDBC catalog 混合多个库后误报单库表数。只读 SQL 仍允许使用完整限定名，不改变既有查询规则。
- 发现工具复用本地工作区访问检查、运行预算、连接版本校验、取消和结果长度限制。不会自动修改会话默认目标，也不增加新的连接配置。
- Explorer 子 worker 保持原来的三个定向只读工具；由父助手完成数据源及范围发现后再派发任务。

## 验证

```sh
cd Data-Agent-Pi-Worker
npm test

cd ..
mvn -pl data-agent-server-app -am test \
  -Dtest=LocalAssistantHttpTest,LocalPiChatHttpTest,LocalPiExplorerWorkerHttpTest,LocalAssistantPolicyTest,LocalMetadataDiscoveryTest \
  -Dsurefire.failIfNoSpecifiedTests=false

DATA_AGENT_PI_MYSQL_LIVE=true \
DATA_AGENT_PI_LOCAL_DIRECTORY=/absolute/path/to/existing/local-directory \
mvn -pl data-agent-server-app -am test \
  '-Dtest=LocalPiMySqlLiveTest#bothEnginesDiscoverSourcesWithoutAnyDefaultTarget' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Node 18 项、后端定向回归 64 项通过。覆盖无默认目标列源、显式范围读取、空工作区、列表截断、最少字段、固定版本不被覆盖、DM Schema 发现、非法参数和缺省范围拦截；保留真实生产 worker 的 Explorer 回归。

真实模型验收已通过：复用现有加密本地配置、MySQL 和 qwen3.8-max，分别对 Java / Pi 的空目标新会话发送“帮看看当前数据有多少表”。两者均实际调用 `list_connections`、`list_namespaces`，识别到一个数据源、多个数据库后按库名请求进一步选择；Pi 还通过只读聚合查询给出了分库表数。Java 场景约 6.1 秒，Pi 场景约 32.0 秒，均恰好一个成功终态。

验收结果位于 `Data-Agent-Server/data-agent-server-app/target/pi-discovery-mysql-live-summary.json`。现有连接、凭证和模型文件的前后指纹一致，临时会话与 worker 已释放。
