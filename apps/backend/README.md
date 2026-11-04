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

Docker Compose reads this setting from the root `.env` (and defaults to `true` if it is unset).
Set `ALLOW_DEV_GOOGLE_TOKENS=false` to require real Google sign-in during local testing.

The apps' developer menus use `/debug/...` routes for test pushes and clearing a streak day. They are on by default in development and off when `NODE_ENV=production`, where they return 404; `ENABLE_DEBUG_ROUTES=true` or `false` overrides either default.

Real Google sign-in requires setting `GOOGLE_SERVER_CLIENT_ID` in a local `.env` file at the repo root:

```bash
cp .env.example .env
```

Then fill in:

```text
GOOGLE_SERVER_CLIENT_ID=your-google-oauth-server-client-id
GOOGLE_ALLOWED_CLIENT_IDS=optional-native-or-web-client-id,optional-second-client-id
```

`GOOGLE_ALLOWED_CLIENT_IDS` is for additional OAuth clients such as the macOS
native client. The original `GOOGLE_SERVER_CLIENT_ID` remains supported.

## Sign in with Apple

The iPhone, iPad and Mac apps also sign in with Apple through `POST /auth/apple`. The backend
checks Apple's identity token against Apple's public keys, for the bundle IDs in
`APPLE_CLIENT_IDS` (by default `com.subhajit.mulberry.beta` and `com.subhajit.mulberry.beta.dev`), and
checks that the token carries the SHA-256 of the nonce the app sends.

An Apple sign-in whose verified email matches an existing account signs in to that account, and
Google sign-in does the same for accounts made with Apple. A Hide My Email address never matches;
people link the other method from Settings (`POST /me/sign-in-methods/google|apple`).

Deleting an account revokes the person's Apple tokens, as Apple requires. That needs the Sign in
with Apple key, `DH4TWPDF63` on team `HMJX9736KN`, kept in the ignored `secrets/apple/` beside the
push keys:

```text
APPLE_TEAM_ID=HMJX9736KN
APPLE_SIGN_IN_KEY_ID=DH4TWPDF63
APPLE_SIGN_IN_PRIVATE_KEY=<the .p8 contents; \n escapes for line breaks are fine>
```

Without them, sign-in still works but nothing is revoked. Locally, `ALLOW_DEV_APPLE_TOKENS`
accepts `dev-apple:SUBJECT:EMAIL` tokens, as `ALLOW_DEV_GOOGLE_TOKENS` does for Google; it is off
in production. Development builds of the apps are signed by a free team, which can't use Sign in
with Apple, so test it with the production identity.

## Firebase Cloud Messaging

Background canvas catch-up uses FCM only when Firebase Admin credentials are configured.
For local builds without credentials, the backend uses a no-op sender so tests and foreground
WebSocket sync still run normally.

Use one of these environment variables outside source control:

```text
FIREBASE_SERVICE_ACCOUNT_PATH=/absolute/path/to/service-account.json
FIREBASE_SERVICE_ACCOUNT_JSON={"type":"service_account",...}
```

## Widget Reloads (APNs)

When the anniversary changes or a pair ends, a silent FCM push tells each device, and
a WidgetKit push (`apns-push-type: widgets`) makes WidgetKit reload the Time together widget, which then asks `GET /widget/time-together` itself (with a per-device secret) so it shows the change even if the app never got the silent push.
iOS doesn't reliably act on a widget reload asked for by an app it woke in the background, but it
does on these. Firebase can't carry them, so the backend sends them to APNs itself, to the widget
push token each iPhone, iPad or Mac reports through `PUT /devices/widget-push`. Without keys the
backend skips them.

```text
APNS_TEAM_ID=HMJX9736KN                 # or APPLE_TEAM_ID
APNS_SANDBOX_KEY_ID=…                   # development builds (aps-environment development)
APNS_SANDBOX_PRIVATE_KEY=-----BEGIN PRIVATE KEY-----\n…\n-----END PRIVATE KEY-----
APNS_PRODUCTION_KEY_ID=…                # TestFlight and App Store builds
APNS_PRODUCTION_PRIVATE_KEY=…
```

