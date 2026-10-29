import AppKit
import Combine
import QuickDraw
import ServiceManagement

/// How Mulberry sits on this Mac: its menu bar icon, its Dock icon and Quick Draw's shortcut.
/// Kept per Mac, not per account.
@MainActor
final class MacPreferences: ObservableObject {
    private enum Key {
        static let showsMenuBarIcon = "mac.preferences.showsMenuBarIcon"
        static let showsInDock = "mac.preferences.showsInDock"
        static let quickDrawShortcut = "mac.preferences.quickDrawShortcut"
    }

    private let defaults: UserDefaults

    /// Off hides the icon; Mulberry still opens from the Dock, Spotlight or Quick Draw's shortcut.
    @Published var showsMenuBarIcon: Bool {
        didSet {
            defaults.set(showsMenuBarIcon, forKey: Key.showsMenuBarIcon)
            // Without the icon, the Dock is the way back in.
            if !showsMenuBarIcon { showsInDock = true }
        }
    }

    /// On keeps Mulberry in the Dock and the app switcher; off shows it there only while a
    /// window is open.
    @Published var showsInDock: Bool {
        didSet { defaults.set(showsInDock, forKey: Key.showsInDock) }
    }

    @Published var quickDrawShortcut: QuickDrawShortcut {
        didSet {
            if let data = try? JSONEncoder().encode(quickDrawShortcut) {
                defaults.set(data, forKey: Key.quickDrawShortcut)
            }
        }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let showsMenuBarIcon = defaults.object(forKey: Key.showsMenuBarIcon) as? Bool ?? true
        self.showsMenuBarIcon = showsMenuBarIcon
        self.showsInDock = !showsMenuBarIcon || defaults.bool(forKey: Key.showsInDock)
        self.quickDrawShortcut = defaults.data(forKey: Key.quickDrawShortcut)
            .flatMap { try? JSONDecoder().decode(QuickDrawShortcut.self, from: $0) } ?? .standard
    }
}

/// Opening Mulberry at login, through the system's own login items.
@MainActor
final class LaunchAtLogin: ObservableObject {
    @Published private(set) var status: SMAppService.Status = SMAppService.mainApp.status
    @Published private(set) var errorMessage: String?

    var isEnabled: Bool { status == .enabled || status == .requiresApproval }
    /// macOS asks the person to allow it in System Settings first.
    var needsApproval: Bool { status == .requiresApproval }

    func refresh() {
        status = SMAppService.mainApp.status
    }

    func setEnabled(_ enabled: Bool) {
        errorMessage = nil
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            errorMessage = error.localizedDescription
        }
        refresh()
    }

    func openLoginItems() {
        SMAppService.openSystemSettingsLoginItems()
    }
}
