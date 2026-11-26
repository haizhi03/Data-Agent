#!/usr/bin/env python3
"""Wrap a verified runtime directory in a native macOS app and optional read-only DMG."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import platform
import plistlib
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*command):
    subprocess.run([str(part) for part in command], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='New .app path')
    parser.add_argument('--version', default='0.1.0')
    parser.add_argument('--build-number', default='1')
    parser.add_argument('--dmg', type=Path, help='Optional new DMG path')
    args = parser.parse_args()
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('Native package currently supports macOS arm64 only')
    bundle = args.bundle.resolve()
    output = args.output.absolute()
    if output.exists() or output.suffix != '.app':
        parser.error('--output must be a new .app path')
    if not re.fullmatch(r'\d+\.\d+\.\d+', args.version) or not args.build_number.isdecimal():
        parser.error('Use a three-component version and numeric build number')
    dmg = args.dmg.absolute() if args.dmg else None
    if dmg and (dmg.exists() or dmg.suffix != '.dmg'):
        parser.error('--dmg must be a new .dmg path')
    manifest = json.loads((bundle / 'bundle-manifest.json').read_text())
    for name, digest in manifest['artifacts'].items():
        if sha(bundle / name) != digest:
            parser.error('Runtime artifact changed: ' + name)
    if sha(bundle / 'host.mjs') != sha(ROOT / 'scripts/desktop/host.mjs'):
        parser.error('Rebuild the runtime bundle with the current parent-pipe host before packaging')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.native-package-', dir=output.parent) as temporary:
        stage = Path(temporary) / output.name
        contents = stage / 'Contents'
        executable = contents / 'MacOS/DataAgent'
        executable.parent.mkdir(parents=True)
        resources = contents / 'Resources'
        resources.mkdir()
        run('xcrun', 'swiftc', '-swift-version', '5', '-target', 'arm64-apple-macos14.0', '-O',
            '-framework', 'AppKit', '-framework', 'WebKit', ROOT / 'scripts/desktop/macos/main.swift', '-o', executable)
        shutil.copytree(bundle, resources / 'bundle', symlinks=True)
        info = {'CFBundleExecutable': 'DataAgent', 'CFBundleIdentifier': 'com.dataagent.desktop',
                'CFBundleName': 'Data Agent', 'CFBundleDisplayName': 'Data Agent', 'CFBundlePackageType': 'APPL',
                'CFBundleShortVersionString': args.version, 'CFBundleVersion': args.build_number,
                'LSMinimumSystemVersion': '14.0', 'NSHighResolutionCapable': True,
                'NSAppTransportSecurity': {'NSAllowsLocalNetworking': True}}
        with (contents / 'Info.plist').open('wb') as file:
            plistlib.dump(info, file)
        build = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'version': args.version,
                 'buildNumber': args.build_number, 'platform': 'macOS arm64', 'signing': 'ad-hoc development',
                 'runtimeBundleManifestSha256': sha(bundle / 'bundle-manifest.json'),
                 'sourceSha256': {str(path.relative_to(ROOT)): sha(path) for path in [
                     ROOT / 'scripts/desktop/macos/main.swift', ROOT / 'scripts/desktop/package_macos.py']},
                 'userConfigurationIncluded': False}
        (resources / 'native-build.json').write_text(json.dumps(build, indent=2) + '\n')
        run('codesign', '--force', '--sign', '-', stage)
        run('codesign', '--verify', '--deep', '--strict', stage)
        stage.rename(output)
    # The external receipt can include the final executable hash without modifying the app seal.
    build['executableSha256'] = sha(output / 'Contents/MacOS/DataAgent')
    build['appPath'] = output.name
    if dmg:
        dmg.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.dmg-package-', dir=output.parent) as temporary:
            image = Path(temporary)
            shutil.copytree(output, image / output.name, symlinks=True)
            (image / 'Applications').symlink_to('/Applications')
            (image / '安装说明.txt').write_text('将 Data Agent.app 拖入 Applications 后打开。\n这是本机开发验证版，尚未完成 Developer ID 签名、公证和干净机器验收。\n配置与日志保存在用户目录，替换应用文件不会覆盖配置。\n')
            run('hdiutil', 'create', '-quiet', '-format', 'UDZO', '-volname', 'Data Agent', '-srcfolder', image, dmg)
        build['dmgSha256'] = sha(dmg)
        build['dmgBytes'] = dmg.stat().st_size
    output.with_suffix('.app.manifest.json').write_text(json.dumps(build, indent=2) + '\n')
    print('Native app ready:', output)
    if dmg:
        print('DMG ready:', dmg)


if __name__ == '__main__':
    main()
