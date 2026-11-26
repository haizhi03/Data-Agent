# macOS 原生窗口与 DMG 开发包

2026-11-26，`feature/desktop-pi`。本机 macOS 26.5 / arm64 验证；当前为本地开发包。

## 当前产物与构建

- 原生应用：`target/native-release/Data Agent.app`
- 磁盘映像：`target/native-release/DataAgent-macos-arm64.dmg`
- 构建清单：`target/native-release/Data Agent.app.manifest.json`

DMG 中包含应用和 Applications 快捷方式，按拖拽复制应用的方式安装。已验证 LaunchServices 启动路径、本机只读映像启动和复制后的运行。Developer ID 签名、公证、干净机器安装以及 Windows 验收仍待完成。

构建继续复用目录包，无 Electron 或第三方窗口运行时：

```sh
python3 scripts/desktop/build.py \
  --output target/desktop-native-runtime-next \
  --driver /absolute/path/mysql-connector-j.jar

python3 scripts/desktop/package_macos.py target/desktop-native-runtime-next \
  --output 'target/native-release-next/Data Agent.app' \
  --dmg target/native-release-next/DataAgent-macos-arm64.dmg
```

构建机需要 Command Line Tools 的 Swift 编译器、macOS SDK、codesign 和 hdiutil。输出路径必须不存在。应用使用 AppKit 窗口、系统 WKWebView 和原有随包 Node / Java / Pi Worker。编译最低目标为 macOS 14，目前验证平台为 macOS 26.5 / arm64。版本号默认为 0.1.0；本轮修复后的原生产物构建号为 2。

应用采用 ad-hoc 开发签名，构建后执行 `codesign --verify --deep --strict`。运行时版本、SHA-256、原生源码摘要及 DMG 摘要均有清单记录。签名完整性检查通过；发布信任与跨机器分发继续单独验收。

## 进程、窗口和数据

原生窗口启动并拥有一个 Node 宿主。通信凭证继续保存在宿主中，WKWebView 只访问本机同源代理，不注入 Shell 或 Node API。窗口限制主页面导航范围，用户点击的 HTTPS 外部链接交给浏览器。原生编辑菜单和文件选择面板已接入。

正常关闭窗口或退出应用时，等待 Node、Java 和会话 Worker 清理后结束原生进程。强制结束原生进程时，私有父管道 EOF 触发 Node 清理。启动探测 Worker 也纳入宿主的进程管理。没有增加任务重放或自动恢复。

测试发现原生 SIGTERM 的退出回调会被 AppKit 的终止等待阻塞。修复为先离开主队列任务，再通过主 RunLoop 进行退出与终止回执。修复前失败和修复后结果分别保留。

连接、模型、凭证和日志继续保存在用户数据目录，默认沿用 `~/.data-agent/local`；运行包不包含这些配置。测试通过 `DATA_AGENT_DATA_DIRECTORY` 指向独立目录，并在结束后清理临时凭证副本。替换或回退应用文件之前先退出应用，避免正在运行的 Java 继续读取被替换的 jar。

原生 WebView 使用临时站点存储。当前验证的跨重启保留范围是后端连接、模型和凭证；前端偏好、文档草稿的跨重启恢复，以及原生下载/导出适配，继续作为后续交付。临时会话关闭后清空，旧实例的会话标识不能在新实例中继续使用。

## 本轮验证

```sh
python3 scripts/validation/native_macos_smoke.py 'target/native-release/Data Agent.app' \
  --dmg target/native-release/DataAgent-macos-arm64.dmg \
  --mysql-local-directory "$HOME/.data-agent/local" \
  --output target/native-install-smoke.json

python3 scripts/validation/desktop_active_smoke.py \
  'target/native-release/Data Agent.app/Contents/Resources/bundle' \
  --native-app 'target/native-release/Data Agent.app' \
  --mysql-local-directory "$HOME/.data-agent/local" \
  --output target/native-active-smoke.json
```

7 项安装与替换检查通过：签名和只读 DMG 完整性、空 PATH 下从只读映像启动并用随包驱动查询、复制后的应用加载用户配置、停止后的应用替换保留连接/模型/凭证、旧临时会话拒绝、应用回退保留配置，以及 LaunchServices 启动。替换/回退检查使用同一已验证载荷；跨版本数据格式迁移和自动更新尚待设计与验证。

4 项原生活动任务退出检查通过：查询和 Pi 模型流分别在 SIGTERM / SIGKILL 下清理原生应用、Node、Java 和 Worker，校验正常退出码或预期的强制结束码。模型测试使用本地可控 SSE 服务和真实随包 Pi Worker，并先等待客户端 TEXT；没有调用真实厂商。原有 8 项目录宿主回归也通过。

可见窗口实测：资源树加载 MySQL 数据库，打开 sys_demo 查询控制台，Cmd+Enter 执行 `SELECT 41 AS n` 得到 41；真实配置的 Qwen 从当前 SQL 回答 41，一轮 SUCCEEDED、零工具调用。点击原生红色关闭按钮后所有所属进程退出，应用退出码为 0。原始配置指纹未变，独立测试实例已清理。

证据见 [native-macos-2026-11-26.json](desktop-validation/native-macos-2026-11-26.json)。本轮完成本机原生开发包交付验证，正式发布门槛继续保留：Developer ID 与公证、干净机器、Windows、跨版本迁移、原生导出和偏好恢复、Explorer 活动退出，以及 DM8 实库。

实现使用系统的 [AppKit 终止回执](https://developer.apple.com/documentation/appkit/nsapplication/reply(toapplicationshouldterminate:)) 管理退出等待。
