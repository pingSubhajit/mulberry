# Phase 8.2: Brush, Eraser, Clear, and Stroke History

## Goal

Make the full app canvas produce real stroke operations and support Android-compatible eraser, clear, undo, and redo behavior.

This is the first slice where the user can draw meaningfully in the full app.

## Scope

Implement:

- brush tool;
- color and width controls using Phase 8.1 defaults;
- eyedropper tool;
- eraser tool;
- clear canvas confirmation;
- undo/redo for stroke and erase actions;
- Mac-native viewport controls;
- operation submission through `CanvasSyncController.submitLocalOperation`.

## Brush Behavior

Primary click-drag with the brush tool active creates a stroke.

Expected operation sequence:

1. `ADD_STROKE` with first normalized point.
2. `APPEND_POINTS` batches while dragging.
3. `FINISH_STROKE` on pointer up.

Dot taps should still create a small stroke, matching Android behavior.

Brush width is selected in rendered surface units and normalized before emitting. Rendering should continue to denormalize width with the same reference-dimension rule as Android: use the smaller active surface dimension.

## Append Batching

Use batching for drag points rather than one operation per pointer event.

The implementation can start simple, but it must preserve:

- ordered operation emission;
- force flush before `FINISH_STROKE`;
- no dropped final points;
- no duplicate client operation IDs.

## Eraser Behavior

Keep Android parity: eraser deletes whole strokes only.

Behavior:

- click/tap in eraser mode hit-tests committed strokes;
- choose the topmost hit stroke;
- emit `DELETE_STROKE`;
- remove it optimistically from local canvas state;
- record an undo action containing the deleted stroke.

No partial eraser, path eraser, or stroke splitting belongs in Phase 8.

Hit-testing:

- perform hit-testing in rendered canvas coordinates;
- tolerance is Android-equivalent `stroke.width / 2 + 16px`;
- then map result back to the committed normalized stroke ID.

## Clear Behavior

Clear canvas must show a confirmation dialog in the full app.

On confirm:

- emit `CLEAR_CANVAS`;
- clear committed strokes;
- clear committed text/sticker elements;
- clear active local/remote strokes in view state;
- clear local undo/redo history.

This mirrors Android.

## Undo/Redo Stroke History

Undo/redo must emit compensating operations, not local-only state changes.

Rules:

- Undo active unfinished stroke: emit `DELETE_STROKE`.
- Undo committed local draw: emit `DELETE_STROKE`.
- Redo draw after undo: replay as a new stroke ID with `ADD_STROKE`, `APPEND_POINTS`, and `FINISH_STROKE`.
- Undo erased stroke: replay it as a new stroke ID.
- Redo erase: emit `DELETE_STROKE`.
- Remote partner operations update the canvas but do not enter this device's undo stack.
- Undo/redo stacks are session-scoped and are not persisted across restart.

## Mac Viewport Controls

Use Mac-native controls:

- pan with two-finger trackpad scroll, Magic Mouse scroll, or Space + drag;
- zoom with pinch gesture;
- zoom with `Cmd +`, `Cmd -`, and `Cmd 0`;
- clamp zoom to `1.0...4.0`.

All drawing and erasing coordinates must convert through the viewport transform back into normalized canvas coordinates before operation emission.

## Eyedropper

Eyedropper is required in Phase 8.

Behavior:

- sample rendered canvas pixel/color from the full app surface;
- show preview while armed if practical;
- commit sampled ARGB to both brush color and text color;
- cancel cleanly on Escape or tool switch;
- disabled while text/sticker editor modal is open.

## Tests

Add or extend `CanvasEditingTests`.

Minimum coverage:

- brush emits `ADD_STROKE`, `APPEND_POINTS`, `FINISH_STROKE` in order;
- final append flush happens before finish;
- stroke IDs and client operation IDs are unique;
- eraser chooses topmost hit stroke;
- eraser emits `DELETE_STROKE`;
- clear raises a confirmation intent before emitting;
- clear emits `CLEAR_CANVAS` only on confirm;
- undo draw emits delete;
- redo draw replays with a new stroke ID;
- undo erase replays with a new stroke ID;
- remote operations do not enter local history.

## Acceptance Criteria

- User can draw in the full app canvas.
- User can erase topmost strokes.
- User can clear with confirmation.
- User can undo and redo draw/erase actions.
- Generated operations sync to the existing sync controller.
- Offline/backoff editing queues operations and updates optimistically.
