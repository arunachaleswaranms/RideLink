#!/usr/bin/env python3
"""Review gate for new production HTTP endpoints and unreviewed runtime dependencies.

This is a source/manifest policy check, not a packet capture or a binary SDK audit.
TLS, trust and host-only ICE behavior are tested in the platform suites.
"""
from pathlib import Path
import re
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parents[1]
APPROVED_GROUPS = {
    'androidx.core', 'androidx.lifecycle', 'androidx.activity', 'androidx.compose',
    'androidx.compose.ui', 'androidx.compose.material3', 'org.jetbrains.kotlinx',
    'org.junit.jupiter', 'org.jetbrains.kotlin', 'org.conscrypt', 'io.github.webrtc-sdk',
    'androidx.room', 'androidx.documentfile', 'androidx.test', 'androidx.test.ext',
    'junit', 'androidx.media3', 'androidx.media',
}
APPROVED_SWIFT = {
    'https://github.com/stasel/WebRTC.git',
    'https://github.com/groue/GRDB.swift.git',
}


def audit():
    errors = []
    catalog = tomllib.loads((ROOT / 'android/gradle/libs.versions.toml').read_text())
    for name, library in catalog['libraries'].items():
        if library.get('group') not in APPROVED_GROUPS:
            errors.append(f'unreviewed Android dependency group: {name}')
    for manifest in (ROOT / 'ios/Packages').glob('*/Package.swift'):
        for url in re.findall(r'\.package\(url:\s*"([^"]+)"', manifest.read_text()):
            if url not in APPROVED_SWIFT:
                errors.append(f'unreviewed Swift dependency in {manifest.relative_to(ROOT)}')
    files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    count = 0
    for name in files:
        path = ROOT / name
        production = '/src/main/' in name or '/Sources/' in name or name.startswith('ios/RideLink/')
        if not production or path.suffix not in {'.kt', '.swift'}:
            continue
        count += 1
        # No production HTTP endpoint currently exists, even in comments. An addition requires
        # explicit review here; local sockets and file/content URIs are intentionally unaffected.
        if re.search(r'https?://', path.read_text()):
            errors.append(f'production HTTP URL requires review: {name}')
    return count, errors


if __name__ == '__main__':
    count, errors = audit()
    for error in errors:
        print(error, file=sys.stderr)
    print(f'Local-only source policy: {count} production source files; {len(errors)} findings')
    sys.exit(bool(errors))
