---
version: 1
slug: "sources-appshell-menubarlockscreen-swift-fd95e6e5"
primary_target: "apps/macos/Sources/AppShell/MenuBarLockScreen.swift"
related_targets: []
---

# Menu bar lock screen

Mode: Operate (glanced at between other work), with the artifact leading.

## Direction contract

THESIS: The menu bar icon opens the partner's view, not a command list: a small stylised iPhone lock screen showing the shared drawing over a wallpaper under the time. It refuses the category default of a menu or a dashboard popover.

OWN-WORLD: A black-bezelled phone at 9:19.5 with continuous corners and a Dynamic Island; the iPhone sign-in wallpaper (mulberry hills, Squee). As the iPhone's lock screen: a status bar (Mulberry as the carrier, the time once unlocked; the Mac's real connection (Wi-Fi, Ethernet or offline) and its battery, green with a bolt while charging, hidden on a Mac without one; no cellular bars), the date short and white ("Fri 30 Oct"), and the time large in neutral Liquid Glass shaped to rounded bold numerals, moulded with a lit upper rim, a shaded lower one and a soft cast shadow. The drawing sits as the partner's iPhone lays it: an iPhone drawing area's proportions fitted inside the screen and centred. The foot is a dark material blur rising from the bottom edge holding Portal-style Liquid Glass buttons with white glyphs.

STORY: You see what your partner sees on their phone right now, and in one click you send a reaction, show or hide the drawings on your desktop, or start drawing.

FIRST VIEWPORT: Island at top; date and an 86 pt condensed heavy clock; the drawing over the wallpaper; low in the foot a glass row of three: heart (unfolds the reactions' rail above the row), the drawings-on-desktop toggle larger in the middle (a custom display icon with a hand-drawn squiggle: turning on draws the squiggle in; off un-draws it and slashes the dimmed display; the glass is the same as its neighbours), Quick Draw; under it Open Mulberry small at bottom left, Settings small at bottom right, the home indicator between. Signed out or unpaired, the row becomes one Sign In or Invite Your Partner button. Behind it, a home screen: swiping up from the home bar (drag, two fingers on the trackpad, or Up arrow) lifts the lock screen's layer off a still wallpaper and drawing, shared by both screens, like a pane of glass: its dark foot and controls let go first, the clock rises and thins, with a rubber band and a spring past the middle, while the home screen sharpens and steps forward; on it, a partner widget (their photo filling it inside a thin rim of glass, their name and when the canvas last changed in white over a dark gradient at its foot) and a streak widget (days and this week at left, the large bolt standing its full height at right, running out past its edge), then four apps with names (Mulberry's own icon, Quick Draw, Reactions, Settings), no dock; swipe down, Down arrow, Esc or the island locks again, and every open starts locked. Hints, never ambient motion: clicking the lock screen's empty glass hops it up 14 pt and springs it back, as iOS does on a tap; the home bar widens, brightens and shows the open hand under the pointer; the very first open plays the hop once. Reduce Motion keeps only the hover.

FORM: Pinned by the user (lock screen mockup with Portal's glass controls), user pin rather than a dealt roll. Signature interaction: reactions arriving while it is open play over the mini lock screen while the island widens to name the sender. Motion: opens from the menu bar with an exponential scale and blur settle; the rail springs out of the heart; closes on Esc (folding the rail first), a click elsewhere, the icon, or losing the keyboard. Keys: 1 to 6 reactions, Space desktop drawings, D Quick Draw, O Mulberry, Command-Comma Settings. Right-click keeps a native menu (Open, Quick Draw, Settings, Quit).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
