# Product

<!-- impeccable:product-schema 1 -->

## Platform

ios

## Users

Two partners in a relationship, each with their own devices. They are apart for most of the day and want small, low-effort ways to feel close: a doodle that appears on the other's wallpaper or desktop, or a reaction sent in a second. On the Mac, one of them is usually at work at a desk, with the shared canvas sitting on the desktop between other tasks.

## Product Purpose

Mulberry pairs exactly two people into one persistent shared drawing canvas. Whatever either draws becomes part of the other's wallpaper, or the desktop overlay on a Mac, so the connection is ambient: it is there every time they look at their screen. Reactions (love, hug, kiss, smile, laugh, sparkles) are quick "thinking of you" taps between drawings. Success is the pair reaching for each other often and feeling it land.

## Positioning

Ambient affection, not collaboration or messaging. The canvas lives on the partner's wallpaper and desktop rather than inside a chat thread; there is no feed, no inbox, and no group.

## Operating Context

- This record is the macOS app: a native app on macOS 26 and later, using Apple's design language (Liquid Glass, native toolbars and controls). Platform is recorded as `ios` for Apple-native guidance; the input is pointer, trackpad and keyboard, not touch.
- It lives in the menu bar. A transparent, click-through desktop overlay shows the shared canvas on a chosen display; Quick Draw (Command-Control-M) brings the overlay forward for drawing; the main window is a portrait panel holding the canvas.
- The same product ships on Android (live wallpaper), iPhone and iPad (wallpaper set by a Shortcuts automation). The Mac mirrors the iPhone and iPad vocabulary: the same tools, toolbar artwork, palette, streak bolt and reactions, adapted to pointer and keyboard.
- Glanced at between other things: a look at the desktop, a few seconds of drawing, a reaction sent from the window or menu bar.

## Capabilities and Constraints

- The canvas, sync, offline outbox, backend client, canvas rendering and sticker store are shared with the iPhone and iPad app in `packages/apple` (ProductCore, CanvasDrawing, StickerAssets). The Mac changes how people draw and edit, never what the canvas contains or how it renders.
- The canvas is 9:20, shared with the phones; text, stickers and strokes are stored normalised and must look the same on every device.
- Canvas elements: strokes (round or dry brush), text (nine fonts, size, rotation, colour, alignment, background pill, wrap width) and stickers from published packs.
- Six reaction types shared with Android: HEART, HUG, KISS, SMILE, LAUGH, SPARKLE.
- Undo and redo only touch this device's own edits, never a partner's.
- Push notifications and a signed, notarised build wait for the paid Apple Developer account.

## Brand Commitments

- Name: Mulberry. Mark: the red mulberry (#B31329 Mulberry red).
- Mascot: Squee, the red squirrel with a pencil.
- Handwritten voice in canvas prompts (Virgil), warm and brief.
- Minimal UI copy: no redundant helper text; icon buttons over text verbs.

## Evidence on Hand

- Toolbar artwork from the iOS app in `Resources/Toolbar` (brush, text, stickers, eraser, eyedropper, clear, streak bolt).
- Canvas fonts in `Resources/Fonts`.

## Product Principles

1. Ambient over attention-grabbing: arrive warmly, never like an alert.
2. Two people only: every surface is about "you and them", never a crowd.
3. Low effort, high feeling: a reaction or reply should take one click.
4. Calm density: a small space holds one feeling, not a dashboard.
5. Native first on the Mac: pointer and keyboard conventions people already know, in the iPhone's visual vocabulary.
