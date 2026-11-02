#!/usr/bin/env python3
"""Metadata for the new, self-authored API 23 application."""
import pathlib
import sys

pathlib.Path(sys.argv[1]).write_text('''version: 3.0.3
apkFileName: guangqi-dashboard-media.apk
usesFramework:
  ids:
  - 1
sdkInfo:
  minSdkVersion: 23
  targetSdkVersion: 23
versionInfo:
  versionCode: 1
  versionName: 0.1.0
resourcesInfo:
  packageId: 127
''', encoding='utf-8')
