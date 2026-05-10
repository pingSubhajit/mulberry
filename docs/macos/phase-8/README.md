# Mulberry macOS Phase 8 Plan

Phase 8 implements the full-app canvas tools for Mulberry on macOS. It is the phase where the main app window stops being a placeholder and becomes a real editing surface that can produce the same v1 canvas operations as Android.

This phase does not implement Quick Draw editing parity, the production sticker asset pipeline, or reactions. Those remain Phase 9, Phase 10, and Phase 11 work respectively.

## Source Documents

- [macOS v1 PRD](../mulberry-macos-v1-prd.md)
- [macOS v1 Architecture](../mulberry-macos-v1-architecture.md)
- Android reference surfaces:
  - `apps/mobile/app/src/main/java/com/subhajit/mulberry/home/CanvasHomeScreen.kt`
  - `apps/mobile/app/src/main/java/com/subhajit/mulberry/home/DrawingCanvas.kt`
  - `apps/mobile/app/src/main/java/com/subhajit/mulberry/home/CanvasTextOverlay.kt`
  - `apps/mobile/app/src/main/java/com/subhajit/mulberry/home/StickerEditorOverlay.kt`
  - `apps/mobile/app/src/main/java/com/subhajit/mulberry/canvas/CanvasRuntime.kt`

## Agreed Product Decisions

Phase 8 includes the Android eyedropper tool. It is part of canvas parity, not a later enhancement.

Sticker work in Phase 8 is limited to operation-compatible placeholder/dev selection and editing. The real sticker catalog client, signed URL loading, disk cache, version invalidation, and offline sticker asset behavior remain Phase 10.

Undo and redo must mirror Android's operation-producing history model. Undo/redo is not local-only view state.

Viewport controls should be Mac-native while preserving Android's normalized coordinate model. Pointer input, hit-testing, and emitted payloads must convert through the viewport transform back into normalized canvas coordinates.

Text and sticker editing should preserve Android's full-screen modal feel, interpreted on macOS as an immersive full-canvas overlay inside the Mulberry app window, not as a macOS fullscreen Space.

The eraser remains stroke-delete only. It emits `DELETE_STROKE` for the topmost hit stroke. No partial eraser or stroke splitting belongs in Phase 8.

The editing/tool runtime should live in a reusable `CanvasEditing` module or an equivalent clearly separated layer, not inside `MainWindowView`. Phase 9 Quick Draw must reuse this canonical tool model.

Phase 8 verification should focus on operation emission, coordinate conversion, undo/redo behavior, and smoke testing. Full UI automation can wait until the surface stabilizes.

Editing should be allowed offline when the user is authenticated, paired, and has usable local canvas state. Operations should queue in the local outbox and optimistically update the canvas.

Reactions are out of Phase 8. Keep existing disabled placeholders if useful, but do not implement reaction rail, send, playback, or notification flows here.

Use Android's exact drawing defaults and palette.

Persist tool metadata across restart, matching Android, but do not persist active drafts or undo/redo stacks.

The signed-in paired user's visible Canvas route should open the real full-app canvas directly, without a second "Open Canvas Surface" click.

## Phase 8 Split

Implement Phase 8 as five mergeable slices:

1. [Canvas Editing Foundation](./01-canvas-editing-foundation.md)
2. [Brush, Eraser, Clear, and Stroke History](./02-brush-eraser-clear.md)
3. [Text Editing](./03-text-editing.md)
4. [Sticker Editing](./04-sticker-editing.md)
5. [App Integration and Verification](./05-integration-verification.md)

Each slice should be independently reviewable and tested. Later slices may depend on earlier slices, but they should avoid mixing unrelated concerns.

## Non-Goals For Phase 8

- No Quick Draw feature parity beyond ensuring the canonical editing engine is reusable in Phase 9.
- No production sticker asset cache, signed URL loading, or catalog invalidation.
- No reaction rail, reaction send, incoming reaction playback, or reaction notifications.
- No new canvas operation types.
- No partial eraser.
- No App Store, DMG, login item, APNs, or packaging work.
- No broad visual redesign beyond a functional full-app editing surface.

## Overall Acceptance Criteria

- A paired user can open the Canvas route and edit directly in the full app.
- The full app can produce Android-compatible operations for brush, eraser, clear, undo, redo, text, sticker, and element edits.
- Generated operation payloads sync through `CanvasSyncController.submitLocalOperation`.
- Offline edits queue locally and optimistically update the canvas when local state is usable.
- Coordinates and widths are normalized correctly.
- Android defaults and palette are preserved.
- Phase 8 tests and macOS smoke checks pass.
