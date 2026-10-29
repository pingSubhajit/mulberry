---
version: 1
slug: "sources-appshell-settingswindow-swift"
primary_target: "Sources/AppShell/SettingsWindow.swift"
related_targets: []
---

# Settings window

Mode: Operate. The visitor came to change how Mulberry behaves on this Mac, or to edit their profile and their partner's, then leave.

## Direction contract

THESIS: Raycast's settings, not System Settings' stock form: a searchable sidebar with the account at its head, and on the right cards of generous rows, each a 14 pt name, an optional line of explanation, and the control at the trailing edge. Stock grouped Form styling read as lifeless and was rejected.

OWN-WORLD: The canvas window's smoky frosted glass behind everything. Cards are a faint primary wash with a hairline and 14 pt corners; section titles sit outside the cards in 14 pt semibold. Tiles are the iPhone's tinted rounded squares with a top-lit edge and a small shadow. Sidebar selection is a soft neutral fill, never the system accent. Buttons are neutral filled pills; menus read as "Value ⌄". Mulberry red only on switches, the slider, prominent Save buttons, focus rings and the account photo ring. Poppins for names and the partner hero.

STORY: Account first (you), then General (this Mac, Quick Draw and the shared canvas style), Desktop, Partner (them), Advanced, About. The window has one fixed size. Search finds a pane by any setting it holds.

SIGNATURE: The Partner pane opens on the two of you under the streak popover's red glow: both photos with doodled hearts, days together and the streak.

STATES: Not signed in and not paired are calm empty states with one action. Partner lock disables the rows and says when they unlock. Launch at login needing approval and shortcut clashes are amber callouts inside the card.

RISK: Custom controls drifting from native behaviour. Switches, sliders, date fields, menus and file pickers stay system controls; only their dressing is custom.
