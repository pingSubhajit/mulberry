# App Store listing

Everything App Store Connect asks for, ready to paste, except screenshots. Record: Techaboard team `HMJX9736KN`, Apple ID `6829284245`, bundle ID `com.subhajit.mulberry.beta`, iOS and macOS under one record (universal purchase).

App Store Connect splits the listing in two. **App Information** (name, subtitle, category, age rating, privacy policy, App Privacy) is shared by iOS and macOS. **Each platform's version page** has its own description, keywords, promotional text, URLs, screenshots and review notes.

Character counts were checked against App Store Connect's limits.

## Fix before submitting

These are likely rejection reasons, or places where the listing would contradict the policy.

1. **Sign in with Apple (guideline 4.8).** Both apps sign in with Google only. Guideline 4.8 requires an equivalent option that limits data to name and email, lets people hide their email, and doesn't track them. None of its exceptions fit Mulberry. Sign in with Apple is the usual answer. It needs app and backend work and new builds.
2. **Version number.** iOS is `0.1`, macOS `0.1.0`. The store shows this as the public version. Ship both as `1.0.0` (the README already asks for matching three-part versions). This needs new builds, which item 1 needs anyway.
3. **Analytics wording in the privacy policy.** The policy and `/delete-account` say analytics events use pairing and invite IDs, "rather than your account". The backend sends `inviter_user_id` and `recipient_user_id` to PostHog (`apps/backend/src/modules/pairing/service.ts`). Change the wording or remove those properties. The App Privacy answers below treat analytics as linked to the user, which is what the code does today.
4. **Retention promises.** The policy promises cleanup within 30 days of revoked sessions, revoked push tokens and expired invites. No backend job deletes them. They go only when the account is deleted.
5. **Who runs Mulberry.** The App Store will list the seller as Techaboard Digi Solutions Private Limited. The policy, terms and `/delete-account` say Mulberry is operated by Subhajit Kundu. Add a line saying Techaboard distributes the Apple apps, or publish from your own developer account.
6. **Deploy the website changes.** `mulberry.my/support` doesn't exist until `apps/web` is deployed. The homepage also called Mulberry "a minimal Android app". Both are fixed in the working tree, not yet live.

## App Information (shared)

