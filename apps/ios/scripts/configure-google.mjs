#!/usr/bin/env node
// Uses server credentials only on the developer's Mac. Copies only the client
// Firebase config into an ignored file; never exports the service account.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { parseEnv, parseArgs } from 'node:util';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const require = createRequire(path.join(root, 'apps/backend/package.json'));
const { GoogleAuth } = require('google-auth-library');
const { values: options } = parseArgs({ options: {
  environment: { type: 'string', default: 'dev' },
  create: { type: 'boolean', default: false },
  'ios-client-id': { type: 'string' },
  'api-url': { type: 'string' },
} });
if (!['dev', 'prod'].includes(options.environment)) throw new Error('Environment must be dev or prod.');
const variant = options.environment;
const bundleId = variant === 'dev' ? 'com.subhajit.mulberry.dev' : 'com.subhajit.mulberry.beta';
if (variant === 'prod' && options['api-url']) throw new Error('Use run-device.py --api-url for a local production-identity test; do not persist its URL in release settings.');

async function main() {
  const env = parseEnv(fs.readFileSync(path.join(root, '.env'), 'utf8'));
  const credentials = JSON.parse(env.FIREBASE_SERVICE_ACCOUNT_JSON);
  const auth = new GoogleAuth({ credentials, scopes: ['https://www.googleapis.com/auth/cloud-platform'] });
  const token = await auth.getAccessToken();
  async function call(resource, body) {
    const response = await fetch(`https://firebase.googleapis.com/v1beta1/${resource}`, {
      method: body ? 'POST' : 'GET',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: body ? JSON.stringify(body) : undefined,
    });
    const result = await response.json();
    if (!response.ok) throw new Error(`Firebase request failed (${response.status}, ${result.error?.status ?? 'unknown'}).`);
    return result;
  }
  const parent = `projects/${credentials.project_id}`;
  let apps = [];
  let pageToken;
  do {
    const page = await call(`${parent}/iosApps${pageToken ? `?pageToken=${encodeURIComponent(pageToken)}` : ''}`);
    apps.push(...(page.apps ?? []));
    pageToken = page.nextPageToken;
  } while (pageToken);
  let app = apps.find(candidate => candidate.bundleId === bundleId && candidate.state === 'ACTIVE');
  if (!app) {
    if (!options.create) throw new Error('No matching iOS app exists. Register the bundle ID in the existing Firebase project.');
    let operation = await call(`${parent}/iosApps`, { bundleId, displayName: variant === 'dev' ? 'Mulberry iOS Dev' : 'Mulberry iOS' });
    for (let attempt = 0; !operation.done && attempt < 30; attempt++) {
      await new Promise(resolve => setTimeout(resolve, 1000));
      operation = await call(operation.name);
    }
    if (!operation.done) throw new Error(`App registration is still running: ${operation.name}`);
    if (operation.error || !operation.response?.name) throw new Error(`App registration failed (${operation.error?.code ?? 'unknown'}).`);
    app = operation.response;
  }
  const config = await call(`${app.name}/config`);
  const destination = path.join(root, `apps/ios/Configuration/GoogleService-Info.${variant}.plist`);
  fs.writeFileSync(destination, Buffer.from(config.configFileContents, 'base64'), { mode: 0o600 });
  fs.chmodSync(destination, 0o600);
  const plist = Buffer.from(config.configFileContents, 'base64').toString('utf8');
  const clientId = options['ios-client-id'] ?? plist.match(/<key>CLIENT_ID<\/key>\s*<string>([^<]+)<\/string>/)?.[1];
  if (clientId) {
    const serverId = env.GOOGLE_SERVER_CLIENT_ID;
    const clientPattern = /^\d+-[A-Za-z0-9_-]+\.apps\.googleusercontent\.com$/;
    if (!clientPattern.test(clientId) || !clientPattern.test(serverId ?? '')) throw new Error('Invalid OAuth client ID.');
    const apiURL = options['api-url'] ? new URL(options['api-url']) : undefined;
    if (apiURL && (!['http:', 'https:'].includes(apiURL.protocol) || apiURL.username || apiURL.password)) throw new Error('Invalid local API URL.');
    const local = path.join(root, `apps/ios/Configuration/${variant === 'dev' ? 'Local' : 'Production.local'}.xcconfig`);
    const previous = fs.existsSync(local) ? fs.readFileSync(local, 'utf8') : '';
    const team = previous.match(/^DEVELOPMENT_TEAM\s*=\s*([A-Z0-9]{10})\s*$/m)?.[1];
    const apiSetting = apiURL ? apiURL.href.replace('://', ':/$()/')
      : variant === 'dev' ? previous.match(/^MULBERRY_API_BASE_URL\s*=\s*(.+)$/m)?.[1] : undefined;
    fs.writeFileSync(local, [
      '// Public client settings. Never add server credentials here.',
      ...(apiSetting ? [`MULBERRY_API_BASE_URL = ${apiSetting}`] : []),
      `GOOGLE_IOS_CLIENT_ID = ${clientId}`,
      `GOOGLE_REVERSED_CLIENT_ID = ${clientId.split('.').reverse().join('.')}`,
      `GOOGLE_SERVER_CLIENT_ID = ${serverId}`,
      ...(team ? [`DEVELOPMENT_TEAM = ${team}`] : []),
      '',
    ].join('\n'), { mode: 0o600 });
    fs.chmodSync(local, 0o600);
    console.log(`Configured ${bundleId}. Client plist and local build settings saved; server credentials were not copied.`);
  } else {
    console.log('Firebase client plist saved. Supply --ios-client-id to write the matching build settings.');
  }
}

main().catch(error => {
  // Error objects from auth libraries can contain request headers or credentials.
  console.error(error instanceof Error && error.message.startsWith('Firebase request failed') ? error.message : 'Google configuration did not finish. Inspect project access and rerun; credentials were not printed.');
  process.exitCode = 1;
});
