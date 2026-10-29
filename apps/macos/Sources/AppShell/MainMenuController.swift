import AppKit
import Overlay
import ProductCore
import QuickDraw

/// The app's menu bar: Mulberry, File, Edit, Settings, Window and Help.
///
/// Undo, Redo and Clear Canvas travel the responder chain, so a text being typed takes them
/// first and the app delegate answers for the canvas otherwise. Cut, Copy, Paste and Select All
/// reach text fields the same way. The Settings menu mirrors the Settings window's switches and
/// is rebuilt each time it opens, so it is never stale.
@MainActor
final class MainMenuController: NSObject, NSMenuDelegate {
    struct Actions {
        let showCanvas: () -> Void
        let openSettings: (SettingsPane?) -> Void
        let toggleQuickDraw: () -> Void
        let sendReaction: (ReactionType) -> Void
        let exportDrawing: () -> Void
        let canExportDrawing: () -> Bool
        let canSendReactions: () -> Bool
        let setDryBrush: (Bool) -> Void
    }

    private let preferences: MacPreferences
    private let launchAtLogin: LaunchAtLogin
    private let overlayController: OverlayController
    private let appStateController: AppStateController
    private let actions: Actions

    private let fileMenu = NSMenu(title: "File")
    private let settingsMenu = NSMenu(title: "Settings")

    init(
        preferences: MacPreferences,
        launchAtLogin: LaunchAtLogin,
        overlayController: OverlayController,
        appStateController: AppStateController,
        actions: Actions
    ) {
        self.preferences = preferences
        self.launchAtLogin = launchAtLogin
        self.overlayController = overlayController
        self.appStateController = appStateController
        self.actions = actions
        super.init()
    }

    func install() {
        let mainMenu = NSMenu(title: "Main Menu")
        mainMenu.addItem(submenu(appMenu()))
        fileMenu.delegate = self
        mainMenu.addItem(submenu(fileMenu))
        mainMenu.addItem(submenu(editMenu()))
        settingsMenu.delegate = self
        mainMenu.addItem(submenu(settingsMenu))
        let windowMenu = windowMenu()
        mainMenu.addItem(submenu(windowMenu))
        let helpMenu = helpMenu()
        mainMenu.addItem(submenu(helpMenu))
        NSApp.mainMenu = mainMenu
        NSApp.windowsMenu = windowMenu
        NSApp.helpMenu = helpMenu
        rebuildFileMenu()
        rebuildSettingsMenu()
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        if menu === fileMenu { rebuildFileMenu() }
        if menu === settingsMenu { rebuildSettingsMenu() }
    }

    // MARK: Menus

