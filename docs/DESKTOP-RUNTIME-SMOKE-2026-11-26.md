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

本步证据只覆盖这台 macOS 开发机。干净 macOS / Windows 安装、原生窗口关闭、签名与分发、升级回退、只读安装目录、打包环境中的运行中查询/模型退出，以及 DM8 驱动和真实数据库仍需分别验收。运行目录包和清空 PATH 不等同于完成上述验收。
