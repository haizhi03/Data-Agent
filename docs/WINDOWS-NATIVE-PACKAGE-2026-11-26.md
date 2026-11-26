# Windows x64 原生开发包

2026-11-26，分支 `feature/desktop-pi`。首批验收目标为 Windows 11 x64；本轮在 macOS arm64 交叉编译，尚未执行 Windows 程序。实现提交：`8c1c776`。

## 产物与使用

- 解压即用包：`target/windows-x64-release/DataAgent-windows-x64.zip`
- 解压后的入口：`Data Agent/DataAgent.exe`，需要保留整个目录。
- 完整文件清单与 SHA-256：`target/windows-x64-release/Data Agent.manifest.json`。

Java 21、Node 22 和自包含 .NET 10 随包提供，用户无需安装开发工具。首版采用 WinForms + 系统共享 WebView2 Evergreen Runtime，缺少 WebView2 时提供微软官方下载入口。当前 ZIP 为未签名开发预览版。

本轮已生成 ZIP 和 Windows x64 原生可执行文件。安装程序的 Inno Setup 脚本与 Windows 构建工作流已补齐；**当前 Mac 未生成或运行 `setup.exe`**。工作流只配置手动触发，本轮未推送或触发 GitHub Actions。

## 构建入口

构建机需要 JDK 21、Maven、Node/npm、Python 3 和 `.NET SDK 10.0.404`。SDK 版本由 `scripts/desktop/windows/global.json` 固定，WebView2 SDK 由 NuGet 锁文件固定。Java/Node 的官方下载地址和 SHA-256 位于 `runtime-lock.windows-x64.json`。

在仓库根目录运行，所有输出目录必须不存在：

```powershell
npm --prefix Data-Agent-Client ci --no-audit --no-fund
python scripts/desktop/build.py --target windows-x64 --output target/desktop-windows-next
python scripts/desktop/package_windows.py target/desktop-windows-next --output "target/windows-next/Data Agent"
```

需要随包 JDBC 驱动时向 `build.py` 显式传入 `--driver C:\absolute\path\driver.jar`，可重复传入。打包不会扫描或复制用户的连接、模型、密码、API Key。当前本地 ZIP 显式包含 MySQL Connector/J 8.3.0；DM8 驱动与实库验收仍待补充。

在 Windows 上生成每用户安装程序：

```powershell
python scripts/desktop/package_windows.py target/desktop-windows-next `
  --output "target/windows-installer-next/Data Agent" `
  --installer "C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
```

输出同目录的 `DataAgent-windows-x64-setup.exe` 和 ZIP。安装默认位于 `%LOCALAPPDATA%\Programs\Data Agent`，无需管理员权限，提供开始菜单入口与可选桌面快捷方式。稳定 AppId 用于后续替换安装；安装器通过应用互斥量检测运行中的窗口。脚本未设置删除用户数据的卸载指令，实际升级/卸载保留行为仍需 Windows 验收。

`.github/workflows/desktop-windows.yml` 在 Windows runner 上构建 ZIP/安装程序，静态检查后安装到临时目录，再执行原生窗口检查与卸载。测试需要 WebView2；工作流使用官方 Evergreen 引导安装程序，并在执行前验证 Microsoft Authenticode 签名。Server runner 的结果也不能替代 Windows 11 干净机器验收。

## 窗口、进程与配置

窗口复用现有前端和 Node 同源代理，私有 API token 保留在宿主中。主窗口只允许访问自己的 loopback origin，外部 HTTP/HTTPS 链接交给默认浏览器，不开放任意本机文件导航或 Node API。WebView2 的用户数据放在用户目录，不写安装目录。

关闭窗口时通过 stdin EOF 通知 Node，再由 Java 取消任务并关闭连接。Windows Job Object 设置 `KILL_ON_JOB_CLOSE`，兜底清理所属 Node/Java/Worker。Node 在加入 Job 后才允许启动后端；如果原生父进程在归属建立之前退出，EOF 阻止后端启动。强制结束原生进程会强制终止 Job 中的进程，不能据此推断数据库服务端已经及时取消查询；该场景单独验收。

配置和日志默认沿用 `%USERPROFILE%\.data-agent\local`，也可用 `DATA_AGENT_DATA_DIRECTORY` 指向独立目录。WebView2 profile 位于该目录的 `webview2`；后端日志位于 `logs/desktop-host.log`，窗口诊断位于 `logs/windows-shell.log`。临时对话继续保存在内存，未增加聊天恢复或自动任务重放。

## 实际验证与待办

本轮通过：

- 后端 Maven 打包、前端类型检查和生产构建。
- Windows 自包含原生窗口交叉编译，最终编译无警告。
- ZIP 的完整性、全部载荷文件 SHA-256、Java/Node/窗口/WebView2 loader 的 x64 PE 格式；窗口为 GUI 子系统，Java 所需私有 VC 运行库在包内。
- macOS 上 9 项共用宿主回归，包括真实 MySQL `SELECT 1`、空 PATH、双实例、正常/异常退出、归属释放前 EOF、Windows 反斜杠与 ADS 路径隔离。

证据及准确产物摘要见 [Windows 交付记录](desktop-validation/native-windows-2026-11-26.json)。Windows 原生检查入口已实现，但本轮没有将其标记为通过：

```powershell
python scripts/validation/windows_bundle_check.py "target/windows-next/Data Agent" --output target/windows-static.json
python scripts/validation/native_windows_smoke.py "target/windows-next/Data Agent" --output target/windows-native.json
```

后者仅允许 Windows 执行，覆盖 WebView2 实际页面导航、空 PATH、带中文和空格的数据目录、私有引导与路径隔离、双实例、标题栏关闭、重启保留配置、强制终止 Job。传入 `--mysql-local-directory` 可追加随包 JDBC 的 `SELECT 1`，使用并清理配置副本。测试不调用真实模型。

下一步在 Windows 11 x64 执行安装、可见页面、连接、SQL/快捷键、真实模型、活动查询/模型流退出、安装目录只读、缺少 WebView2 提示，以及升级/卸载保留配置验收。正式发布还需 Authenticode 签名；Windows ARM64 与跨版本数据迁移另行验证。阶段 D 保持进行中。

参考：[微软跨平台 Windows 构建属性](https://learn.microsoft.com/en-us/dotnet/core/project-sdk/msbuild-props#enablewindowstargeting)、[WebView2 分发](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution)、[Windows Job Object](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)、[.NET 10 支持系统](https://github.com/dotnet/core/blob/main/release-notes/10.0/supported-os.md)。
