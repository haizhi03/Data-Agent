#!/usr/bin/env python3
"""Assemble a macOS arm64 or Windows x64 bundle without user configuration or credentials."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]


def run(command, cwd=ROOT):
    command = list(command)
    if command[0] in ('mvn', 'npm'):
        command[0] = shutil.which(command[0]) or command[0]
    subprocess.run([str(part) for part in command], cwd=cwd, check=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    default_target = 'windows-x64' if platform.system() == 'Windows' else 'macos-arm64'
    parser.add_argument('--target', choices=['macos-arm64', 'windows-x64'], default=default_target)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--java-home', type=Path, help='JDK 21 used by Maven (the runtime is downloaded separately)')
    parser.add_argument('--driver', type=Path, action='append', default=[], help='Explicit JDBC jar to include; repeatable')
    args = parser.parse_args()
    windows = args.target == 'windows-x64'
    if not windows and (platform.system() != 'Darwin' or platform.machine() != 'arm64'):
        parser.error('macOS runtime assembly requires macOS arm64 (otool dependency audit)')
    lock_path = ROOT / 'scripts/desktop' / ('runtime-lock.windows-x64.json' if windows else 'runtime-lock.json')
    lock = json.loads(lock_path.read_text(encoding='utf-8'))
    output = (args.output or ROOT / ('target/desktop-pi-' + args.target)).absolute()
    if output.exists():
        parser.error('Output already exists; choose a new output directory')
    for driver in args.driver:
        if not driver.is_file() or driver.suffix != '.jar':
            parser.error('Each --driver must be an existing JDBC jar')
    if len({p.name for p in args.driver}) != len(args.driver):
        parser.error('Driver filenames must be unique')
    java_home = args.java_home
    if java_home is None:
        settings = subprocess.check_output(['java', '-XshowSettings:properties', '-version'], stderr=subprocess.STDOUT, text=True)
        java_home = Path(re.search(r'java.home = (.+)', settings).group(1).strip())
    java_home = java_home.resolve()
    settings = subprocess.check_output([str(java_home / ('bin/java.exe' if os.name == 'nt' else 'bin/java')), '-version'], stderr=subprocess.STDOUT, text=True)
    if not re.search(r'version "21\.', settings):
        parser.error('A JDK 21 is required')
    # Keep Maven compilation and the packaged runtime on the same JDK.
    os.environ['JAVA_HOME'] = str(java_home)
    run(['mvn', '-f', ROOT / 'Data-Agent-Server/pom.xml', '-pl', 'data-agent-server-app', '-am', '-DskipTests', 'package'])
    run(['npm', 'run', 'build'], ROOT / 'Data-Agent-Client')
    output.parent.mkdir(parents=True, exist_ok=True)
    cache = ROOT / 'target/desktop-downloads'
    cache.mkdir(parents=True, exist_ok=True)
    def download(kind):
        archive = cache / lock[kind + 'Archive']
        if not archive.exists():
            partial = archive.with_suffix('.part')
            with urllib.request.urlopen(lock[kind + 'URL'], timeout=60) as response, partial.open('wb') as file:
                shutil.copyfileobj(response, file)
            if digest(partial) != lock[kind + 'Sha256']:
                partial.unlink()
                raise RuntimeError(kind + ' archive checksum mismatch')
            partial.rename(archive)
        if digest(archive) != lock[kind + 'Sha256']:
            raise RuntimeError(kind + ' cached archive checksum mismatch')
        return archive
    archive = download('node')
    java_archive = download('java')
    with tempfile.TemporaryDirectory(prefix='.desktop-build-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'bundle'
        stage.mkdir()
        runtime = stage / 'runtime'
        runtime.mkdir()
        # Use the standalone distribution rather than Homebrew's JDK, whose AWT libraries
        # can still reference /opt/homebrew even after jlink.
        extracted = Path(temporary) / 'java-extracted'
        extracted.mkdir()
        if windows:
            with zipfile.ZipFile(java_archive) as zipped:
                zipped.extractall(extracted)
            java_runtime = next(p.parent.parent for p in extracted.glob('*/bin/java.exe'))
        else:
            run(['tar', '-xzf', java_archive, '-C', extracted])
            java_runtime = next(extracted.glob('*/Contents/Home'))
        shutil.copytree(java_runtime, runtime / 'java', symlinks=True)
        java_name = 'runtime/java/bin/java.exe' if windows else 'runtime/java/bin/java'
        node_name = 'runtime/node/bin/node.exe' if windows else 'runtime/node/bin/node'
        node = stage / node_name
        node.parent.mkdir(parents=True)
        if windows:
            with zipfile.ZipFile(archive) as zipped:
                prefix = lock['nodeArchive'].removesuffix('.zip')
                node.write_bytes(zipped.read(prefix + '/node.exe'))
                (runtime / 'node/LICENSE').write_bytes(zipped.read(prefix + '/LICENSE'))
        else:
            with tarfile.open(archive) as tar:
                prefix = lock['nodeArchive'].removesuffix('.tar.gz')
                with tar.extractfile(prefix + '/bin/node') as source, node.open('wb') as destination:
                    shutil.copyfileobj(source, destination)
                (runtime / 'node/LICENSE').write_bytes(tar.extractfile(prefix + '/LICENSE').read())
        node.chmod(0o755)
        if not windows or os.name == 'nt':
            run([stage / java_name, '-version'])
            run([node, '--version'])
        shutil.copy2(ROOT / 'Data-Agent-Server/data-agent-server-app/target/data-agent-server-app-0.0.1-SNAPSHOT.jar', stage / 'server.jar')
        shutil.copytree(ROOT / 'Data-Agent-Client/dist', stage / 'web')
        worker = stage / 'worker'
        shutil.copytree(ROOT / 'Data-Agent-Pi-Worker/src', worker / 'src')
        for name in ['package.json', 'package-lock.json']:
            shutil.copy2(ROOT / 'Data-Agent-Pi-Worker' / name, worker / name)
        run(['npm', 'ci', '--omit=dev', '--ignore-scripts', '--no-audit', '--no-fund'], worker)
        shutil.copy2(ROOT / 'scripts/desktop/host.mjs', stage / 'host.mjs')
        shutil.copy2(ROOT / 'LICENSE', stage / 'LICENSE')
        shutil.copy2(lock_path, stage / 'runtime-lock.json')
        if windows:
            (stage / 'start.cmd').write_bytes(b'@echo off\r\nsetlocal\r\n"%~dp0runtime\\node\\bin\\node.exe" "%~dp0host.mjs"\r\n')
        else:
            (stage / 'start.command').write_text('#!/bin/sh\nCDPATH= cd -P -- "${0%/*}" || exit 1\nexec ./runtime/node/bin/node ./host.mjs\n')
            (stage / 'start.command').chmod(0o755)
        (stage / 'drivers').mkdir()
        for driver in args.driver:
            shutil.copy2(driver, stage / 'drivers' / driver.name)
        sources = list((ROOT / 'scripts/desktop').glob('*'))
        sources += [ROOT / 'Data-Agent-Server/data-agent-server-app/src/main/java/edu/zsc/ai/local' / name
                    for name in ['LocalHostLifecycle.java', 'LocalRuntimeConfiguration.java']]
        sources += [ROOT / 'Data-Agent-Client/src/lib/localRuntime.ts']
        # Worker dependencies are JS-only. Reject host-native addons in a cross-built bundle.
        if windows and list(worker.rglob('*.node')):
            raise RuntimeError('Windows worker contains native addons; assemble dependencies on Windows')
        native_files = [] if windows else [node, stage / java_name, *runtime.rglob('*.dylib'), *worker.rglob('*.node')]
        external = set()
        for native in native_files:
            for line in subprocess.check_output(['otool', '-L', str(native)], text=True).splitlines()[1:]:
                dependency = line.strip().split(' (')[0]
                if not dependency.startswith(('@', '/usr/lib/', '/System/Library/')):
                    external.add(dependency)
        if external:
            raise RuntimeError('Runtime has package-external native dependencies: ' + ', '.join(sorted(external)))
        manifest = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'platform': lock['platform'],
                    'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    'buildJava': settings.strip(), 'runtimeLock': lock, 'externalNativeDependencies': None if windows else sorted(external),
                    'crossCompiled': windows and os.name != 'nt',
                    'userConfigurationIncluded': False, 'drivers': {p.name: digest(p) for p in args.driver},
                    'artifacts': {name: digest(stage / name) for name in ['server.jar', 'host.mjs', 'web/index.html', 'worker/package-lock.json', node_name, java_name]},
                    'sourceSha256': {str(p.relative_to(ROOT)): digest(p) for p in sources if p.is_file()}}
        (stage / 'bundle-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        stage.rename(output)
    print('Bundle ready:', output, flush=True)


if __name__ == '__main__':
    main()
