#!/usr/bin/env python3
"""Build and install Mulberry on a paired physical iPhone. No simulator fallback."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', default=os.environ.get('MULBERRY_DEVICE'),
                        help='Physical device UUID, UDID, or exact name; inferred only if one is connected')
    parser.add_argument('--team-id', default=os.environ.get('MULBERRY_TEAM_ID'))
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
            if hardware.get('reality') != 'physical' or hardware.get('deviceType') != 'iPhone':
                continue
            identities = [device['identifier'], hardware.get('udid'), state.get('name')]
            if args.device and args.device not in identities:
                continue
            connected = connection.get('state', connection.get('tunnelState')) == 'connected'
            if not connected:
                continue
            version = software.get('osVersionNumber', '')
            if isinstance(version, dict):
                version = version.get('stringValue', '')
            devices.append((device['identifier'], hardware.get('udid'), state.get('name', 'iPhone'), version))
        if len(devices) != 1:
            parser.error('Connect and unlock one paired iPhone, or select it with --device. '
                         'Complete Trust and Developer Mode on the phone first. No matching physical iPhone is connected.'
                         if not devices else 'More than one iPhone is connected. Select one with --device.')
        identifier, udid, name, version = devices[0]
        if not udid:
            parser.error('Xcode did not report a UDID for the selected iPhone. Finish pairing first.')
        if not version or int(version.split('.')[0]) < 27:
            parser.error('The selected iPhone must run iOS 27 or newer.')
        print(f'Device: {name}; iOS {version}; SDK {sdk}', flush=True)
        if args.check:
            return
        if not args.team_id or not re.fullmatch(r'[A-Z0-9]{10}', args.team_id) or args.team_id == 'FAKETEAMID':
            parser.error('Set --team-id to your real 10-character Apple development team ID.')

        # The imported App Intent descriptors must match the signed device app.
        # This intentionally updates the bundled template sources for the chosen team.
        subprocess.run([sys.executable, str(root / 'scripts/make-shortcut.py'),
                        '--team-id', args.team_id, '--bundle-id', 'com.subhajit.mulberry.dev'], env=env, check=True)
        subprocess.run([sys.executable, str(root / 'scripts/generate-project.py')], env=env, check=True)
        output = root / '.build/device'
        command = ['xcodebuild', '-project', str(root / 'Mulberry.xcodeproj'),
                   '-scheme', 'Mulberry', '-configuration', 'Debug',
                   '-destination', f'platform=iOS,id={udid}', '-derivedDataPath', str(output),
                   '-allowProvisioningUpdates', f'DEVELOPMENT_TEAM={args.team_id}']
        entitlements = 'Mulberry.entitlements' if args.push else 'LocalDelivery.entitlements'
        command.append(f'CODE_SIGN_ENTITLEMENTS=Configuration/{entitlements}')
        subprocess.run(command + ['build'], env=env, check=True)
        app = output / 'Build/Products/Debug-iphoneos/Mulberry.app'
        subprocess.run(['xcrun', 'devicectl', 'device', 'install', 'app', '--device', identifier,
                        str(app)], env=env, check=True)
        subprocess.run(['xcrun', 'devicectl', 'device', 'process', 'launch', '--device', identifier,
                        'com.subhajit.mulberry.dev'], env=env, check=True)
        print('Installed Mulberry. Sign in with Google, then choose your wallpaper and add the bundled Shortcut.')
        print('APNs enabled.' if args.push else 'Local notification mode: APNs entitlement omitted.')


if __name__ == '__main__':
    main()
