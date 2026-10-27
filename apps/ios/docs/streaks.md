# iOS streaks and celebrations

The header streak pill and Settings > Daily streak open a native bottom sheet. It uses the Android bolt artwork, lower oval background, weekly activity marks, active/new/lost wording, and a full-width Continue button. Content scrolls at large text sizes. The app does not change the backend's streak rules.

`GET /streak?today=yyyy-MM-dd` uses the device's local calendar day. The drawer, header and banner share `StreakModel`. The model refreshes with the foreground bootstrap poll and when the drawer opens. Completed drawing operations already carry `clientLocalDate`; queued offline drawings count on their original day after the server accepts them. Offline UI keeps the last confirmed state and explains refresh failure in the drawer.

`StreakProgress` persists its baseline and banner expiry per account and pair. A confirmed increase shows “Your streak just got a level up” and one burst. The banner lasts one hour or until tapped; tapping opens the drawer. Repeated polls and relaunches do not replay confetti. Unpairing/account changes clear visible state and invalidate in-flight results.

A transition from unpaired or pending acceptance to paired shows the pairing confirmation sheet, for either partner. An already-paired cold launch does not celebrate again. The sheet waits for the invite UI to dismiss before presenting. The native Continue button and swipe dismissal return to the existing flow.

Both celebrations share `CelebrationConfetti`. Its point-based particle engine follows Android's Konfetti 2.0.5 recurrence at 60 Hz: two groups of 70 particles, 360 ms emission, 100-degree spread, speed 22–44, damping 0.9, accumulated gravity, mass variance, and 2D/3D rotation. It uses Android's eight colors and three sizes/shapes. Animation ends after four seconds, ignores touches and is suppressed by Reduce Motion. ISC attribution is bundled in `Resources/Legal/konfetti_license.txt`.

The level-up banner bobs upward by 7 points and back, with 850 ms per direction and the Android FastOutSlowIn curve (0.4, 0, 0.2, 1). The offset does not change canvas layout. Reduce Motion and an inactive scene stop the bob.

The phase animator is recreated when motion becomes enabled or disabled. On the physical iPhone, an animator initially given only its resting phase did not start when the scene became active and its phase list changed. Captured frames reproduced the stationary banner with the old identity and showed movement after resetting its identity. Keep this foreground transition when changing the animation.

## Validation

`python3 apps/ios/scripts/test-product.py` runs the Swift client against an isolated real backend implementation. It covers pairing, both partners' streak counts, completed drawings, offline replay, original local dates, a missed day and unpairing. It does not change the local development database or the real account.

`apps/ios/scripts/test-streak.sh` checks the production streak model's persistence, duplicate suppression, offline state, reconnection and account/pair request races. `StreakTests.swift` covers copy states, banner expiry/dismissal, authenticated requests, pairing transitions, and compares the particle positions against Konfetti's frame-by-frame recurrence.

`apps/ios/scripts/preview-streak.sh <simulator-udid>` builds a separate sample-data app using the production views and streak model. It has no real login or server connection. The iPhone 17 Pro iOS 27 simulator was used to inspect the banner, pairing burst, lost/new states, banner tap/dismissal and canvas hit testing. The real iPhone 17 was used to check the drawer with real server state and Continue dismissal. Screenshots are in `docs/evidence/streak/`.

The first confetti approximation was replaced after visual feedback. The final recurrence is mathematically checked against the Android library. A live side-by-side Android comparison was not completed: the Android emulator stayed locked and unresponsive, and the user shut it down. The real account was not unpaired to test pairing celebrations; those use isolated tests and the separate preview app.
