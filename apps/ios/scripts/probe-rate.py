#!/usr/bin/env python3
"""Probe Shortcut runs or simulated notification delivery in a simulator.

Finish the template's permission prompts first. Output records requests only.
Inspect the app event log for completions and the screen for visual correctness.
"""
import argparse
import json
import os
import subprocess
import time
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
parser.add_argument('--interval', type=float, default=30)
parser.add_argument('--count', type=int, default=5)
parser.add_argument('--trigger', choices=['shortcut', 'notification'], default='notification')
args = parser.parse_args()
if args.interval < 1 or not 1 <= args.count <= 20:
    parser.error('Use interval >= 1 second and count between 1 and 20')
env = dict(os.environ)
env.setdefault('DEVELOPER_DIR', '/Applications/Xcode.app/Contents/Developer')
server = env.get('MULBERRY_SERVER', 'http://127.0.0.1:8787').rstrip('/')
device = env.get('MULBERRY_SIMULATOR', 'booted')
for index in range(args.count):
    with urlopen(Request(server + '/publish', method='POST'), timeout=15) as response:
        manifest = json.load(response)
    if args.trigger == 'notification':
        payload = {'aps': {'alert': {'title': 'Mulberry canvas updated',
            'body': f'Test revision {manifest["revision"]}. Simulated remote notification.'},
            'thread-id': 'mulberry-delivery-lab', 'interruption-level': 'passive'},
            'kind': 'wallpaper_update', 'revision': manifest['revision'], 'transport': 'simulator_push'}
        result = subprocess.run(['xcrun', 'simctl', 'push', device,
            env.get('MULBERRY_BUNDLE_ID', 'com.subhajit.mulberry.deliverylab'), '-'],
            input=json.dumps(payload), env=env, capture_output=True, text=True)
    else:
        result = subprocess.run(['xcrun', 'simctl', 'openurl', device,
            'shortcuts://run-shortcut?name=Mulberry%20Wallpaper%20POC'], env=env, capture_output=True, text=True)
    print(json.dumps({'revision': manifest['revision'], 'requestedAt': time.time(),
        'trigger': args.trigger, 'launchStatus': result.returncode,
        'stderr': result.stderr.strip(), 'wallpaperChanged': 'unverified'}), flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
    if index + 1 < args.count:
        time.sleep(args.interval)
