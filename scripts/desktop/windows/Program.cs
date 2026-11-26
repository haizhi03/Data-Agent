using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32.SafeHandles;

namespace DataAgent.Desktop;

internal static class Program
{
    [STAThread]
    private static void Main()
    {
        using var installMutex = new Mutex(false, "DataAgent.Desktop"); // Installer detects running copies.
        ApplicationConfiguration.Initialize();
        Application.Run(new DesktopWindow());
    }
}

internal sealed class DesktopWindow : Form
{
    private readonly WebView2 browser = new() { Dock = DockStyle.Fill };
    private readonly Label status = new() { Dock = DockStyle.Fill, Text = "正在启动 Data Agent…", TextAlign = ContentAlignment.MiddleCenter };
    private readonly string data = Path.GetFullPath(Environment.GetEnvironmentVariable("DATA_AGENT_DATA_DIRECTORY")
        ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".data-agent", "local"));
    private Process? host;
    private ProcessJob? job;
    private Uri? origin;
    private bool closing;
    private bool stopped;
    private readonly CancellationTokenSource lifetime = new();

    public DesktopWindow()
    {
        Text = "Data Agent";
        Size = new Size(1280, 850);
        MinimumSize = new Size(900, 600);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(browser);
        Controls.Add(status);
        Shown += async (_, _) => await StartAsync();
        FormClosing += OnClosing;
    }

    private async Task StartAsync()
    {
        try
        {
            // Evergreen is shared and updated by Microsoft; never use a developer's Edge profile.
            _ = CoreWebView2Environment.GetAvailableBrowserVersionString();
            Directory.CreateDirectory(Path.Combine(data, "logs"));
            var environment = await CoreWebView2Environment.CreateAsync(userDataFolder: Path.Combine(data, "webview2"));
            if (closing) return;
            await browser.EnsureCoreWebView2Async(environment);
            if (closing) return;
            browser.CoreWebView2.NavigationStarting += (_, e) =>
            {
                if (!IsLocal(e.Uri)) { e.Cancel = true; OpenExternal(e.Uri); }
            };
            browser.CoreWebView2.NewWindowRequested += (_, e) => { e.Handled = true; OpenExternal(e.Uri); };
            browser.CoreWebView2.PermissionRequested += (_, e) => e.State = CoreWebView2PermissionState.Deny;
            browser.CoreWebView2.Settings.AreDevToolsEnabled = false;
            browser.CoreWebView2.Settings.IsStatusBarEnabled = false;
            var bundle = Path.Combine(AppContext.BaseDirectory, "bundle");
            job = new ProcessJob();
            var info = new ProcessStartInfo(Path.Combine(bundle, "runtime", "node", "bin", "node.exe"))
            {
                WorkingDirectory = data, UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8
            };
            info.ArgumentList.Add(Path.Combine(bundle, "host.mjs"));
            info.ArgumentList.Add("--parent-pipe");
            info.ArgumentList.Add("--start-gated");
            host = Process.Start(info) ?? throw new IOException("HOST_START_FAILED");
            // The host cannot spawn Java or Worker until assignment succeeds.
            job.Add(host);
            _ = LogErrorsAsync(host);
            await host.StandardInput.WriteAsync("start\n");
            await host.StandardInput.FlushAsync();
            var line = await host.StandardOutput.ReadLineAsync(lifetime.Token).AsTask().WaitAsync(TimeSpan.FromSeconds(60), lifetime.Token);
            using var ready = JsonDocument.Parse(line ?? throw new IOException("HOST_EXITED"));
            var root = ready.RootElement;
            if (root.GetProperty("type").GetString() != "ready" || root.GetProperty("hostPid").GetInt32() != host.Id
                || !Uri.TryCreate(root.GetProperty("url").GetString(), UriKind.Absolute, out var url)
                || url.Scheme != "http" || url.Host != "127.0.0.1" || url.Port <= 0 || url.AbsolutePath != "/"
                || url.Query != "" || url.Fragment != "" || url.UserInfo != "") throw new IOException("HOST_READY_INVALID");
            origin = url;
            var navigation = new TaskCompletionSource<bool>();
            browser.CoreWebView2.NavigationCompleted += (_, e) => navigation.TrySetResult(e.IsSuccess);
            browser.Source = origin;
            if (!await navigation.Task.WaitAsync(TimeSpan.FromSeconds(30), lifetime.Token)) throw new IOException("WEBVIEW_NAVIGATION_FAILED");
            status.Visible = false;
            // Optional public test receipt; the private API token never leaves the Node host.
            var receipt = Environment.GetEnvironmentVariable("DATA_AGENT_NATIVE_STATUS_FILE");
            if (!string.IsNullOrEmpty(receipt))
                await File.WriteAllTextAsync(receipt, JsonSerializer.Serialize(new { type = "native-ready", appPid = Environment.ProcessId,
                    hostPid = host.Id, backendPid = root.GetProperty("backendPid").GetInt32(), url = origin.AbsoluteUri, navigationCompleted = true }));
            await host.WaitForExitAsync(lifetime.Token);
            if (!closing) throw new IOException("HOST_EXITED");
        }
        catch (OperationCanceledException) when (closing) { }
        catch (WebView2RuntimeNotFoundException)
        {
            if (!closing && MessageBox.Show(this, "需要 Microsoft Edge WebView2 Runtime。是否打开微软官方下载页？安装后重新打开 Data Agent。",
                "Data Agent", MessageBoxButtons.YesNo, MessageBoxIcon.Information) == DialogResult.Yes)
                OpenExternal("https://developer.microsoft.com/microsoft-edge/webview2/");
            Close();
        }
        catch (Exception error)
        {
            if (!closing)
            {
                try
                {
                    Directory.CreateDirectory(Path.Combine(data, "logs"));
                    File.AppendAllText(Path.Combine(data, "logs", "windows-shell.log"),
                        $"WINDOWS_SHELL_FAILED {error.GetType().Name} {(error is Win32Exception native ? native.NativeErrorCode : 0)}\n");
                }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
                MessageBox.Show(this, $"启动或运行失败（{error.GetType().Name}）。请查看用户目录 .data-agent/local/logs 下的日志。", "Data Agent", MessageBoxButtons.OK, MessageBoxIcon.Error);
                Close();
            }
        }
    }

    private bool IsLocal(string value) => Uri.TryCreate(value, UriKind.Absolute, out var url) && origin != null
        && url.Scheme == origin.Scheme && url.Host == origin.Host && url.Port == origin.Port && url.UserInfo == "";

    private static void OpenExternal(string value)
    {
        if (Uri.TryCreate(value, UriKind.Absolute, out var url) && url.Scheme is "https" or "http")
            try { Process.Start(new ProcessStartInfo(url.AbsoluteUri) { UseShellExecute = true }); } catch (Win32Exception) { }
    }

    private async Task LogErrorsAsync(Process process)
    {
        try
        {
            using var file = new StreamWriter(Path.Combine(data, "logs", "windows-shell.log"), append: true) { AutoFlush = true };
            while (await process.StandardError.ReadLineAsync() is { } line) await file.WriteLineAsync(line);
        }
        catch (IOException) { /* The host has its own log; logging must not prevent shutdown. */ }
        catch (UnauthorizedAccessException) { }
    }

    private async void OnClosing(object? sender, FormClosingEventArgs e)
    {
        if (stopped) return;
        e.Cancel = true;
        if (closing) return;
        closing = true;
        status.Text = "正在退出 Data Agent…";
        status.Visible = true;
        lifetime.Cancel();
        browser.Enabled = false;
        try
        {
            if (host is { HasExited: false })
            {
                host.StandardInput.Close(); // EOF asks Node -> Java to cancel work and exit gracefully.
                try { await host.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(20)); }
                catch (TimeoutException) { host.Kill(entireProcessTree: true); }
            }
        }
        catch (InvalidOperationException) { }
        catch (Win32Exception) { }
        catch (IOException) { }
        finally
        {
            job?.Dispose(); // Also catches grandchildren if the host exited unexpectedly.
            browser.Dispose();
            host?.Dispose();
            stopped = true;
            Close();
        }
    }
}

