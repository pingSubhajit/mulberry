# Mulberry analytics tracking plan

PostHog is Mulberry's product analytics. Every event reaches PostHog through the backend: the
server records its own events, and the apps send theirs to `POST /analytics/events`. Nothing in
the apps talks to PostHog directly.

## Rules

- **People are users.** Every event's `distinct_id` is the Mulberry user ID of the person who
  acted. Before sign-in it is the install's anonymous ID, which is merged into the user at sign-in.
- **Pairs are groups.** Events about a pair carry the PostHog group `pair` (key: the pair ID) and a
  `pair_id` property. The group's `name` is "Partner 1 x Partner 2", inviter first.
- **Personal details live only on people and pairs.** Email and names are person and group
  properties. Event properties never carry them, so deleting a person removes them.
- **Deleting an account removes its details.** The person's email and name are cleared at once and
  the PostHog person is deleted an hour later. On every pair they were ever in, current or ended,
  they show as "Deleted account" with no email (`analytics_pair_members` remembers those pairs), and
  a remaining partner's `partner_name` and `partner_email` are cleared.
- **No IP location.** Every event comes from the server, so each one sets `$geoip_disable`; otherwise
  PostHog would place everyone where the server is.
- **Production only.** Servers whose `POSTHOG_ENVIRONMENT` isn't `production` record nothing unless
  `POSTHOG_DISABLED=false`, and then every person they record is marked internal.
- **Internal people** (`is_internal = true`) are the accounts in `POSTHOG_INTERNAL_EMAILS`, and
  everyone recorded by a development server. The PostHog project filters them out by default.
- **Nothing is lost.** Events are written to the `analytics_outbox` table in the same database
  transaction as the change they describe, then sent with retries. Each event's ID is its PostHog
  `uuid`, so a retry never counts twice.

## Definitions

| Term | Meaning |
|---|---|
| Active pair (day) | A pair that drew, wrote, placed a sticker or sent a reaction that UTC day: one `mulberry_pair_active_day` |
| Active pair (week) | The same per ISO week: one `mulberry_pair_active_week` |
| Paired | `is_paired = true` on the person |
| Half-pair | A person with `is_paired = false` who has signed up |
| Can see doodles | `wallpaper_visible = true`: at least one surface (Android wallpaper, iOS Shortcut, Mac overlay) currently shows the latest drawings |
| Activated pair | Both partners have `wallpaper_ever_visible = true` and the pair has an active day within 7 days of `paired_at` |

## Person properties

| Property | Meaning |
|---|---|
| `email`, `name` | The account's email and display name |
| `display_label` | "Name - email", or the email before a name is set. PostHog shows it wherever it names a person |
| `sign_in_methods` | `google`, `apple` |
| `signed_up_at` | Account creation time (set once) |
| `is_internal` | Internal or development account |
| `is_paired`, `pair_id`, `pair_name`, `partner_name`, `partner_email`, `paired_at` | Current pair; cleared on unpair |
| `platforms`, `device_families` | Every platform and device family seen, e.g. `android`, `ios`, `macos` / `iphone`, `ipad`, `mac`, `android_phone` |
| `last_platform`, `last_device_family`, `last_app_build` | The most recent client |
| `wallpaper_visible`, `wallpaper_ever_visible`, `visible_surfaces` | Doodle visibility across surfaces |
| `onboarding_completed` | Finished profile onboarding |

## Pair (group) properties

`name` ("Partner 1 x Partner 2"), `partner_one_name`, `partner_one_email`, `partner_two_name`,
`partner_two_email`, `paired_at`, `status` (`active`, `ended`), `ended_at`, `ended_reason`,
`is_internal` (either partner internal).

## Server events

Every server event also carries `environment`, `source` (`server`, or `backfill` for history
recorded before this plan), and, when the request came from
an app, `platform`, `device_family` and `app_build`.

| Event | When | Properties |
|---|---|---|
| `mulberry_user_signed_up` | An account is created | `sign_in_method` |
| `mulberry_app_opened` | First request to `/bootstrap` each UTC day | |
| `mulberry_onboarding_completed` | Profile onboarding finishes the first time | |
| `mulberry_invite_created` | A new invite code is made | `invite_id`, `expires_at` |
| `mulberry_invite_redeem_failed` | A code is refused | `reason`: `not_found`, `expired`, `no_longer_valid`, `own_invite`, `rate_limited` |
| `mulberry_invite_redeemed` | A partner enters a valid code | `invite_id`, `inviter_user_id` |
| `mulberry_invite_declined` | The partner declines | `invite_id`, `inviter_user_id` |
| `mulberry_invite_expired` | An unused invite passes its expiry (sent by a sweep, timestamped at expiry) | `invite_id` |
| `mulberry_invite_accepted` | The partner accepts | `invite_id`, `inviter_user_id` |
| `mulberry_pair_created` | A pair forms (by the accepting partner) | `invite_id`, `inviter_user_id` |
| `mulberry_pair_ended` | A pair ends | `reason`: `unpaired`, `account_deleted`; `pair_age_days`, `pair_active_days` |
| `mulberry_account_deleted` | An account is deleted (personless) | `was_paired`, `account_age_days`, `is_internal` |
| `mulberry_canvas_batch_ingested` | Canvas changes are saved | counts per operation type, `sticker_pack_keys` |
| `mulberry_canvas_first_action` | A pair's first canvas change | `first_action_type` |
| `mulberry_reaction_sent` | A reaction is sent | `reaction_type` |
| `mulberry_pair_active_day` / `_week` | A pair's first activity in a UTC day / ISO week | `activity_source`: `canvas`, `reaction` (`unknown` when backfilled) |
| `mulberry_streak_milestone` | A pair's streak reaches 3, 7, 14, 30, 50, 100, 200 or 365 days | `streak_days` |
| `mulberry_wallpaper_first_visible` | A surface shows doodles for the first time | `surface_type` |
| `mulberry_wallpaper_visibility_changed` | A surface starts or stops showing doodles | `surface_type`, `can_see_latest_drawings` |
| `mulberry_push_sent` | A visible notification is sent, per recipient | `push_type` |

