# Share frames

The share feature turns what a partner drew on your lock screen into a 1080×1920 image for Instagram Stories, WhatsApp Status or anywhere else. The app draws the lock-screen content, puts a frame over it, adds a caption and a small brand line, and hands the image to the system share sheet.

Frames are server-driven catalog content, like sticker packs and wallpapers (see [server-driven-content.md](server-driven-content.md)). They are managed through the admin API and the web admin, and apps download them at runtime. Plain and Polaroid are also bundled in each app, so sharing works offline and before the catalog loads.

Scope of the first version: iOS (iPhone and iPad) and Android. The Mac comes later.

## Decisions

- Every frame shows an iPhone-style phone (or, for Polaroid, a polaroid print). Everyone sees the iPhone lock-screen style: the date above a big clock.
- The clock and date show the current time when the image is made.
- By default the screen shows the user's real lock-screen image, exactly as Mulberry composes it. The Wallpaper tool can switch to the fallback wallpaper instead.
- The caption defaults to "Look what <partner name> drew on my lock screen". With no partner name, use "my love". The user can edit it, up to 60 characters.
- Seasonal frames have date windows. During a window, the seasonal frame comes first and is preselected; otherwise Polaroid is preselected.
- The only entry point in the first version is a share button on the canvas.

## Frame assets

Each frame is one image plus one layout file.

- **Frame image:** 1080×1920, RGBA with straight (non-premultiplied) alpha. It contains the background, the phone or polaroid print, the elements in front of it, the Dynamic Island, glass glare and cast shadows. The screen is transparent. It has no lock-screen content, caption or brand line.
- **Layout:** JSON, described below.

The production sources, previews, reference renderer and validator are in `temp/share-frames/v1/` (not committed). `temp/share-frames/v1/src/render_preview.py` is the reference renderer that the apps must match, and `validate.py` is the authoring validator.

### Layout schema (layoutVersion 1)

```json
{
  "layoutVersion": 1,
  "key": "valentines",
  "canvas": { "width": 1080, "height": 1920 },
  "content": {
    "kind": "phone",
    "aspectRatio": 0.4612676056338028,
    "quad": {
      "topLeft": { "x": 195.10683, "y": 380.69175 },
      "topRight": { "x": 754.01647, "y": 321.94798 },
      "bottomRight": { "x": 881.36938, "y": 1533.62994 },
      "bottomLeft": { "x": 322.45974, "y": 1592.37371 }
    }
  },
  "caption": {
    "position": { "x": 40.0, "y": 1503.072 },
    "anchor": "bottomLeft",
    "rotationDegrees": 7,
    "maxWidth": 580,
    "maxLines": 3,
    "maxFontSize": 64,
    "minFontSize": 40,
    "lineHeight": 1.05,
    "align": "left",
    "inkColor": "#B31329",
    "backing": { "color": "#FFF4DF", "paddingX": 12, "paddingY": 7 }
  },
  "brand": {
    "position": { "x": 917, "y": 1535.5 },
    "anchor": "center",
    "markHeight": 37,
    "fontSize": 24,
    "color": "#8C1729",
    "backing": { "color": "#FFF9EB", "paddingX": 13, "paddingY": 8, "cornerRadius": 15 }
  }
}
```

- `layoutVersion`: clients skip any frame whose `layoutVersion` they don't support (only 1 exists).
- `content.kind`: `phone` or `polaroid`. It doesn't change how the frame is drawn.
- `content.aspectRatio`: always 1179/2556, the shape of the content image.
- `content.quad`: where the corners of the content image go, in frame pixels. It's a parallelogram (an affine image of the content rectangle). It already includes a few pixels of bleed, so the content always fills the transparent screen.
- `caption.anchor`: `topLeft`, `topRight`, `bottomLeft` or `bottomRight`; `brand.anchor`: `center`. Positive rotation is clockwise.
- `backing` may be `null` for the caption or the brand.
- Colours are `#RRGGBB`.

## Rendering contract

All coordinates are in the 1080×1920 output.

1. **Content image (1179×2556).**
   - **Wallpaper layer:**
     - **Real wallpaper:** the image Mulberry currently puts on this device's lock screen, background and doodles included, exactly as Mulberry composes it on this platform. Scale it to fill 1179×2556, centre-cropped.
     - **Switch off:** the fallback wallpaper (iOS asset `AuthBackground`, Android `auth_login_bg.jpg`), scaled to fill and centre-cropped. Darken it with black at 20% opacity, then draw the canvas doodles on top exactly as the lock screen draws them. Never dim the real wallpaper.
   - **Clock (drawn after the wallpaper):** white text.
     - **Date line:** `EEEE, d MMMM` (e.g. "Friday, 6 November"), Poppins Medium at 59px, centred horizontally, its baseline at 11% of the height.
     - **Time:** Poppins SemiBold, centred, its top at 13% of the height. Size it so the ink height of "7:23" is 230px: `size = round(320 × 230 / inkHeight("7:23" at 320px))`. Use the device's 12/24-hour setting with no AM/PM and no leading zero (`h:mm` or `H:mm`).
     - **Shadow:** the clock's alpha, blurred with radius 4, at 30% black, offset 3px down, drawn under the clock.
   - With the real wallpaper, the doodles are already in the wallpaper image. Draw the clock last in both cases, as the system lock screen draws its clock over everything.
