import AppKit
import SwiftUI

@MainActor
public final class MulberryApplicationDelegate: NSObject, NSApplicationDelegate {
    private var statusItem: NSStatusItem?
    private var mainWindowController: MainWindowController?

    public override init() {
        super.init()
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        installStatusItem()
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        item.button?.title = "Mulberry"
        item.button?.toolTip = "Mulberry"

        let menu = NSMenu()
        menu.addItem(disabledItem("Not paired"))
        menu.addItem(disabledItem("Offline"))
        menu.addItem(.separator())
        menu.addItem(menuItem("Open Mulberry", action: #selector(openMulberry)))
        menu.addItem(quickDrawMenuItem())
        menu.addItem(menuItem("Send Heart", action: #selector(sendHeart)))
        menu.addItem(.separator())
        menu.addItem(disabledItem("Streak: --"))
        menu.addItem(overlayMenu())
        menu.addItem(.separator())
        menu.addItem(menuItem("Settings...", action: #selector(openSettings)))
        menu.addItem(menuItem("Quit Mulberry", action: #selector(quitMulberry)))

        item.menu = menu
        statusItem = item
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
        submenu.addItem(disabledItem("Show Overlay"))
        submenu.addItem(disabledItem("Choose Display"))
        submenu.addItem(disabledItem("Reset Position"))
        item.submenu = submenu
        return item
    }

    @objc private func openMulberry() {
        showMainWindow()
    }

    @objc private func quickDraw() {
        showMainWindow()
    }

    @objc private func sendHeart() {
        showMainWindow()
    }

    @objc private func openSettings() {
        showMainWindow()
    }

    @objc private func quitMulberry() {
        NSApp.terminate(nil)
    }

    private func showMainWindow() {
        if mainWindowController == nil {
            mainWindowController = MainWindowController()
        }

        NSApp.activate(ignoringOtherApps: true)
        mainWindowController?.showWindow(nil)
        mainWindowController?.window?.makeKeyAndOrderFront(nil)
    }
}

@MainActor
final class MainWindowController: NSWindowController {
    init() {
        let view = MainWindowView()
        let hostingController = NSHostingController(rootView: view)
        let window = NSWindow(contentViewController: hostingController)
        window.title = "Mulberry"
        window.setContentSize(NSSize(width: 920, height: 640))
        window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
        window.center()
        window.isReleasedWhenClosed = false
        super.init(window: window)
    }

    required init?(coder: NSCoder) {
        nil
    }
}

private struct MainWindowView: View {
    var body: some View {
        VStack(spacing: 12) {
            Text("Mulberry")
                .font(.system(size: 32, weight: .semibold, design: .rounded))
            Text("macOS app foundation")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
        .frame(minWidth: 720, minHeight: 480)
    }
}
