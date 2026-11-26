#!/usr/bin/env python3
"""Check read-only DMG startup and stopped-app replacement without touching original user data."""
import argparse
import datetime
import errno
import hashlib
import json
import os
from pathlib import Path
import plistlib
import queue
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
sys.dont_write_bytecode = True
from desktop_smoke import fingerprints, request, gone


def configuration_fingerprints(root):
    # Runtime logs append on each launch; compare only retained user configuration.
    return {name: value for name, value in fingerprints(root).items() if not name.startswith('logs/')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('app', type=Path)
    parser.add_argument('--dmg', type=Path, required=True)
    parser.add_argument('--mysql-local-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    app, dmg = args.app.resolve(), args.dmg.resolve()
    original = fingerprints(args.mysql_local_directory)
    application = fingerprints(app)
    checks = []
    report = {'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'status': 'FAILED',
              'scope': 'development-machine native packaging and same-payload replacement, not schema migration',
              'dmgSha256': hashlib.sha256(dmg.read_bytes()).hexdigest(), 'modelVendorCalls': 0, 'checks': checks}
    processes, receipts = [], []
    mounted = False
    temporary = tempfile.TemporaryDirectory(prefix='native install ', dir=app.parent.parent)
    temp = Path(temporary.name)
    mount = temp / 'Read Only Image'
    mount.mkdir()

    def stop(proc, ready):
        proc.terminate()
        assert proc.wait(timeout=15) == 0
        for key in ['hostPid', 'backendPid']:
            gone(ready[key])

    try:
        subprocess.run(['/usr/bin/codesign', '--verify', '--deep', '--strict', str(app)], check=True)
        subprocess.run(['/usr/bin/hdiutil', 'verify', '-quiet', str(dmg)], check=True)
        subprocess.run(['/usr/bin/hdiutil', 'attach', '-readonly', '-nobrowse', '-mountpoint', str(mount), str(dmg)],
                       check=True, stdout=subprocess.DEVNULL)
        mounted = True
        assert os.statvfs(mount).f_flag & os.ST_RDONLY
        try:
            (mount / 'write-probe').write_text('must not be writable')
            raise AssertionError('Image accepted a write')
        except OSError as error:
            assert error.errno == errno.EROFS
        mounted_app = mount / app.name
        assert fingerprints(mounted_app) == application
        checks.append({'case': 'signature-and-read-only-dmg-integrity', 'status': 'PASS'})
        data = temp / 'user data'
        shutil.copytree(args.mysql_local_directory, data, ignore=shutil.ignore_patterns('*.lock', 'logs', 'drivers'))
        config_path = data / 'connections.json'
        config = json.loads(config_path.read_text())
        connection = next(c for c in config['connections'] if c['dbType'].lower() == 'mysql')
        driver_sha = hashlib.sha256(Path(connection['driverJarPath']).read_bytes()).hexdigest()
        driver_name = next(p.name for p in (app / 'Contents/Resources/bundle/drivers').glob('*.jar')
                           if hashlib.sha256(p.read_bytes()).hexdigest() == driver_sha)

        def bind_driver(root):
            connection['driverJarPath'] = str(root / 'Contents/Resources/bundle/drivers' / driver_name)
            connection['name'] = 'Native retention check'
            config_path.write_text(json.dumps(config))

        def start(root):
            env = {**os.environ, 'PATH': '', 'DATA_AGENT_DATA_DIRECTORY': str(data)}
            for key in list(env):
                if key.startswith(('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'SPRING_', 'APP_', 'NODE_OPTIONS')):
                    env.pop(key)
            proc = subprocess.Popen([str(root / 'Contents/MacOS/DataAgent')], cwd=temp, env=env, stdin=subprocess.PIPE,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            processes.append(proc)
            lines = queue.Queue()
            def read():
                for line in proc.stdout:
                    lines.put(line)
                lines.put(None)
            threading.Thread(target=read, daemon=True).start()
            line = lines.get(timeout=60)
            assert line, 'Native process did not announce readiness'
            ready = json.loads(line)
            assert ready['type'] == 'native-ready' and ready['appPid'] == proc.pid
            receipts.append(ready)
            return proc, ready

        def verify(ready):
            base = ready['url']
            status, body, _ = request(base, '/api/runtime/capabilities')
            capability = json.loads(body)['data']
            assert status == 200 and capability['features']['piAssistant']['state'] == 'READY'
            status, body, _ = request(base, '/api/connections')
            assert status == 200 and any(c['name'] == connection['name'] for c in json.loads(body)['data'])
            status, models, _ = request(base, '/api/ai/model-configs')
            assert status == 200 and json.loads(models)['data']
            status, body, _ = request(base, '/api/db/sql/execute', {'connectionId': connection['id'],
                'catalog': connection.get('database') or 'sys_demo', 'sql': 'SELECT 1 AS native_runtime', 'maxRows': 1, 'timeoutMs': 15000})
            result = json.loads(body)['data']
            assert status == 200 and result['success'] and result['rows'][0][0] == 1
            return capability['runtimeInstanceId']

        bind_driver(mounted_app)
        proc, ready = start(mounted_app)
        verify(ready)
        stop(proc, ready)
        assert fingerprints(mounted_app) == application
        checks.append({'case': 'native-jre-worker-jdbc-start-from-read-only-image-with-empty-path', 'status': 'PASS'})
        subprocess.run(['/usr/bin/hdiutil', 'detach', '-quiet', str(mount)], check=True)
        mounted = False

        installed = temp / 'Applications/Data Agent.app'
        installed.parent.mkdir()
        shutil.copytree(app, installed, symlinks=True)
        bind_driver(installed)
        proc, ready = start(installed)
        runtime = verify(ready)
        status, body, _ = request(ready['url'], '/api/local/conversations', {})
        conversation = json.loads(body)['data']['conversationId']
        assert status == 200
        stop(proc, ready)
        saved = configuration_fingerprints(data)
        checks.append({'case': 'copied-application-loads-user-configuration', 'status': 'PASS'})

        backup = temp / 'rollback.app'
        installed.rename(backup)
        shutil.copytree(app, installed, symlinks=True)
        proc, ready = start(installed)
        assert verify(ready) != runtime
        status, body, _ = request(ready['url'], '/api/local/conversations/' + str(conversation), headers={'X-Local-Runtime-Id': runtime})
        assert status == 409 and 'LOCAL_RUNTIME_MISMATCH' in body
        stop(proc, ready)
        assert configuration_fingerprints(data) == saved
        checks.append({'case': 'stopped-app-replacement-retains-connections-models-and-vault', 'status': 'PASS'})
        checks.append({'case': 'old-temporary-conversation-rejected-after-replacement', 'status': 'PASS'})

        shutil.rmtree(installed)
        backup.rename(installed)
        proc, ready = start(installed)
        verify(ready)
        stop(proc, ready)
        assert configuration_fingerprints(data) == saved and fingerprints(installed) == application
        checks.append({'case': 'stopped-app-rollback-retains-user-configuration', 'status': 'PASS'})
        # Exercise LaunchServices (the Finder launch path), including its /dev/null stdin.
        stdout, stderr = temp / 'finder-ready.jsonl', temp / 'finder-stderr.log'
        launcher = subprocess.Popen(['/usr/bin/open', '-n', '-W', '--stdout', str(stdout), '--stderr', str(stderr),
            '--env', 'DATA_AGENT_DATA_DIRECTORY=' + str(data), '--env', 'PATH=', str(installed)])
        processes.append(launcher)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if stdout.exists() and stdout.read_text().endswith('\n'):
                break
            time.sleep(.1)
        ready = json.loads(stdout.read_text().splitlines()[0])
        receipts.append(ready)
        assert ready['type'] == 'native-ready'
        verify(ready)
        os.kill(ready['appPid'], signal.SIGTERM)
        gone(ready['appPid']); gone(ready['hostPid']); gone(ready['backendPid'])
        assert launcher.wait(timeout=5) == 0 and configuration_fingerprints(data) == saved
        checks.append({'case': 'launchservices-finder-path-starts-with-external-user-data', 'status': 'PASS'})
        report['status'] = 'PASS'
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
        # LaunchServices owns its app PID; stop only a remaining app in this private install path.
        if 'installed' in locals():
            entry = str(installed / 'Contents/MacOS/DataAgent')
            for line in subprocess.check_output(['/bin/ps', '-axo', 'pid=,comm='], text=True).splitlines():
                parts = line.strip().split(None, 1)
                if len(parts) == 2 and parts[1] == entry:
                    os.kill(int(parts[0]), signal.SIGTERM); gone(int(parts[0]))
        for ready in receipts:
            gone(ready['hostPid']); gone(ready['backendPid'])
        if mounted:
            subprocess.run(['/usr/bin/hdiutil', 'detach', '-quiet', str(mount)], check=True)
        temporary.cleanup()
        report['originalConfigurationUnchanged'] = original == fingerprints(args.mysql_local_directory)
        report['applicationUnchanged'] = application == fingerprints(app)
        assert report['originalConfigurationUnchanged'] and report['applicationUnchanged']
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