2. **Map the content** into `content.quad` with an affine transform: topLeft ← (0,0), topRight ← (1179,0), bottomLeft ← (0,2556). Use high-quality filtering.
3. **Draw the frame image** over it, as straight alpha composited normally.
4. **Draw the caption** (below).
5. **Draw the brand line** (below).
6. **Export** a 1080×1920 image (PNG, or JPEG at quality 0.92 or higher) and open the system share sheet.

### Caption

- **Font:** Permanent Marker (`permanent_marker_regular.ttf`). **Ink:** `inkColor`.
- **Fitting:** wrap on spaces. Pick the largest integer font size from `maxFontSize` down to `minFontSize` at which the text fits in at most `maxLines` lines, each no wider than `maxWidth` (`maxWidth` excludes backing padding).
  - At a given size: fill the first line as far as it goes. Then split the remaining words into the fewest lines that fit, choosing the split with the smallest sum of squared unused line widths.
  - If nothing fits at `minFontSize`, use `minFontSize` and wrap greedily. The 60-character limit keeps this from happening with the shipped layouts.
- **Lines:** line advance is `fontSize × lineHeight`. Align each line's ink top using the font's ink bounds for that line.
  - With `align` `left`, lines start at the block's left padding.
  - With `align` `right`, lines end at the block's right padding.
- **Backing:** each line gets its own strip: the line's ink box plus `paddingX`/`paddingY`, with 7px rounded corners, filled with `backing.color`.
- **Block size:** width = widest line + 2×paddingX. Height = (lines − 1) × advance + tallest line's ink height + 2×paddingY.
- **Placement:** the block's `anchor` corner (unrotated, padding included) sits at `position`, and the block rotates about that point by `rotationDegrees`, clockwise for positive values.

Text measurements differ slightly between FreeType (the reference renderer) and Core Text or Android text. Match the reference renderer within a few pixels.

### Brand line

- **Content:** the Mulberry mark followed by "mulberry.my" in Poppins Medium at `fontSize`.
  - The mark is `markHeight` tall at its natural aspect ratio and tinted with `color`.
  - There's a 13px gap between the mark and the text.
- **Backing:** a rounded rectangle (`cornerRadius`) around both, with `paddingX`/`paddingY`, filled with `backing.color`.
- **Placement:** with anchor `center`, `position` is the centre of the whole padded block.

## Catalog API

Frames follow [server-driven-content.md](server-driven-content.md): stable identity, drafts, publish, hide vs delete, history and restore, and immutable published content.

- **Identity:** `(frameKey, frameVersion)`. The key is lowercase and hyphenated, e.g. `valentines`; the version is a positive integer.
- **Locked once published:** the image and the layout of a published version never change. To change art or layout, copy to a new version, edit the draft and publish it.
- **Always editable:** `title`, `sortOrder`, `availableFrom`, `availableUntil` and visibility.
- **Date windows:** `availableFrom` and `availableUntil` are optional local calendar dates (`YYYY-MM-DD`), both inclusive. A frame with no window is always available. The admin resets seasonal windows each year.

### Admin (requires `x-admin-password`)

| Method and route | Input | Result |
| --- | --- | --- |
| `GET /admin/share-frames` | None | `items`: every version, drafts included, with metadata, visibility, `contentLocked`, URLs and layout |
| `GET /admin/share-frames/:key/:version` | None | One version |
| `POST /admin/share-frames` | Multipart: `frameKey`, `frameVersion` (default 1), `title`, optional `sortOrder`, `availableFrom`, `availableUntil`, `published`; `layout` (JSON string); one `image` file | New version, a draft by default. Duplicate or previously used versions return 409 |
| `PATCH /admin/share-frames/:key/:version` | JSON: any of `title`, `sortOrder`, `availableFrom`, `availableUntil` (string or null), `published` | Updated metadata; publishing locks the content |
| `PUT /admin/share-frames/:key/:version/content` | Multipart: optional `image`, optional `layout` | Replaces a draft's image and/or layout; 409 if locked |
| `POST /admin/share-frames/:key/:version/copy` | JSON `newVersion` | Copies to an unpublished, unlocked draft |
| `DELETE /admin/share-frames/:key/:version` | JSON `confirm: true` after approval | Removes the row; keeps the assets and history; 409 `CONFIRMATION_REQUIRED` without confirmation |

