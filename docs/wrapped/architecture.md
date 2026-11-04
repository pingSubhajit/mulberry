# Mulberry Wrapped: Architecture

Last updated: 4 November 2026

This is the system design for *Our Year in Doodles* (internally Mulberry Wrapped). The [North Star](north-star.md) says what the recap is and why. This document says how the server and the apps build it, and the contract between them. Read the North Star first.

## Contents

1. [Overview](#1-overview)
2. [Core decisions](#2-core-decisions)
3. [Recap definitions](#3-recap-definitions)
4. [Generation](#4-generation)
5. [The RecapDocument](#5-the-recapdocument)
6. [Replay data](#6-replay-data)
7. [Design assets and themes](#7-design-assets-and-themes)
8. [API](#8-api)
9. [Live state and side effects](#9-live-state-and-side-effects)
10. [Apps](#10-apps)
11. [Control and testing](#11-control-and-testing)
12. [Storage and privacy](#12-storage-and-privacy)
13. [Open items](#13-open-items)

## 1. Overview

```
  EXISTING DATA                 GENERATION (at cutoff)            SERVING (on every open)
 ┌──────────────────┐        ┌────────────────────────┐        ┌──────────────────────────┐
 │ canvas_operations│──┐     │ 1. Eligibility         │        │ Recap API                │
 │ pair_activity_days│ │     │ 2. Facts compiler      │──────▶ │  facts + definition      │
 │ reaction_events  │──┼───▶ │    (frozen numbers)    │ recap_ │  + live state + viewer   │
 │ time zone history│  │     │ 3. Replay compiler     │ facts  │  ──▶ RecapDocument       │
 │ pairs, stickers  │──┘     │    (compact streams)   │        │  (cards, per viewer)     │
 └──────────────────┘        └───────────┬────────────┘        └────────────┬─────────────┘
                                         │ replay blobs (private)            │
   RECAP DEFINITION          ┌───────────▼────────────┐   DESIGN ASSETS      │
   "year-2026": cards,       │ Supabase Storage + CDN │◀── (public, one file │
   rules, copy, themes       └───────────┬────────────┘    per theme)        │
                                         ▼                                  ▼
                       ┌──────────────────────────────────────────────────────────┐
                       │ APP: Recap runtime (iPhone/iPad shared package, Android)  │
                       │  feed ─ layout registry ─ native card views ─ layers      │
                       │  replay player (existing canvas renderer)                 │
                       │  asset prefetch and cache ─ image and video export        │
                       └───────────────┬──────────────────────────────────────────┘
                                       │ progress, note, nudge, reactions
                                       ▼
                       ┌──────────────────────────────────────────────────────────┐
                       │ STATE AND SIDE EFFECTS: recap_progress, recap_notes,      │
                       │ realtime hub (poster unlock), pushes, note scheduler ──▶  │
                       │ canvas operations at local midnight ──▶ normal sync,      │
                       │ push and wallpaper paths on every platform                │
                       └──────────────────────────────────────────────────────────┘
```

## 2. Core decisions

1. **Facts are frozen at the cutoff; cards are built on every open.** A job computes each pair's facts once and stores them. The RecapDocument is never stored. The API builds it on each request from the facts, the definition, live state and the viewer. This gives per-viewer wording, cards that change with time (the note card, the poster lock), a pair-still-exists check on every open, and wording fixes after launch without recomputing anything.
2. **A recap is a definition, not app code.** `year-2026` is a backend definition: its cards, the rule for each card, wording, themes and eligibility. A Valentine's or anniversary recap is a new definition on the same facts and layouts. Changing a definition is a backend deploy, never an app release.
3. **The server never renders a couple's canvas.** Replays and past canvases are sent as compact drawing data. The apps draw them with their existing canvas renderers. Design assets (videos, motion, illustrations) are the only media the server sends, and they're the same for everyone with the same theme.
4. **Cards match app builds on both sides.** Each card names its `layout` and `layoutVersion`. The server leaves out cards a build can't draw, using `X-App-Build`. The app also skips any layout it doesn't know.
5. **Side effects go through existing paths.** The midnight note becomes ordinary canvas operations. React sends an ordinary reaction. Poster unlock uses the existing realtime hub and push.

## 3. Recap definitions

A definition lives in the backend repo, for example `apps/backend/src/modules/recaps/definitions/year2026.ts`. It's reviewed like code and ships with a backend deploy. Switches that must change without a deploy (on/off, dates, allowlist) live in the database (see [Control and testing](#11-control-and-testing)).

```ts
interface RecapDefinition {
  id: string                       // "year-2026"
  title: string                    // "Our Year in Doodles 2026"
  cutoff: string                   // ISO date; activity after it isn't counted
  eligibility: {
    fullMinActiveDays: number      // 10
    shortCardIds: string[]         // "Your first chapter"
  }
  themes: ThemeDefinition[]
  pickTheme: (facts: RecapFacts) => ThemeId
  cards: CardDefinition[]          // in feed order
}

interface CardDefinition {
  id: string                       // stable, e.g. "colours"
  layout: LayoutName
  layoutVersion: number
  requiredFacts: FactName[]
  show: (context: CardContext) => boolean      // "only if the streak is at least 3"
  build: (context: CardContext) => CardContent // data, layers and wording
}
```

- **Facts are named and versioned**, for example `colours.v1`, `streak.v1`, `peakHour.v1`, `coupleType.v1`, `replayWindows.v1`, `posterChoices.v1`. A definition asks only for the facts it needs, so later definitions can add facts without touching old ones.
- **`CardContext`** holds the pair's facts, the viewer (which partner is looking), live state and the current time. `show` and `build` must be pure: same input, same card.
- **Wording lives in the definition**, filled in on the server, so every platform gets the same copy at once.

## 4. Generation

A job runs once at the cutoff, outside the request path, as a backend command.

1. **Eligibility.** Every pair that exists at the cutoff and has at least one active day. Pairs below `fullMinActiveDays` get the short version.
2. **Facts.** Computed from `canvas_operations`, `pair_activity_days`, `reaction_events` and the time zone history. Local times use the zone each person had at that moment, with the fallbacks in the North Star.
3. **Replays.** The replay compiler turns each window the definition needs (day one, the rest of the year, the biggest day, poster choices) into a replay blob in private storage.
4. **Theme.** `pickTheme` runs on the facts and the result is stored with them.

Rules:

- **Per pair and idempotent.** Each pair is written in its own transaction. A rerun skips finished pairs, and an admin call can regenerate one pair.
- **Frozen.** Facts and blobs don't change after generation unless regenerated on purpose.
- **Nothing for later pairings.** A pair formed after the cutoff gets no recap.

## 5. The RecapDocument

What `GET /recaps/:recapId` returns for one viewer. Every field the apps need comes from here.

```ts
interface RecapDocument {
  recapId: string
  schemaVersion: 1
  title: string
  availableUntil: string            // the app clears its cache after this
  viewer: { userId: string; displayName: string; photoUrl: string | null }
  partner: { userId: string; displayName: string; photoUrl: string | null }
  theme: { id: ThemeId; palette: ThemePalette }   // colours the native UI only
  assets: AssetEntry[]              // only this pair's theme files
  cards: Card[]                     // already filtered for this viewer and build
}

interface ThemePalette { base: string; primary: string; accent: string; ink: string }

interface Card {
  id: string
  layout: LayoutName
  layoutVersion: number
  layers: LayerRef[]                // design assets placed in the layout's slots
  data: LayoutData                  // typed per layout, see below
  copy: Record<string, string>      // final wording, names filled in
  actions: { share: "image" | "video" | "none"; react: boolean; save: boolean }
}

interface LayerRef { slot: "background" | "motion" | "illustration" | "foreground"; assetId: string }
```

`LayoutData` is a union with one shape per layout. For the 2026 cards:

| Layout | `data` |
| --- | --- |
| `cover` | Year |
| `canvasReplay` | Segments: each with a replay window ID, duration, speed curve, captions and changing labels, and whether clears are kept |
| `splitComparison` | Two values with labels, and the colour strip |
| `timeVisual` | `calendar` (active days, longest streak) or `dial` (hour buckets) |
| `persona` | Title and roles |
| `poster` | Canvas choices (replay window IDs), stats, `locked`, `partnerReached` |
| `drawingInput` | `availableUntil`, `noteSent`, and the note if one was left |

The apps own how each layout looks and moves. The server owns what goes in it.

### Versioning

- **`schemaVersion`** changes only for breaking changes to the document. The server sends the newest version a build understands.
- **`layoutVersion`** changes when a layout's `data` changes in a way older views can't read.
- **The layout support table** on the server maps `layout@version` to the first build that draws it, per platform. The server uses it with `X-App-Platform` and `X-App-Build` to leave out cards. A build with no recap support gets no document.

## 6. Replay data

One format serves the time-lapses, "the canvas that night" and the poster choices.

```ts
interface ReplayBlob {
  formatVersion: 1
  windowId: string
  start: string                      // ISO
  end: string
  initial: CanvasElement[]           // the canvas as it was at `start`
  events: ReplayEvent[]              // in order, times in ms from `start`
}
```

- **`initial`** is the canvas at the start of the window, so a one-day replay starts from what was already there.
- **`events`** are finished strokes, text and sticker adds, updates and deletes, stroke deletes and clears, each with a time and its author. Unfinished batches (`APPEND_POINTS`) are folded into their stroke.
- **Points** are relative to the canvas, as stored today, quantised to 16-bit integers and delta-encoded.
- **A still canvas** (a poster choice) is a blob with `initial` and no events.
- **Encoding:** gzipped JSON for version 1, which is easy to inspect. Switch to binary only if sizes call for it.
- **Delivery:** private bucket, signed URL per request, fetched per card as the feed reaches it. Today's canvas shows while a replay loads.

Timing (segment durations, speed curves, the switch to eased canvas states past about 150 strokes) lives in the card's `data` and the app's player, not in the blob.

## 7. Design assets and themes

Backgrounds, motion graphics, illustrations and videos are downloaded, never bundled. The apps contain only the native layouts and the Lottie or Rive runtime.

### Layers

A layout has named slots: `background`, `motion`, `illustration`, `foreground`. A card fills slots with asset IDs. The native view puts native content (numbers, names, replays, buttons) between and above the layers.

### Assets

```ts
interface AssetEntry {
  id: string
  kind: "video" | "lottie" | "rive" | "image"
  still: AssetFile                   // shown while loading and with Reduce Motion
  variants: AssetVariant[]
}

interface AssetVariant { deviceClass: "phone" | "tablet"; file: AssetFile }
interface AssetFile { url: string; format: string; bytes: number; hash: string }
```

- **Themes are separate files.** A themed asset has one file for each theme, of every kind: video, Lottie or Rive, image. The apps never recolour anything.
- **The server picks the theme.** The document lists only the pair's theme files, so a phone downloads one file per asset and never needs to know which theme it has. The theme's palette is sent only to colour native UI: text, buttons, the progress line and the poster frame.
- **The app picks the variant** for its device class and supported formats.
- **Publishing check:** a themed asset can't be published until it has a file for every theme in the definition.

### Delivery

- **Storage:** a public `recap-assets` bucket in Supabase Storage, served through its CDN. The assets aren't personal.
- **Paths include the content hash**, for example `recap-assets/bg-orbits/midnight/<hash>.mp4`, uploaded with a cache lifetime of a year. A changed file gets a new path, so the cache never needs clearing.
- **Uploads** go through admin endpoints built like the wallpaper pipeline in [Server-driven content](../server-driven-content.md), with the same history and confirmation rules.

### Prefetch

1. When the recap becomes available, the app fetches the document and starts downloading assets in the background: the first cards' assets first, the rest on Wi-Fi.
2. Opening the feed waits only for the first few cards. Later cards show their still frame until their files are ready.
3. Files are cached by hash. The cache is cleared after `availableUntil`.
4. Video export uses the cached files, so shared videos match the screen.

## 8. API

All routes need the normal bearer token and check that the viewer is in the pair.

| Method and route | Purpose |
| --- | --- |
| Bootstrap field `recap` | `{ recapId, title, availableUntil, opened }` or `null`. Drives the canvas banner and the Settings entry. |
| `GET /recaps/:recapId` | The RecapDocument for this viewer and build. |
| `GET /recaps/:recapId/replays/:windowId` | A signed URL for one replay blob. |
| `POST /recaps/:recapId/progress` | `{ cardId }`, the furthest card reached. The first call sends the partner "*[Partner]* just unwrapped your year". |
| `POST /recaps/:recapId/nudge` | Nudges the partner from the locked poster. Rate-limited. |
| `PUT /recaps/:recapId/note` | Leaves or replaces the midnight note: drawing data in the same shape as replay strokes. |

Admin, behind `x-admin-password`:

| Method and route | Purpose |
| --- | --- |
| `GET`, `PATCH /admin/recaps/:recapId` | Read or change the on/off switch, dates and allowlist. |
| `POST /admin/recaps/:recapId/generate` | Generate facts for all pairs, or for one pair with a chosen cutoff. |
| `GET /admin/recaps/:recapId/pairs/:pairId` | The stored facts and the document each partner would get. |
| `/admin/recap-assets/...` | Upload, publish and hide design assets, as for wallpapers. |

## 9. Live state and side effects

### Progress and the poster

- `recap_progress` keeps each partner's furthest card and when they first opened.
- The poster's `locked` is worked out on each request: unlocked once both partners have reached it.
- When the second partner reaches it, the server sends a `RECAP_PROGRESS` event through the existing realtime hub (`canvasSync`), so an open feed unlocks at once, and a push if the partner's app is closed.

### Pushes

| Type | When | Who |
| --- | --- | --- |
| `RECAP_READY` | Launch, or when the recap is first available to a pair | Devices on a build that supports the recap |
| `RECAP_UPDATE_NEEDED` | Same moment | Devices on older builds: "Update Mulberry to see your year" |
| `RECAP_PARTNER_OPENED` | The partner's first open | The other partner |
| `RECAP_POSTER_NUDGE` | The nudge button | The other partner |

- Builds come from `user_clients.app_build`.
- Older Android builds drop data pushes they don't recognise, so `RECAP_UPDATE_NEEDED` must be sent with a notification block that Android shows on its own.
- The Mac app gets no recap pushes.
- Launch sends are batched and spread over a few minutes.

### The midnight note

1. `PUT /note` stores the drawing in `recap_notes` with its author and recipient. A partner can replace it until delivery.
2. A scheduler runs every minute. A note is due when it's past midnight on 1 January in the recipient's current time zone (`users.time_zone`, falling back to `Asia/Kolkata`).
3. On delivery, the server writes the note to the pair's canvas as ordinary operations credited to the author, through the canvas service. Sync, push, the wallpaper and widgets then update on every platform, the Mac included, with no app code.
4. Delivery is marked in the same transaction, so a note is never delivered twice.

### Reactions

The React button calls the existing reactions endpoint. It's logged in `reaction_events` like any other reaction.

## 10. Apps

The same runtime on both platforms:

- **Apple:** models and loading in `packages/apple/Sources/ProductCore`, views in `ProductUI`, shared by iPhone and iPad. The Mac app doesn't include it.
- **Android:** one `recap` feature package.

Parts:

| Part | What it does |
| --- | --- |
| Feed | Vertical paging, progress line, close button, Share, React and Save, play when in view, "Back to your canvas" on the last card |
| Layout registry | Maps `layout@version` to a native view. Unknown layouts are skipped. |
| Layer host | Places the slot assets behind and in front of the native content, with the still frame as fallback |
| Asset loader | Prefetch, cache by hash, variant choice, cache clearing |
| Replay player | Plays segments with speed curves and changing labels, drawing with the existing canvas renderers (`CanvasDrawing` on Apple, `drawing/render` on Android) |
| Export | Story-sized images, and on-device video (AVAssetWriter, MediaCodec) from the same layers and player |
| Entry points | Canvas banner and Settings entry from bootstrap, and routing for the recap push types |
| Analytics | `mulberry_recap_*` events |

## 11. Control and testing

`recap_controls`, one row per recap:

- `enabled`: whether the recap is live for everyone eligible.
- `availableFrom`, `availableUntil`.
- `allowlistPairIds`: pairs that see it before launch, for testing and creators.

Testing before launch:

- Generate facts for a test pair with any cutoff through the admin route, then open the recap on real devices with the allowlist.
- Golden tests on the backend: fixed facts and a fixed time give a fixed document for each card.
- Turning `enabled` off hides the recap within a minute, with no release.

## 12. Storage and privacy

New tables, each tied to the pair and removed with it:

| Table | Holds |
| --- | --- |
| `recap_controls` | The switches above |
| `recap_facts` | `(recap_id, pair_id)`, facts version, facts, theme, cutoff, generated time |
| `recap_replays` | `(recap_id, pair_id, window_id)`, storage path, size, hash |
| `recap_progress` | `(recap_id, pair_id, user_id)`, furthest card, first opened |
| `recap_notes` | `(recap_id, pair_id, author_user_id)`, recipient, drawing, delivered time |
| `recap_assets` | Asset ID, kind, themed, files per theme and device class, still frame, published |

- **Unpairing and account deletion** cascade to every recap row, and also delete the pair's replay blobs from storage.
- **Replay blobs** are private and reached only through short-lived signed URLs, checked against the pair.
- **Design assets** are public and contain nothing personal.
- **No card quotes typed text.** Text elements in replays draw as they did on the canvas, but no wording is built from them.

## 13. Open items

- **Poster unlock when a partner never updates.** To decide later.
- **Lottie or Rive**, depending on the designer's tool.
- **Couple types**, their themes and thresholds.
- **Whether a delivered midnight note counts toward the streak** on 1 January.
