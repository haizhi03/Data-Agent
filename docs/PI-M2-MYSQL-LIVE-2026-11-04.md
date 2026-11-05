# PI-M2：现有 MySQL 与真实模型验收

日期：2026-11-04，最终完成时间 12:04（Asia/Shanghai）。

## 环境与范围

- 使用现有连接 ID 1：`localhost:3306`，数据库 `sys_demo`；复用现有加密连接、驱动与凭证。
- 使用现有 MAIN_CHAT 模型：DashScope 兼容接口的 `qwen3.8-max`，由真实 pi-agent-core worker 直接调用厂商。
- 使用当前代码另起临时本地实例（随机回环端口），原 8081 实例保持运行。结束后关闭临时会话、worker、连接池与测试实例。
- 数据库操作仅为元数据读取与 SELECT，没有创建库 / 账号 / 表，没有执行数据写入或 DDL。
- 检查现有本地数据目录内连接、模型、凭证与密钥文件的内容指纹，前后一致；锁文件排除在内容比较之外。

## 实际结果

先通过工作台只读执行端点获取独立基准，再核对 Pi 工具结果与回答，不预先把答案交给模型。

| 场景 | 真实执行结果 | 场景总耗时 |
| --- | --- | --- |
| 列表 → 字段 → 总数 | 实际读取 10 张表与 sys_user 字段，COUNT(*) 得到 12，与直接查询一致 | 26.4 秒 |
| 同一会话追问 status=0 | 沿用原连接、库与 sys_user，实际条件聚合得到 1，与直接查询一致 | 15.1 秒 |
| 驱动返回上限 | 现有演示表交叉组合的独立计数为 588；原样执行编号查询，实际仅返回 200 行，truncated=true，回答明确说明是部分结果 | 12.8 秒 |
| 不存在的表 | 实际执行一次只读 SELECT，MySQL 返回 1146 / 42S02；success=false、statementState=FAILED、transactionOutcome=NONE，模型解释失败且未重试 | 17.9 秒 |
| 真实模型输出中取消 | 接收到 TEXT 后请求取消，只产生一个 CANCELLED DONE | 25.1 秒 |

取消场景的 25.1 秒包含等待模型首段输出的时间，不是点击停止到取消完成的延迟。本轮没有单独统计首字延迟或取消响应延迟，以上单次场景耗时也不代表 p95 指标。

所有案例各自只有一个 DONE。查询失败时，助手可以成功完成错误解释，但 SQL 执行结果仍为 FAILED；两种状态分别核对。

## 验证器修正

最初追问用例将结果矩阵左上角直接当成计数，错误假设模型始终返回一行一列。复核时保留生成 SQL、列名与聚合值，实际观察到合法的分组查询：

```sql
SELECT status, COUNT(*) AS cnt
FROM sys_user
GROUP BY status
ORDER BY status;
```

返回为 `[[0,1],[1,11]]`，目标 `status=0` 的计数为 1；第一格是状态值，不是人数。验证器调整为支持标量计数、按 status 定位的分组计数、具有明确停用别名的条件聚合，同时继续与独立的 `WHERE status=0` 基准核对。未知形态保留失败，不任意猜测第一格。补充两项离线测试，防止把总数 12 当成停用人数或对未知列形态误判。

生产 SQL 校验和查询链路未因这次测试修正而改变。最终完整重跑五个真实场景，全部通过；当次追问生成的是带 `WHERE status=0` 的单列 COUNT 查询。

## 可重复入口

新增 `LocalPiMySqlLiveTest`，默认不运行。它要求本机已有 `sys_demo.sys_user` 与 `sys_demo.sys_role_permission` 演示表，不创建或导入测试数据；会真实调用已配置模型。

在 `Data-Agent-Server` 执行：

```sh
DATA_AGENT_PI_MYSQL_LIVE=true \
DATA_AGENT_PI_LOCAL_DIRECTORY="$HOME/.data-agent/local" \
mvn -pl data-agent-server-app -am \
  -Dtest=LocalPiMySqlLiveTest,LocalPiAggregateAssertionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

最终结果：5 项真实验收 + 2 项验证器回归，7 项通过，0 失败、0 错误、0 跳过，Maven BUILD SUCCESS。`git diff --check` 通过。

本地可检查摘要位于 `Data-Agent-Server/data-agent-server-app/target/pi-mysql-live-summary.json`，包括独立基准、生成 SQL、执行编号、状态、截断、聚合值、场景耗时及限长回答片段；不包含凭证或完整聊天历史。该文件属于临时构建产物，clean 会删除。

## 验收边界

本次证明当前 macOS、现有 MySQL 演示库与该 Qwen 模型路径下的五个场景可用。不推广为其他模型、Anthropic、DM、Windows 或桌面安装包已验收。真实取消覆盖模型输出阶段；查询执行中的取消、后台身份恢复与 worker 崩溃取消仍由此前的确定性夹具覆盖，没有为实库验收发起人为慢查询。
