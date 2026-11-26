#!/usr/bin/env python3
"""Package a self-contained Windows x64 native shell, portable ZIP and optional Inno installer."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PROJECT = ROOT / 'scripts/desktop/windows'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('--output', type=Path, required=True, help='New Windows application directory')
    parser.add_argument('--dotnet', default='dotnet', help='Pinned .NET SDK executable (global.json)')
    parser.add_argument('--installer', type=Path, help='Inno Setup ISCC.exe; only on Windows')
    args = parser.parse_args()
    bundle, output = args.bundle.resolve(), args.output.absolute()
    zip_path = output.parent / 'DataAgent-windows-x64.zip'
    receipt = output.with_suffix('.manifest.json')
    installer_path = output.parent / 'DataAgent-windows-x64-setup.exe'
    if any(p.exists() for p in [output, zip_path, receipt]) or (args.installer and installer_path.exists()):
        parser.error('Output directory/archive/receipt already exists; choose a new parent directory')
    if args.installer and os.name != 'nt':
        parser.error('Inno Setup installer compilation requires Windows')
    manifest = json.loads((bundle / 'bundle-manifest.json').read_text(encoding='utf-8'))
    if manifest['platform'] != 'Windows x64':
        parser.error('A Windows x64 runtime bundle is required')
    for name, digest in manifest['artifacts'].items():
        if sha(bundle / name) != digest:
            parser.error('Runtime artifact changed: ' + name)
    if sha(bundle / 'host.mjs') != sha(ROOT / 'scripts/desktop/host.mjs'):
        parser.error('Rebuild the runtime bundle with the current host before packaging')
    dotnet = shutil.which(args.dotnet) or str(Path(args.dotnet).resolve())
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.windows-package-', dir=output.parent) as temporary:
        stage = Path(temporary) / output.name
        subprocess.run([dotnet, 'restore', '--locked-mode'], cwd=PROJECT, check=True)
        subprocess.run([dotnet, 'publish', '-c', 'Release', '--no-restore', '-o', str(stage)], cwd=PROJECT, check=True)
        shutil.copytree(bundle, stage / 'bundle')
        (stage / '使用说明.txt').write_text(
            '解压到完整目录后打开 DataAgent.exe。首批验收目标：Windows 11 x64。\n'
            'Java、Node 和 .NET 随包提供，无需另外安装。需要 Microsoft WebView2 Evergreen Runtime；缺少时应用提供官方下载入口。\n'
            '这是未签名的开发预览版，Windows 实机安装、查询和退出仍需验收。\n'
            '配置和日志默认保存在 %USERPROFILE%\\.data-agent\\local，升级或卸载不会删除此目录。\n', encoding='utf-8')
        build = {'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'platform': 'Windows x64',
                 'buildHost': os.name, 'version': '0.1.0', 'signing': 'unsigned development preview',
                 'windowsRuntimeVerified': False, 'userConfigurationIncluded': False,
                 'runtimeBundleManifestSha256': sha(bundle / 'bundle-manifest.json'),
                 'sourceSha256': {str(p.relative_to(ROOT)): sha(p) for p in [
                     ROOT / 'scripts/desktop/package_windows.py', *sorted(PROJECT.glob('*'))] if p.is_file()}}
        # Full payload hashes also cover self-contained .NET and the WebView2 loader.
        build['files'] = {p.relative_to(stage).as_posix(): sha(p) for p in sorted(stage.rglob('*')) if p.is_file()}
        stage.rename(output)
    with zipfile.ZipFile(zip_path, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
        for p in sorted(output.rglob('*')):
            if p.is_file():
                zipped.write(p, output.name + '/' + p.relative_to(output).as_posix())
    build['zipSha256'] = sha(zip_path)
    build['zipBytes'] = zip_path.stat().st_size
    if args.installer:
        subprocess.run([str(args.installer.resolve()), '/DPackageDir=' + str(output), '/DOutputDir=' + str(output.parent),
                        str(PROJECT / 'installer.iss')], check=True)
        build['installerSha256'] = sha(installer_path)
        build['installerBytes'] = installer_path.stat().st_size
    receipt.write_text(json.dumps(build, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('Windows app ready:', output)
    print('Portable ZIP ready:', zip_path)
    if args.installer:
        print('Installer ready:', installer_path)


if __name__ == '__main__':
    main()
