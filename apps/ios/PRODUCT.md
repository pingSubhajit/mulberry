# Product

<!-- impeccable:product-schema 1 -->

## Platform

ios

## Users

Two partners in a relationship, each with their own phone. They are apart for most of the day and want small, low-effort ways to feel close: a doodle that appears on the other's wallpaper, or a reaction tapped in a second.

## Product Purpose

Mulberry pairs exactly two people into one persistent shared drawing canvas. Whatever either draws becomes part of the other's wallpaper, so the connection is ambient: it is there every time they look at their phone. Reactions (love, hug, kiss, smile, laugh, sparkles) are quick "thinking of you" taps between drawings. Success is the pair reaching for each other often and feeling it land.

## Positioning

Ambient affection, not collaboration or messaging. The canvas lives on the partner's wallpaper rather than inside a chat thread; there is no feed, no inbox, and no group.

## Operating Context

- Android (live wallpaper), iPhone and iPad (wallpaper set by a Shortcuts automation), and a macOS app in progress.
- On iOS, reactions play over the canvas when Mulberry opens, and appear in the Dynamic Island and on the Lock Screen as a Live Activity while they are fresh.
- Glanced at between other things: a lock-screen look, a Dynamic Island pop, a few seconds of drawing.

## Capabilities and Constraints

- Six reaction types, shared with Android: HEART, HUG, KISS, SMILE, LAUGH, SPARKLE. The server coalesces a partner's reactions into one batch with counts per type, played on one of the recipient's devices.
- Live Activity: started locally on the current free Personal Team signing; push-to-start from the backend needs a paid Apple Developer team. It ends only when Mulberry opens and the canvas plays the reactions; the backend never ends it.
- Live Activities animate only when their content changes (about two seconds); no looping animation.
- Partner profile: display name and an optional photo URL from the bootstrap state.

## Brand Commitments

- Name: Mulberry. Mark: the red mulberry (BrandMark, #B31329 Mulberry red).
- Mascot: Squee, the red squirrel with a pencil.
- Handwritten voice in canvas prompts (Virgil), warm and brief.
- Minimal UI copy: no redundant helper text; icon buttons over text verbs.

## Evidence on Hand

- Reaction artwork: six animated GIFs (`Resources/Assets.xcassets/Reaction*.dataset`).
- Brand mark SVG, Squee illustrations, Virgil and Poppins fonts in `Resources`.

## Product Principles

1. Ambient over attention-grabbing: arrive warmly, never like an alert.
2. Two people only: every surface is about "you and them", never a crowd.
3. Low effort, high feeling: a reaction or reply should take one tap.
4. Calm density: a small space holds one feeling, not a dashboard.
