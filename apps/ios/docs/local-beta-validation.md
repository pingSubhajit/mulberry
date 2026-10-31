# Physical beta validation — 31 October 2026

The local production-identity build is `com.subhajit.mulberry.beta`, signed by Techaboard team `HMJX9736KN`. It uses the normal icon, Firebase project `mulberry-494003`, and the existing Docker backend at `http://MAC_LAN_IP:8080/`. Validation used the physical iPhone 17 on iOS 27.2. Times below use India Standard Time.

## Wallpaper setup failure and fix

Both installed apps registered `mulberry` and `mulberry-dev`. A manual Shortcut run from beta completed in beta, but its shared success URL opened the old development app. The next notification test consequently ran in development, while the newly imported Shortcut watched beta. The development journal recorded failed probes 26 and 27; beta recorded the successful manual wallpaper runs. This was an app-routing collision, not proof that beta's wallpaper action had failed.

Each app now registers its exact bundle identifier as its callback scheme. The manual Shortcut success, error and cancel URLs use that same identifier, and the root view accepts only its own identifier. Google OAuth keeps its separate reversed-client-ID scheme. The callback regression test failed on the original overlapping schemes and passed after the fix.

The signed fixed build passed, installed over beta without clearing its data, and completed the manual round trip back to beta. The following automatic test remained in beta: probe 2 was scheduled at 17:17:49.440, prepared its image at 17:17:57.878, and recorded `shortcut_reported_applied` at 17:17:59.834. It then removed one matching delivered notification. Development recorded no new test after its earlier failed probe 27.

## Verified on the phone

Real Google sign-in completed through the configured native client and the existing backend Web audience. Local-network permission was granted. The existing profile and paired canvas loaded from the Docker backend. A cold launch of the fixed beta build retained the session and returned directly to the canvas.

Notification access was granted. Beta received an APNs device token, and Firebase Messaging registered an active `IOS` / `prod` token in the local backend. Neither the APNs registration error nor the push registration error was set. The signed profile uses the development APNs entitlement; `prod` is the app environment, not the APNs transport environment.

A real Firebase Admin message was sent to that one local beta registration using the backend's `applePushConfiguration` function and a `CANVAS_UPDATED` payload for the current paired canvas. Firebase accepted it at 17:18:25.546. With beta in the background and without tapping a notification, the Shortcut prepared the image at 17:18:27.957, recorded completion at 17:18:29.684, and removed one matching remote notification. This confirms the sandbox Firebase/APNs path through the imported beta Shortcut, separately from the local notification test.

The phone was then locked with its side button and screen sharing was stopped. CoreDevice reported `passcodeRequired: true` before the next send. Firebase accepted the locked-phone message at 17:29:35.748 for revision 12991. Beta prepared the image at 17:29:40.095, recorded completion at 17:29:43.673, and removed one matching remote notification. CoreDevice still reported `passcodeRequired: true` after the receipt was read. No unlock, app opening or notification tap occurred during this check. This was a repeat of the current canvas revision, not a new partner drawing.

A temporary Spring Kittens sticker was added through the physical app. The backend recorded `ADD_STICKER_ELEMENT` at revision 12990. Going Home caused the new sticker to appear on the actual Home Screen wallpaper. Undo recorded `DELETE_STICKER_ELEMENT` at revision 12991. The canvas and wallpaper returned to their previous contents. Existing drawings were retained.

Visual checks confirmed setup success, the test sticker on the actual wallpaper, and its removal. A Shortcut receipt alone is not a pixel readback. Device screenshots and raw receipts contain private test data and remain in ignored local evidence directories.

## Two-device stroke flow and latency

The same beta code and client configuration was built for the iPhone 17 simulator on iOS 27.0. The user signed in with the Google account already paired with the physical phone. The source app stayed in the foreground; the receiving physical iPhone stayed on its Home Screen. The local backend and PostgreSQL had stopped before this check and were started through Hun, retaining the database volume.

Five separate purple strokes were drawn through the simulator UI. The backend recorded each stroke under the account other than the physical phone's account. Its normal push dispatcher logged a canvas update to one peer token for every finished revision, with zero invalid tokens. No direct Firebase test message was sent for these trials. The phone prepared the exact matching revision, completed Set Wallpaper and removed its matching notification. All five strokes were also visually confirmed on the actual wallpaper, without opening Mulberry on the receiving phone.

Timing starts at the source app's `FINISH_STROKE.clientCreatedAt` and ends at the physical phone's `shortcut_reported_applied` timestamp for that exact canvas revision. It measures time after finishing a stroke, rather than time spent drawing it. The source-to-backend column is included in the total.

| Trial | Finished revision | Source to backend | Backend to image ready | Set Wallpaper and result recording | Total |
| --- | --- | --- | --- | --- | --- |
| 1 | 13066 | 0.031 s | 4.789 s | 1.553 s | 6.374 s |
| 2 | 13069 | 0.015 s | 4.834 s | 1.737 s | 6.586 s |
| 3 | 13072 | 0.009 s | 4.778 s | 1.539 s | 6.326 s |
| 4 | 13075 | 0.009 s | 4.708 s | 1.588 s | 6.304 s |
| 5 | 13078 | 0.024 s | 4.292 s | 1.559 s | 5.875 s |

The mean was 6.293 seconds, the median 6.326 seconds, and the range 5.875–6.586 seconds. A useful reading of this small local sample is approximately 6.3 seconds, with observed results between 5.9 and 6.6 seconds. Separate device wall clocks were not calibrated, and the end timestamp is the wallpaper action returning, not an instrumented first-pixel timestamp. This is not a production latency guarantee or a measurement of rapid consecutive strokes.

Undo removed exactly the five measured stroke IDs, confirmed against the database. Cleanup reached revision 13083 and recorded wallpaper completion at 19:40:42.518 IST. The user's earlier drawings, including the small mark already present at the baseline, were preserved. The four installed Mulberry preview apps were uninstalled from this simulator; the real apps and their sessions were retained.

The source canvas, actual physical wallpaper and cleanup were visually checked. Screenshots and raw timing/backend receipts remain in ignored local evidence directories; the sanitized measurements are recorded above.

## Limits

This pass used existing Google accounts, completed profiles and pairing; it did not create a fresh account or recreate an Android pairing. The earlier direct Firebase probes were followed by the real peer-recipient and automatic-push tests above. No Android UI edit was tested. Every drawing tool was not retested. Device Hub's synthetic drag on the physical phone reported a finger radius of 4370 and was rejected as a palm, so that earlier same-phone save/undo check used a sticker instead of weakening palm rejection. The simulator-origin checks above used actual stroke operations.

Eight configuration tests passed, including side-by-side callback isolation. The signed beta device build and the earlier optimized development build passed. No TestFlight upload, App Store submission or production APNs key was created in this pass.
