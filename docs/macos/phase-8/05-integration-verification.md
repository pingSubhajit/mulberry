# Phase 8.5: App Integration and Verification

## Goal

Wire the Phase 8 editing engine into the real full app canvas route, remove placeholder friction, and verify the full Phase 8 behavior end to end.

## Visible App Route

For signed-in paired users, the sidebar `Canvas` route should open the real full-app canvas directly.

Do not keep a visible "Canvas home" placeholder that requires a second "Open Canvas Surface" click.

Recommended behavior:

- signed out: show/auth route;
- signed in but unpaired: show pairing state;
- paired with usable local canvas state: show full canvas editor;
- paired but recovering initial canvas state: show canvas loading/recovery state;
- offline with usable local canvas state: show full canvas editor with offline/pending status.

Pair, streak, and sync status can appear as compact chrome around the canvas. They should not block direct canvas access for paired users.

## Full App Canvas Layout

Build a functional, barebones UI aligned with mobile parity. Final visual design comes later.

Expected surface:

- large canvas-first editor;
- toolbar for undo, redo, brush, width, color/custom color, eyedropper, eraser, text, sticker, clear;
- compact sync/pending/offline state;
- immersive in-window modals for text and sticker editing;
- clear confirmation dialog.

Avoid marketing or landing-page composition. This is a working tool surface.

## Sync Integration

All local tool actions must submit operations through `CanvasSyncController.submitLocalOperation`.

Expected behavior:

- connected: queue and send immediately through existing sync flow;
- offline/backoff: queue pending operations and optimistically update canvas;
- recovery: allow edits only after local canvas state is usable;
- hard invalid state: disable tools and explain the state.

Do not bypass the existing pending operation queue.

## Phase Boundaries

Keep out of this integration slice:

- Quick Draw parity implementation;
- production sticker catalog/cache;
- reaction send/playback;
- login item and notification work;
- packaging and notarization.

However, the integration should leave seams ready for:

- Phase 9 Quick Draw using `CanvasEditing`;
- Phase 10 replacing placeholder stickers with real catalog/cache;
- Phase 11 enabling reactions in the menu/app.

## Keyboard and Input

Required keyboard behavior:

- `Cmd+Z`: undo;
- `Shift+Cmd+Z` or `Cmd+Y`: redo;
- `Cmd+Enter`: commit text/sticker editor where applicable;
- `Esc`: cancel/close active editor or transient tool state;
- `Cmd+0`: reset zoom;
- `Cmd+plus`: zoom in;
- `Cmd+minus`: zoom out;
- Space + drag: pan canvas.

Trackpad/mouse behavior:

- primary drag draws in brush mode;
- click erases topmost hit stroke in eraser mode;
- two-finger scroll or Magic Mouse scroll pans;
- pinch zooms;
- click/drag selected text/sticker moves it.

## Verification Bar

Automated checks:

- `CanvasEditingTests` pass;
- `CanvasCoreTests` pass;
- `CanvasRenderingTests` pass;
- macOS Swift package tests pass;
- `pnpm --dir apps/macos run smoke` passes.

Manual smoke:

1. Sign in or use a paired local state.
2. Open the Canvas route and verify the real editor appears directly.
3. Draw a stroke.
4. Undo and redo the stroke.
5. Erase a stroke.
6. Clear canvas and verify confirmation is required.
7. Add text with nondefault font, color, pill, alignment, scale, rotation, and width.
8. Edit and delete text.
9. Add a placeholder/dev sticker.
10. Edit sticker scale/rotation and delete it.
11. Pan and zoom the canvas.
12. Use eyedropper and verify brush/text colors update.
13. Disconnect network or force sync backoff and verify edits queue optimistically when local state is usable.
14. Confirm generated operations reach `CanvasSyncController.submitLocalOperation`.

## Documentation Updates

After implementation, update:

- `docs/macos/mulberry-macos-v1-prd.md` only if Phase 8 scope changed;
- `docs/macos/mulberry-macos-v1-architecture.md` if module boundaries changed materially;
- `apps/macos/README.md` with the current full-app canvas behavior and test commands.

## Acceptance Criteria

- Phase 8 full app canvas is the primary signed-in paired Canvas route.
- All Phase 8 tools emit Android-compatible operations.
- Offline edit queue behavior works when local state is usable.
- Text and sticker immersive editors work inside the app window.
- Reactions remain disabled/out of scope.
- Placeholder sticker behavior is clearly isolated for Phase 10 replacement.
- Tests and smoke checks pass.
