import AppKit
import Overlay
import SwiftUI

@MainActor
public final class MulberryApplicationDelegate: NSObject, NSApplicationDelegate {
    private let router = AppRouter()
    private let overlayController = PlaceholderOverlayController()
    private var statusItem: NSStatusItem?
    private var mainWindowController: MainWindowController?

    public override init() {
        super.init()
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        // TODO: When Mulberry is packaged and launched at login, add OS-level
        // duplicate-launch handling so a second app invocation activates the
        // existing process instead of allowing parallel app instances.
        installStatusItem()
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.title = "Mulberry"
        item.button?.toolTip = "Mulberry"
        statusItem = item
        refreshStatusMenu()
    }

    private func refreshStatusMenu() {
        let menu = NSMenu()
        menu.addItem(disabledItem("Not paired"))
        menu.addItem(disabledItem("Offline"))
        menu.addItem(.separator())
        menu.addItem(menuItem("Open Mulberry", action: #selector(openMulberry)))
        menu.addItem(quickDrawMenuItem())
        menu.addItem(disabledItem("Send Heart"))
        menu.addItem(.separator())
        menu.addItem(menuItem("Streak: --", action: #selector(openStreak)))
        menu.addItem(overlayMenu())
        menu.addItem(.separator())
        menu.addItem(menuItem("Settings...", action: #selector(openSettings)))
        menu.addItem(menuItem("Quit Mulberry", action: #selector(quitMulberry)))

        statusItem?.menu = menu
    }

    private func menuItem(_ title: String, action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    private func quickDrawMenuItem() -> NSMenuItem {
        let item = NSMenuItem(title: "Quick Draw", action: #selector(quickDraw), keyEquivalent: "m")
        item.keyEquivalentModifierMask = [.command, .control]
        item.target = self
        item.isEnabled = false
        return item
    }

    private func disabledItem(_ title: String) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        return item
    }

    private func overlayMenu() -> NSMenuItem {
        let item = NSMenuItem(title: "Overlay", action: nil, keyEquivalent: "")
        let submenu = NSMenu(title: "Overlay")
        let toggleTitle = overlayController.isVisible ? "Hide Overlay" : "Show Overlay"
        submenu.addItem(menuItem(toggleTitle, action: #selector(toggleOverlay)))
        submenu.addItem(disabledItem("Choose Display"))
        submenu.addItem(menuItem("Reset Position", action: #selector(resetOverlayPosition)))
        item.submenu = submenu
        return item
    }

    @objc private func openMulberry() {
        showMainWindow(route: .canvasHome)
    }

    @objc private func quickDraw() {
        // Disabled until Phase 3, when Quick Draw becomes an overlay mode.
    }

    @objc private func sendHeart() {
        // Disabled until reactions and authenticated networking land.
    }

    @objc private func openStreak() {
        showMainWindow(route: .streak)
    }

    @objc private func toggleOverlay() {
        overlayController.toggle()
        refreshStatusMenu()
    }

    @objc private func resetOverlayPosition() {
        overlayController.resetPosition()
        refreshStatusMenu()
    }

    @objc private func openSettings() {
        showMainWindow(route: .settings)
    }

    @objc private func quitMulberry() {
        NSApp.terminate(nil)
    }

    private func showMainWindow(route: AppRoute) {
        if mainWindowController == nil {
            mainWindowController = MainWindowController(
                router: router,
                overlayController: overlayController
            ) { [weak self] in
                self?.returnToAccessoryMode()
            }
        }

        router.open(route)
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        mainWindowController?.showWindow(nil)
        mainWindowController?.window?.makeKeyAndOrderFront(nil)
    }

    private func returnToAccessoryMode() {
        NSApp.setActivationPolicy(.accessory)
    }
}

@MainActor
final class MainWindowController: NSWindowController, NSWindowDelegate {
    private let onWindowClosed: () -> Void

    init(
        router: AppRouter,
        overlayController: PlaceholderOverlayController,
        onWindowClosed: @escaping () -> Void
    ) {
        self.onWindowClosed = onWindowClosed
        let view = MainWindowView(router: router, overlayController: overlayController)
        let hostingController = NSHostingController(rootView: view)
        let window = NSWindow(contentViewController: hostingController)
        window.title = "Mulberry"
        window.setContentSize(NSSize(width: 920, height: 640))
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.center()
        window.isReleasedWhenClosed = false
        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) {
        nil
    }

    func windowWillClose(_ notification: Notification) {
        onWindowClosed()
    }
}
