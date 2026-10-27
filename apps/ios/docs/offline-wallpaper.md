# Offline wallpaper updates

The app restores the last signed-in account, pairing and drawing document before making network requests. New strokes, undo, redo and clears use the existing on-disk outbox. A cold offline launch can edit the saved canvas. DrawingSync still checks the live account and pairing before uploading; a changed pairing leaves the old pair's edits isolated on disk.

Going Home or locking the phone schedules the existing local notification automation when the rendered content differs from the last completed image. The content signature includes visible strokes, background choice, drawing visibility and brush mode. It does not depend on a new server revision. Changing only a background therefore also needs an update. The existing Update wallpaper button runs the Shortcut directly. A previously completed Shortcut setup is still required before automatic triggers are sent.

The Shortcut attempts a short network refresh, using a separate URLSession with a three-second resource timeout and no wait for connectivity. It shares the backend client's session and token-refresh serialization. Connection failures and server 5xx responses use the saved local canvas; explicit login rejection, account changes, pairing mismatches and malformed data do not silently fall back. Completion records locally and removes only matching notifications. A receipt for an older export cannot acknowledge a newer drawing or photo. Rendering stays at native pixel resolution.

Queued edits upload when the app's foreground sync reconnects. Upload while the app remains suspended or force-quit is not guaranteed by this implementation. Cached remote drawings show the last received partner state until a network refresh succeeds. A photo from the online gallery still needs a connection to download; built-in images and photos already available on the phone work offline.

## Automated checks

Run `apps/ios/scripts/test-offline.sh <simulator-UDID>` on a booted iOS 27 simulator. It compiles the production model, editor, wallpaper library, renderer, delivery service and stores. Only network, notification-center delivery and external sign-in/push services are replaced. Before the fix, this harness failed cold canvas restoration, offline editing after launch and offline Shortcut export. The expanded checks cover those cases plus actual exported pixels, base-photo changes, local imports, visibility, cleanup, changes during a Shortcut, cold restarts, server errors and rejected credentials. It does not run Apple's Set Wallpaper action.

Run `python3 apps/ios/scripts/test-product.py` for the Swift suite and isolated real TypeScript backend routes. The reconnect checks verify saved operation IDs, deduplication, persistence across restart, wrong-pair isolation and partner-visible strokes after upload. The real backend contract uses an in-memory database and an injected network outage; it never stops the developer's backend or touches production.

Run `apps/ios/scripts/test-rendering.sh <simulator-UDID>` for the existing 18 canvas/export pixel comparisons. The signed Debug device build also passes. Its existing Firebase token/deletion API deprecation warnings are unrelated to this change.

## Physical-device verification — passed 2026-10-27

Installed the signed Debug build on the user's iPhone 17 running iOS 27.0.1. Both local Docker services were stopped at the start. New strokes updated the actual Home Screen wallpaper automatically. A terminated/relaunched app restored the saved canvas while still offline; new drawing, undo and redo worked. Changing the base image through Update wallpaper ran the real Shortcut without a server. Changing only the image and locking the phone also updated the Lock Screen, with a closed padlock visible. Matching local notifications were removed after completion.

After restarting the existing local backend and database, the phone uploaded all 103 queued operations exactly once: server revision advanced from 773 to 876 and the phone's pending queue emptied. This included all 93 operations already waiting before the test. The paired Pixel 8a Android app persisted revision 876 and the exact same two stroke IDs as the phone and server. No credentials were changed and no production data was used.

Airplane mode with Wi-Fi off then passed an erase-to-wallpaper update. The backend was stopped again for a clear-to-wallpaper test, which also passed. Test marks were removed, the original Red waves image restored, airplane mode disabled, Wi-Fi restored, and the local backend left running. The final reconnect reached revision 878 with no pending edits and an empty canvas, matching the visible canvas at the start.

Evidence is in [the device results](evidence/offline/results.json), the filtered delivery journal and five screenshots alongside it. The phone was connected over USB with Device Hub screen sharing and no debugger. Photo-library import was exercised by the simulator harness but not repeated on the physical phone. Remote APNs and long-term suspended-app upload remain outside this offline correction's proof.
