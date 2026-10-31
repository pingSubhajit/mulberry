#!/usr/bin/env python3
"""Build and install Mulberry on a paired physical iPhone or iPad. No upload."""
import argparse
import json
import os
from pathlib import Path
import re
import plistlib
import subprocess
import sys
import tempfile
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', default=os.environ.get('MULBERRY_DEVICE'),
                        help='Physical iPhone/iPad UUID, UDID, or exact name; inferred only if one is connected')
    parser.add_argument('--team-id', default=os.environ.get('MULBERRY_TEAM_ID'))
    parser.add_argument('--environment', choices=['dev', 'prod'], default='dev',
                        help='App identity, icon and Firebase registration; independent of compilation mode')
    parser.add_argument('--release', action='store_true', help='Use optimised compilation; still installs locally')
    parser.add_argument('--api-url', help='Explicit local backend override for this device build only')
    parser.add_argument('--push', action='store_true',
                        help='Use the APNs entitlement; requires push-enabled provisioning')
    parser.add_argument('--check', action='store_true', help='Check device and SDK only; do not build or install')
    args = parser.parse_args()
    env = dict(os.environ)
    env.setdefault('DEVELOPER_DIR', '/Applications/Xcode.app/Contents/Developer')
    root = Path(__file__).resolve().parents[1]
    sdk = subprocess.check_output(['xcrun', '--sdk', 'iphoneos', '--show-sdk-version'], env=env, text=True).strip()
    if int(sdk.split('.')[0]) < 27:
        parser.error('Select Xcode 27 or newer with DEVELOPER_DIR.')

    with tempfile.TemporaryDirectory(prefix='mulberry-device-') as directory:
        inventory = Path(directory) / 'devices.json'
        subprocess.run(['xcrun', 'devicectl', 'list', 'devices', '--quiet', '--timeout', '30',
                        '--json-output', str(inventory)], env=env, check=True)
        devices = []
        for device in json.loads(inventory.read_text())['result']['devices']:
            properties = device.get('properties', {})
            hardware = properties.get('hardware', device.get('hardwareProperties', {}))
            state = properties.get('state', device.get('deviceProperties', {}))
            connection = properties.get('connection', device.get('connectionProperties', {}))
            software = properties.get('software', device.get('deviceProperties', {}))
            if hardware.get('reality') != 'physical' or hardware.get('deviceType') not in {'iPhone', 'iPad'}:
                continue
            identities = [device['identifier'], hardware.get('udid'), state.get('name')]
            if args.device and args.device not in identities:
                continue
            connected = connection.get('state', connection.get('tunnelState')) == 'connected'
            # Wi-Fi devices can be paired and available with no active tunnel yet.
            # An explicit selection is allowed to open that tunnel on demand below.
            if not connected and not (args.device and connection.get('pairingState') == 'paired'):
                continue
            version = software.get('osVersionNumber', '')
            if isinstance(version, dict):
                version = version.get('stringValue', '')
            devices.append((device['identifier'], hardware.get('udid'), state.get('name', 'iPhone'), version))
        if len(devices) != 1:
            parser.error('Connect and unlock a paired iPhone or iPad, or select it with --device. '
                         'Complete Trust and Developer Mode first. No matching physical device is connected.'
                         if not devices else 'More than one device is connected. Select one with --device.')
        identifier, udid, name, version = devices[0]
        if not udid:
            parser.error('Xcode did not report a UDID for the selected device. Finish pairing first.')
        if not version or int(version.split('.')[0]) < 27:
            parser.error('The selected device must run iOS/iPadOS 27 or newer.')
        print(f'Device: {name}; iOS {version}; SDK {sdk}', flush=True)
        subprocess.run(['xcrun', 'devicectl', 'device', 'info', 'details', '--device', identifier,
                        '--quiet', '--timeout', '30'], env=env, check=True)
        if args.check:
            return
        if not args.team_id or not re.fullmatch(r'[A-Z0-9]{10}', args.team_id) or args.team_id == 'FAKETEAMID':
            parser.error('Set --team-id to your real 10-character Apple development team ID.')

        subprocess.run([sys.executable, str(root / 'scripts/generate-project.py')], env=env, check=True)
        configuration = {('dev', False): 'Debug', ('dev', True): 'DevRelease',
                         ('prod', False): 'ProdDebug', ('prod', True): 'Release'}[(args.environment, args.release)]
        output = root / '.build' / f'device-{args.environment}'
        command = ['xcodebuild', '-project', str(root / 'Mulberry.xcodeproj'),
                   '-scheme', 'Mulberry', '-configuration', configuration,
                   '-destination', f'platform=iOS,id={udid}', '-derivedDataPath', str(output),
                   '-clonedSourcePackagesDirPath', str(root / '.build/device/SourcePackages'),
                   '-disableAutomaticPackageResolution',
                   '-allowProvisioningUpdates', '-allowProvisioningDeviceRegistration',
                   f'DEVELOPMENT_TEAM={args.team_id}',
                   'MULBERRY_LOCAL_DEVICE_BUILD=YES',
                   'CODE_SIGN_IDENTITY=Apple Development', 'APS_ENVIRONMENT=development']
        if args.api_url:
            url = urlparse(args.api_url)
            if url.scheme not in {'http', 'https'} or not url.hostname or url.username or url.password:
                parser.error('--api-url must be an HTTP(S) address without credentials.')
            command += [f'MULBERRY_API_BASE_URL={args.api_url}', 'MULBERRY_ALLOW_LOCAL_SERVER=YES']
        entitlements = 'Mulberry.entitlements' if args.push else 'LocalDelivery.entitlements'
        # Override only our target's setting, not CODE_SIGN_ENTITLEMENTS globally:
        # Swift package resource bundles must not receive the app's entitlements.
        command.append(f'MULBERRY_ENTITLEMENTS_FILE={root / "Configuration" / entitlements}')
        subprocess.run(command + ['build'], env=env, check=True)
        app = output / f'Build/Products/{configuration}-iphoneos/Mulberry.app'
        bundle_id = plistlib.loads((app / 'Info.plist').read_bytes())['CFBundleIdentifier']
        subprocess.run(['xcrun', 'devicectl', 'device', 'install', 'app', '--device', identifier,
                        str(app)], env=env, check=True)
        subprocess.run(['xcrun', 'devicectl', 'device', 'process', 'launch', '--device', identifier,
                        bundle_id], env=env, check=True)
        print('Installed Mulberry. Sign in with Google, then choose your wallpaper and add the bundled Shortcut.')
        print('APNs enabled.' if args.push else 'Local notification mode: APNs entitlement omitted.')


if __name__ == '__main__':
    main()
