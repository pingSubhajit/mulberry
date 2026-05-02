# Mulberry Backend Local Dev

## Docker Compose

From the repo root:

```bash
docker compose up --build
```

This starts:

- `postgres` on `localhost:5432`
- `backend` on `localhost:8080`

The Android `devDebug` build already points at:

```text
http://10.0.2.2:8080/
```

So an Android emulator can talk to the backend without changing the mobile config.

## Google Sign-In In Dev

For local development, the backend accepts development Google tokens when:

```bash
ALLOW_DEV_GOOGLE_TOKENS=true
```

That is already enabled in `docker-compose.yml`.

Real Google sign-in requires setting `GOOGLE_SERVER_CLIENT_ID` in a local `.env` file at the repo root:

```bash
cp .env.example .env
```

Then fill in:

```text
GOOGLE_SERVER_CLIENT_ID=your-google-oauth-server-client-id
```

## Firebase Cloud Messaging

Background canvas catch-up uses FCM only when Firebase Admin credentials are configured.
For local builds without credentials, the backend uses a no-op sender so tests and foreground
WebSocket sync still run normally.

Use one of these environment variables outside source control:

```text
FIREBASE_SERVICE_ACCOUNT_PATH=/absolute/path/to/service-account.json
FIREBASE_SERVICE_ACCOUNT_JSON={"type":"service_account",...}
```

## Sticker Packs (Catalog + Assets)

Sticker packs are stored in Supabase Storage (separate from the app bundle). The backend needs:

```text
SUPABASE_URL=
SUPABASE_SERVICE_ROLE_KEY=
SUPABASE_STICKER_BUCKET=
STICKER_ADMIN_PASSWORD=
```

For local dev, you can reuse the wallpaper bucket + password:

```text
SUPABASE_STICKER_BUCKET=SUPABASE_WALLPAPER_BUCKET
STICKER_ADMIN_PASSWORD=WALLPAPER_ADMIN_PASSWORD
```

Admin endpoints use the `x-sticker-admin-password` header.

To import a local folder of `.png` stickers as a pack version (example uses the repo-root `temp/` folder):

```bash
cd apps/backend
npx tsx scripts/import-sticker-pack.ts \
  --pack-key kawaii-cats \
  --title "Kawaii Cats" \
  --version 1 \
  --dir "../../temp/Kawaii Cats" \
  --published true \
  --featured true \
  --sort-order 0
```

## Production Deployment On Fly.io

The checked-in production deployment config lives at:

- [fly.toml](../../fly.toml)
- [fly.secrets.example](fly.secrets.example)

The production target is a single always-on Fly machine in `bom` (Mumbai) behind the stable public domain `https://api.mulberry.my/`. Supabase remains the database and wallpaper-storage backend.

## End-to-End Flow

1. Start Docker Compose from the repo root.
2. Run the Android app in the `devDebug` variant.
3. On emulator/device A:
   - sign in
   - finish onboarding
   - generate an invite code
4. On emulator/device B:
   - sign in
   - finish onboarding
   - enter the invite code
   - accept the invite
5. Both clients should route into the paired home flow.

## Resetting Local State

To clear the database and start fresh:

```bash
docker compose down -v
docker compose up --build
```
