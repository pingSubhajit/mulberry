#!/usr/bin/env python3
"""Let Debug builds reach the development server on the local network over plain HTTP.

The App Store build ships Info.plist as written: App Transport Security on, and no request for
local network access. Debug builds add both here, once Xcode has processed Info.plist.
"""
import os
import plistlib
from pathlib import Path

if os.environ['CONFIGURATION'] != 'Debug':
    raise SystemExit(0)

path = Path(os.environ['TARGET_BUILD_DIR']) / os.environ['INFOPLIST_PATH']
data = path.read_bytes()
fmt = plistlib.FMT_BINARY if data.startswith(b'bplist') else plistlib.FMT_XML
info = plistlib.loads(data)
info['NSAppTransportSecurity'] = {'NSAllowsArbitraryLoads': True, 'NSAllowsLocalNetworking': True}
info['NSLocalNetworkUsageDescription'] = 'Connect to the Mulberry development server on your local network.'
path.write_bytes(plistlib.dumps(info, fmt=fmt))
