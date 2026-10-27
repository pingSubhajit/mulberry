# Canvas and wallpaper geometry

The iOS wallpaper renderer used normalized stroke coordinates against the full
screen, while touch input and the app renderer used the shorter drawing view.
On the tested iPhone, those areas were 402 × 874 and 402 × 590 points. The old
export therefore stretched the vertical stroke positions by about 48%.

The wallpaper now fits and centers the measured drawing area inside the screen,
using a single scale for both axes. This follows Android's
`calculateSnapshotPlacement` in `DefaultCanvasSnapshotRenderer.kt`. Background
photos still fill the screen. Drawing data and touch coordinates are unchanged.

`DoodleTouchView` saves its layout through `CanvasViewportStore`, scoped to the
user and pairing. A background Shortcut reads that size from disk. The wallpaper
content signature includes the size, so a layout change invalidates the prior
receipt. An old receipt cannot acknowledge a newer layout. With no saved size,
placement falls back to the screen, matching Android's missing-viewport rule.

Brush paths render in their original point space before uniform scaling. This
preserves dry-brush seeds, bristle counts and minimum widths as well as shape.

## Verification on 2026-10-27

The new square regression failed against the old renderer before the fix.
After the fix, 37 canvas/export image checks pass at 2× and 3× for round and dry
brushes, thin through thick widths, full-screen and shorter drawing areas, and
PNG round trips. Full-screen comparisons remain byte-exact. Translated canvas
comparisons permit at most two 8-bit levels at antialiased edges, with total
channel error below 0.001%. Android's 774 × 1134 authored-canvas fixture produces
the same centered 768 × 1125.2092 placement, with y offset 281.3954.

All 30 offline checks pass, including actual view layout persistence, pairing
isolation, background export and stale-layout receipt handling. The signed
device build passed and was installed on the physical iPhone 17 with iOS 27.0.1.

With the local backend stopped, a square was drawn through the app and applied
by the existing notification-triggered Shortcut to the guitar wallpaper pair.
It retained its shape on the actual Home Screen. Captures and measured image
geometry are in `evidence/geometry/`. Test marks were undone after verification.

This matches Android's local canvas-to-wallpaper placement policy. The existing
sync format carries normalized points, not a shared authored aspect ratio, so
this does not establish identical layouts between phones with different canvas
dimensions. No Android, backend or wire-format changes were made.
