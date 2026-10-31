# Mulberry for macOS

The native Mac app: a menu bar app that keeps the shared canvas on the desktop, opens a portrait canvas window, and lets you draw over the desktop with Quick Draw. Its modules are a Swift package. The generated `MulberryMac.xcodeproj` builds the signed app host and embeds its Mac provisioning profile.

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
open apps/macos/.build/xcode/Build/Products/Debug/Mulberry.app
```

Run the smoke checks (unit tests, overlay regression, canvas render fixtures and an unsigned development app bundle):

```bash
pnpm --dir apps/macos run smoke
```

From the repository root, the same commands are `pnpm dev:macos`, `pnpm build:macos` and `pnpm smoke:macos`.

Sign-in reads `MULBERRY_MAC_GOOGLE_CLIENT_ID` and `MULBERRY_MAC_GOOGLE_CLIENT_SECRET` from `apps/macos/.env` (see `.env.example`). The backend must list that client ID in `GOOGLE_ALLOWED_CLIENT_IDS`. Production builds read `apps/macos/.env.release`; development builds read `.env`. Only the three named native-client settings are copied. An installed app uses its bundled configuration, rather than a `.env` in its launch directory. Production defaults to `https://api.mulberry.my/`. Local HTTP in a production build requires an explicit override and a loopback host.

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

## Environments and signing

There are two environments, `dev` and `prod`, independent of debug or release compilation. Development uses `com.subhajit.mulberry.dev` and the purple icon. Production temporarily uses `com.subhajit.mulberry.beta` and the normal icon, matching the iPhone and iPad beta. `.beta` is a bundle identity, not a third environment.

The generated Xcode configurations are Debug (dev), DevRelease (optimized dev), ProdDebug (prod) and Release (optimized prod). The shared schemes are Mulberry and Mulberry Production. Run `python3 apps/macos/Scripts/generate-project.py` after changing the generator or top-level resource folders.

`Scripts/build-app.sh` creates a local Apple Development signed app. Production uses Techaboard `HMJX9736KN`; development keeps the existing personal team `YGJRT89MH9`, without restricted push entitlements, matching iOS. The paid beta has remote push enabled. `MULBERRY_MAC_DEVELOPMENT_TEAM` can select another team. Xcode manages the Mac-specific profile. The local build script explicitly uses sandbox APNs even with release compilation. A distribution archive uses Release's production APNs setting and needs matching distribution signing and Firebase credentials.

To rebuild the optimized production identity against the local container:

```bash
CONFIGURATION=release ENVIRONMENT=prod \
MULBERRY_ALLOW_LOCAL_SERVER=YES \
MULBERRY_MAC_API_BASE_URL=http://127.0.0.1:8080/ \
apps/macos/Scripts/build-app.sh
```

The result is `apps/macos/.build/xcode/Build/Products/Release/Mulberry.app`. Local builds do not upload Crashlytics symbols or publish anything.

For CI without an Apple account or private client files, set `MULBERRY_MAC_UNSIGNED_BUILD=YES` with `ENVIRONMENT=dev`. This disables signing and provisioning and permits missing development client configuration. Sign-in and remote updates require real client configuration. Unsigned production builds are rejected; the smoke script always packages unsigned development.

The app uses the existing Desktop Google OAuth client, the existing backend audience allowlist, and the user's Firebase project `mulberry-494003`. Firebase's public configuration is reused from `apps/ios/Configuration/GoogleService-Info.{dev,prod}.plist` after checking its bundle ID. The Mac beta uses the existing topic-specific beta sandbox APNs key; no additional server secret or Google Web client is needed.

The App Sandbox permits outgoing network access, Google OAuth's loopback server, and files selected by the user. AppKit explicitly passes the APNs token to Firebase Messaging. The backend records this app's push token as `MACOS` / `prod`. A push fetches authoritative state for the signed-in account and current pair; canvas, pairing, presence and reaction messages reuse the existing app controllers. Sign-out revokes this installation's push registration before clearing the backend session. Settings → Advanced shows the push connection and the last received/applied drawing revisions. Polling remains a fallback while the overlay is visible. Closing the app is not a promise of continuous background execution.

See [local beta validation](docs/local-beta-validation.md) for the tested flows and limits. Private device screenshots and raw receipts are excluded from Git. App Store Connect setup, TestFlight upload, distribution signing and notarization remain outside this local pass.
