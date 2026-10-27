#!/usr/bin/env node
// Publish a revision and inject an alert into a booted simulator. No APNs involved.
import { spawnSync } from 'node:child_process';

const server = process.env.MULBERRY_SERVER ?? 'http://127.0.0.1:8787';
const device = process.env.MULBERRY_SIMULATOR ?? 'booted';
const bundle = process.env.MULBERRY_BUNDLE_ID ?? 'com.subhajit.mulberry.deliverylab';
const response = await fetch(new URL('/publish', server), { method: 'POST' });
if (!response.ok) throw new Error(`Mock publish failed: ${response.status}`);
const canvas = await response.json();
const payload = {
  aps: { alert: { title: 'Mulberry canvas updated', body: `Test revision ${canvas.revision}. Simulated remote notification.` },
    'interruption-level': 'passive', 'thread-id': 'mulberry-delivery-lab' },
  kind: 'wallpaper_update',
  revision: canvas.revision,
  transport: 'simulator_push',
};
const pushed = spawnSync('xcrun', ['simctl', 'push', device, bundle, '-'], {
  input: JSON.stringify(payload), encoding: 'utf8',
  env: { ...process.env, DEVELOPER_DIR: process.env.DEVELOPER_DIR ?? '/Applications/Xcode.app/Contents/Developer' },
});
if (pushed.status !== 0) throw new Error(pushed.stderr || pushed.error?.message || 'simctl push failed');
console.log(JSON.stringify({ revision: canvas.revision, transport: 'simulator_push',
  result: pushed.stdout.trim(), wallpaperChanged: 'unverified' }, null, 2));
