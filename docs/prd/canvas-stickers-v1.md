## Problem Statement

Mulberry’s shared drawing canvas has expanded from stroke-only doodles to include text elements, which establishes a foundation for richer “story editor” overlays. The next step is stickers: curated sticker packs that users can browse, preview, and place onto the shared canvas.

However, sticker assets cannot ship inside the app bundle, and the product roadmap includes future monetization (paid packs and Mulberry Pro subscription) with pair-scoped sharing. This requires an architecture that:

- Delivers sticker packs remotely (catalog + assets)
- Works with the existing canvas sync + durable snapshot model
- Preserves offline/low-network usability with caching and placeholders
- Supports future paywalls without breaking existing canvases or sync history
- Keeps entitlements correct across pairing, unpairing, and re-pairing

## Solution

Introduce a remote “Sticker Packs” system and a new Sticker overlay element type on the canvas, using the same mental model as text elements (tap-to-place, transform-on-canvas, commit-on-gesture-end, undo/redo, shared via sync).

Sticker packs are served from the backend via a catalog API and managed via admin endpoints. Sticker assets are delivered via access-controlled downloads (signed/expiring URLs or an authenticated proxy), with separate thumbnail and full-resolution variants.

On the canvas, stickers are represented by stable identifiers (pack id + pack version + sticker id) rather than URLs, ensuring that durable canvas history can be replayed indefinitely even when URLs expire. Overlays (text + stickers) share a single z-order stack and are represented as a unified “elements” list in the durable canvas snapshot schema.

V1 ships with a small set of free packs (globally unlocked), but the architecture includes provisions for future locked/paid packs and Mulberry Pro subscription access.

## User Stories

1. As a paired user, I want to browse sticker packs, so that I can find stickers to decorate our canvas.
2. As a paired user, I want sticker packs to be ordered and curated (featured), so that I discover the best packs first.
3. As a paired user, I want to see locked packs in the list (even if I can’t use them yet), so that I know what exists.
4. As a paired user, I want to open a pack and preview a grid of sticker thumbnails, so that I understand what I’m getting before unlocking.
5. As a paired user, I want free packs to appear as unlocked by default in v1, so that I can start using stickers immediately.
6. As a paired user, I want stickers to download on demand, so that the app doesn’t require huge upfront downloads.
7. As a paired user, I want a clear placeholder state when a sticker asset is not downloaded yet, so that the canvas remains usable while it loads.
8. As a paired user, I want sticker downloads to be resilient to flaky networks, so that I don’t end up with a permanently broken pack.
9. As a paired user, I want downloaded stickers to be cached for offline use, so that I can place stickers without network once cached.
10. As a paired user, I want to tap a sticker to place it on the canvas, so that adding stickers is fast and fun.
11. As a paired user, I want stickers to be moveable, scalable, and rotatable on the canvas, so that I can compose them like a story editor.
12. As a paired user, I want transforms to feel smooth locally, so that interactions feel polished.
13. As a paired user, I want transform changes to sync only when I finish the gesture, so that sync isn’t noisy or jittery.
14. As a paired user, I want my partner to see the sticker I placed, so that the canvas feels truly shared.
15. As a paired user, I want to delete a sticker from the canvas, so that I can remove mistakes.
16. As a paired user, I want undo/redo to work for sticker placement, deletion, and transforms, so that experimentation is safe.
17. As a paired user, I want stickers and text to share a consistent layering model, so that I can stack overlays predictably.
18. As a paired user, I want “last touched comes to front” behavior in v1, so that I can naturally bring an overlay above others without a layer panel.
19. As a paired user, I want sticker elements to be included in wallpaper snapshot rendering, so that the lock screen reflects the full canvas.
20. As a paired user, I want wallpaper snapshot rendering to avoid blocking on network, so that my wallpaper doesn’t stall if a sticker isn’t downloaded yet.
21. As a paired user, I want older app versions that don’t support stickers to fail gracefully, so that we can roll out stickers without breaking sync.
22. As a paired user, I want sticker packs to remain renderable even if they’re unpublished later, so that old canvases don’t break.
23. As a paired user, I want packs to be immutable/versioned, so that sticker art updates don’t retroactively change my past canvases.
24. As a paired user, I want sticker pack metadata to be English-only in v1, so that we can ship quickly.
25. As a paired user, I want a curated, app-safe set of packs (no user uploads) in v1, so that content is consistent and moderation is not required.
26. As a future paying user, I want to unlock a pack, so that I can use premium stickers.
27. As a future paying user, I want my purchase to be tied to my account, so that I keep it if I disconnect and pair with someone else.
28. As a paired user, I want access to be the union of both partners’ entitlements while paired, so that either partner’s packs are usable in the shared canvas.
29. As a future paying user, I want refunds/entitlement revocations to prevent new use but not break already-placed stickers, so that my canvas history remains stable.
30. As an admin, I want to upload and publish/unpublish packs and stickers, so that we can manage the catalog without app releases.
31. As an admin, I want to change ordering/featured configuration, so that we can curate seasonal or promotional content.
32. As a security-conscious user, I want paid sticker assets to be access-controlled, so that casual link-sharing doesn’t unlock content.

## Implementation Decisions