The keys must be APNs auth keys valid for the environment and for the topic
`<bundle ID>.push-type.widgets` (team-scoped keys are).

## PostHog Product Analytics

`docs/analytics/tracking-plan.md` lists every event and property. In short:

- People are users and pairs are a PostHog group named "Partner 1 x Partner 2".
- Events go into the `analytics_outbox` table in the same transaction as the change they record, and
  a worker sends them to PostHog with retries.
- The apps send their own events to `POST /analytics/events`.

Production records analytics once `POSTHOG_PROJECT_API_KEY` is set. Other environments record
nothing unless `POSTHOG_DISABLED=false`, and then everyone they record is marked internal.

```text
POSTHOG_PROJECT_API_KEY=phc_...
POSTHOG_HOST=https://us.i.posthog.com
POSTHOG_ENVIRONMENT=production        # defaults to production when NODE_ENV=production
POSTHOG_INTERNAL_EMAILS=a@x.com,b@y.com  # marked is_internal and filtered out of reports
POSTHOG_PERSONAL_API_KEY=phx_...      # deletes a deleted account's PostHog person
POSTHOG_PROJECT_ID=123
POSTHOG_DISABLED=                     # unset: on in production, off elsewhere
```

The first production start after this change backfills PostHog once from the database: sign-ups,
onboarding, current pairs and their active days and reactions. It's recorded in `schema_backfills`
as `analytics.posthog.v2`.

To create or update the Mulberry dashboard, saved insights, the internal-account filter and the
event descriptions (needs a **PostHog personal API key**):

```bash
cd apps/backend
POSTHOG_API_HOST=https://us.posthog.com \
POSTHOG_PROJECT_ID=123 \
POSTHOG_PERSONAL_API_KEY=phx_... \
pnpm posthog:setup-dashboards
```

## Sticker Packs (Catalog + Assets)

Sticker packs are stored in Supabase Storage (separate from the app bundle). The backend needs:

```text
SUPABASE_URL=
SUPABASE_SERVICE_ROLE_KEY=
SUPABASE_STICKER_BUCKET=
ADMIN_PASSWORD=
```

`SUPABASE_STICKER_BUCKET` falls back to `SUPABASE_WALLPAPER_BUCKET` when unset.

Admin endpoints use the `x-admin-password` header. See [Server-driven content](../../docs/server-driven-content.md) for the catalog API, version rules, history, recovery, and agent workflow.

Sticker importers create drafts and publish only after all sticker uploads pass. Existing versions cannot be overwritten. Use the admin API for production; local imports require isolated database and storage settings.

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

## iOS Wallpaper Shortcut Link

The iOS app opens its wallpaper Shortcut from an iCloud link, which goes straight to Shortcuts' Add screen. Bootstrap returns the links in `wallpaperShortcutLinks`, keyed by bundle ID, because each build's Shortcut calls that build's own App Intents:

```text
WALLPAPER_SHORTCUT_LINKS=com.subhajit.mulberry.beta=https://www.icloud.com/shortcuts/…,com.subhajit.mulberry.beta.dev=https://www.icloud.com/shortcuts/…
```

Without a link for its bundle ID, the app falls back to sharing its bundled template. To make a link, build the template for that bundle ID (`apps/ios/scripts/make-shortcut.py`), add it on a device, then choose Share → Copy iCloud Link in Shortcuts. The shared copy must be named exactly "Mulberry Sync".

## Production Deployment On Fly.io

The checked-in production deployment config lives at:

- [fly.toml](../../fly.toml)
- [fly.secrets.example](fly.secrets.example)

The production target is a single always-on Fly machine in `sin` (Singapore) behind the stable public domain `https://api.mulberry.my/`. Supabase remains the existing database and wallpaper-storage backend; changing the Fly region does not provision or move a database.

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