## App events (Phase 2)

### Headers on every request to the Mulberry API

| Header | Value |
|---|---|
| `X-App-Platform` | `ios`, `android` or `macos` |
| `X-App-Build` | The integer build number |
| `X-Device-Family` | `iphone`, `ipad`, `mac`, `android_phone` or `android_tablet` (tablet: smallest width ≥ 600dp) |
| `X-Analytics-Anonymous-Id` | A lowercase UUID made once per install and kept until the app is deleted, across sign-outs |

Android debug builds keep leaving out `X-App-Platform` and `X-App-Build`, as before, but send the
other two.

### `POST /analytics/events`

- `Authorization: Bearer …` is optional. Without it, `X-Analytics-Anonymous-Id` is required.
- Body: `{ "events": [ { "id", "event", "occurredAt", "properties" } ] }`, 1 to 50 events.
  - `id`: a lowercase UUID made when the event happens and reused on every retry.
  - `occurredAt`: ISO 8601 in UTC.
- Reply: `202 { "accepted": n, "rejected": n }`. Invalid events are dropped one by one.
- Clients drop the batch on any 2xx or 4xx reply, and keep it for the next try on a network error
  or 5xx. Analytics never shows UI, never blocks, and never signs anyone out: a 401 here just drops
  the batch.
- Clients keep up to 200 unsent events on disk (oldest dropped first), and send on launch, a few
  seconds after a new event, and when the app goes to the background.

### Events

| Event | When | Properties |
|---|---|---|
| `mulberry_onboarding_step_viewed` | An onboarding or pairing screen appears | `step` |
| `mulberry_wallpaper_setup_step` | A wallpaper, Shortcut or overlay setup step appears, finishes, fails or is skipped | `surface_type`, `step`, `outcome`, optional `failure_reason` |
| `mulberry_push_opened` | The person opens a notification | `push_type` |
| `mulberry_share_opened` | The share sheet opens from the canvas | None |
| `mulberry_share_frame_selected` | The person taps a frame on the share sheet | `frame_key` |
| `mulberry_share_completed` | The image goes to the system share sheet (iOS: after sharing finishes; Android: when the chooser opens) | `frame_key`, `used_real_wallpaper`, `caption_edited`, `activity_type` (iOS only, optional) |

**`step` (onboarding):** `welcome`, `sign_in`, `profile_name`, `partner_details`, `pairing_choice`,
`invite_code_shown`, `redeem_code_entry`, `invite_review`, `waiting_for_partner`,
`paired_celebration`, `notifications_permission`, `wallpaper_setup`, `onboarding_finished`.

**`surface_type`:** `ANDROID_WALLPAPER`, `IOS_SHORTCUT`, `MACOS_OVERLAY`.

**`step` (wallpaper setup):** `intro`, `choose_background`, `set_wallpaper`, `install_shortcut`,
`create_automation`, `grant_permission`, `enable_overlay`, `run_test`, `finished`.

**`outcome`:** `viewed`, `completed`, `failed`, `skipped`. **`failure_reason`:** snake_case, at
most 64 characters, no personal details.

**`push_type`:** `CANVAS_NUDGE`, `DRAW_REMINDER`, `REACTION`, `PAIRING_CONFIRMED`,
`PAIRING_DISCONNECTED`, `PARTNER_VISIBILITY_CHANGED`, `ANNIVERSARY_CHANGED`, `OTHER`.

The server rejects any other event name or value, so a new step or push type is added here and in
`apps/backend/src/infra/analytics/events.ts` first.

## History and legacy data

- Events recorded before this plan have no `source` property. They were keyed to invite and pair
  IDs, not people, so the saved insights leave them out (`source` is set).
- On its first production start, the backend backfills PostHog once from the database, with
  `source = backfill`:
  - Every account's sign-up and onboarding.
  - Every current pair, with its active days, active weeks and reactions.
  - Each person's and pair's properties.
- PostHog attaches an event to its pair group only when the event is recent and belongs to a person,
  so backfilled events, which carry old timestamps, have no pair group. Pair trends, funnels and
  retention count unique pairs (so their lists name pairs) from 2026-11-04 on; the "Most active
  pairs" tables read `pair_id` and cover the whole history.
- Older events sometimes had a `source` property of their own (`canvas`, `reaction`). Only events
  whose `source` is `server`, `app` or `backfill` follow this plan.
- Pairs that ended before the backfill can't be recovered, because unpairing deletes them.
- Invites that expired earlier are recorded by the expiry sweep, timestamped at their expiry.

## Operations

- `pnpm posthog:setup-dashboards` in `apps/backend` does three things:
  - Sets the project's internal-account filter.
  - Adds the event descriptions.
  - Creates or updates the "Mulberry — Core" dashboard: funnels, active pairs, retention by pair,
    setup steps, notifications, pair endings, plus the "Unpaired people" and "Most active pairs"
    (30 days and all time) tables.
- A row that keeps failing to send is retried with backoff for about a day, then kept with
  `dead_at` set. To check the outbox:

  ```sql
  SELECT kind, count(*), max(last_error) FROM analytics_outbox GROUP BY kind
  ```