| Field | Value |
| --- | --- |
| Name | `Mulberry: Doodles for Couples` (29). If you want the plain name, use `Mulberry` (8). Words in the name count for search, so the longer one helps people find it. |
| Subtitle | `Draw on each other's wallpaper` (30) |
| Primary category | Lifestyle (matches the Mac app's `LSApplicationCategoryType`) |
| Secondary category | Graphics & Design. Avoid Social Networking: it invites review under the user-generated content rules (1.2) and the social media age-rating questions. |
| Content rights | Answer **Yes, it contains third-party content**, only if you hold the rights to every sticker pack, wallpaper and font (the fonts are SIL OFL). If every sticker and wallpaper is your own, answer No. |
| Privacy Policy URL | `https://mulberry.my/privacy` |
| User Privacy Choices URL (optional) | `https://mulberry.my/delete-account` |
| License agreement | Apple's standard EULA. The terms already include Apple's required clauses. |

### Age rating

Answer the questionnaire like this:

| Question | Answer |
| --- | --- |
| Parental controls | No |
| Age assurance | No |
| Unrestricted web access | No. The only in-app web view shows Canny feedback. The Website and Source code links open Safari. |
| User-generated content | Yes |
| Messaging and chat | Yes. People talk to each other through drawings and reactions. |
| Social media | No. Nothing is shown in a feed or spread beyond the pair. |
| Advertising | No |
| Contests, gambling, loot boxes | No |
| Medical, wellness | No |
| Profanity, horror, alcohol/drugs, mature themes, sexual content, violence, weapons | None |

That works out to **4+**. Your terms say Mulberry isn't for anyone under 18. Use the age-rating override to pick **18+**, so the store and the terms agree. If you'd rather keep teenagers, change the terms to 13+ (Google accounts already require 13). The Play listing says 12+, so it doesn't match the terms either.

## Pricing and availability

- Price: Free. No in-app purchases.
- Availability: **India only**. The privacy policy and terms are written for an India-only launch and promise an update before Mulberry goes to other countries. Add countries only after that update.
- Trader status (EU Digital Services Act): only matters for EU storefronts. With India-only availability, it doesn't apply yet.

## App Privacy

- Do you or your third-party partners collect data from this app? **Yes**
- Tracking: **No** for every data type. Nothing is used for advertising or shared with data brokers, and the apps have no advertising ID.

| Data type | Linked to the user | Purposes | Where it comes from |
| --- | --- | --- | --- |
| Contact Info → Name | Yes | App Functionality | Google name, display name, the name you give your partner |
| Contact Info → Email Address | Yes | App Functionality | Google sign-in; also sent to Canny when you send feedback |
| User Content → Photos or Videos | Yes | App Functionality | Profile photo and partner photo (uploaded, stored in Supabase) |
| User Content → Customer Support | Yes | App Functionality | Feedback sent through Canny |
| User Content → Other User Content | Yes | App Functionality | Drawings, typed text, sticker placements, reactions |
| Identifiers → User ID | Yes | App Functionality, Analytics | Account ID; also included in PostHog pairing events |
| Identifiers → Device ID | Yes | App Functionality | Random per-install ID and the Firebase push token |
| Usage Data → Product Interaction | Yes | Analytics, App Functionality | PostHog events from the backend; streak activity days |
| Diagnostics → Crash Data | No | App Functionality | Firebase Crashlytics, with no user ID attached |
| Diagnostics → Other Diagnostic Data | No | App Functionality | Crashlytics and Firebase Installations |
| Other Data → Other Data Types | Yes | App Functionality | Anniversary date, wallpaper and desktop overlay status, the device's time zone |

This matches the apps' own `PrivacyInfo.xcprivacy`, with one change: Analytics is added to User ID, because of the PostHog properties in fix 3. Remove that purpose if you remove those properties.

The Google Sign-In SDK's own manifest also lists phone number, coarse location and other usage data. Mulberry asks Google only for `openid email profile`, and Google collects anything else in its own sign-in screen, not in Mulberry. The table leaves those out. That's a judgement call. Add them if you'd rather declare everything an SDK lists.

## Version page: iPhone and iPad

| Field | Value |
| --- | --- |
| Promotional text (129/170) | `New: photos on your canvas. Snap a moment, doodle a heart around it, and it lands on their lock screen, the first thing they see.` Written fresh for each release, as `promotional_text` in the version's iOS What's New note; the `ios-release` subskill sets it. |
| Keywords (97/100) | `love,long distance,ldr,relationship,partner,boyfriend,girlfriend,lock screen,sketch,note,miss you` |
| Support URL | `https://mulberry.my/support` |
| Marketing URL | `https://mulberry.my` |
| Copyright | `2026 Subhajit Kundu` |
| What's New | Not shown for a first version. |

The keywords leave out words already in the name and subtitle (mulberry, doodles, couples, draw, wallpaper); Apple indexes those already. Separate keywords with commas and no spaces.

### Description

```text
Mulberry is a private canvas for you and your partner, and it lives on your wallpaper.

Draw a heart, write "miss you", add a sticker. It appears on your partner's wallpaper, so the next time they pick up their phone, there you are. There's no chat to open and nothing to reply to. It's just a small, quiet sign that you're thinking of them.

ONE CANVAS, TWO PEOPLE
Pair with a six-digit invite code and you share one canvas with one person. Either of you can add to it, erase, or start fresh. There's no feed, no followers and no likes, so it never becomes another app to keep up with.

ON THEIR WALLPAPER
On iPhone and iPad, Mulberry puts the canvas on your wallpaper with a Shortcuts automation. Set it up once from the app and new drawings update your Home Screen and Lock Screen on their own. Use one of Mulberry's wallpapers as the background, or your own photo.

DRAW YOUR WAY
• Smooth or dry-brush strokes in any colour, with an eyedropper and custom colours
• Text in nine fonts, with size, rotation, colour and a background
• Sticker packs for when words won't do
• Eraser, clear, and undo and redo for your own edits
• Apple Pencil on iPad, with palm rejection

SAY IT IN A TAP
Send love, a hug, a kiss, a smile, a laugh or sparkles. Your reactions play over the canvas when your partner opens Mulberry.

ON YOUR MAC TOO
Mulberry for Mac sits in the menu bar and shows your shared canvas on the desktop, right where you work. Press Control-Command-M to draw from anywhere.

KEEP YOUR STREAK
Draw something every day to grow your streak, and see your week at a glance.

PRIVATE BY DESIGN
Your canvas is shared only with the one person you pair with. Unpair at any time and shared drawing stops straight away. Delete your account from Settings whenever you like. Mulberry has no ads and doesn't track you.

GOOD TO KNOW
• Sign in with your Google account.
• Your partner needs Mulberry on their own device.
• Wallpaper updates arrive through notifications and Shortcuts, so a Focus or Scheduled Summary can delay them.

Mulberry is open source.
```

If you add Sign in with Apple (fix 1), change the first "Good to know" line to `Sign in with Apple or Google.`

The description doesn't name Android, because guideline 2.3.10 bars other mobile platforms from metadata. It says "your partner needs Mulberry on their own device" instead. The website and the review notes can say Android.

## Version page: Mac

Name, subtitle, category, age rating and App Privacy come from the shared App Information. Promotional text, keywords, Support URL, Marketing URL and copyright are the same as iOS.

### Description

```text
Mulberry is a private canvas for you and your partner. On your Mac, it lives on your desktop.

Draw a heart, write "miss you", add a sticker. It appears on your partner's phone wallpaper or Mac desktop. Their drawings appear on yours, quietly, between everything else you're doing. There's no chat to open and nothing to reply to.

ON YOUR DESKTOP
Mulberry sits in the menu bar and shows your shared canvas over the desktop, on the display and in the corner you choose. It stays out of the way of your clicks.

QUICK DRAW
Press Control-Command-M from any app to bring the canvas forward and draw. Change the shortcut in Settings.

ONE CANVAS, TWO PEOPLE
Pair with a six-digit invite code and you share one canvas with one person. There's no feed, no followers and no likes.

DRAW YOUR WAY
• Smooth or dry-brush strokes in any colour, with an eyedropper and custom colours
• Text in nine fonts, with size, rotation, colour and a background
• Sticker packs for when words won't do
• Eraser, clear, and undo and redo for your own edits

SAY IT IN A CLICK
Send love, a hug, a kiss, a smile, a laugh or sparkles from the window or the menu bar.

KEEP YOUR STREAK
Draw something every day to grow your streak.

PRIVATE BY DESIGN
Your canvas is shared only with the one person you pair with. Unpair at any time, and delete your account from Settings whenever you like. Mulberry has no ads and doesn't track you.

GOOD TO KNOW
• Sign in with your Google account.
• Your partner needs Mulberry on their own device.

Mulberry is open source.
```

## App Review information

### Demo account

Review can't happen without a working, already-paired account. Set this up before submitting:

1. Create a new Gmail account just for review, for example `mulberry.review@gmail.com`. Turn off 2-Step Verification.
2. Sign in to Mulberry with it on a test device. Finish onboarding and set the wallpaper background.
3. Pair it with a second account you control, for example on your Android phone. Draw something on the canvas from both sides.
4. Keep the second device nearby while the app is in review, so you can draw if the reviewer asks.

Google sometimes asks "Verify it's you" when an account signs in from a new place, and App Review signs in from the US. Give the account a recovery email you check, and sign in once from a VPN or a different network beforehand, so Google already knows the account works from new locations.

### Contact

First name, last name, phone and email of whoever can answer App Review during review.

### Notes, iPhone and iPad

Paste this into the review notes and fill in the brackets:

```text
Mulberry is a private, shared drawing canvas for two people in a relationship. What one person draws appears on the other's wallpaper.

DEMO ACCOUNT
Sign in with Google: [email] / [password]
This account is already paired with our second test account, "[partner name]", so the canvas, partner profile and streak are available straight away. We can draw from the partner account during review if you'd like to see live sync. Contact [phone/email].

WALLPAPER
iOS doesn't let apps change the wallpaper directly, so Mulberry uses a Shortcuts automation that the user adds and turns on once:
1. Settings (gear) > Wallpaper setup.
2. Allow notifications, then add the "Mulberry Sync" Shortcut from the share sheet.
3. Tap Edit, choose a wallpaper in Set Wallpaper, then tap the arrow beside Mulberry in the trigger and turn Automation on.
4. Tap Test wallpaper update. The Home Screen and Lock Screen then show the canvas.
The app never changes the wallpaper without that automation. The automation runs when a "Mulberry canvas updated" notification arrives, which happens after the partner draws.

SAFETY
Content is visible only to the two paired accounts. Pairing needs a six-digit code that one person shares with the other and that the other then accepts, so strangers can't reach a user. Either person can unpair at any time (Settings > Partner > Unpair), which stops sharing immediately and works as a block. People can report misuse to the contact address in the Terms of Use and on mulberry.my/support. Account deletion is in Settings, below Log Out.

The partner may use Mulberry on Android. Pairing and drawing work across both platforms.
```

### Notes, Mac

```text
Mulberry for Mac shows the same shared canvas on the desktop. It's a menu bar app with no Dock icon. Click the Mulberry icon in the menu bar to open it.

DEMO ACCOUNT
The same Google account as iOS: [email] / [password]. Sign-in opens in the default browser and returns to the app. The account is already paired.

WHAT TO TRY
• The canvas appears on the desktop, under windows, and doesn't block clicks.
• Press Control-Command-M (Quick Draw) from any app to draw on the canvas.
• The menu bar icon opens a small preview with reactions and Quick Draw.
• Settings > Account > "Delete your account" deletes the account.

Onboarding asks whether Mulberry should open at login. It's never added as a login item without that answer, and the user can change it in Settings.
```

## Non-screenshot assets

- **App icon:** comes from the build (`apps/ios/Resources/AppIcon.icon`, Icon Composer). App Store Connect takes it from the uploaded binary, so nothing to upload.
- **App preview video:** optional; skipped.
- **Support page:** `apps/web/app/support/page.tsx`. Covers pairing, the wallpaper Shortcut, troubleshooting, Mac Quick Draw, unpairing and reporting, and account deletion.

## After approval

- Add an App Store button to mulberry.my next to the Google Play one, and make `/download` send iPhone visitors to `https://apps.apple.com/app/id6829284245`.
- Add matching iOS wording to the Play listing's "What's new" and description if you mention the iPhone app there.
