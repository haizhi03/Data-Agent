#!/usr/bin/env python3
"""Exercise the assembled bundle with an empty PATH, disposable data, and no model calls."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import signal
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


def fingerprints(root):
    return {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob('*') if p.is_file() and not p.name.endswith('.lock')}


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def gone(pid):
    deadline = time.monotonic() + 20
    while alive(pid) and time.monotonic() < deadline:
        time.sleep(.1)
    assert not alive(pid), f'Child process {pid} did not exit'


def request(base, route, body=None, headers=None):
    req = urllib.request.Request(base + route, headers=headers or {},
                                 data=None if body is None else json.dumps(body).encode())
    if body is not None:
        req.add_header('Content-Type', 'application/json')
    try:
        response = urllib.request.urlopen(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, response.read().decode(), dict(response.headers)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--mysql-local-directory', type=Path, help='Clone existing local configuration for a read-only SELECT 1 using the bundled driver')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle.resolve()
    manifest = json.loads((bundle / 'bundle-manifest.json').read_text())
    for name, digest in manifest['artifacts'].items():
        assert hashlib.sha256((bundle / name).read_bytes()).hexdigest() == digest, f'Artifact changed: {name}'
    before = fingerprints(args.mysql_local_directory) if args.mysql_local_directory else None
    bundle_before = fingerprints(bundle)
    processes = []
    checks = []
    output = args.output.absolute()
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'platform': manifest['platform'],
              'bundleManifestSha256': hashlib.sha256((bundle / 'bundle-manifest.json').read_bytes()).hexdigest(),
              'checks': checks, 'modelCalls': 0, 'status': 'FAILED'}
    try:
        with tempfile.TemporaryDirectory(prefix='data-agent desktop smoke ') as temporary:
            temp = Path(temporary)

            def start(data, root=bundle):
                # Build tools and globally installed Java/Node are deliberately unreachable.
                env = {**os.environ, 'PATH': '', 'DATA_AGENT_DATA_DIRECTORY': str(data)}
                for key in list(env):
                    if key.startswith(('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SPRING_', 'APP_', 'NODE_OPTIONS')):
                        env.pop(key)
                process = subprocess.Popen([str(root / 'start.command')], cwd=temp, env=env,
                                           stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
                processes.append(process)
                messages = queue.Queue()
                def read_lines():
                    for line in process.stdout:
                        messages.put(line)
                    messages.put(None)
                threading.Thread(target=read_lines, daemon=True).start()
                line = messages.get(timeout=60)
                assert line is not None, 'Host exited before readiness: ' + process.stderr.read()
                ready = json.loads(line)
                assert ready['type'] == 'ready' and ready['hostPid'] == process.pid
                assert 'transportToken' not in line and 'apiKey' not in line
                return process, ready

            primary_data = temp / 'primary data'
            if args.mysql_local_directory:
                shutil.copytree(args.mysql_local_directory, primary_data, ignore=shutil.ignore_patterns('*.lock', 'logs', 'drivers'))
                config_path = primary_data / 'connections.json'
                config = json.loads(config_path.read_text())
                candidates = [c for c in config['connections'] if c['dbType'].lower() == 'mysql']
                assert candidates, 'No existing MySQL connection configured'
                connection = candidates[0]
                driver = next((p for p in (bundle / 'drivers').glob('*.jar')
                               if hashlib.sha256(p.read_bytes()).hexdigest() == hashlib.sha256(Path(connection['driverJarPath']).read_bytes()).hexdigest()), None)
                assert driver, 'Pass the configured MySQL driver to build.py --driver'
                connection['driverJarPath'] = str(driver)
                config_path.write_text(json.dumps(config))
            first, ready = start(primary_data)
            base = ready['url']
            status, body, headers = request(base, '/')
            assert status == 200 and '/__data_agent_host.js' in body and '/assets/' in body
            assert headers.get('Cache-Control') == 'no-store'
            status, public_bridge, _ = request(base, '/__data_agent_host.js')
            assert status == 200 and '"transportToken":""' in public_bridge and base + '/api' in public_bridge
            status, body, _ = request(base, '/api/runtime/capabilities')
            capabilities = json.loads(body)['data']
            assert status == 200 and capabilities['mode'] == 'LOCAL'
            assert capabilities['features']['databaseWorkbench']['state'] == 'READY'
            assert capabilities['features']['piAssistant']['state'] == 'READY'
            assert not capabilities['accountLoginEnabled'] and not capabilities['chatHistoryPersistenceEnabled']
            checks.append({'case': 'packaged-ui-java-worker-start-without-path', 'status': 'PASS'})
            for route in ['/api/runtime/capabilities', '/__data_agent_host.js']:
                assert request(base, route, headers={'Origin': 'https://example.invalid'})[0] == 403
                assert request(base, route, headers={'Host': 'attacker.invalid'})[0] == 403
                assert request(base, route, headers={'Sec-Fetch-Site': 'cross-site'})[0] == 403
            assert request(base, '/%2e%2e/server.jar')[0] == 404
            assert request(base, '/server.jar')[0] == 404
            assert request(base, '/runtime-lock.json')[0] == 404
            status, body, _ = request(base, '/api/local/conversations/1', headers={
                'X-Local-Runtime-Id': '00000000-0000-4000-8000-000000000000'})
            assert status == 409 and 'LOCAL_RUNTIME_MISMATCH' in body
            checks.append({'case': 'origin-host-and-static-path-isolation', 'status': 'PASS'})
            # Backend API remains authenticated; the parent strips the private handshake before logging.
            log_path = primary_data / 'logs/desktop-host.log'
            log = log_path.read_text()
            assert 'DATA_AGENT_HOST_READY ' not in log and 'transportToken' not in log
            port = re.search(r'Tomcat started on port (\d+)', log).group(1)
            backend_url = 'http://127.0.0.1:' + port
            assert request(backend_url, '/api/runtime/capabilities')[0] == 401
            checks.append({'case': 'private-bootstrap-and-authenticated-backend', 'status': 'PASS'})
            if args.mysql_local_directory:
                status, body, _ = request(base, '/api/db/sql/execute', {
                    'connectionId': connection['id'], 'catalog': connection.get('database') or 'sys_demo',
                    'sql': 'SELECT 1 AS packaged_runtime', 'timeoutMs': 15000, 'maxRows': 1})
                result = json.loads(body)['data']
                assert status == 200 and result['success'] and result['rows'][0][0] == 1
                checks.append({'case': 'packaged-jre-mysql-driver-read-only-query', 'status': 'PASS'})
            second, other = start(temp / 'second data')
            assert other['url'] != base and other['backendPid'] != ready['backendPid']
            assert request(other['url'], '/api/runtime/capabilities')[0] == 200
            checks.append({'case': 'two-instances-use-independent-ports-and-data', 'status': 'PASS'})
            first.send_signal(signal.SIGTERM)
            assert first.wait(timeout=25) == 0
            gone(ready['backendPid'])
            assert request(other['url'], '/api/runtime/capabilities')[0] == 200
            checks.append({'case': 'graceful-exit-cleans-backend-without-affecting-other-instance', 'status': 'PASS'})
            second.kill()
            second.wait(timeout=5)
            gone(other['backendPid'])
            checks.append({'case': 'killed-host-eof-cleans-backend', 'status': 'PASS'})
            # A missing packaged jar must fail visibly, release processes, and never announce ready.
            broken = temp / 'broken bundle'
            broken.mkdir()
            for name in ['runtime', 'worker', 'web']:
                (broken / name).symlink_to(bundle / name, target_is_directory=True)
            for name in ['host.mjs', 'start.command']:
                shutil.copy2(bundle / name, broken / name)
            env = {**os.environ, 'PATH': '', 'DATA_AGENT_DATA_DIRECTORY': str(temp / 'broken data')}
            failure = subprocess.Popen([str(broken / 'start.command')], cwd=temp, env=env,
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            processes.append(failure)
            stdout, stderr = failure.communicate(timeout=30)
            assert failure.returncode != 0 and '"type":"ready"' not in stdout and 'BACKEND_EXITED' in stderr
            checks.append({'case': 'missing-jar-fails-without-false-ready', 'status': 'PASS'})
        report['bundleUnchanged'] = bundle_before == fingerprints(bundle)
        assert report['bundleUnchanged']
        report['originalConfigurationUnchanged'] = before is None or before == fingerprints(args.mysql_local_directory)
        assert report['originalConfigurationUnchanged']
        report['status'] = 'PASS'
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            for pipe in [process.stdin, process.stdout, process.stderr]:
                if pipe:
                    pipe.close()
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
