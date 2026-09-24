# DM8 SQL 解析器

DM 插件使用 ANTLR 4。语法源文件位于
`Data-Agent-Server/data-agent-server-plugins/dm-plugin/src/main/antlr4/edu/zsc/ai/plugin/dm/parser/base/`，
分别为 `DMLexer.g4` 和 `DMParser.g4`。这两份语法取自本机 Chat2DB Community 的 DM 语法，保留了原文件中的许可证声明。修改语法时只编辑 `.g4`，不要编辑 Maven 生成的 Java 文件。

在 JDK 17 下运行 `mvn -pl data-agent-server-plugins/dm-plugin -am test`，ANTLR Maven 插件会生成 Lexer、Parser、Listener 和 Visitor 到 `target/generated-sources/antlr4/`。

运行期由 `DmSqlParser` 解析 SQL 脚本。完整模式提供语法错误位置、语句类型与范围、对象名、字段和别名；简单模式只识别语句类型和边界。`SqlValidator` 的单语句校验和 `SqlSplitter` 的脚本拆分均使用语法树。Agent 只读通道只接受解析成功且 AST 未包含 `INTO`、`FOR UPDATE`、函数调用或序列 `NEXTVAL` 等可能改变状态的 SELECT。

前端在 DM 连接的 SQL 编辑器中调用 `POST /api/db/sql/analyze`，提交 `connectionId`、`catalog`、`schema`、`sql`，并用返回的 `errors` 绘制 Monaco 错误标记。`statements` 包含 `type`、`objectType`、首尾位置和对象引用；DM DDL 成功执行后会据此刷新当前 Schema 中已展开的对应对象目录。非 DM 插件若未实现 `SqlAnalyzer`，不会被当成 DM SQL 解析。

`EXPLAIN` 在执行前仅扫描第一个有效词；只有 EXPLAIN 才进入完整语法分析并提取内部 SQL，再通过 DM JDBC 的 `getExplainInfo` 获取计划。其他 SQL 继续走既有 JDBC 执行路径。解析失败的 EXPLAIN 不会回退为普通 JDBC 执行。

该语法基于 Oracle PL/SQL 语法适配 DM，不等同于认证 DM8 所有兼容模式和专有扩展。尚需结合指定 DM8 构建、兼容配置与真实驱动进行语法覆盖、执行计划和权限场景验收；未被语法识别的 SQL 会报告错误，而不会被当成已验证的只读 SQL。
