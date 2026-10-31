#!/usr/bin/env python3
"""Copy only the native OAuth settings and this environment's public Firebase plist."""
import os
from pathlib import Path
import plistlib
from urllib.parse import urlparse

root=Path(__file__).resolve().parents[1]
env=os.environ['MULBERRY_APP_ENVIRONMENT']
if env not in ('dev','prod'): raise SystemExit('Invalid app environment')
unsigned = os.environ.get('MULBERRY_MAC_UNSIGNED_BUILD') == 'YES'
if unsigned and env != 'dev': raise SystemExit('Unsigned packaging is available only for development builds')
destination=Path(os.environ['TARGET_BUILD_DIR'])/os.environ['UNLOCALIZED_RESOURCES_FOLDER_PATH']
destination.mkdir(parents=True,exist_ok=True)
source=root/('.env' if env=='dev' else '.env.release')
values={}
if source.exists():
    for line in source.read_text().splitlines():
        line=line.strip().removeprefix('export ')
        if line and not line.startswith('#') and '=' in line:
            key,value=line.split('=',1)
            values[key.strip()]=value.strip().strip('\"\'')
keys=['MULBERRY_MAC_GOOGLE_CLIENT_ID','MULBERRY_MAC_GOOGLE_CLIENT_SECRET','MULBERRY_MAC_API_BASE_URL']
values={k:os.environ.get(k,values.get(k,'')) for k in keys}
if not values[keys[0]] and not unsigned: raise SystemExit(f'{source.name} requires the existing Desktop Google OAuth client ID')
api=values[keys[2]] or ('http://127.0.0.1:8080/' if env=='dev' else 'https://api.mulberry.my/')
parsed=urlparse(api)
local=parsed.hostname in ('127.0.0.1','localhost','::1')
if parsed.scheme!='https' and not (parsed.scheme=='http' and (env=='dev' or os.environ.get('MULBERRY_ALLOW_LOCAL_SERVER')=='YES') and local):
    raise SystemExit('HTTP requires an explicit local-server build and a loopback host')
values[keys[2]]=api
if any('\n' in v or '\r' in v for v in values.values()): raise SystemExit('Invalid client configuration')
(destination/'.env').write_text('\n'.join(k+'='+v for k,v in values.items())+'\n')
firebase=root.parent/'ios/Configuration'/f'GoogleService-Info.{env}.plist'
if unsigned and not firebase.exists():
    (destination/'GoogleService-Info.plist').unlink(missing_ok=True)
    print('Unsigned development build without Firebase; sign-in and remote updates need local client configuration')
    raise SystemExit(0)
config=plistlib.loads(firebase.read_bytes())
if config.get('BUNDLE_ID')!=os.environ['PRODUCT_BUNDLE_IDENTIFIER']: raise SystemExit('Firebase bundle ID does not match the app')
if any(k in config for k in ('private_key','private_key_id','client_secret')): raise SystemExit('Server credentials must never be bundled')
(destination/'GoogleService-Info.plist').write_bytes(firebase.read_bytes())
print(f'Copied {env} client configuration; local backend: {local}')
