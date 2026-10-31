#!/usr/bin/env python3
"""Allow local HTTP only for development or an explicit on-device test override."""
import os
import plistlib
from pathlib import Path
from urllib.parse import urlparse

path = Path(os.environ['TARGET_BUILD_DIR']) / os.environ['INFOPLIST_PATH']
data = path.read_bytes()
fmt = plistlib.FMT_BINARY if data.startswith(b'bplist') else plistlib.FMT_XML
info = plistlib.loads(data)
allow_local = os.environ.get('MULBERRY_ALLOW_LOCAL_SERVER') == 'YES'
url = urlparse(info.get('MulberryAPIBaseURL', ''))
if not allow_local and url.scheme != 'https':
    raise SystemExit('error: Production requires HTTPS. For a local device test, explicitly enable MULBERRY_ALLOW_LOCAL_SERVER.')
if allow_local:
    info['NSAppTransportSecurity'] = {'NSAllowsArbitraryLoads': True, 'NSAllowsLocalNetworking': True}
    info['NSLocalNetworkUsageDescription'] = 'Connect to the Mulberry development server on your local network.'
else:
    info.pop('NSAppTransportSecurity', None)
    info.pop('NSLocalNetworkUsageDescription', None)
path.write_bytes(plistlib.dumps(info, fmt=fmt))