// Closing this handle (including abrupt native process termination) kills the entire owned tree.
internal sealed class ProcessJob : IDisposable
{
    private readonly SafeFileHandle handle;
    public ProcessJob()
    {
        handle = CreateJobObject(IntPtr.Zero, null);
        if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
        var limits = new ExtendedLimits { BasicLimitInformation = new BasicLimits { LimitFlags = 0x2000 } }; // KILL_ON_JOB_CLOSE
        if (!SetInformationJobObject(handle, 9, ref limits, (uint)Marshal.SizeOf<ExtendedLimits>()))
        {
            var error = Marshal.GetLastWin32Error(); handle.Dispose(); throw new Win32Exception(error);
        }
    }
    public void Add(Process process)
    {
        if (!AssignProcessToJobObject(handle, process.Handle)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
    public void Dispose() => handle.Dispose();
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits
    {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public nuint MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public nuint Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters { public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount; }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits
    {
        public BasicLimits BasicLimitInformation;
        public IoCounters IoInfo;
        public nuint ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern SafeFileHandle CreateJobObject(IntPtr attributes, string? name);
    [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetInformationJobObject(SafeFileHandle job, int infoClass, ref ExtendedLimits info, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AssignProcessToJobObject(SafeFileHandle job, IntPtr process);
}
