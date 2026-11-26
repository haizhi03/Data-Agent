#!/usr/bin/env python3
"""Check Windows package hashes/PE architecture without claiming Windows execution."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import struct
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def pe(path):
    content = path.read_bytes()
    assert content[:2] == b'MZ', str(path)
    offset = struct.unpack_from('<I', content, 0x3c)[0]
    assert content[offset:offset + 4] == b'PE\0\0', str(path)
    assert struct.unpack_from('<H', content, offset + 4)[0] == 0x8664, 'Expected x64: ' + str(path)
    return {'machine': 'AMD64', 'subsystem': struct.unpack_from('<H', content, offset + 24 + 68)[0]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package = args.package.resolve()
    receipt = json.loads(package.with_suffix('.manifest.json').read_text(encoding='utf-8'))
    assert receipt['platform'] == 'Windows x64' and not receipt['userConfigurationIncluded']
    assert {p.relative_to(package).as_posix() for p in package.rglob('*') if p.is_file()} == set(receipt['files'])
    for name, digest in receipt['files'].items():
        assert sha(package / name) == digest, name
    native = {name: pe(package / name) for name in ['DataAgent.exe', 'bundle/runtime/node/bin/node.exe',
               'bundle/runtime/java/bin/java.exe', 'runtimes/win-x64/native/WebView2Loader.dll']}
    assert (package / 'coreclr.dll').is_file() and (package / 'System.Windows.Forms.dll').is_file()
    assert native['DataAgent.exe']['subsystem'] == 2, 'The native app must use the Windows GUI subsystem'
    for name in ['jli.dll', 'vcruntime140.dll', 'vcruntime140_1.dll', 'msvcp140.dll']:
        assert (package / 'bundle/runtime/java/bin' / name).is_file(), 'Missing private JRE dependency: ' + name
    assert (package / 'bundle/worker/node_modules/@earendil-works/pi-agent-core/package.json').is_file()
    assert not list(package.rglob('*.node')), 'Host-native worker dependencies cannot cross platforms'
    archive = package.parent / 'DataAgent-windows-x64.zip'
    assert sha(archive) == receipt['zipSha256']
    with zipfile.ZipFile(archive) as zipped:
        assert zipped.testzip() is None
        assert set(zipped.namelist()) == {package.name + '/' + name for name in receipt['files']}
        for name, digest in receipt['files'].items():
            assert hashlib.sha256(zipped.read(package.name + '/' + name)).hexdigest() == digest, name
    report = {'verifiedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'status': 'PASS',
              'scope': 'Static package integrity and x64 PE checks; no Windows process executed',
              'fileCount': len(receipt['files']), 'nativeFiles': native, 'zipSha256': receipt['zipSha256'],
              'zipBytes': receipt['zipBytes'], 'receiptSha256': sha(package.with_suffix('.manifest.json')),
              'windowsRuntimeVerified': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
