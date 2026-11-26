import AppKit
import WebKit
import Darwin

// The window owns one existing Node host. No Node or Java credentials enter WebKit.
final class DesktopApp: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate {
    private var window: NSWindow!
    private var webView: WKWebView!
    private var host: Process?
    private let input = Pipe()
    private let output = Pipe()
    private var buffer = Data()
    private var origin: URLComponents?
    private var quitting = false
    private var terminateSignal: DispatchSourceSignal?

    func applicationDidFinishLaunching(_ notification: Notification) {
        signal(SIGTERM, SIG_IGN)
        terminateSignal = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        terminateSignal?.setEventHandler {
            // terminateLater runs an AppKit modal loop; leave the main dispatch item first.
            RunLoop.main.perform(inModes: [.common]) { NSApp.terminate(nil) }
        }
        terminateSignal?.resume()
        let menu = NSMenu()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "退出 Data Agent", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        let appItem = NSMenuItem(); appItem.submenu = appMenu; menu.addItem(appItem)
        let editMenu = NSMenu(title: "编辑")
        for (title, action, key) in [("撤销", "undo:", "z"), ("重做", "redo:", "Z"),
                                     ("剪切", "cut:", "x"), ("复制", "copy:", "c"),
                                     ("粘贴", "paste:", "v"), ("全选", "selectAll:", "a")] {
            editMenu.addItem(withTitle: title, action: Selector(action), keyEquivalent: key)
        }
        let editItem = NSMenuItem(title: "编辑", action: nil, keyEquivalent: "")
        editItem.submenu = editMenu; menu.addItem(editItem); NSApp.mainMenu = menu
        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1280, height: 820),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
        window.title = "Data Agent — 正在启动…"
        window.minSize = NSSize(width: 960, height: 640)
        window.isReleasedWhenClosed = false
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.navigationDelegate = self; webView.uiDelegate = self
        window.contentView = webView
        window.center(); window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
        startHost()
    }

    private func startHost() {
        guard let resources = Bundle.main.resourceURL else { fail("应用资源缺失，请重新安装。"); return }
        let bundle = resources.appendingPathComponent("bundle")
        let process = Process()
        process.executableURL = bundle.appendingPathComponent("runtime/node/bin/node")
        process.arguments = [bundle.appendingPathComponent("host.mjs").path, "--parent-pipe"]
        // Finder may start in a read-only application directory. The host chooses the user data directory.
        process.currentDirectoryURL = FileManager.default.temporaryDirectory
        process.standardInput = input; process.standardOutput = output; process.standardError = FileHandle.standardError
        output.fileHandleForReading.readabilityHandler = { [weak self] file in
            let data = file.availableData
            if data.isEmpty { file.readabilityHandler = nil; return }
            DispatchQueue.main.async { self?.consume(data) }
        }
        process.terminationHandler = { [weak self] _ in
            RunLoop.main.perform(inModes: [.common, .modalPanel]) {
                guard let self = self else { return }
                if self.quitting { NSApp.reply(toApplicationShouldTerminate: true) }
                else { self.fail("本地服务已退出，请关闭并重新打开应用。") }
            }
        }
        host = process
        do { try process.run() }
        catch { host = nil; fail("无法启动随包运行时，请重新安装应用。"); return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 60) { [weak self] in
            guard let self = self, self.origin == nil, !self.quitting else { return }
            self.host?.terminate(); self.fail("启动超时，请检查数据目录中的 logs/desktop-host.log。")
        }
    }

    private func consume(_ data: Data) {
        guard !quitting, origin == nil else { return }
        buffer.append(data)
        if buffer.count > 8192 { host?.terminate(); fail("本地服务启动信息无效。"); return }
        while let end = buffer.firstIndex(of: 10) {
            let line = buffer.prefix(upTo: end); buffer.removeSubrange(...end)
            guard let frame = try? JSONSerialization.jsonObject(with: line) as? [String: Any],
                  frame["type"] as? String == "ready", let address = frame["url"] as? String,
                  let url = URLComponents(string: address), url.scheme == "http", url.host == "127.0.0.1",
                  let port = url.port, (1...65535).contains(port), url.user == nil, url.password == nil,
                  url.query == nil, url.fragment == nil, url.path.isEmpty || url.path == "/",
                  frame["hostPid"] as? Int == Int(host?.processIdentifier ?? 0), let target = url.url else {
                host?.terminate(); fail("本地服务启动信息无效。"); return
            }
            origin = url
            window.title = "Data Agent"
            webView.load(URLRequest(url: target))
            // Public readiness receipt for local validation; no transport token or user configuration.
            var receipt = frame; receipt["type"] = "native-ready"; receipt["appPid"] = Int(getpid())
            if let encoded = try? JSONSerialization.data(withJSONObject: receipt) {
                FileHandle.standardOutput.write(encoded + Data([10]))
            }
        }
    }

    private func fail(_ message: String) {
        guard !quitting else { return }
        window.title = "Data Agent — 启动失败"
        let label = NSTextField(wrappingLabelWithString: message)
        label.alignment = .center; label.font = .systemFont(ofSize: 16)
        let view = NSView(); label.translatesAutoresizingMaskIntoConstraints = false; view.addSubview(label)
        NSLayoutConstraint.activate([label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                                     label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                                     label.widthAnchor.constraint(lessThanOrEqualToConstant: 680)])
        window.contentView = view
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let process = host, process.isRunning else { return .terminateNow }
        if quitting { return .terminateLater }
        quitting = true
        input.fileHandleForWriting.closeFile()
        process.terminate()
        DispatchQueue.main.asyncAfter(deadline: .now() + 20) {
            if process.isRunning { kill(process.processIdentifier, SIGKILL) }
        }
        return .terminateLater
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = action.request.url, let expected = origin,
           url.scheme == expected.scheme, url.host == expected.host, url.port == expected.port {
            decisionHandler(.allow)
        } else {
            if action.navigationType == .linkActivated, let url = action.request.url, url.scheme == "https" {
                NSWorkspace.shared.open(url)
            }
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, runOpenPanelWith parameters: WKOpenPanelParameters,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping ([URL]?) -> Void) {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = false; panel.allowsMultipleSelection = parameters.allowsMultipleSelection
        panel.beginSheetModal(for: window) { response in completionHandler(response == .OK ? panel.urls : nil) }
    }
}

let app = NSApplication.shared
let delegate = DesktopApp()
app.setActivationPolicy(.regular)
app.delegate = delegate
app.run()
