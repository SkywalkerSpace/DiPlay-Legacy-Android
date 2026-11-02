#!/usr/bin/env python3
"""Check artifacts that are required for Android 6 and 32-bit installations."""
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as apk:
    names = apk.namelist()
    assert 'AndroidManifest.xml' in names
    assert 'classes.dex' in names
    assert apk.read('classes.dex')[:8] == b'dex\n035\x00', 'API 23 needs compatible DEX'
    assert any(name.startswith('META-INF/') and name.endswith('.SF') for name in names), 'Missing v1 signature'
    assert not any(name.startswith('lib/') for name in names), 'Unexpected native ABI dependency'
    assert not any('mirrordiag' in name or 'test-pattern' in name for name in names), 'Unexpected mirror payload'
    assert apk.testzip() is None
print('PASS: DEX 035, v1 signature entries, no native libraries (32-bit compatible)')
