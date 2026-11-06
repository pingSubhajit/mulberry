---
name: app-versions
description: Read or change the build numbers that decide which Mulberry iOS and Android builds are asked, or forced, to update. Use when the user asks to force an update, nudge people to update, raise or lower the minimum or recommended build, record a newly live build, or check the current app version thresholds.
---

# App versions

Each platform (`ios`, `android`) has three build numbers, kept in Firebase Remote Config and changed through the backend's admin API:

| Field | Meaning |
| --- | --- |
| `liveBuild` | The newest build fully available in the store. Neither threshold may go above it. |
| `recommendedBuild` | Builds below it are asked to update, and can dismiss the prompt. On Android this is Play's update prompt, which nobody sees unless this is set. |
| `minimumBuild` | Builds below it are blocked until they update. The server also turns them away with 426 within a minute. |

They must stay `minimumBuild ≤ recommendedBuild ≤ liveBuild`. Builds are compared by build number, never by version name: iOS `CFBundleVersion` (shared with the Mac, bumped by the `testflight-release` subskill) and Android `versionCode` (`MULBERRY_VERSION_CODE` in `apps/mobile/gradle.properties`). The two counters are separate, so the platforms' numbers differ. iPad counts as `ios`; the Mac app has no gate.

**Every change reaches real users at once, from any backend.** The local backend publishes to the same Firebase project as production, so a change through `localhost` changes the live apps too. There is no test environment.

## Calling the API

Production: `https://api.mulberry.my`. Authenticate with the admin password in the `x-admin-password` header. Read it from the repo-root `.env` without printing it:

```sh
API=https://api.mulberry.my
ADMIN_PASSWORD="$(grep '^ADMIN_PASSWORD=' .env | cut -d= -f2- | tr -d "'\"")"
```

If the server answers 401 with that password, ask the user for the production one. Never guess, and never echo the password.

Read everything (values, a one-line summary in `message`, and recent changes in `history`):

```sh
curl -s "$API/admin/app-versions" -H "x-admin-password: $ADMIN_PASSWORD"
```

Change any of one platform's values; fields left out stay as they are:

```sh
curl -s -X PATCH "$API/admin/app-versions/android" \
  -H "x-admin-password: $ADMIN_PASSWORD" -H "content-type: application/json" \
  -d '{"liveBuild": 7, "recommendedBuild": 7}'
```

Every reply has a `message` saying what happened, or what to send instead. Relay it to the user. A successful change also returns `previous`, `policy`, `changed`, and the same `policies` and `history` as the read.

| Status | `code` | Meaning |
| --- | --- | --- |
| 200 | | Changed, or `Nothing changed` if the values already matched. |
| 400 | `INVALID_APP_VERSIONS` | A bad value, unknown field, or broken ordering. `message` says how to fix it; `current` and `proposed` show the values. |
| 401 | | Missing or wrong password. |
| 404 | `UNKNOWN_PLATFORM` | Use `ios` or `android`. |
| 409 | `CONFIRMATION_REQUIRED` | Raising `minimumBuild` needs `"confirm": true`. See below. |
| 502, 503 | | Remote Config failed or isn't configured on the server. Report it; don't retry in a loop. |

## Rules

1. **Read first.** Get the current values and tell the user what you're about to change from and to.
2. **Never send `"confirm": true` on your own.** A 409 means the change would block everyone below the new minimum. Show the user the 409's `message` and send `confirm` only after they explicitly agree in this conversation. Lowering a minimum needs no confirmation.
3. **Set `liveBuild` only once a build is fully available:** showing in the App Store (allow a few hours for every region), or reviewed and rolled out to everyone on Play's open testing track, Mulberry's public Android channel. The `public-release` subskill sets it when a version is marked public, so it rarely needs setting by hand.
4. **Don't raise `minimumBuild` past a build that dropped support for an OS version** (iOS or Android) unless the user means to strand people on it: they'd be blocked with no update they can install.
5. **One platform per request.** iOS and Android releases go live at different times.

## Common requests

- **"Build N is live on Android, nudge everyone":** `{"liveBuild": N, "recommendedBuild": N}`.
- **"Force everyone onto build N":** confirm the build is fully live, then `{"liveBuild": N, "recommendedBuild": N, "minimumBuild": N}`. Expect a 409; ask the user; resend with `"confirm": true`.
- **"Undo the forced update":** `{"minimumBuild": <previous value>}`. `history` shows earlier values.
- **Breaking backend change:** ship the compatible app build, wait until most people have it, raise the minimum to it, then deploy the backend change. Builds from before the gate (iOS build 6 and earlier, Android `versionCode` 27 and earlier) can't be blocked, so the API must keep working for them.
