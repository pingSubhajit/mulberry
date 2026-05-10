# Phase 8.1: Canvas Editing Foundation

## Goal

Create the reusable editing layer that all Phase 8 tools use, and that Phase 9 Quick Draw can reuse without duplicating tool logic.

This slice should not build every visible tool. It should establish the domain model, defaults, operation factories, viewport math, metadata persistence, and test harness needed by later slices.

## Recommended Module Boundary

Prefer a new Swift package target named `CanvasEditing`.

If the project owner chooses not to add a target, use a clearly separated `Sources/CanvasEditing/` folder and keep the same dependency direction. Do not bury this logic in `AppShell` or `MainWindowView`.

Recommended dependencies:

- `CanvasEditing` depends on `CanvasCore`.
- It may depend on `Persistence` if metadata persistence is implemented directly there.
- It should not depend on `AppShell`, `Overlay`, `QuickDraw`, `Networking`, or concrete WebSocket transport.

## Responsibilities

`CanvasEditing` owns:

- selected tool;
- last non-none tool;
- brush color;
- text color;
- brush width;
- Android palette/defaults;
- eyedropper armed/preview/commit state;
- selected element;
- active editor session state;
- viewport transform;
- pointer-to-canvas coordinate conversion;
- operation factory helpers;
- local history model shape;
- clear confirmation intent;
- disabled/editability state derivation.

`CanvasEditing` does not own:

- WebSocket connection;
- REST recovery;
- accepted operation persistence;
- server revision ordering;
- AppKit overlay windowing;
- final sticker catalog/cache pipeline;
- reaction send/playback.

## Tool Defaults

Use Android values exactly:

```swift
defaultColorArgb = 0xFFB31329
defaultBrushWidthPx = 10
minBrushWidthPx = 4
maxBrushWidthPx = 42
strokeHitTolerancePx = 16
```

Palette order:

```text
0xFFB31329
0xFFFF6A2A
0xFFFFE66D
0xFF2457D6
0xFF5BB7E8
0xFF006B4F
0xFF567A3A
0xFF80D8B0
0xFFE85072
0xFFA78BFA
0xFF3B2F8F
0xFFF5B83D
0xFF141414
0xFFF7F4EF
0xFF3A3A3A
0xFF7A4A32
0xFFB56A43
0xFFD8A0A8
0xFF00A53C
```

The selected color behaves like Android:

- text tool uses selected text color;
- other tools use selected brush color;
- eyedropper commit writes both brush color and text color.

## Persistent Metadata

Persist across restart:

- selected brush color;
- selected text color;
- selected brush width;
- selected tool;
- last non-none tool;
- last known canvas viewport size, if useful for diagnostics and width normalization.

Do not persist:

- active in-progress stroke;
- open text editor draft;
- open sticker editor draft;
- undo/redo stacks.

This should live in SQLite/persistence, not only SwiftUI `@State`, because Quick Draw needs the same state in Phase 9.

## Viewport Transform

Model viewport transform explicitly:

- scale, default `1.0`;
- offset in rendered surface points/pixels, default zero;
- min scale `1.0`;
- max scale `4.0`.

All emitted geometry must be normalized to the canonical canvas coordinate system. Hit-testing may operate in rendered coordinates, but the operation payloads must store normalized values.

## Editing Eligibility

Editing is enabled when:

- user is authenticated;
- user is paired;
- a pair session ID exists;
- a usable local canvas state exists.

Editing is allowed while offline or in reconnect backoff. Operations should queue through the existing pending operation path and optimistically update the canvas.

Editing is disabled when:

- signed out;
- unpaired;
- bootstrap has no pair session;
- recovery has not yet produced a usable local canvas state;
- local state is in a hard error where emitting new operations could corrupt ordering.

## Operation Factory

Add helpers for local client operations so every tool emits consistent metadata:

- lowercase UUID client operation ID;
- current ISO timestamp;
- current local date;
- stroke/element target ID where required;
- correct `CanvasOperationType`;
- correct `CanvasOperationPayload`.

Do not introduce new operation types.

## Tests

Create `CanvasEditingTests`.

Minimum coverage:

- Android default values and palette order;
- selected color behavior for brush vs text;
- eyedropper commit updates brush and text color;
- viewport coordinate normalization and denormalization;
- brush width normalization based on active surface size;
- editability state for signed out, unpaired, recovering, offline, connected;
- persisted metadata round trip, if persistence is included in this slice.

## Acceptance Criteria

- The package/module compiles.
- Existing macOS tests still pass.
- New editing foundation tests pass.
- No visible full app tool UI is required yet.
