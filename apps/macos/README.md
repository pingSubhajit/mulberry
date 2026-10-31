# Mulberry for macOS

The native Mac app: a menu bar app that keeps the shared canvas on the desktop, opens a portrait canvas window, and lets you draw over the desktop with Quick Draw. It is a Swift package; the build scripts assemble a signed `.app` bundle.

## Requirements

- macOS 26 or newer (Liquid Glass)
- Xcode 26 or newer

## Development

Run the app directly:

```bash
pnpm --dir apps/macos run dev
```

Build and assemble a local app bundle, signed with your Apple Development certificate:

```bash
pnpm --dir apps/macos run build
open .build/app/Mulberry.app
```

Run the smoke checks (unit tests, overlay regression, canvas render fixtures and the app bundle):

```bash
pnpm --dir apps/macos run smoke
```

From the repository root, the same commands are `pnpm dev:macos`, `pnpm build:macos` and `pnpm smoke:macos`.

Sign-in reads `MULBERRY_MAC_GOOGLE_CLIENT_ID` and `MULBERRY_MAC_GOOGLE_CLIENT_SECRET` from `apps/macos/.env` (see `.env.example`). The backend must list that client ID in `GOOGLE_ALLOWED_CLIENT_IDS`. Release builds (`CONFIGURATION=release`) read `apps/macos/.env.release` instead, and refuse an API address that isn't HTTPS; without one they use `https://api.mulberry.my/`.

## Shared Code

The iPhone and iPad app and this app share four targets in [`packages/apple`](../../packages/apple): `ProductCore` (backend client, Keychain session, canvas model, offline outbox, WebSocket sync and the streak), `CanvasDrawing` (the canvas renderer: strokes, text and stickers), `StickerAssets` (sticker catalog, download and disk cache) and `ProductUI` (the reactions' artwork, picker and playback, and the celebration confetti). This app adds what is particular to the Mac: the menu bar, the desktop overlay and when it syncs, Quick Draw, Google sign-in through the browser, and its pointer-driven editing tools and editors.

## What the App Does

- **Menu bar.** Mulberry runs as a menu bar app. Clicking its icon opens a small iPhone lock screen: the shared drawing over a wallpaper under the time, as the partner sees it. Three glass buttons sit at its foot: a heart that unfolds the six reactions, the drawings on the desktop (shown or hidden) larger in the middle, and Quick Draw; Mulberry and Settings sit in the bottom corners. Swiping up from its home bar, two fingers up on the trackpad, or the Up arrow slides the lock screen away to a home screen: your partner and the streak as widgets, and Mulberry, Quick Draw, Reactions and Settings as apps; swiping down, the Down arrow, Esc or the Dynamic Island locks it again. Reactions arriving while it is open play on it. A right click opens a menu with Open Mulberry, Quick Draw, Settings and Quit. The icon can be hidden in Settings; the Dock icon then stays on so Mulberry is always reachable.
- **Desktop overlay.** A transparent, click-through window behind other windows shows the shared canvas on a chosen display, at a chosen strength. Its display, frame, visibility and strength are saved per Mac.
- **Canvas window.** A fixed 9:20 portrait panel of frosted glass, with the iPad's tools: brush, text, stickers, eraser, eyedropper and clear, plus undo and redo, colour and width. The toolbar holds reactions, the streak and the profile menu.
- **Quick Draw.** Control-Command-M by default, from any app: the overlay comes forward over a glass panel with the tools in a column beside it. Its grip drags the drawing between displays. Esc or Done closes it.
- **Keyboard.** B, T, S, E and I pick the brush, text, stickers, eraser and eyedropper; Command-Z and Shift-Command-Z undo and redo this Mac's own edits; Command-Delete clears the canvas after asking; Delete removes a selected text.
- **Menus.** Mulberry, File (Quick Draw, Send Reaction, Export Drawing), Edit (undo, redo, the clipboard, Clear Canvas), Settings (the overlay, dry brush, login and menu bar options), Window and Help (Send Feedback).
- **Settings.** Its own window: your account and photo; open at login, menu bar and Dock, Quick Draw's shortcut and dry brush strokes; the desktop overlay; your partner's profile, pairing and whether their wallpaper shows your drawings; sync diagnostics; and About, with Send Feedback, which opens the feedback board in its own window.

## Structure

`Sources/` holds one target per concern:

| Target | Holds |
| --- | --- |
| `AppShell` | The app delegate, menus, canvas window, Quick Draw panel, editors, Settings and the streak and reactions popovers |
| `Auth` | Google sign-in through the browser and the saved session |
| `Networking` | The backend client's configuration for this Mac |
| `Sync` | When the canvas syncs, over WebSocket or by catching up |
| `CanvasRendering` | The canvas view, fonts and offscreen rendering |
| `CanvasEditing` | The editing engine: tools, strokes, elements and undo history |
| `Persistence` | Local storage for the session and cached state |
| `Overlay` | The desktop overlay window and its settings |
| `QuickDraw` | Quick Draw's global shortcut |
| `Stickers` | Sticker artwork loading for the canvas |

## App Store

The Mac app shares the iPhone and iPad app's App Store record, as Universal Purchase: one listing, with Mac added as a platform rather than a separate app. So it takes the same bundle IDs, `com.subhajit.mulberry` for release builds (`CONFIGURATION=release`) and `com.subhajit.mulberry.dev` for development ones, and the same icons, compiled by `Scripts/build-app.sh` from the iPhone app's Icon Composer files: `apps/ios/Resources/AppIcon.icon`, and the purple `AppIconDev.icon` for development builds. Each build keeps its own saved session.

It runs in the App Sandbox, with only what it needs: outgoing network for the backend, sync, sticker artwork and sign-in; incoming network for Google sign-in's return to its loopback server; and files the person chooses, for Export Drawing and a profile photo. Its privacy manifest, `Resources/PrivacyInfo.xcprivacy`, matches the iPhone app's.

Crash reports go to Firebase Crashlytics, as the iPhone app's do: sharing its bundle IDs, the Mac shares its Firebase app and its public client configuration, `apps/ios/Configuration/GoogleService-Info.{dev,prod}.plist`, which the build copies in after checking its bundle ID. A release build needs the production one, and uploads its symbols. Development builds have test buttons in Settings → Advanced. Only one copy of a build runs: a second hands over to the first and quits.

Signing for the Mac App Store (a Mac App Distribution certificate and provisioning profile), adding the Mac to the App Store Connect record, push notifications and a notarised Developer ID DMG wait for the paid Apple Developer account.