- **Catalog + delivery architecture**
  - Reuse the existing “remote catalog + object storage + mobile download/cache” pattern already established for wallpapers, but with sticker-specific data models and endpoints.
  - Sticker assets are not bundled in the APK. The backend serves pack metadata and mediates asset access.
  - Packs are **visible-but-locked** (future), with a pack detail page that includes a **thumbnail grid preview**.
  - V1 includes only **free packs**, treated as **globally unlocked** (no per-user entitlement record required for free access), but the API and schema include room for future lock states.
  - Sticker packs are **immutable and versioned**: updates publish a new version rather than mutating an existing pack version. Canvas references include the version so past canvases remain stable.
  - Each sticker has at least two asset variants: **thumbnail** (for grids) and **full-res** (for on-canvas rendering).
  - Pack metadata is **English-only** in v1; schema should allow future localization without requiring it.

- **Access control and future paywalls**
  - Sticker asset downloads are **access-controlled** (signed/expiring URLs or authenticated download proxy) to support future paid packs.
  - Canvas history stores **stable identifiers** for stickers (pack id + pack version + sticker id), never a raw URL, so durable replay does not break when URLs expire.
  - Entitlements are **owned by the user account**, not the pair session, so they survive pairing disconnects.
  - While paired, sticker access is defined as the **union of both users’ entitlements**.
  - Future monetization model:
    - **Per-pack purchases** (later) and a **Mulberry Pro subscription** (later) that unlocks all packs.
  - **Refund/revocation behavior**:
    - Revocation blocks **new placements/downloads** where applicable, but **already-placed stickers remain renderable** (“grandfather rendering”).
  - **Unpublish/removal behavior**:
    - Unpublished packs should be hidden from discovery, but assets referenced by existing canvases remain retrievable (subject to entitlement rules where applicable).

- **Canvas model and sync**
  - Stickers are an overlay element type, analogous to text elements:
    - Place → transform (move/scale/rotate) → commit on gesture end
    - Delete via eraser/delete affordance
    - Undo/redo for add/update/delete
  - Overlays (text + stickers) share a single z-order stack, separate from strokes (strokes remain the base layer).
  - Z-order rule in v1 is **implicit “bump-to-front on interaction”** (last added/edited/moved becomes top-most), avoiding a layers UI.
  - Durable snapshot schema should represent overlays as a **unified list** (e.g. `elements: [...]` with a `kind` discriminator) rather than parallel arrays per overlay type, to keep ordering, rendering, and future extensions coherent.
  - Backward compatibility: older clients that don’t support stickers may simply not render stickers (v1 acceptable) until updated.

- **Mobile caching and offline behavior**
  - Pack metadata can be cached; sticker assets are downloaded lazily.
  - If an asset is missing, the app shows a placeholder and triggers a download.
  - Wallpaper snapshot rendering remains non-blocking:
    - Missing sticker assets render as missing/placeholder in the snapshot; snapshot can be marked dirty and re-rendered after downloads complete.

- **Admin and operational tooling**
  - Provide admin endpoints (protected by the same admin-password pattern used elsewhere) to:
    - Upload packs and stickers
    - Upload variants (thumbnail/full)
    - Set ordering/featured configuration
    - Publish/unpublish packs

- **Modules to build/modify (high-level)**
  - Backend:
    - Sticker catalog service (pack list/detail, featured ordering, publish state)
    - Sticker storage abstraction (upload, public/signed URL generation or proxy)
    - Entitlement check layer (user ownership + paired union view)
  - Mobile:
    - Sticker pack repository (catalog fetch + cache)
    - Sticker asset cache/downloader (variant-aware, placeholder support)
    - Canvas overlay element runtime generalized from “text-only overlay” to “elements overlay”
    - Wallpaper snapshot renderer extended to include sticker elements (with missing-asset handling)

## Testing Decisions

- **What makes a good test**
  - Test externally observable behavior and contracts (API responses, entitlement gating, durable snapshot materialization, rendering outcomes), not private implementation details.
  - Prefer deterministic tests that validate input → state → output (e.g. sequence of operations → snapshot elements list).

- **Backend tests**
  - Catalog tests:
    - Published packs ordering/featured behavior
    - Pack detail includes the expected sticker thumbnail metadata
  - Access-control tests:
    - Asset download requests are allowed/denied based on entitlement rules
    - Union-of-entitlements behavior while paired
  - Canvas durability tests:
    - Sticker element operations materialize into the durable snapshot deterministically
    - Snapshot replay remains stable across expiring URLs (i.e. no URLs stored in history)

- **Mobile tests**
  - Canvas runtime tests:
    - Placing a sticker emits the correct operation and updates render state
    - Transform gesture commit emits a single update operation
    - Undo/redo works across add/update/delete sticker elements
    - Z-order bump-to-front behavior for interactions
  - Asset caching tests:
    - Placeholder rendering when asset missing, followed by successful render after download
    - Variant selection (thumbnail vs full) is correct by usage context
  - Snapshot renderer tests:
    - Stickers render when assets are present
    - Snapshot render does not block on missing assets and can re-render after download

## Out of Scope

- Shipping paid packs in v1 (v1 ships free packs only).
- Mulberry Pro subscription purchase flow and billing integration (planned later).
- User-uploaded stickers (moderation/content pipeline is explicitly deferred).
- Animated stickers (static stickers only in v1).
- Advanced per-sticker styling (tint/shadow/opacity/flip) beyond transform.
- Explicit layer-management UI (bring forward/back controls) in v1.
- Localization of sticker pack metadata in v1.

## Further Notes

- This PRD intentionally optimizes for durability: existing canvases must continue to render even as catalogs evolve, packs are unpublished, or asset URLs rotate.
- Access-controlled delivery is required for future paywalls; stable sticker identifiers in canvas history are the key architectural constraint.
- Reusing the wallpaper catalog pattern reduces risk and accelerates development, while still allowing stickers to introduce entitlement logic and private asset delivery.
