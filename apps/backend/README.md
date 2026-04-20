# Elaris Backend Local Dev

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
