# Phase 8.4: Sticker Editing

## Goal

Implement sticker element insertion, editing, deletion, and undo/redo in the macOS full app canvas while keeping the production sticker asset pipeline in Phase 10.

## Scope

Implement:

- sticker tool selection;
- local/dev placeholder sticker picker;
- Android-style full-canvas sticker editor overlay;
- sticker selection and movement;
- scale and rotation controls;
- add/update/delete operations;
- undo/redo for sticker actions.

Do not implement the Phase 10 sticker pipeline in this slice.

## Phase Boundary

Phase 8 includes:

- operation-compatible sticker insertion;
- placeholder/dev sticker choices;
- fallback rendering through the existing renderer;
- editing existing sticker elements from server state;
- generating Android-compatible sticker element payloads.

Phase 10 includes:

- sticker pack catalog client;
- signed URL loading;
- thumbnail/full asset download;
- disk cache;
- version-aware invalidation;
- offline sticker rendering once cached;
- production failure/loading states for sticker assets.

## Sticker Element Fields

Support every Android sticker payload field:

- `id`;
- `createdAt`;
- `center`;
- `rotationRad`;
- `scale`;
- `packKey`;
- `packVersion`;
- `stickerId`.

## Editor Model

Use the same immersive full-canvas overlay model as Android.

The overlay should provide:

- centered sticker preview or placeholder;
- Done;
- picker panel using local/dev choices;
- size/scale control;
- rotation control;
- delete.

New sticker flow:

- choose sticker tool;
- click empty canvas to place a draft sticker at a normalized point;
- open sticker editor overlay;
- Done commits `ADD_STICKER_ELEMENT`;
- cancel of a new draft emits no operation.

Existing sticker flow:

- select/double-click existing sticker;
- open editor overlay;
- changed fields commit `UPDATE_STICKER_ELEMENT`;
- delete emits `DELETE_STICKER_ELEMENT`.

## Placeholder Picker

Until Phase 10, provide a small local/dev picker that produces stable identifiers.

Requirements:

- stable `packKey`;
- positive `packVersion`;
- stable `stickerId`;
- visible placeholder or fallback rendering if no image exists;
- no reliance on live signed URLs.

The exact placeholder IDs can be simple as long as they are deterministic and accepted by the operation model.

## On-Canvas Transform

Phase 8 should support:

- select sticker;
- drag selected sticker to move;
- editor controls or handles for scale and rotation.

Use Android scale bounds:

- minimum `0.08`;
- maximum `1.6`.

## Undo/Redo

Undo/redo emits compensating operations:

- undo add: emit `DELETE_STICKER_ELEMENT`;
- redo add: emit `ADD_STICKER_ELEMENT`;
- undo delete: emit `ADD_STICKER_ELEMENT`;
- redo delete: emit `DELETE_STICKER_ELEMENT`;
- undo update: emit `UPDATE_STICKER_ELEMENT` with previous element state;
- redo update: emit `UPDATE_STICKER_ELEMENT` with next element state.

Remote sticker operations do not enter local undo history.

## Tests

Minimum coverage:

- new sticker commit emits `ADD_STICKER_ELEMENT`;
- canceled new sticker emits no operation;
- existing sticker update emits `UPDATE_STICKER_ELEMENT`;
- unchanged edit emits no operation;
- delete emits `DELETE_STICKER_ELEMENT`;
- every sticker field round-trips through operation payload encoding;
- undo/redo for add, delete, and update emit correct compensating operations;
- scale is clamped to `0.08...1.6`;
- placeholder picker produces stable identifiers.

## Acceptance Criteria

- User can insert a placeholder/dev sticker from the full app canvas.
- User can edit sticker scale and rotation.
- User can move and delete stickers.
- Sticker operations are Android-compatible.
- Missing sticker assets render via fallback until Phase 10.
- Sticker undo/redo works as operation-producing history.
