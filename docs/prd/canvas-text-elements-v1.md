## Problem Statement

Couples currently use the shared drawing canvas to create doodles using freehand strokes, but they cannot place text on the canvas. This prevents them from adding captions, labels, short notes, or “story-style” overlays that are a core part of modern expressive canvas experiences. The absence of text also limits future expansion toward stickers and other overlay elements.

## Solution

Add a new Text tool to the canvas that lets users create, select, edit, move, resize, rotate, and delete text overlays with an Instagram Story editor feel. Text elements must be:

- Shared between paired users via the existing canvas sync system
- Included in the server canvas snapshot and the mobile wallpaper snapshot rendering pipeline
- Undoable/redoable for all text mutations
- Always rendered above strokes in v1

Text editing should use a full-screen overlay editor (dimmed background, focused editing, “Done” action), and transforms should commit on gesture end (local is smooth; remote sees the final state).

## User Stories

1. As a paired user, I want to add text to the shared canvas, so that I can caption what I drew.
2. As a paired user, I want the other person to see the text I add, so that the canvas feels truly shared.
3. As a paired user, I want text to appear in the wallpaper snapshot, so that my lock screen reflects the full canvas.
4. As a paired user, I want to tap an empty area to create text, so that adding feels fast and natural.
5. As a paired user, I want new text to immediately open the keyboard editor, so that I can start typing right away.
6. As a paired user, I want to select an existing text by tapping it, so that I can move or style it.
7. As a paired user, I want to edit an existing text (via an explicit action like double-tap or an edit button), so that I can fix typos without accidental keyboard popups.
8. As a paired user, I want text editing to happen in a focused overlay, so that typing doesn’t fight the canvas layout/gestures.
9. As a paired user, I want to drag a selected text to reposition it, so that I can place it precisely.
10. As a paired user, I want to pinch to resize text, so that I can emphasize or de-emphasize captions.
11. As a paired user, I want to rotate text, so that I can match the playful story-editor aesthetic.
12. As a paired user, I want transform changes to feel buttery-smooth locally, so that interactions feel polished.
13. As a paired user, I want transform updates to sync only when I finish the gesture, so that syncing is stable and not noisy.
14. As a paired user, I want the remote user to see the final placed text, so that their view is consistent.
15. As a paired user, I want to delete a text element, so that I can remove mistakes.
16. As a paired user, I want a confirmation-free delete for a selected text (with undo), so that I can move quickly.
17. As a paired user, I want to change the text color using the existing palette, so that styling is consistent with the app.
18. As a paired user, I want to toggle a “background pill” behind text, so that captions remain readable over drawings.
19. As a paired user, I want the background pill to use my selected color, so that styling feels cohesive.
20. As a paired user, I want the text color to automatically invert when the background pill is enabled, so that it stays readable.
21. As a paired user, I want to choose between a small set of fonts, so that text can feel playful or clean.
22. As a paired user, I want at least a clean font and a handwritten font, so that I can match the mood of the doodle.
23. As a paired user, I want text alignment controls (left/center/right), so that captions can be composed cleanly.
24. As a paired user, I want center alignment to be the default, so that it matches story editors.
25. As a paired user, I want multi-line text, so that I can write short messages.
26. As a paired user, I want text to auto-wrap within a text box width, so that it formats nicely without manual newlines.
27. As a paired user, I want line breaks to remain stable as I resize/scale, so that my composition doesn’t reflow unexpectedly.
28. As a paired user, I want text to always render above strokes, so that it behaves like an overlay.
29. As a paired user, I want undo to revert any text change (content/style/transform), so that experimentation is safe.
30. As a paired user, I want redo to restore text changes, so that I can move forward confidently.
31. As a paired user, I want undo/redo to work even if I just finished a transform, so that the tool feels responsive.
32. As a paired user, I want adding/editing text to mark the canvas as changed, so that snapshots refresh correctly.
33. As a paired user, I want text operations to be resilient to retries/duplication, so that flaky networks don’t corrupt the canvas.
34. As a paired user, I want conflicts (both users editing at once) to resolve deterministically, so that the canvas doesn’t diverge.
35. As a paired user, I want text rendering to be consistent between live canvas and snapshot rendering, so that what I see is what gets saved.
36. As a paired user, I want the Text tool to be discoverable in the same control tray as other tools, so that it’s easy to learn.
37. As a paired user, I want to switch back to draw/erase quickly after adding text, so that the flow stays fast.
38. As a paired user, I want text selection visuals (bounds/handles) to be clear, so that I understand what I’m manipulating.
39. As a paired user, I want accessibility-friendly controls (tap targets, labels), so that text editing is usable for more people.
40. As a paired user, I want the foundation to support stickers later, so that future “story editor” features build naturally on this.