    private func appMenu() -> NSMenu {
        let menu = NSMenu(title: "Mulberry")
        menu.addItem(item("About Mulberry") { [actions] in actions.openSettings(.about) })
        menu.addItem(.separator())
        menu.addItem(item("Settings…", key: ",") { [actions] in actions.openSettings(nil) })
        menu.addItem(.separator())
        let services = NSMenuItem(title: "Services", action: nil, keyEquivalent: "")
        let servicesMenu = NSMenu(title: "Services")
        services.submenu = servicesMenu
        NSApp.servicesMenu = servicesMenu
        menu.addItem(services)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Hide Mulberry", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h"))
        let hideOthers = NSMenuItem(title: "Hide Others", action: #selector(NSApplication.hideOtherApplications(_:)), keyEquivalent: "h")
        hideOthers.keyEquivalentModifierMask = [.command, .option]
        menu.addItem(hideOthers)
        menu.addItem(NSMenuItem(title: "Show All", action: #selector(NSApplication.unhideAllApplications(_:)), keyEquivalent: ""))
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Quit Mulberry", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q"))
        return menu
    }

    private func rebuildFileMenu() {
        fileMenu.removeAllItems()
        let shortcut = preferences.quickDrawShortcut
        let quickDraw = item(overlayController.isQuickDrawActive ? "Finish Quick Draw" : "Quick Draw", action: actions.toggleQuickDraw)
        // Shown for reference; the shortcut itself works from any app.
        quickDraw.keyEquivalent = shortcut.key
        quickDraw.keyEquivalentModifierMask = shortcut.modifiers
        fileMenu.addItem(quickDraw)

        let reactions = NSMenuItem(title: "Send Reaction", action: nil, keyEquivalent: "")
        let reactionsMenu = NSMenu(title: "Send Reaction")
        for reaction in ReactionType.allCases {
            reactionsMenu.addItem(item("\(reaction.emoji)  \(reaction.menuTitle)", enabled: actions.canSendReactions) { [actions] in
                actions.sendReaction(reaction)
            })
        }
        reactions.submenu = reactionsMenu
        fileMenu.addItem(reactions)
        fileMenu.addItem(.separator())

        fileMenu.addItem(item("Export Drawing…", key: "e", modifiers: [.command, .shift], enabled: actions.canExportDrawing, action: actions.exportDrawing))
        fileMenu.addItem(.separator())
        fileMenu.addItem(NSMenuItem(title: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w"))
    }

    private func editMenu() -> NSMenu {
        // AppKit adds Writing Tools, AutoFill, Dictation and Emoji & Symbols to a menu named Edit.
        let menu = NSMenu(title: "Edit")
        menu.addItem(NSMenuItem(title: "Undo", action: #selector(CanvasMenuActions.undo(_:)), keyEquivalent: "z"))
        let redo = NSMenuItem(title: "Redo", action: #selector(CanvasMenuActions.redo(_:)), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        menu.addItem(redo)
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x"))
        menu.addItem(NSMenuItem(title: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c"))
        menu.addItem(NSMenuItem(title: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v"))
        menu.addItem(NSMenuItem(title: "Delete", action: #selector(NSText.delete(_:)), keyEquivalent: ""))
        menu.addItem(NSMenuItem(title: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"))
        menu.addItem(.separator())
        let clear = NSMenuItem(title: "Clear Canvas…", action: #selector(CanvasMenuActions.clearCanvas(_:)), keyEquivalent: "\u{8}")
        clear.keyEquivalentModifierMask = [.command]
        menu.addItem(clear)
        menu.addItem(.separator())
        return menu
    }

    private func rebuildSettingsMenu() {
        settingsMenu.removeAllItems()
        let bootstrap = appStateController.loadState.bootstrap

        settingsMenu.addItem(toggle("Show on Desktop", on: overlayController.isVisible) { [overlayController] in
            overlayController.isVisible ? overlayController.hide() : overlayController.show()
        })

        let display = NSMenuItem(title: "Display", action: nil, keyEquivalent: "")
        let displayMenu = NSMenu(title: "Display")
        let selected = overlayController.selectedDisplayID ?? overlayController.displays.first?.id
        for screen in overlayController.displays {
            displayMenu.addItem(toggle(screen.name, on: screen.id == selected) { [overlayController] in
                overlayController.selectDisplay(id: screen.id)
            })
        }
        display.submenu = displayMenu
        settingsMenu.addItem(display)

        let visibility = NSMenuItem(title: "Visibility", action: nil, keyEquivalent: "")
        let visibilityMenu = NSMenu(title: "Visibility")
        let steps = [1.0, 0.75, 0.5, 0.25]
        let nearest = steps.min { abs($0 - overlayController.opacity) < abs($1 - overlayController.opacity) }
        for step in steps {
            visibilityMenu.addItem(toggle("\(Int(step * 100))%", on: step == nearest) { [overlayController] in
                overlayController.setOpacity(step)
            })
        }
        visibility.submenu = visibilityMenu
        settingsMenu.addItem(visibility)
        settingsMenu.addItem(item("Reset Position") { [overlayController] in overlayController.resetPosition() })
        settingsMenu.addItem(.separator())

        let dryBrush = bootstrap?.canvasStrokeRenderMode == "dry"
        settingsMenu.addItem(toggle("Dry Brush Strokes", on: dryBrush, enabled: { [appStateController] in
            appStateController.loadState.bootstrap?.isPaired == true
        }) { [actions] in actions.setDryBrush(!dryBrush) })
        settingsMenu.addItem(.separator())

        settingsMenu.addItem(toggle("Open at Login", on: launchAtLogin.isEnabled) { [launchAtLogin] in
            launchAtLogin.setEnabled(!launchAtLogin.isEnabled)
        })
        settingsMenu.addItem(toggle("Show in Menu Bar", on: preferences.showsMenuBarIcon) { [preferences] in
            preferences.showsMenuBarIcon.toggle()
        })
        // The Dock is the way back while the menu bar icon is hidden.
        settingsMenu.addItem(toggle("Show in Dock", on: preferences.showsInDock, enabled: { [preferences] in
            preferences.showsMenuBarIcon
        }) { [preferences] in preferences.showsInDock.toggle() })
        settingsMenu.addItem(.separator())
        settingsMenu.addItem(item("All Settings…") { [actions] in actions.openSettings(nil) })
    }

    private func windowMenu() -> NSMenu {
        let menu = NSMenu(title: "Window")
        menu.addItem(NSMenuItem(title: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m"))
        menu.addItem(.separator())
        menu.addItem(item("Canvas", key: "0", action: actions.showCanvas))
        menu.addItem(.separator())
        menu.addItem(NSMenuItem(title: "Bring All to Front", action: #selector(NSApplication.arrangeInFront(_:)), keyEquivalent: ""))
        return menu
    }

    private func helpMenu() -> NSMenu {
        let menu = NSMenu(title: "Help")
        menu.addItem(item("Mulberry Website") { NSWorkspace.shared.open(URL(string: "https://mulberry.my")!) })
        menu.addItem(item("Privacy Policy") { [actions] in actions.openSettings(.about) })
        return menu
    }

    // MARK: Items

    private func submenu(_ menu: NSMenu) -> NSMenuItem {
        let item = NSMenuItem(title: menu.title, action: nil, keyEquivalent: "")
        item.submenu = menu
        return item
    }

    private func item(
        _ title: String,
        key: String = "",
        modifiers: NSEvent.ModifierFlags = [.command],
        enabled: @escaping () -> Bool = { true },
        action: @escaping () -> Void
    ) -> NSMenuItem {
        let item = ClosureMenuItem(title: title, keyEquivalent: key, enabled: enabled, action: action)
        item.keyEquivalentModifierMask = key.isEmpty ? [] : modifiers
        return item
    }

    private func toggle(_ title: String, on: Bool, enabled: @escaping () -> Bool = { true }, action: @escaping () -> Void) -> NSMenuItem {
        let item = item(title, enabled: enabled, action: action)
        item.state = on ? .on : .off
        return item
    }
}

/// A menu item that runs a closure, enabled while its condition holds.
@MainActor
private final class ClosureMenuItem: NSMenuItem, NSMenuItemValidation {
    private let run: () -> Void
    private let isAvailable: () -> Bool

    init(title: String, keyEquivalent: String, enabled: @escaping () -> Bool, action: @escaping () -> Void) {
        run = action
        isAvailable = enabled
        super.init(title: title, action: #selector(runAction), keyEquivalent: keyEquivalent)
        target = self
    }

    @available(*, unavailable)
    required init(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func runAction() {
        run()
    }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        isAvailable()
    }
}

/// Actions that travel the responder chain from the Edit menu: text views answer Undo and Redo
/// themselves; the app delegate answers for the canvas.
@MainActor
@objc protocol CanvasMenuActions {
    func undo(_ sender: Any?)
    func redo(_ sender: Any?)
    func clearCanvas(_ sender: Any?)
}

extension ReactionType {
    /// The reaction's name in menus.
    var menuTitle: String {
        switch self {
        case .heart: "Love"
        case .hug: "Hug"
        case .kiss: "Kiss"
        case .smile: "Smile"
        case .laugh: "Laugh"
        case .sparkle: "Sparkles"
        }
    }
}
