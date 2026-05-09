# Mulberry for macOS

This is the permanent native macOS app workspace for Mulberry. It starts as a Swift Package for Phase 1 speed, while the build scripts produce a `.app` bundle shape so the project can move toward signing, notarization, and DMG packaging without keeping production code in the overlay prototype.

## Requirements

- macOS 14 or newer
- Xcode 16 or newer, or a command-line Swift 6 toolchain

## Development

Run the app directly:

```bash
pnpm --dir apps/macos run dev
```

The app starts in menu-bar mode without a Dock icon. Choose **Open Mulberry** from the menu bar item to show the main window; while that window is open, Mulberry appears in the Dock.

Build and assemble a local app bundle:

```bash
pnpm --dir apps/macos run build
open .build/app/Mulberry.app
```

Run the Phase 1 smoke target:

```bash
pnpm --dir apps/macos run smoke
```

From the repository root, the same commands are available as:

```bash
pnpm dev:macos
pnpm build:macos
pnpm smoke:macos
```

## Phase 1 Scope

The Phase 1 shell intentionally contains only the permanent app foundation:

- native AppKit/SwiftUI executable under `apps/macos`;
- module folders for app shell, auth, networking, sync, canvas, persistence, overlay, Quick Draw, notifications, and settings;
- accessory-mode launch behavior;
- status bar item;
- blank primary window opened from the status menu;
- bundle metadata, entitlements placeholders, and local build scripts.

Overlay behavior, Google Sign-In, sync, persistence, canvas rendering, and packaging/notarization are later PRD phases.