History and restore work like the other catalog types (`GET /admin/share-frames/history`, `POST /admin/share-frames/history/:changeId/restore`).

Admin items have the public item's fields (below) plus `originalUrl`, `published`, `contentLocked`, `publishedAt`, `createdAt` and `updatedAt`. The `layout` may also be sent as a file part (`curl -F layout=@layout.json`).

**Validation on create and replace:**
- The image decodes, is exactly 1080×1920 and has an alpha channel.
- The layout matches the schema, with `layoutVersion` 1, `key` equal to `frameKey`, and the quad ratio within 1% of `aspectRatio`.
- Every pixel with alpha below 255 lies inside the quad.
- `availableFrom` is not after `availableUntil`.
- Errors use stable `code`s: `INVALID_FRAME_IMAGE`, `INVALID_FRAME_LAYOUT`, `INVALID_CATALOG_INPUT`, `FRAME_NOT_FOUND`, `FRAME_VERSION_EXISTS`, `FRAME_VERSION_IMMUTABLE`, `CONFIRMATION_REQUIRED`, plus the shared catalog codes.

**Storage:** keep the original PNG. Generate a full WebP with alpha (quality 90) and a 270×480 WebP thumbnail. All paths are immutable (UUID), with public URLs like wallpapers. In development, `SHARE_FRAME_LOCAL_STORAGE_DIR` keeps assets on the backend's disk, served under `/dev/share-frame-assets/`; production refuses it.

### Public (bearer token)

`GET /share-frames` returns every published frame, in or out of its window. The client filters by its own local date.

```json
{
  "items": [
    {
      "key": "christmas",
      "version": 1,
      "title": "Christmas",
      "sortOrder": 60,
      "availableFrom": "2026-12-15",
      "availableUntil": "2026-12-26",
      "frameUrl": "https://…/full.webp",
      "thumbnailUrl": "https://…/thumb.webp",
      "width": 1080,
      "height": 1920,
      "layout": { "layoutVersion": 1, "…": "…" }
    }
  ]
}
```

## Client rules

- **What to show:** published frames whose window contains today's local date, or that have no window. Skip any with an unsupported `layoutVersion`. If several versions of one key arrive, use the highest.
- **Order:**
  1. In-window frames that have a window (seasonal), by `sortOrder`.
  2. Then the rest, by `sortOrder`.
- **Preselection:**
  1. The first seasonal frame in its window.
  2. Otherwise `polaroid`.
  3. Otherwise the first frame.
- **Caching:** cache frame images by `(key, version)`. They never change.
- **Bundled frames:**
  - Ship `plain` and `polaroid`, version 1 (frame image and layout), in each app.
  - Show them when the catalog can't be loaded.
  - When the catalog loads, its versions replace them.
- **Opening without a swap:** the person must never see one frame replaced by another a moment after the sheet opens.
  - Keep the catalog's last reply on the device and open on it (arranged for today), then refresh it in the background.
  - The first time, before any reply is kept, wait up to 1.5 seconds for the catalog with the preview's spinner showing; then fall back to the bundled frames.
  - Once a frame is on screen, a later reply never replaces it while that frame is still in the reply; new frames just join the row. An unreachable catalog changes nothing.
- **Rendering:** do it on the device. Never upload the user's wallpaper.

## Share screen

- **Entry:** a share button on the canvas (SF Symbol `square.and.arrow.up` on iOS, the platform share icon on Android). It's available once paired. On phones it sits in the canvas's right-hand column, under the reactions button; on iPad it's in the navigation bar.
- **Screen:** the platform's standard full-height sheet, following each platform's own patterns rather than copying the other's. Both show the same things in the same order: the preview, growing into whatever height the rest leaves; a row of frame thumbnails with no names, no title and no background (the selected one ringed in the accent colour; screen readers still read each name); the caption; and a "My wallpaper" switch (off: the fallback wallpaper; disabled when the lock screen can't be drawn).
  - **iOS:** a `NavigationStack` and `Form` in a sheet. The navigation bar has Close on the left, the inline title "Share", and Share as the prominent confirmation action on the right. The caption and switch share one grouped section.
  - **Android:** a Material `ModalBottomSheet` with the standard drag handle and no close button (it closes with a swipe down, Back or a tap outside) and no title. The caption is an outlined text field labelled "Caption", the switch is a list row, and Share is a full-width filled button at the foot.

  Keep copy minimal: no helper text.
- **Copy (identical on every platform):**
  - Title (iOS) and button: "Share"
  - Caption field: "Caption"
  - Switch: "My wallpaper"
  - Default caption: "Look what {name} drew on my lock screen"
  - Name fallback: "my love"
- **Analytics:** through each app's existing client analytics:
  - `mulberry_share_opened`
  - `mulberry_share_frame_selected` (`frame_key`)
  - `mulberry_share_completed` (`frame_key`, `used_real_wallpaper`, `caption_edited`, plus the destination activity on iOS when available)
