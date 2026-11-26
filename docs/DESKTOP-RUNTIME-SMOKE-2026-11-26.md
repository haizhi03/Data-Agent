# 阶段 D：最小运行包与进程验证

日期：2026-11-26。范围：`feature/desktop-pi`，本机 macOS 26.5 / arm64。

本步完成 NEXT-03 的目录包启动验证。使用一个 Node 宿主提供前端静态文件、代理 API，并管理 Java 子进程；沿用现有 React / Java / Pi 代码。启动不需要 Maven、Vite、全局 Java 或全局 Node。原生窗口、应用安装器及升级仍属后续阶段 D 的交付。

## 组装与启动

构建机需要 JDK 21、Maven、Node/npm 和已安装依赖的 Client。执行：

```sh
python3 scripts/desktop/build.py \
  --output target/desktop-pi-macos-arm64-next \
  --driver /absolute/path/mysql-connector-j.jar
```

输出目录必须不存在，避免覆盖旧产物。`--driver` 可重复传入，仅复制显式指定的 JDBC 文件。当前目录包里的驱动通过工作台导入使用，不自动覆盖用户已有驱动配置。

构建会编译后端和前端，按 Worker lockfile 安装生产依赖。Java 运行时与 Node 二进制分别使用锁定的独立发行包，下载后验证 SHA-256；版本、下载地址和摘要见 [`runtime-lock.json`](../scripts/desktop/runtime-lock.json)。Java 编译器和运行时分开，避免将 Homebrew JDK 的包外字体库引用带入运行包。构建会审计原生库依赖，发现非系统的包外绝对路径即失败。

生成目录含 `server.jar`、`web/`、`worker/`、`runtime/java/`、`runtime/node/`、`drivers/` 和 `start.command`，以及版本、来源和摘要清单。构建不复制用户连接、密码、模型凭证或聊天。

本轮实际产物为 `target/desktop-pi-macos-arm64/`。从仓库根目录可用独立数据目录启动：

```sh
DATA_AGENT_DATA_DIRECTORY="$PWD/target/desktop-pi-data" \
  ./target/desktop-pi-macos-arm64/start.command
```

终端输出 `ready` 和本机 URL，浏览器打开该 URL。前端、后端分别自动分配端口，保持终端运行，Ctrl-C 退出。未指定数据目录时沿用 `~/.data-agent/local`，不会把配置写进运行包。验证和试用使用独立目录，以免与已有开发实例共享配置。

## 引导与退出

Java 启用 `app.local.host-stdio=true` 后，在启动就绪时通过父进程私有 stdout 管道交付地址、实例身份与通信凭证。宿主消费该行，不写入日志。浏览器仅取得同源 API 地址和公开实例身份；Java 凭证保存在宿主进程中，由 API 代理添加。

前端允许同源、空凭证的宿主代理；其他跨源宿主仍要求原有凭证。宿主限制准确的 Host / Origin，拒绝跨站请求和静态目录越界。后端继续绑定 loopback 并验证通信身份；客户端的旧实例标识保留给后端校验，不用当前实例标识覆盖。

宿主接收 SIGINT/SIGTERM 时停止前端服务、断开请求并关闭 Java stdin；Java 监听 EOF 后关闭应用上下文。宿主遭 SIGKILL 时，操作系统关闭管道，Java 也会退出。该行为仅在显式启用宿主管道时生效，普通开发启动保持现有行为。后端意外退出或启动超时会结束宿主，不继续展示 READY。

## 验证与边界

验证入口：

```sh
python3 scripts/validation/desktop_smoke.py target/desktop-pi-macos-arm64 \
  --mysql-local-directory "$HOME/.data-agent/local" \
  --output target/desktop-pi-smoke.json
```

省略 `--mysql-local-directory` 时只运行离线启动与生命周期检查。提供时复制配置到测试临时目录，将所选 MySQL 驱动路径改为包内文件，仅执行 `SELECT 1`。原配置前后计算指纹，测试目录随后删除，不调用模型。

本轮记录见 [`desktop-validation/results-2026-11-26.json`](desktop-validation/results-2026-11-26.json)。检查包括空 PATH、无关工作目录与带空格的数据路径下启动 UI / Java / 真实 Pi Worker；同源引导、后端鉴权、Host / Origin / 静态路径隔离与旧实例拒绝；随包 JRE + MySQL 驱动执行 `SELECT 1`；两个实例独立端口与数据；正常退出、宿主 SIGKILL 后 Java 退出；缺少服务端 jar 时明确失败且不虚报就绪。运行前后包文件及原配置指纹一致。

前端 299 项通过，后端本地与上下文/记忆回归 259 项通过、17 项实库默认跳过；TypeScript、生产构建和后端打包通过。

本步证据只覆盖这台 macOS 开发机。干净 macOS / Windows 安装、原生窗口关闭、签名与分发、升级回退、只读安装目录、打包环境中的 Explorer 活动任务退出，以及 DM8 驱动和真实数据库仍需分别验收。运行目录包和清空 PATH 不等同于完成上述验收。


## 同日后续：页面流程与活动任务退出

已通过真实 Chrome 页面操作完成：编辑并测试已有模型配置（密钥留空保留，不回显）、保存连接设置并重启确认、打开 sys_demo 控制台、选择第二条 SQL 取得回答 29、执行查询查看结果 29、手动附带该结果取得回答 29、附带缺失字段错误取得 SQLSTATE 42S22 的解释，以及不重新附带结果的连续追问。四轮聊天都使用同一 Pi 会话，终态 SUCCEEDED，工具调用均为 0。页面确认每次发送后结果选择清空。浏览器自动化宿主曾不可用，随后改用本机 Chrome 的可见窗口完成，未将失败的工具操作计作通过。

页面验收另发现 Cmd+Enter 被 Monaco 的默认插入行命令截走。工作台现通过编辑器自己的 action 注册执行快捷键，并通过 ref 使用最新执行回调；在新版运行包中仅按 Command+Return 执行 `SELECT 73 AS n`，结果表格返回 73。原有前端 299 项回归及生产构建再次通过。

活动任务验证入口：

```sh
python3 scripts/validation/desktop_active_smoke.py target/desktop-pi-macos-arm64 \
  --mysql-local-directory "$HOME/.data-agent/local" \
  --output target/desktop-active-smoke.json
```

查询用纯 SELECT 的合成聚合产生可取消的工作负载，8 秒超时作兜底；观察实例用唯一标记在 MySQL PROCESSLIST 中确认查询实际运行。模型用随包真实 Pi Worker 与本地可控 SSE 模型服务，标准 Node preload 只重定向测试域名，不修改运行包、不使用真实厂商密钥。必须先观测到客户端 TEXT，再结束宿主，并检查 Worker 消失和模型连接断开；不能把刚发出模型请求算作已验证输出中的取消。

验证复现了原退出顺序的问题：Java 先进入 Tomcat 请求排空，运行中查询延迟取消，宿主退出 5 秒后仍存活。修复为在 EOF 后先关闭本实例会话、取消连接上的 SQL，再关闭应用上下文。使用原有取消和连接关闭逻辑，没有新增任务恢复或重放机制。

修复后，查询和 Pi 模型流分别在 SIGTERM / SIGKILL 四种场景下通过，客户端请求在退出前都确实处于运行中；本机本轮清理约 160～219 毫秒。模型中断未产生 SUCCEEDED，模型服务没有收到重试。原有 8 项目录包冒烟回归也已通过。详细结果追加在原 JSON 的 `followUp`，保留修复前失败证据和各次包指纹。测试未改变原配置，测试实例已停止，临时凭证副本已清理。
