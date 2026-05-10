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

Run the macOS smoke target:

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

## Phase 3 Overlay And Quick Draw Behavior

The app shell now includes the initial routing, overlay, and Quick Draw platform model:

- the main app window has a typed route model and a sidebar for Canvas, Overlay, Pairing, Streak, and Settings;
- Settings is a route inside the main app window;
- the status menu reuses one main window and one overlay window;
- Send Heart is visible but disabled until reactions and networking land;
- Overlay > Show/Hide Overlay toggles the transparent click-through overlay;
- Overlay > Choose Display opens the Overlay route in the main window;
- Overlay > Reset Position recenters the overlay on the selected display;
- the overlay defaults to a 9:20 portrait frame, sized for desktop at 450 x 1000 points before display clamping;
- overlay visibility, selected display, and frame are persisted in UserDefaults;
- Command-Control-M enters Quick Draw using a temporary debug drawing layer;
- Quick Draw raises the overlay, enables input, shows the debug tool palette, and exits on Done, Escape, or app deactivation.
