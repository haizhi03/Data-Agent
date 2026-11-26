#!/usr/bin/env python3
"""Validate packaged shutdown during a real MySQL read and a controlled real Pi stream."""
import argparse
import concurrent.futures
import datetime
import hashlib
import http.server
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
sys.dont_write_bytecode = True
from desktop_smoke import fingerprints, request, alive


def until(check, seconds, message):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.1)
    raise AssertionError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--native-app', type=Path, help='Drive the native app and its parent-pipe lifecycle instead of start.command')
    parser.add_argument('--mysql-local-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    native_app = args.native_app.resolve() if args.native_app else None
    if native_app and bundle != native_app / 'Contents/Resources/bundle':
        parser.error('For native validation, bundle must be the app Contents/Resources/bundle')
    original = fingerprints(args.mysql_local_directory)
    artifact = fingerprints(bundle)
    manifest_sha = hashlib.sha256((bundle / 'bundle-manifest.json').read_bytes()).hexdigest()
    report = {'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'status': 'FAILED',
              'launchMode': 'native-app' if native_app else 'directory-host',
              'modelTransport': 'test-only fetch redirect to local SSE fixture; real packaged Pi worker',
              'realModelVendorCalls': 0, 'bundleManifestSha256': manifest_sha, 'checks': []}
    if native_app:
        report['nativeExecutableSha256'] = hashlib.sha256((native_app / 'Contents/MacOS/DataAgent').read_bytes()).hexdigest()
        report['nativeBuildSha256'] = hashlib.sha256((native_app / 'Contents/Resources/native-build.json').read_bytes()).hexdigest()
    processes = []
    backend_pids = []
    active = threading.Event()
    disconnected = threading.Event()
    model_calls = []

    class Model(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass
        def do_POST(self):
            body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
            assert body['model'] == 'desktop-active-fixture'
            model_calls.append(1)
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            try:
                for _ in range(300):
                    chunk = {'id': 'fixture', 'object': 'chat.completion.chunk', 'created': 1, 'model': body['model'],
                             'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'working '}, 'finish_reason': None}]}
                    self.wfile.write(('data: ' + json.dumps(chunk) + '\n\n').encode())
                    self.wfile.flush()
                    active.set()
                    time.sleep(.1)
            except (BrokenPipeError, ConnectionResetError):
                disconnected.set()

    model = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Model)
    model.daemon_threads = True
    threading.Thread(target=model.serve_forever, daemon=True).start()
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=3)
    temporary = tempfile.TemporaryDirectory(prefix='desktop active ', dir=bundle.parent)
    try:
        temp = Path(temporary.name)
        hook = temp / 'fixture-fetch.mjs'
        hook.write_text('''const original = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  const req = new Request(input, init);
  if (new URL(req.url).hostname !== 'desktop-fixture.example.invalid') return original(input, init);
  const headers = new Headers(req.headers); headers.set('Authorization', 'Bearer DESKTOP_FIXTURE_ONLY');
  return original(new URL(new URL(req.url).pathname, process.env.DESKTOP_FIXTURE_URL), {
method: req.method, headers, body: await req.arrayBuffer(), signal: req.signal,
  });
};
''')
        connection_id = None
        catalog = None

        def clone(name):
            nonlocal connection_id, catalog
            data = temp / name
            shutil.copytree(args.mysql_local_directory, data, ignore=shutil.ignore_patterns('*.lock', 'logs', 'drivers'))
            p = data / 'connections.json'
            config = json.loads(p.read_text())
            connection = next(c for c in config['connections'] if c['dbType'].lower() == 'mysql')
            digest = hashlib.sha256(Path(connection['driverJarPath']).read_bytes()).hexdigest()
            driver = next(p for p in (bundle / 'drivers').glob('*.jar') if hashlib.sha256(p.read_bytes()).hexdigest() == digest)
            connection['driverJarPath'] = str(driver)
            connection_id, catalog = connection['id'], connection.get('database') or 'sys_demo'
            p.write_text(json.dumps(config))
            return data

        def start(name, fixture=False):
            data = clone(name)
            env = {**os.environ, 'PATH': '', 'DATA_AGENT_DATA_DIRECTORY': str(data)}
            for key in list(env):
                if key.startswith(('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SPRING_', 'APP_', 'NODE_OPTIONS')):
                    env.pop(key)
            if fixture:
                # A standard Node preload redirects only the fixture hostname; package files remain unchanged.
                env['NODE_OPTIONS'] = '--import=' + hook.as_uri()
                env['DESKTOP_FIXTURE_URL'] = 'http://127.0.0.1:' + str(model.server_port)
            entry = native_app / 'Contents/MacOS/DataAgent' if native_app else bundle / 'start.command'
            proc = subprocess.Popen([str(entry)], cwd=temp, env=env, stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            processes.append(proc)
            lines = queue.Queue()
            def read():
                for line in proc.stdout:
                    lines.put(line)
                lines.put(None)
            threading.Thread(target=read, daemon=True).start()
            line = lines.get(timeout=60)
            assert line, 'Packaged startup failed'
            ready = json.loads(line)
            assert ready['type'] == ('native-ready' if native_app else 'ready')
            backend_pids.append(ready['backendPid'])
            return proc, ready

        observer, observer_ready = start('observer')
        observer_url = observer_ready['url']

        def query(base, sql, timeout=15000):
            status, body, _ = request(base, '/api/db/sql/execute', {'connectionId': connection_id, 'catalog': catalog,
                'sql': sql, 'timeoutMs': timeout, 'maxRows': 10, 'executionId': str(uuid.uuid4())})
            result = json.loads(body)['data']
            assert status == 200 and result['success'], 'Read query failed'
            return result

        def running(marker):
            return bool(query(observer_url, "SELECT ID FROM information_schema.PROCESSLIST WHERE INFO LIKE '%" + marker
                              + "%' AND INFO NOT LIKE 'SELECT ID%'")['rows'])

        def worker_pids(parent):
            result = []
            for line in subprocess.check_output(['/bin/ps', '-axo', 'pid=,ppid=,args='], text=True).splitlines():
                parts = line.split(None, 2)
                if len(parts) == 3 and int(parts[1]) == parent and '/worker/src/worker.js' in parts[2]:
                    result.append(int(parts[0]))
            return result

        for kind in ['query', 'model']:
            for how in [signal.SIGTERM, signal.SIGKILL]:
                name = kind + '-' + how.name
                print('Running ' + name, flush=True)
                proc, ready = start(name, fixture=kind == 'model')
                record = {'case': name, 'status': 'FAILED'}
                report['checks'].append(record)
                if kind == 'query':
                    marker = 'desktop_active_' + uuid.uuid4().hex
                    digits = '(SELECT 0 AS n ' + ''.join('UNION ALL SELECT ' + str(n) + ' ' for n in range(1, 10)) + ')'
                    aliases = list('abcdefghi')
                    slow = 'SELECT SUM(' + '+'.join(a + '.n' for a in aliases) + ') FROM ' + ' CROSS JOIN '.join(digits + ' ' + a for a in aliases)
                    task = pool.submit(query, ready['url'], slow + ' /* ' + marker + ' */', 8000)
                    until(lambda: running(marker), 10, 'Slow query was not observed in MySQL')
                    workers = []
                else:
                    active.clear(); disconnected.clear()
                    text_seen = threading.Event()
                    status, body, _ = request(ready['url'], '/api/ai/model-setups', {'name': 'Desktop fixture', 'provider': 'CUSTOM',
                        'apiStyle': 'OPENAI_CHAT_COMPLETIONS', 'endpoint': 'https://desktop-fixture.example.invalid/v1/',
                        'compatibilityProfile': 'openai-chat-v1', 'apiKey': 'DESKTOP_FIXTURE_ONLY', 'modelId': 'desktop-active-fixture',
                        'capabilityProfile': 'text-tools-v1', 'defaultOptions': {'thinkingMode': 'DISABLED', 'maxOutputTokens': 2048},
                        'maxContextTokens': 32768, 'memoryThreshold': 4000, 'enabled': True, 'chatVisible': True, 'makeDefault': False})
                    assert status == 200, body
                    model_id = json.loads(body)['data']['model']['id']
                    status, body, _ = request(ready['url'], '/api/local/conversations', {})
                    conversation = json.loads(body)['data']['conversationId']
                    def stream():
                        payload = json.dumps({'conversationId': conversation, 'modelConfigId': model_id, 'message': 'Explain SELECT slowly. Do not use tools.'}).encode()
                        req = urllib.request.Request(ready['url'] + '/api/local/pi/chat/stream', data=payload, headers={'Content-Type': 'application/json'})
                        with urllib.request.urlopen(req, timeout=40) as response:
                            for line in response:
                                if line.startswith(b'data:'):
                                    block = json.loads(line[5:])
                                    if block.get('type') == 'TEXT' and block.get('data'):
                                        text_seen.set()
                                    assert not (block.get('done') and 'SUCCEEDED' in block.get('data', '')), 'Interrupted stream falsely succeeded'
                    task = pool.submit(stream)
                    assert active.wait(timeout=15), 'Fixture never received a real Pi model request'
                    assert text_seen.wait(timeout=15), 'No Pi text reached the client before shutdown'
                    workers = worker_pids(ready['backendPid'])
                    assert workers, 'No active packaged Pi worker found'
                assert not task.done(), 'Task finished before shutdown was tested'
                record['requestActiveBeforeShutdown'] = True
                started = time.monotonic()
                proc.send_signal(how)
                until(lambda: not alive(ready['backendPid']), 5, 'Java survived host exit for more than 5 seconds')
                for worker in workers:
                    until(lambda: not alive(worker), 5, 'Pi worker survived Java exit')
                if kind == 'query':
                    until(lambda: not running(marker), 5, 'MySQL query survived packaged shutdown')
                else:
                    assert disconnected.wait(timeout=5), 'Model transport was not disconnected'
                exit_code = proc.wait(timeout=5)
                assert exit_code == (0 if how == signal.SIGTERM else -signal.SIGKILL), 'Unexpected application exit status'
                if native_app:
                    until(lambda: not alive(ready['hostPid']), 5, 'Node host survived native app exit')
                try:
                    task.result(timeout=5)
                except (OSError, ValueError, TimeoutError):
                    pass  # Closing a live request is expected; successful DONE is forbidden above.
                record.update(status='PASS', cleanupMs=round((time.monotonic() - started) * 1000), workers=len(workers), exitCode=exit_code)
        report.update(status='PASS', fixtureModelRequests=len(model_calls), originalConfigurationUnchanged=original == fingerprints(args.mysql_local_directory),
                      bundleUnchanged=artifact == fingerprints(bundle))
        assert report['originalConfigurationUnchanged'] and report['bundleUnchanged'] and len(model_calls) == 2
    except Exception as error:
        report['failureReason'] = str(error)
        raise
    finally:
        for proc in processes:
            if proc.poll() is None:
                proc.terminate()
                try:
                    proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    proc.kill(); proc.wait(timeout=5)
        for pid in backend_pids:
            until(lambda: not alive(pid), 20, 'Backend cleanup failed')
        pool.shutdown(wait=True)
        model.shutdown(); model.server_close()
        temporary.cleanup()
        report['originalConfigurationUnchanged'] = original == fingerprints(args.mysql_local_directory)
        report['bundleUnchanged'] = artifact == fingerprints(bundle)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
