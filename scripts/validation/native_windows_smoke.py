#!/usr/bin/env python3
"""Windows-only native lifecycle checks; optional JDBC check uses a disposable config clone."""
import argparse
import ctypes
from contextlib import contextmanager
from ctypes import wintypes
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request


def fingerprints(root):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob('*') if p.is_file() and not p.name.endswith('.lock')
            and not {'logs', 'webview2'}.intersection(p.relative_to(root).parts)}


def request(base, route, body=None, headers=None):
    req = urllib.request.Request(base.rstrip('/') + route, headers=headers or {},
                                 data=None if body is None else json.dumps(body).encode())
    if body is not None:
        req.add_header('Content-Type', 'application/json')
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mysql-local-directory', type=Path)
    args = parser.parse_args()
    if os.name != 'nt':
        parser.error('This test must execute on Windows; use windows_bundle_check.py for cross-build checks')
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    user = ctypes.WinDLL('user32', use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    callback_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user.EnumWindows.argtypes = [callback_type, wintypes.LPARAM]
    user.GetWindowThreadProcessId.argtypes = [wintypes.HWND, ctypes.POINTER(wintypes.DWORD)]
    user.PostMessageW.argtypes = [wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM]

    def alive(pid):
        handle = kernel.OpenProcess(0x1000, False, pid)
        if not handle:
            return False
        try:
            code = wintypes.DWORD()
            assert kernel.GetExitCodeProcess(handle, ctypes.byref(code))
            return code.value == 259
        finally:
            kernel.CloseHandle(handle)

    def gone(pid):
        deadline = time.monotonic() + 25
        while alive(pid) and time.monotonic() < deadline:
            time.sleep(.1)
        assert not alive(pid), f'Owned process {pid} did not exit'

    def close_window(pid):
        found = []
        @callback_type
        def visit(window, _):
            owner = wintypes.DWORD()
            user.GetWindowThreadProcessId(window, ctypes.byref(owner))
            if owner.value == pid:
                found.append(window)
                user.PostMessageW(window, 0x10, 0, 0)  # WM_CLOSE, same path as title-bar close.
            return True
        user.EnumWindows(visit, 0)
        assert found, 'No native window found'

    package = args.package.resolve()
    processes = []
    checks = []
    before_package = fingerprints(package)
    before_config = fingerprints(args.mysql_local_directory) if args.mysql_local_directory else None
    report = {'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'platform': 'Windows x64',
              'checks': checks, 'status': 'FAILED', 'modelCalls': 0}
    @contextmanager
    def isolated_directory():
        temporary = tempfile.TemporaryDirectory(prefix='data-agent Windows 测试 ')
        try:
            yield temporary.name
        finally:
            # Windows cannot remove open profile/config files. Stop owned apps before cleanup,
            # including failed startup, then allow WebView2's browser process to release its files.
            for process in processes:
                if process.poll() is None:
                    process.kill(); process.wait(timeout=10)
            deadline = time.monotonic() + 30
            while True:
                try:
                    temporary.cleanup()
                    break
                except PermissionError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(.2)
    try:
        with isolated_directory() as temporary:
            temp = Path(temporary)
            def start(data):
                receipt = temp / (data.name + '.ready.json')
                receipt.unlink(missing_ok=True)
                env = {**os.environ, 'PATH': '', 'DATA_AGENT_DATA_DIRECTORY': str(data),
                       'DATA_AGENT_NATIVE_STATUS_FILE': str(receipt)}
                for key in list(env):
                    if key.startswith(('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SPRING_', 'APP_', 'NODE_OPTIONS')):
                        env.pop(key)
                process = subprocess.Popen([str(package / 'DataAgent.exe')], cwd=temp, env=env)
                processes.append(process)
                deadline = time.monotonic() + 75
                while not receipt.exists() and time.monotonic() < deadline and process.poll() is None:
                    time.sleep(.1)
                assert receipt.exists(), 'Native app did not become ready (check WebView2 installation and user logs)'
                ready = json.loads(receipt.read_text(encoding='utf-8-sig'))
                assert ready['appPid'] == process.pid and ready['type'] == 'native-ready' and ready['navigationCompleted']
                assert 'transportToken' not in ready
                return process, ready

            data = temp / 'primary data'
            if args.mysql_local_directory:
                shutil.copytree(args.mysql_local_directory, data, ignore=shutil.ignore_patterns('*.lock', 'logs', 'drivers', 'webview2'))
                config_file = data / 'connections.json'
                config = json.loads(config_file.read_text(encoding='utf-8'))
                connection = next(c for c in config['connections'] if c['dbType'].lower() == 'mysql')
                digest = hashlib.sha256(Path(connection['driverJarPath']).read_bytes()).hexdigest()
                driver = next(p for p in (package / 'bundle/drivers').glob('*.jar') if hashlib.sha256(p.read_bytes()).hexdigest() == digest)
                connection['driverJarPath'] = str(driver)
                config_file.write_text(json.dumps(config), encoding='utf-8')
            first, ready = start(data)
            base = ready['url']
            status, body = request(base, '/')
            assert status == 200 and '/__data_agent_host.js' in body
            status, body = request(base, '/api/runtime/capabilities')
            capabilities = json.loads(body)['data']
            assert status == 200 and capabilities['mode'] == 'LOCAL'
            assert capabilities['features']['piAssistant']['state'] == 'READY'
            checks.append({'case': 'native-webview-java-worker-start-with-empty-path-unicode-data-directory', 'status': 'PASS'})
            status, body = request(base, '/__data_agent_host.js')
            assert status == 200 and '"transportToken":""' in body
            for route in ['/%2e%2e%5cserver.jar', '/index.html%3Astream', '/%00', '/server.jar']:
                assert request(base, route)[0] == 404, route
            assert request(base, '/api/runtime/capabilities', headers={'Origin': 'https://example.invalid'})[0] == 403
            assert request(base, '/api/runtime/capabilities', headers={'Host': 'attacker.invalid'})[0] == 403
            checks.append({'case': 'private-bootstrap-origin-and-windows-path-isolation', 'status': 'PASS'})
            if args.mysql_local_directory:
                status, body = request(base, '/api/db/sql/execute', {'connectionId': connection['id'],
                    'catalog': connection.get('database') or 'sys_demo', 'sql': 'SELECT 1 AS windows_runtime', 'timeoutMs': 15000, 'maxRows': 1})
                result = json.loads(body)['data']
                assert status == 200 and result['success'] and result['rows'][0][0] == 1
                checks.append({'case': 'bundled-jre-jdbc-read-only-query', 'status': 'PASS'})
            second, other = start(temp / 'second data')
            assert other['url'] != base and other['backendPid'] != ready['backendPid']
            checks.append({'case': 'parallel-native-instances-isolated', 'status': 'PASS'})
            close_window(first.pid)
            assert first.wait(timeout=30) == 0
            gone(ready['hostPid']); gone(ready['backendPid'])
            assert request(other['url'], '/api/runtime/capabilities')[0] == 200
            checks.append({'case': 'window-close-cleans-owned-host-backend-only', 'status': 'PASS'})
            saved = fingerprints(data)
            restarted, fresh = start(data)
            assert fresh['hostPid'] != ready['hostPid'] and fingerprints(data) == saved
            close_window(restarted.pid)
            assert restarted.wait(timeout=30) == 0
            gone(fresh['hostPid']); gone(fresh['backendPid'])
            checks.append({'case': 'restart-preserves-user-configuration', 'status': 'PASS'})
            second.kill(); second.wait(timeout=10)
            gone(other['hostPid']); gone(other['backendPid'])
            checks.append({'case': 'terminated-native-job-cleans-descendants', 'status': 'PASS'})
        assert before_package == fingerprints(package), 'Application wrote into installed payload'
        assert before_config is None or before_config == fingerprints(args.mysql_local_directory), 'Original configuration changed'
        report.update(status='PASS', packageUnchanged=True, originalConfigurationUnchanged=True)
    finally:
        for process in processes:
            if process.poll() is None:
                process.kill(); process.wait(timeout=10)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
