#!/usr/bin/env python3
"""Bundle a Shortcut whose app identity matches this exact signed build."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(os.environ['SRCROOT'])
script = root / 'scripts/make-shortcut.py'
team = os.environ.get('DEVELOPMENT_TEAM') or 'FAKETEAMID'
bundle = os.environ['PRODUCT_BUNDLE_IDENTIFIER']
if team == 'FAKETEAMID' and os.environ.get('PLATFORM_NAME') != 'iphonesimulator':
    raise SystemExit('error: Select an Apple development team before building for a device.')
directory = Path(os.environ['DERIVED_FILE_DIR']) / 'ShortcutTemplates'
stamp = directory / '.identity.json'
identity = dict(team=team, bundle=bundle, source=hashlib.sha256(script.read_bytes()).hexdigest())
if not (directory / 'Mulberry Sync.shortcut').exists() or not stamp.exists() or json.loads(stamp.read_text()) != identity:
    subprocess.run([sys.executable, str(script), '--team-id', team, '--bundle-id', bundle,
                    '--output-dir', str(directory)], check=True)
    stamp.write_text(json.dumps(identity))
