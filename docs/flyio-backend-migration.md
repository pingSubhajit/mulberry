# Fly.io Backend Migration Runbook

This runbook moves the Mulberry backend from Railway to Fly.io without changing the public production API origin. The cutover keeps `https://api.mulberry.my/` stable for mobile and web clients while reusing the current Supabase database and storage.

## Target shape

- Fly app: `mulberry-api`
- Region: `bom` (Mumbai)
- Runtime: existing root `Dockerfile`
- Public health check: `GET /health`
- WebSocket sync: `GET /canvas/sync`
- Machine count: `1`
- Autostop: disabled
- Database and storage: existing Supabase project
- Rollback: switch `api.mulberry.my` DNS back to Railway

If `mulberry-api` is not available in your Fly organization, create a different unique app name and update the `app` field in [`fly.toml`](/Users/subho/Documents/Workspace/Projects/elaris/fly.toml).

## 1. Prepare Fly and secrets

Install and authenticate:

```bash
brew install flyctl
fly auth login
```

Create the app before the first deploy:

```bash
fly apps create mulberry-api
```

Create a local secrets file from the template:

```bash
cp apps/backend/fly.secrets.example .fly.secrets
```

Fill `.fly.secrets` with the current production values from Railway:

- `DATABASE_URL`
- `GOOGLE_SERVER_CLIENT_ID`
- `FIREBASE_SERVICE_ACCOUNT_JSON`
- `SUPABASE_URL`
- `SUPABASE_SERVICE_ROLE_KEY`
- `SUPABASE_WALLPAPER_BUCKET`
- `WALLPAPER_ADMIN_PASSWORD`

Use a single-line JSON string for `FIREBASE_SERVICE_ACCOUNT_JSON`.

Import the secrets into Fly:

```bash
fly secrets import < .fly.secrets
```

The non-secret runtime config already lives in [`fly.toml`](/Users/subho/Documents/Workspace/Projects/elaris/fly.toml):

- `NODE_ENV=production`
- `PORT=8080`
- `ALLOW_DEV_GOOGLE_TOKENS=false`

## 2. First deploy on the Fly hostname

Run the first deploy from the repository root:

```bash
fly deploy --ha=false
```

Force the app back to one machine after the first deploy:

```bash
fly scale count 1
```

Confirm the machine is in Mumbai and healthy:

```bash
fly status
fly checks list
```

Smoke-test the Fly hostname:

```bash
curl -i https://mulberry-api.fly.dev/health
curl -i https://mulberry-api.fly.dev/bootstrap
```

Expected results:

- `/health` returns `200` with `{"ok":true}`
- `/bootstrap` without auth returns `401` with `Missing bearer token`

## 3. Functional validation before cutover

Validate the Fly hostname before touching DNS:

1. Sign in on two real devices against the existing prod mobile build.
2. Confirm onboarding and bootstrap succeed.
3. Confirm pairing succeeds.
4. Confirm both devices can connect to `/canvas/sync`.
5. Draw on device A and verify device B receives the foreground stroke updates.
6. Background one device and confirm push-triggered snapshot recovery still converges.
7. Verify wallpaper catalog and wallpaper admin flows still work.

Keep Railway live while doing all Fly-hostname validation.

## 4. Attach `api.mulberry.my`

Attach the custom domain to the Fly app:

```bash
fly certs add api.mulberry.my
fly certs check api.mulberry.my
```

Follow the DNS instructions returned by Fly. Do not switch production traffic yet.

Before cutover:

1. Lower the DNS TTL for `api.mulberry.my`.
2. Wait for the lower TTL to propagate.
3. Confirm Fly shows the certificate and DNS state as ready.

## 5. DNS cutover

Once the Fly hostname has passed the full smoke test:

1. Update `api.mulberry.my` DNS to point to Fly.
2. Wait for TLS and DNS propagation to settle.
3. Re-run the same smoke checks against `https://api.mulberry.my/`.

Production-domain checks:

```bash
curl -i https://api.mulberry.my/health
curl -i https://api.mulberry.my/bootstrap
fly logs
```

Device checks:

1. Google auth succeeds.
2. Pairing succeeds.
3. Two foreground devices exchange websocket strokes.
4. Background catch-up still works.
5. Wallpaper catalog and admin endpoints still work.

Monitor Fly logs during the first hour after cutover for:

- health check failures
- websocket disconnect spikes
- startup or memory crashes
- repeated auth or FCM errors

## 6. Rollback

Keep Railway deployed and untouched until Fly passes the observation window.

Rollback steps:

1. Point `api.mulberry.my` DNS back to Railway.
2. Wait for DNS propagation.
3. Confirm `/health` and `/bootstrap` on `api.mulberry.my`.
4. Re-test sign-in, pairing, websocket sync, and background sync.

No client rebuild is required because the public API domain remains the same.

## 7. Post-cutover cleanup

After Fly remains stable for the rollback window:

1. Export the final Fly app config with `fly config save` if needed.
2. Remove or archive the Railway deployment once you are confident rollback is no longer needed.
3. Remove the temporary local `.fly.secrets` file.
