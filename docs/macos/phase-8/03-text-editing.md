# Phase 8.3: Text Editing

## Goal

Implement Android-compatible text element creation, editing, deletion, and undo/redo in the macOS full app canvas.

The interaction should preserve Android's full-screen modal editing model, adapted as an immersive full-canvas overlay inside the Mulberry app window.

## Scope

Implement:

- text tool selection;
- click empty canvas to create draft text at a normalized point;
- double-click or select existing text to edit;
- Android-style full-canvas text editor overlay;
- move/transform selected text on canvas;
- add/update/delete operations;
- undo/redo for text actions.

## Text Element Fields

Support every Android text payload field:

- `id`;
- `text`;
- `createdAt`;
- `center`;
- `rotationRad`;
- `scale`;
- `boxWidth`;
- `colorArgb`;
- `backgroundPillEnabled`;
- `font`;
- `alignment`.

Fonts:

- `POPPINS`
- `VIRGIL`
- `DM_SANS`
- `SPACE_MONO`
- `PLAYFAIR_DISPLAY`
- `BANGERS`
- `PERMANENT_MARKER`
- `KALAM`
- `OSWALD`

The macOS resources already include matching font files under `apps/macos/Resources/Fonts`.

Alignment:

- `LEFT`
- `CENTER`
- `RIGHT`

## Editor Model

Use an immersive full-canvas overlay inside the app window, not a detached inspector.

The overlay should provide:

- text input;
- Done;
- font picker;
- size/scale control;
- rotation control;
- color palette and custom color;
- alignment cycle/control;
- background pill toggle;
- delete.

Keyboard behavior:

- `Cmd+Enter` commits;
- `Esc` cancels a new draft;
- `Esc` closes an existing edit session without emitting an extra update unless fields changed and the chosen UX explicitly commits on close;
- Delete button emits delete.

Blank text behavior:

- blank new draft cancels/deletes the draft;
- blank existing text deletes the element on commit, matching Android's blank-means-delete behavior.

## Operation Emission

New text:

- create a draft locally while editing;
- on commit with nonblank text, emit `ADD_TEXT_ELEMENT`;
- if blank, emit nothing for a new draft.

Existing text:

- on commit with changed nonblank fields, emit `UPDATE_TEXT_ELEMENT`;
- if fields did not change, emit nothing;
- on blank commit or delete, emit `DELETE_TEXT_ELEMENT`.

All geometry must be normalized.

## On-Canvas Transform

Phase 8 should support desktop-friendly element manipulation:

- select text;
- drag selected text to move;
- provide handles or editor controls for resize/scale, rotation, and box width.

Trackpad two-finger transform can be added later, but is not required for Phase 8.

## Undo/Redo

Undo/redo emits compensating operations:

- undo add: emit `DELETE_TEXT_ELEMENT`;
- redo add: emit `ADD_TEXT_ELEMENT`;
- undo delete: emit `ADD_TEXT_ELEMENT`;
- redo delete: emit `DELETE_TEXT_ELEMENT`;
- undo update: emit `UPDATE_TEXT_ELEMENT` with previous element state;
- redo update: emit `UPDATE_TEXT_ELEMENT` with next element state.

Remote text operations do not enter local undo history.

## Tests

Minimum coverage:

- new text commit emits `ADD_TEXT_ELEMENT`;
- blank new text emits no add;
- existing text update emits `UPDATE_TEXT_ELEMENT`;
- unchanged edit emits no operation;
- blank existing text emits `DELETE_TEXT_ELEMENT`;
- delete emits `DELETE_TEXT_ELEMENT`;
- every text field round-trips through operation payload encoding;
- undo/redo for add, delete, and update emit correct compensating operations;
- geometry remains normalized through viewport transform.

## Acceptance Criteria

- User can create text from the full app canvas.
- User can edit all Android text fields.
- User can move and transform text.
- User can delete text.
- Text operations sync to Android-compatible payloads.
- Text undo/redo works as operation-producing history.
