#!/usr/bin/env python3
"""Validate the decoded manifest and metadata emitted by apktool from the built APK."""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(sys.argv[1])
manifest = ET.parse(root / 'AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
assert manifest.get('package') == 'org.example.guangqidashboard'
permissions = {node.get(android + 'name') for node in manifest.findall('uses-permission')}
assert permissions == {
    'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.SYSTEM_ALERT_WINDOW',
}
app = manifest.find('application')
assert app is not None
assert app.get(android + 'allowBackup') == 'false'
assert app.find('service') is None, 'No background playback or projection service expected'
assert app.find('receiver') is None, 'No boot activation expected'
activities = app.findall('activity')
assert len(activities) == 1
assert activities[0].get(android + 'name') == '.MainActivity'
metadata = (root / 'apktool.yml').read_text(encoding='utf-8')
assert re.search(r'^  minSdkVersion: [\'\"]?23[\'\"]?\s*$', metadata, re.M)
assert re.search(r'^  targetSdkVersion: [\'\"]?23[\'\"]?\s*$', metadata, re.M)
assert re.search(r'^  versionName: [\'\"]?0\.1\.0[\'\"]?\s*$', metadata, re.M)
print('PASS: packaged API 23 metadata, package, launcher, permissions, no background components')
