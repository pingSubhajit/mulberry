# Mulberry macOS Background Sync Plan

This note records the sync posture agreed during Phase 7 so it can be revisited during Phase 11 notifications, login item, and background resilience work.

## Product Model

Treat macOS surfaces like Android app states:

- Full app window open: Android foreground equivalent. Keep a WebSocket open for low-latency canvas operations, acknowledgements, flow control, and user-originated mutations.
- Quick Draw active: Android foreground equivalent. Keep a WebSocket open because the user is actively editing from the desktop overlay.
- Passive overlay visible only: Android background/live-wallpaper equivalent. Do not keep a long-running WebSocket open only to refresh ambient drawings. Use push-nudged REST recovery when APNs support exists.
- Overlay hidden and app window closed: idle. Do not hold a WebSocket. Recover when the user opens the app, enters Quick Draw, shows the overlay, or a future push/login-item path requests a recovery.

The macOS transparent overlay exists for the same product job as Android live wallpaper: showing partner drawings ambiently. It should not force the backend to scale one persistent WebSocket per passive desktop surface.

## Phase 7 Interim Behavior

Phase 7 should keep the WebSocket implementation because it is still the right foreground transport. The immediate refactor is to make WebSocket lifetime demand-driven:

- `foregroundWebSocket`: full app or Quick Draw requests live sync.
- `overlayRecovery`: passive overlay requests REST recovery/polling, not WebSocket.
- `idle`: no live socket and no background polling.

Until Phase 11 APNs is implemented, `overlayRecovery` may use a conservative periodic REST catch-up loop while the app process is running. This is an interim substitute for push nudges, not the final architecture.

Presence reporting for `MACOS_OVERLAY.canSeeLatestDrawings` stays strict. It should be true only when the overlay is visible, the local canvas has real recovered/rendered state, no blocking sync/render error is active, the known revision is applied, and the most recent overlay recovery is fresh.

## Phase 11 Target

Phase 11 should replace overlay polling with APNs-nudged recovery:

1. Register a stable macOS device instance with `platform=MACOS`, `pushProvider=APNS`, environment, app version, and token readiness.
1. On accepted canvas operations, the backend should enqueue a low-priority canvas update push for passive macOS overlay devices, analogous to Android background sync nudges.
1. The APNs payload should include `pairSessionId`, `latestRevision`, and enough metadata to decide whether the local store is stale without opening a WebSocket.
1. When the login item/app process receives the push, compare `latestRevision` with local `lastAppliedServerRevision`.
1. If stale, call REST recovery from the local revision. Fall back to snapshot plus tail when the gap is large or the server asks for resync.
1. Update the overlay render model and report `MACOS_OVERLAY.canSeeLatestDrawings=true` only after recovery succeeds.
1. Keep WebSocket reconnect scoped to full app and Quick Draw activation.

## Backend Implications

The backend should not rely on passive macOS clients being live WebSocket recipients. WebSocket broadcast remains useful for foreground clients, but push dispatch should be the reliability path for passive overlay freshness.

Useful server-side additions for Phase 11:

- platform-aware APNs token storage alongside existing FCM token support;
- deduped canvas update push queue per device/pair/revision;
- APNs payload type for macOS canvas recovery nudges;
- metrics that separate foreground WebSocket delivery from background push recovery;
- optional `latestRevision` in REST `/canvas/ops` responses so clients can mark "fresh as of server revision" even when no operations are returned.

## Manual Checks When Revisiting

- Open full app and verify a WebSocket opens.
- Close full app with overlay visible and verify the WebSocket closes.
- Confirm passive overlay still catches up through REST or APNs recovery.
- Enter Quick Draw from passive overlay and verify WebSocket reconnects.
- Exit Quick Draw and verify it returns to overlay recovery mode.
- Hide overlay and close full app, then verify no sync socket or polling loop remains active.
- Verify `MACOS_OVERLAY.canSeeLatestDrawings` does not become true after a failed recovery or stale overlay poll.