## Implementation Decisions

- Add a new canvas tool: `TEXT`.
- Introduce a first-class canvas element type for text (distinct from strokes) that is:
  - Stored in the server snapshot
  - Persisted locally on device
  - Replayed/rendered in the live canvas UI and in wallpaper snapshot rendering
- Extend the canvas operation vocabulary to include text element operations:
  - Add text element (committed when created)
  - Update text element (committed on transform end and on style changes)
  - Delete text element
- Keep operations commit-on-release for transforms and final-only for remote updates.
- Text is always rendered above strokes in v1 (no layering UI).
- Editing interaction model:
  - Text tool selected from the existing control tray
  - Tap empty canvas creates a new text element and opens an Instagram-style full-screen editor overlay
  - Tap an existing element selects it
  - Double-tap or an explicit “Edit” action opens the editor overlay for that element
- Transform interaction model:
  - Selected text supports move + pinch-resize + rotate in v1
  - Stable line breaks: resizing/transform scales the rendered block instead of reflowing lines
- Text layout model:
  - Multiline with auto-wrapping inside a stored max-width box
  - Manual newlines are supported
- Text styling model (v1 bundle):
  - Color selection (reuse existing palette source)
  - Background pill toggle
    - When enabled, background uses the selected color and text color auto-inverts for contrast
  - Font choice limited to:
    - Poppins Regular
    - Virgil Regular
  - Alignment per element: left/center/right, default center
- Undo/redo:
  - All text mutations are undoable/redoable: add, delete, edit content, change style, and transform changes
  - History should treat “gesture sessions” as single undo steps (e.g., one drag or transform sequence)
- Backend snapshot:
  - Extend the durable snapshot JSON schema to include text elements alongside strokes
  - Materialize text operations into the snapshot on add/update/delete (not only on a “finish” event)
  - Maintain existing dedupe and monotonic revision guarantees for the new operation types
- Forward compatibility:
  - Text element schema and operations should be designed to generalize cleanly to stickers later (shared transform + z-order assumptions, element identity, and style payload conventions)

## Testing Decisions

- Good tests validate externally observable behavior:
  - Given a sequence of accepted operations, the server snapshot includes the correct strokes and text elements
  - Given local UI events, the canvas state and emitted operations match the intended commit-on-release behavior
  - Given undo/redo inputs, state transitions are correct and deterministic
- Mobile tests:
  - Canvas runtime tests that validate:
    - Creating text emits the correct operation and updates render state
    - Transform gesture commit produces a single update operation
    - Undo/redo reverts and reapplies text changes correctly
  - Persistence tests that validate local storage and reload of text elements and correct canvas “dirty” behavior for snapshots
- Backend tests:
  - Operation acceptance + dedupe tests for the new text operation types
  - Snapshot materialization tests for add/update/delete text operations and conflict/determinism scenarios
- Prior art:
  - Use the existing canvas runtime tests as the model for deterministic event → state → outbound operation testing
  - Use existing backend canvas operation tests for dedupe/monotonic revision and snapshot updates

## Out of Scope

- Stickers (beyond ensuring the design generalizes)
- Freeform layering/reordering of elements (texts always on top in v1)
- Rich typography beyond v1 bundle (font weights, more fonts, shadows/outlines, per-letter styling)
- Live remote preview of in-progress transforms (remote sees final committed state only)
- Advanced text templates (curved text, gradients, animated text)

## Further Notes

- The UI target is explicitly “Instagram Story editor feel”; decisions above prioritize predictable gestures, focused editing, and minimal accidental keyboard activations.
- Snapshot rendering must include text elements to avoid “missing content” between live canvas and wallpaper output.
