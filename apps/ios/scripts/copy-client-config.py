#!/usr/bin/env python3
"""Copy the public Firebase client plist for this exact app variant."""
import os
import plistlib
from pathlib import Path

root = Path(os.environ['SRCROOT'])
variant = 'dev' if os.environ['CONFIGURATION'] == 'Debug' else 'prod'
source = root / f'Configuration/GoogleService-Info.{variant}.plist'
destination = Path(os.environ['TARGET_BUILD_DIR']) / os.environ['UNLOCALIZED_RESOURCES_FOLDER_PATH'] / 'GoogleService-Info.plist'
if source.exists():
    config = plistlib.loads(source.read_bytes())
    if config.get('BUNDLE_ID') != os.environ['PRODUCT_BUNDLE_IDENTIFIER']:
        raise SystemExit('error: Firebase client configuration has the wrong bundle ID.')
    if any(key in config for key in ['private_key', 'private_key_id', 'client_secret']):
        raise SystemExit('error: Server credentials must never be bundled.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())
elif variant == 'prod':
    raise SystemExit('error: Add the production public Firebase client plist before a release build.')
else:
    destination.unlink(missing_ok=True)
    print('warning: Firebase client config absent. Remote updates are unavailable in this build.')
