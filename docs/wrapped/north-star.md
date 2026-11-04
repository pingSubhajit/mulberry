# Mulberry Wrapped: North Star

Last updated: 4 November 2026

This is the reference for Mulberry's yearly recap. It covers what the recap is, why we're building it, what each screen shows, what the server and apps need, the data behind each screen, and the decisions that are still open. Read it before designing or building any part of the recap.

## Contents

1. [In one paragraph](#1-in-one-paragraph)
2. [Name](#2-name)
3. [Why we're building it](#3-why-were-building-it)
4. [Principles](#4-principles)
5. [Who gets a recap](#5-who-gets-a-recap)
6. [Timeline](#6-timeline)
7. [The experience](#7-the-experience)
8. [The cards](#8-the-cards)
9. [Canvas replays](#9-canvas-replays)
10. [Moments for two](#10-moments-for-two)
11. [Sharing and growth](#11-sharing-and-growth)
12. [System design](#12-system-design)
13. [Data](#13-data)
14. [What needs to be built](#14-what-needs-to-be-built)
15. [Scope for 2026](#15-scope-for-2026)
16. [Measuring success](#16-measuring-success)
17. [Privacy and safety](#17-privacy-and-safety)
18. [Risks](#18-risks)
19. [Open decisions](#19-open-decisions)
20. [Beyond 2026](#20-beyond-2026)

## 1. In one paragraph

Once a year, each Mulberry pair gets a short feed of full-screen cards about their year on the shared canvas. It replays everything they drew, from their first day to today, and shows their colours, when they draw, their longest streak, the reactions they sent, and the kind of couple they are. It ends with a poster to share and a note for the partner that reaches them at midnight on 1 January. The cards scroll vertically like Reels or TikTok. The apps draw every card natively, and the server decides which cards a pair sees and what each one says, so the same system can run next year's recap and other recaps without app releases.

## 2. Name

- **Public name (confirmed):** *Our Year in Doodles*, for example "Our Year in Doodles 2026".
- **Internal name:** Mulberry Wrapped. Use it in code, docs and conversation, never in the apps or marketing.

Why not "Wrapped" in public: the word belongs to Spotify in most people's minds. *Our Year in Doodles* makes sense on its own in an Instagram Story, even to someone who has never heard of Mulberry.

## 3. Why we're building it

Mulberry is a private canvas for two. That privacy is what people value, and it also means the product can't spread through its own network: once a partner joins, nothing in the app reaches anyone else. The recap is the one moment in the year when couples *want* to show Mulberry to other people, on their own terms.

It serves three goals:

1. **Growth.** Share cards and videos put Mulberry in front of the couples' friends at the time of year when people already post recaps.
2. **Reactivation.** "Your year is ready" is the most natural reason a lapsed pair will ever have to open the app again.
3. **Retention.** The recap ends with a note drawn for the partner and a button back to the live canvas, so it starts the new year with drawing.

It also does something no competitor can: we keep every stroke a couple has drawn, so we can replay their whole year.

## 4. Principles

1. **It belongs to the pair, not to one person.** Every card is about "you two". Both partners take part, and some moments only happen when both have watched.
2. **Roles, never scores.** We never show who drew more as a percentage. Imbalance becomes complementary roles, such as "The Artist & The Hype Person".
3. **Private by default.** Nothing is shared unless someone taps Share. Couples can hide the canvas on anything they share.
4. **Never quote what people wrote.** Drawings already feel intimate. Quoting typed text feels like we were reading it.
5. **Only what's true and full enough.** If a stat is too thin, its card doesn't appear. The feed never shows an empty or awkward card.
6. **Native and server-driven.** The apps draw every card natively. The server sends which cards appear and what they say. No web views.
7. **Built to be reused.** Every layout is generic enough for next year's recap and for other recaps, such as Valentine's week.
8. **Mulberry's voice.** Squee narrates, in the handwritten style of the canvas prompts. Warm and short, never loud.
9. **Nothing to sell.** The recap is a gift. It has no offers, upsells or paid extras.

## 5. Who gets a recap

| Situation | What they get |
| --- | --- |
| Paired at the cutoff, 10 or more active days in the year | The full recap |
| Paired at the cutoff, fewer than 10 active days | **"Your first chapter"**, a short version (see [The cards](#8-the-cards)) |
| Paired, but no activity in the last 60 days or more | The full recap or the short version, with gentler wording, such as "Some years are quieter" |
| Paired, with no activity at all | No recap |
| Not paired at the cutoff | No recap, ever, including from a past pairing |
| Account deleted | Nothing; the data is already gone |

An "active day" is a day in `pair_activity_days`, the same days the streak counts.

A recap covers the current pairing only. It never includes data from an earlier partner. Unpairing deletes the pair's history, and that stays the rule, so this always holds (see [Data](#13-data)).

The recap is on Android, iPhone and iPad. The Mac app doesn't show it.

## 6. Timeline

| Date (2026) | What happens |
| --- | --- |
| Early December | Spotify Wrapped launches and recaps fill people's feeds. We don't compete with it. |
| **About 20 December** | **Cutoff.** Stats are computed up to this date and recaps are generated. Days after the cutoff aren't counted. |
| A few days before launch | Creators get early access, so posts are already out at launch. |
| **Last week of December** | **Launch.** Banner, push notification, and the Settings entry go live. |
| **Midnight, 1 January** | Midnight notes reach partners, at each recipient's local midnight (see [Moments for two](#10-moments-for-two)). |
| 31 January 2027 | Last day to watch it again. After that it's hidden. |

## 7. The experience

### Ways in

- A Squee banner on the canvas: "Your year is ready".
- A push notification: "Your year in doodles is here".
- A push notification when the partner starts: "*[Partner]* just unwrapped your year".
- A Settings entry to watch again, until 31 January.

### The feed

- Full-screen cards in a vertical feed. Swipe up for the next card, swipe down to go back.
- The user sets the pace. No timers, and nothing advances on its own.
- Each card plays its animation when it stops in view, and plays it again when you come back.
- **Top bar:** close button and the year.
- **Right edge:** a thin line shows how far through the feed you are.
- **Buttons on the right, as on Reels:**
  - **Share** the card: an image, or a video for animated cards.
  - **React:** send your partner a heart, hug, kiss, smile, laugh or sparkles about this card. It arrives as a normal Mulberry reaction.
  - **Save** the card to Photos.
- **The end:** the last card has a "Back to your canvas" button. Until midnight on 1 January the last card is the midnight note (card 14). After that, it's the poster (card 13).

Why a reel feed and not a tap-through story: people choose how long to stay on each card, which matters for the guessing cards and the poster. The buttons on the right fit cards people choose to stay on. And a long card, like the 15–20 second time-lapse, feels normal in a reel and wrong in a story.

## 8. The cards

Wording below is draft. Squee narrates throughout.

| # | Card | What it shows | Layout |
| --- | --- | --- | --- |
| 1 | **Cover** | "Our Year in Doodles 2026", both profile photos, and Squee holding a rolled-up canvas. A "swipe up" hint, on this card only. | Cover |
| 2 | **Day one, then the year** | One continuous 15–20 second time-lapse. The first active day replays slowly, stroke by stroke ("Day one", with the date, or "It started with a line" if day one was only a few strokes). Then, with no cut, it speeds up through the rest of the year; the date label goes from days to weeks to months ("…and then the rest of 2026"). Clears stay in, so the canvas fills and wipes through the year. It settles on today's canvas while the stroke count ticks up to the year's total, then loops. Tap to pause. | Canvas replay |
| 3 | **Guess: their colour** | "Which colour did *[Partner]* reach for most?" Four swatches. A wrong tap shakes; the right one fills in. Scrolling past without answering reveals it. | Guess |
| 4 | **Your colours** | A split screen with each partner's most-used colour as a big named swatch: "You were Mulberry Red. They were Monsoon Blue." A strip along the bottom shows the year's colours in the order they were used. | Split comparison |
| 5 | **When you think of each other** | A 24-hour dial glowing at the pair's peak hours: "Most of your doodles happen around 11:40 pm." One line names the pattern: night owls, early birds, or lunch-break sketchers. | Time visual |
| 6 | **Your biggest day** | The date and stroke count ("14 Feb: 212 strokes"). That day's time-lapse plays behind it for about 5 seconds and ends on the canvas as it looked that night. | Canvas replay |
| 7 | **Your streak** | The longest streak in large type, and the total days on the canvas together. A calendar of the year, with active days lighting up one by one. | Time visual |
| 8 | **Fresh starts** | "You started fresh 23 times", with a quick animation of a canvas being wiped. | Big number |
| 9 | **Favourites** | The most-used sticker, shown large and bouncing in. Both names written in the pair's most-used font. | Favourites |
| 10 | **Reactions** | A big total ("4,120 hearts between you") with the reaction animations falling down the screen. A breakdown by type underneath. | Big number |
| 11 | **Guess: who's the night owl?** | Two profile photos to tap. The answer leads into the next card. | Guess |
| 12 | **Your couple type** | A title such as *Midnight Muses*, with its own Squee illustration, and two roles, such as "The Artist & The Hype Person". Never percentages. This is the card people will share most, so it must look like a poster on its own. | Persona |
| 13 | **Your poster** | Swipe sideways inside the card to choose your favourite canvas of the year, or hide the canvas. Shows the key stats, the couple type and a small Mulberry mark, with a large "Share to Story" button. **Locked until both partners reach this card**; until then it says "Waiting for *[Partner]*", with a nudge button. | Poster |
| 14 | **A note for midnight** | The last card. A canvas pops up: "Leave something behind for *[Partner]* to wake up to on New Year's." A short line underneath says that whatever you draw here will be sent to *[Partner]* at midnight on 1 January. Once the note is left, a "Back to your canvas" button closes the recap. The card appears only until midnight on 1 January. | Drawing input |

### "Your first chapter" (short version)

For pairs with fewer than 10 active days: cards 1, 2, 4, 13 and 14. On card 2, the speed-up covers weeks instead of months.

### Couple types (draft)

The server picks one title and one pair of roles from the year's stats. This list is a starting point, not final:

| Title | When |
| --- | --- |
| Midnight Muses | Most activity between 10 pm and 3 am |
| Morning Sketchers | Most activity between 5 am and 10 am |
| Lunch-break Doodlers | Most activity around midday |
| Sticker Hoarders | Stickers make up an unusually large share of what they add |
| Clean-Slate Artists | They clear the canvas unusually often |
| Steady Hearts | A long streak, or active on most days of the year |

| Roles | When |
| --- | --- |
| The Artist & The Hype Person | One partner draws most; the other mostly reacts |
| Co-Artists | Both draw about equally |
| The Storyteller & The Illustrator | One adds most of the text; the other most of the strokes |

## 9. Canvas replays

Cards 2 and 6 replay the canvas. We store strokes, not "doodles", so the replay works on time windows rather than drawings.

### What gets replayed

- **Time windows, not doodles.** Card 2 replays the first active day, then the rest of the year. Card 6 replays one day.
- **Day one, not the first doodle.** A pair's very first strokes are usually a test line to see whether the app works. That alone isn't worth a card. A slow day one that speeds up into the full year makes a thin start a strength: "It started with a line."
- **Clears stay in.** Watching the canvas fill and wipe over and over through the year is part of what makes it interesting.

### Timing

- A replay never runs at real speed. Each segment has a fixed length, and the drawing fits inside it.
- Within a segment, time is shared between strokes by how many points each has, so a long stroke takes longer to draw and a dot appears almost instantly.
- Text and stickers pop in at their place in the order, each with a short fixed animation.
- When a segment holds too many strokes to draw one by one (more than about 150), the replay moves through canvas states on an eased clock instead of drawing every stroke. The year segment of card 2 always works this way.

### Segments

A replay card is a list of segments. Each segment has:

- a time window (start and end),
- a duration on screen,
- a speed curve (steady, or speeding up),
- a caption, and labels that change with the playback (such as the date going from days to weeks to months),
- whether clears are kept.

Card 2 is two segments: "day one, slow" and "rest of the year, speeding up". Card 6 is one. Next year's recap can reuse this for things like "your Valentine's week".

### Background

The replay draws on Mulberry's paper background. We don't keep a history of each person's wallpaper, and we don't plan to.

## 10. Moments for two

These are what set the recap apart from Spotify's. Each person's recap is personal; ours is shared.

- **Unwrap together.** When one partner opens the recap, the other gets "*[Partner]* just unwrapped your year".
- **The poster waits for both.** Card 13 unlocks only after both partners have reached it, with a nudge button while one is waiting. This is how the recap brings a quiet partner back.
- **Guessing cards.** Cards 3 and 11 ask you to guess something about your partner. It's playful, made for two, and gets couples talking.
- **React about a card.** The React button sends a normal Mulberry reaction about the card on screen.
- **A note for midnight.** Card 14 lets each partner leave a doodle that reaches the other at midnight on 1 January, in the recipient's time zone, and shows on their wallpaper. This uses Mulberry's core feature, the wallpaper, rather than just copying Spotify's format.

## 11. Sharing and growth

- **Share cards** sized for Instagram Stories, with a small Mulberry mark and a link: "Make yours → mulberry.my/doodles". That page needs building.
- **Videos** of animated cards are made on the device and carry the same small Mulberry mark.
- **Public totals for press.** For example: "India drew 3.2M doodles on Mulberry this year. The most popular hour was 11:47 pm. Valentine's Day was 4× a normal day. The most-used colour was Mulberry red." That's a ready-made press release and Reel script, and it reveals no one's data. It needs a line in the privacy policy first (see [Privacy and safety](#17-privacy-and-safety)).
- **Early access for creators** a few days before launch, so their posts are out when everyone else gets theirs.
- **Lapsed pairs** who are still paired get "Your year is ready". This is the reactivation goal.

## 12. System design

The server-driven contract will be designed in its own document. This section records the decisions that are already made.

### Decided

- **Native on every platform.** Android, iPhone and iPad draw every card with native views. No web views. The Mac app doesn't show the recap.
- **The server decides the content.** For each pair, the server sends the recap: which cards, in what order, with which layout, and what data and wording each one shows. The apps own how each layout looks and moves.
- **A small set of reusable layouts.** New recaps combine existing layouts with new data, without an app release. A new layout needs an app release.
- **Unknown layouts are skipped.** An app that gets a layout it doesn't know leaves that card out instead of failing, so the server can add cards that older builds simply don't show.
- **Replays use the apps' own renderers.** The apps already draw canvas operations, so the replay player reuses that drawing code.
- **Videos are made on the device.** AVAssetWriter on Apple platforms and MediaCodec on Android, so the server pays no rendering cost.

### Layouts

| Layout | Used by | What the server sends |
| --- | --- | --- |
| Cover | 1 | Title, year, partner photos |
| Canvas replay | 2, 6 | A list of segments (see [Canvas replays](#9-canvas-replays)) and the replay data |
| Guess | 3, 11 | Prompt, options, the correct answer |
| Split comparison | 4 | Two values with labels, plus the colour strip |
| Time visual | 5, 7 | A dial or a calendar, with its data |
| Big number | 8, 10 | Number, label, animation asset |
| Favourites | 9 | Sticker and font IDs |
| Persona | 12 | Title, roles, illustration ID |
| Poster | 13 | Canvas choices, stats, and a flag that it waits for both partners |
| Drawing input | 14 | Prompt, and when to deliver the note |

### Replay data

A year of raw canvas operations can be large, because each stroke arrives as many small batches of points. Rather than sending the raw operations, the server should send a compact replay stream: finished strokes with their points, text and sticker additions, and clears, in order, with times. Each card asks only for its own windows.

`canvas_snapshots` holds only each pair's latest canvas. Past canvases, such as "the canvas that night" on card 6 and the poster choices on card 13, are rebuilt on the server from `canvas_operations`. The canvas just before each clear is a finished picture the couple chose to wipe, which makes those moments natural poster choices.

### Proposed: delivering the midnight note

The server keeps each note's drawing, then adds it to the shared canvas at midnight in the recipient's time zone, credited to the author. From there it travels through the normal sync, push and wallpaper paths on every platform. If partners are in different time zones, each note arrives at its recipient's midnight. This needs confirming during design.

## 13. Data

### What each card uses

| Card | Data | Where it lives | Status |
| --- | --- | --- | --- |
| 2, 6 | Strokes, texts, stickers and clears, with who and when | `canvas_operations` | Ready |
| 3, 4 | Stroke colour for each person | `canvas_operations` (the `colorArgb` of each stroke) | Ready |
| 5, 11 | When each person draws, in their local time | `canvas_operations` and the time zone history | Ready from 4 November 2026; earlier activity uses the fallback below |
| 7 | Active days and the longest streak | `pair_activity_days` | Ready |
| 8 | Clears | `CLEAR_CANVAS` operations | Ready |
| 9 | Stickers and fonts | Sticker and text operations | Ready, as long as old sticker art stays available |
| 10 | Reactions, with sender, type and time | `reaction_events` | Logging since 4 November 2026; no backfill |
| 12 | Built from the stats above | — | Ready |
| 13–14 | Recap progress, guesses, notes | New tables | Built with the feature |

Points in a stroke are stored relative to the canvas size, so a replay draws correctly at any screen size.

### Added for the recap (4 November 2026)

- **`reaction_events`**: every reaction sent, with the pair, the sender, the type and the time. It's written in the same transaction as the reaction inbox. Before this, `reaction_inboxes` only held the batch waiting to play and reset afterwards, so who sent what was lost. Code: `apps/backend/src/modules/reactions/service.ts`.
- **Time zones**: every app sends the device's IANA time zone (such as `Asia/Kolkata`) in an `X-Time-Zone` header on every request. The server keeps the current zone in `users.time_zone` and adds a row to `user_time_zone_changes` whenever it changes, so any moment can be read in the time zone the person was in *then*, including after they travel. Code: `apps/backend/src/modules/timeZones/recorder.ts`, the shared Apple `BackendClient`, and Android's `TimeZoneHeaderInterceptor`.

### Rules that follow from the data

- **Reactions in 2026 cover only part of the year.** We decided not to backfill from PostHog. For 2026, card 10 only appears if there are enough reactions since 4 November, and its wording must not suggest a full year, for example "4,120 hearts since November". From 2027, it covers the whole year.
- **Local time.** An event's local time uses the zone the person had at that moment: the latest change at or before it in `user_time_zone_changes`. For activity before a person's first recorded zone, use their earliest recorded zone. If a person has no recorded zone at all, use `Asia/Kolkata`, which is correct because Mulberry was only available in India before 4 November 2026.
- **Older apps don't send a time zone.** Only people on the builds released after 4 November send it. The fallback above covers everyone else.

### Rules to protect the data until the recap is generated

- **No canvas compaction.** Don't prune old `APPEND_POINTS` rows or other operations before the recap is generated. The replays need them.
- **Keep old sticker art.** Replays rebuild stickers from `(packKey, packVersion, stickerId)`. Hiding a pack keeps its art, but deleting a pack version breaks old canvases (see [Server-driven content](../server-driven-content.md)). Don't delete pack versions that have been used.
- **Unpairing deletes history.** `unpair` deletes the pair and all its data. A couple who unpair and pair again start their recap from the new pairing. We decided to keep it this way rather than hold data for a possible re-pair.

## 14. What needs to be built

### Backend

- Eligibility: who gets the full recap, the short version, or none.
- A stats job that runs at the cutoff and computes every pair's numbers, using each person's local time.
- The couple type and roles.
- Replay data: past canvases rebuilt from operations, and the compact replay stream.
- A recap API that returns each pair's cards.
- Storage for each pair's generated recap, guess answers, how far each partner has got (which unlocks the poster), and midnight notes with their delivery times.
- Push notifications: "your year is ready" and "*[Partner]* just unwrapped your year".
- Delivering the midnight notes at each recipient's local midnight.
- Public totals for press and the share page.
- A debug route to generate a recap for a test pair, with a chosen cutoff, so the recap can be checked before launch.

### Apps (Android, iPhone and iPad)

- The reel feed: vertical paging, the progress line, the buttons on the right, play-when-in-view.
- One native view for each layout in the table above.
- The replay player: segments, speed curves, captions that change with playback, built on the existing canvas renderers.
- Share and save: images for still cards, on-device videos for animated cards.
- Ways in: the canvas banner, the push notifications, the Settings entry.
- The drawing input inside card 14.

### Web

- The `mulberry.my/doodles` page that share cards link to.

### Design

- A Squee illustration for each couple type, plus the cover and the share-card frames.
- The motion for each layout.
- All wording, in Mulberry's voice and on every platform at once.

## 15. Scope for 2026

There are about seven weeks before launch in the last week of December, alongside the iOS launch. A realistic first version:

- **Ship:** cards 1, 2, 4, 7, 12, 13 and 14, with the share cards, the ways in, and "Your first chapter".
- **Later:** cards 3, 5, 6, 8, 9, 10 and 11.

The full card list is the long-term vision; the first version is a subset of it.

## 16. Measuring success

Track these with PostHog events named `mulberry_recap_*`:

- Recap opened, and from where (banner, push, Settings).
- The deepest card each person reaches.
- Each share, with the card and the format (image or video).
- Guess answered, right or wrong.
- Poster unlocked (both partners reached it).
- Midnight note left, and delivered.
- Time-lapse saved or exported.

What success looks like:

- **Reach:** most eligible pairs open the recap, and in most of them *both* partners do.
- **Sharing:** a meaningful share of people who open it share at least one card.
- **Reactivation:** lapsed pairs come back and draw again in January.
- **Retention:** more pairs where both partners are active in January than in a normal month.
- **Growth:** new installs and new pairs around launch, especially from `mulberry.my/doodles`.

Set number targets once we know how many pairs are eligible.

## 17. Privacy and safety

- The recap uses only data Mulberry already keeps to run the app. The privacy policy was updated on 4 November 2026 to cover the reaction log, time zones, and showing pairs summaries of their year.
- **Before publishing public totals,** add a line to the privacy policy about anonymous, combined statistics. Change it in all three copies, which the website also reads.
- No card quotes typed text.
- Nothing is shared without a tap. On the poster, couples can hide the canvas.
- No recap includes data from an earlier partner, and nobody who is unpaired gets one.
- Gentler wording for pairs who have gone quiet.

## 18. Risks

| Risk | What we do about it |
| --- | --- |
| Thin data for new pairs (Android launched in April, iOS is launching now) | "Your first chapter", and cards that disappear when their data is thin |
| A breakup between the cutoff and launch | Check that the pair still exists when the recap is opened, not only at generation |
| Large replay data on slow connections | The compact replay stream, loaded per card, with today's canvas shown while it loads |
| Reaction counts that look low in 2026 | Show them only above a threshold, with "since November" wording |
| Too much to build before the last week of December | The smaller 2026 scope |
| A late-December launch leaves only a few days to leave a midnight note | Card 14 is the last card, so everyone who finishes the feed reaches it. It disappears at midnight on 1 January |
| A pack version deleted, breaking old canvases | Hide packs instead of deleting them until the recap has run |
| Feeling like surveillance | Roles not scores, no quoted text, wording that feels warm rather than analytical |

## 19. Open decisions

- **Couple types.** The final list of titles and roles, and the thresholds for each.
- **How the midnight note is delivered.** The experience is decided (see card 14). The proposed way to deliver it is to add it to the shared canvas at the recipient's midnight (see [System design](#12-system-design)).

### Decided (4 November 2026)

- **Public name:** *Our Year in Doodles*.
- **Unpairing:** keeps deleting everything. We don't hold data for a possible re-pair.
- **Mac:** the Mac app doesn't show the recap.
- **Midnight note:** the last card of the feed. Whatever a partner draws there is sent to the other at midnight on 1 January. There's no separate "draw the first doodle" card after it.
- **Launch:** the last week of December, with the cutoff around 20 December.

## 20. Beyond 2026

The same system should run:

- **Each year's recap,** with new cards added over time.
- **A Valentine's recap** of the pair's Valentine's week.
- **Anniversary recaps,** using the anniversary date we already store.
- **Small seasonal recaps,** such as a month in review, if they prove popular.

Each one is new data and wording on existing layouts, sent from the server, without an app release.
