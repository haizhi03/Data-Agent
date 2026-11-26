#!/usr/bin/env python3
"""Assemble the macOS arm64 development bundle; never copy user configuration or credentials."""
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

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((ROOT / 'scripts/desktop/runtime-lock.json').read_text())


def run(command, cwd=ROOT):
    subprocess.run([str(part) for part in command], cwd=cwd, check=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT / 'target/desktop-pi-macos-arm64')
    parser.add_argument('--java-home', type=Path, help='JDK 21 used by Maven (the runtime is downloaded separately)')
    parser.add_argument('--driver', type=Path, action='append', default=[], help='Explicit JDBC jar to include; repeatable')
    args = parser.parse_args()
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('This first bundle is verified only on macOS arm64')
    output = args.output.absolute()
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
    settings = subprocess.check_output([str(java_home / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True)
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
        archive = cache / LOCK[kind + 'Archive']
        if not archive.exists():
            partial = archive.with_suffix('.part')
            with urllib.request.urlopen(LOCK[kind + 'URL'], timeout=60) as response, partial.open('wb') as file:
                shutil.copyfileobj(response, file)
            if digest(partial) != LOCK[kind + 'Sha256']:
                partial.unlink()
                raise RuntimeError(kind + ' archive checksum mismatch')
            partial.rename(archive)
        if digest(archive) != LOCK[kind + 'Sha256']:
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
        run(['tar', '-xzf', java_archive, '-C', extracted])
        java_runtime = next(extracted.glob('*/Contents/Home'))
        shutil.copytree(java_runtime, runtime / 'java', symlinks=True)
        run([runtime / 'java/bin/java', '-version'])
        node = runtime / 'node/bin/node'
        node.parent.mkdir(parents=True)
        with tarfile.open(archive) as tar:
            prefix = LOCK['nodeArchive'].removesuffix('.tar.gz')
            with tar.extractfile(prefix + '/bin/node') as source, node.open('wb') as destination:
                shutil.copyfileobj(source, destination)
            (runtime / 'node/LICENSE').write_bytes(tar.extractfile(prefix + '/LICENSE').read())
        node.chmod(0o755)
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
        shutil.copy2(ROOT / 'scripts/desktop/runtime-lock.json', stage / 'runtime-lock.json')
        (stage / 'start.command').write_text('#!/bin/sh\nCDPATH= cd -P -- "${0%/*}" || exit 1\nexec ./runtime/node/bin/node ./host.mjs\n')
        (stage / 'start.command').chmod(0o755)
        (stage / 'drivers').mkdir()
        for driver in args.driver:
            shutil.copy2(driver, stage / 'drivers' / driver.name)
        sources = list((ROOT / 'scripts/desktop').glob('*'))
        sources += [ROOT / 'Data-Agent-Server/data-agent-server-app/src/main/java/edu/zsc/ai/local' / name
                    for name in ['LocalHostLifecycle.java', 'LocalRuntimeConfiguration.java']]
        sources += [ROOT / 'Data-Agent-Client/src/lib/localRuntime.ts']
        native_files = [runtime / 'node/bin/node', runtime / 'java/bin/java', *runtime.rglob('*.dylib'), *worker.rglob('*.node')]
        external = set()
        for native in native_files:
            for line in subprocess.check_output(['otool', '-L', str(native)], text=True).splitlines()[1:]:
                dependency = line.strip().split(' (')[0]
                if not dependency.startswith(('@', '/usr/lib/', '/System/Library/')):
                    external.add(dependency)
        if external:
            raise RuntimeError('Runtime has package-external native dependencies: ' + ', '.join(sorted(external)))
        manifest = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'platform': LOCK['platform'],
                    'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    'buildJava': settings.strip(), 'runtimeLock': LOCK, 'externalNativeDependencies': sorted(external),
                    'userConfigurationIncluded': False, 'drivers': {p.name: digest(p) for p in args.driver},
                    'artifacts': {name: digest(stage / name) for name in ['server.jar', 'host.mjs', 'web/index.html', 'worker/package-lock.json', 'runtime/node/bin/node']},
                    'sourceSha256': {str(p.relative_to(ROOT)): digest(p) for p in sources if p.is_file()}}
        (stage / 'bundle-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        stage.rename(output)
    print('Bundle ready:', output, flush=True)


if __name__ == '__main__':
    main()
