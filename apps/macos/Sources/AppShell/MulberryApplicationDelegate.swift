import AppKit
import Auth
import Combine
import Networking
import Overlay
import Persistence
import QuickDraw
import SwiftUI

@MainActor
public final class MulberryApplicationDelegate: NSObject, NSApplicationDelegate {
    private let configuration = MacAppConfiguration.current
    private lazy var database: MulberryDatabase = {
        do {
            return try MulberryDatabase()
        } catch {
            fatalError("Unable to open Mulberry local database: \(error)")
        }
    }()
    private let router = AppRouter()
    private lazy var authController = AuthSessionController(
        configuration: configuration,
        presentationAnchorProvider: { [weak self] in
            self?.mainWindowController?.window ?? NSApp.keyWindow ?? NSApp.windows.first
        }
    )
    private lazy var appStateController = AppStateController(
        authController: authController,
        database: database,
        configuration: configuration
    )
    private let overlayController = OverlayController()
    private var quickDrawController: QuickDrawController?
    private var overlayStateCancellable: AnyCancellable?
    private var authStateCancellable: AnyCancellable?
    private var appStateCancellable: AnyCancellable?
    private var statusItem: NSStatusItem?
    private var mainWindowController: MainWindowController?

    public override init() {
        super.init()
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        installApplicationIcon()
        installMainMenu()
        // TODO: When Mulberry is packaged and launched at login, add OS-level
        // duplicate-launch handling so a second app invocation activates the
        // existing process instead of allowing parallel app instances.
        let quickDrawController = QuickDrawController(overlayController: overlayController)
        self.quickDrawController = quickDrawController
        quickDrawController.start()
        appStateController.restoreCachedBootstrap()
        authController.restoreSessionOnLaunch()
        overlayStateCancellable = overlayController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                self?.refreshStatusMenu()
            }
        }
        authStateCancellable = authController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                if let self {
                    self.appStateController.acceptAuthState(
                        self.authController.state,
                        overlayController: self.overlayController
                    )
                }
                self?.syncRouteWithAuthState()
                self?.refreshStatusMenu()
            }
        }
        appStateCancellable = appStateController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                self?.refreshStatusMenu()
            }
        }
        installStatusItem()
    }

    private func installApplicationIcon() {
        guard let image = loadApplicationIconImage() else {
            return
        }
        NSApp.applicationIconImage = image
    }

    private func loadApplicationIconImage() -> NSImage? {
        if let image = NSImage(named: "AppIcon") {
            return image
        }
        if let url = Bundle.main.url(forResource: "AppIcon", withExtension: "icns") {
            return NSImage(contentsOf: url)
        }
        let developmentURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("Resources/AppIcon.icns")
        return NSImage(contentsOf: developmentURL)
    }

    private func installMainMenu() {
        let mainMenu = NSMenu(title: "Main Menu")

        let appMenuItem = NSMenuItem()
        let appMenu = NSMenu(title: "Mulberry")
        appMenu.addItem(NSMenuItem(
            title: "About Mulberry",
            action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)),
            keyEquivalent: ""
        ))
        appMenu.addItem(.separator())
        let settingsItem = NSMenuItem(title: "Settings...", action: #selector(openSettings), keyEquivalent: ",")
        settingsItem.target = self
        appMenu.addItem(settingsItem)
        appMenu.addItem(.separator())
        let quitItem = NSMenuItem(title: "Quit Mulberry", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        quitItem.target = NSApp
        appMenu.addItem(quitItem)
        appMenuItem.submenu = appMenu
        mainMenu.addItem(appMenuItem)

        let windowMenuItem = NSMenuItem()
        let windowMenu = NSMenu(title: "Window")
        windowMenu.addItem(NSMenuItem(
            title: "Minimize",
            action: #selector(NSWindow.performMiniaturize(_:)),
            keyEquivalent: "m"
        ))
        windowMenu.addItem(NSMenuItem(
            title: "Close",
            action: #selector(NSWindow.performClose(_:)),
            keyEquivalent: "w"
        ))
        windowMenuItem.submenu = windowMenu
        mainMenu.addItem(windowMenuItem)
        NSApp.windowsMenu = windowMenu

        NSApp.mainMenu = mainMenu
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    public func applicationWillTerminate(_ notification: Notification) {
        quickDrawController?.stop()
    }

    private func installStatusItem() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        if let image = loadStatusBarImage() {
            image.isTemplate = true
            image.size = NSSize(width: 18, height: 18)
            item.button?.image = image
            item.button?.imagePosition = .imageOnly
        } else {
            item.button?.title = "Mulberry"
        }
        item.button?.toolTip = "Mulberry"
        statusItem = item
        refreshStatusMenu()
    }

    private func loadStatusBarImage() -> NSImage? {
        if let image = NSImage(named: "MenuBarIconTemplate") {
            return image
        }
        if let url = Bundle.main.url(forResource: "MenuBarIconTemplate", withExtension: "png") {
            return NSImage(contentsOf: url)
        }
        let developmentURL = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("Resources/MenuBarIconTemplate.png")
        return NSImage(contentsOf: developmentURL)
    }

    private func refreshStatusMenu() {
        let menu = NSMenu()
        let bootstrap = appStateController.loadState.bootstrap
        menu.addItem(disabledItem(bootstrap?.partnerTitle ?? authController.state.statusTitle))
        menu.addItem(disabledItem(authController.state.statusDetail))
        menu.addItem(.separator())
        menu.addItem(menuItem("Open Mulberry", action: #selector(openMulberry)))
        menu.addItem(quickDrawMenuItem())
        menu.addItem(disabledItem("Send Heart"))
        menu.addItem(.separator())
        menu.addItem(menuItem(bootstrap?.streakTitle ?? "Streak: --", action: #selector(openStreak)))
        menu.addItem(overlayMenu())
        menu.addItem(.separator())
        menu.addItem(menuItem("Settings...", action: #selector(openSettings), keyEquivalent: ","))
        menu.addItem(menuItem("Quit Mulberry", action: #selector(quitMulberry), keyEquivalent: "q"))

        statusItem?.menu = menu
    }

    private func menuItem(
        _ title: String,
        action: Selector,
        keyEquivalent: String = "",
        modifierMask: NSEvent.ModifierFlags = [.command]
    ) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: keyEquivalent)
        item.keyEquivalentModifierMask = keyEquivalent.isEmpty ? [] : modifierMask
        item.target = self
        return item
    }

    private func quickDrawMenuItem() -> NSMenuItem {
        let title = overlayController.isQuickDrawActive ? "Exit Quick Draw" : "Quick Draw"
        return menuItem(
            title,
            action: #selector(quickDraw),
            keyEquivalent: "m",
            modifierMask: [.command, .control]
        )
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
        submenu.addItem(menuItem("Choose Display...", action: #selector(openOverlayDisplaySettings)))
        submenu.addItem(menuItem("Reset Position", action: #selector(resetOverlayPosition)))
        item.submenu = submenu
        return item
    }

    @objc private func openMulberry() {
        showMainWindow(route: defaultRouteForCurrentAuth())
    }

    @objc private func quickDraw() {
        if overlayController.isQuickDrawActive {
            quickDrawController?.exitQuickDraw()
        } else {
            quickDrawController?.enterQuickDraw()
        }
        refreshStatusMenu()
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

    @objc private func openOverlayDisplaySettings() {
        showMainWindow(route: .overlayStatus)
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
                authController: authController,
                appStateController: appStateController,
                overlayController: overlayController
            ) { [weak self] in
                self?.returnToAccessoryMode()
            }
        }

        router.open(normalizedRoute(route))
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        mainWindowController?.showWindow(nil)
        mainWindowController?.window?.makeKeyAndOrderFront(nil)
    }

    private func returnToAccessoryMode() {
        NSApp.setActivationPolicy(.accessory)
    }

    private func defaultRouteForCurrentAuth() -> AppRoute {
        switch authController.state {
        case .signedIn:
            .canvasHome
        case .refreshing:
            .bootstrap
        case .signedOut, .signingIn, .failed:
            .authLanding
        }
    }

    private func normalizedRoute(_ route: AppRoute) -> AppRoute {
        switch authController.state {
        case .signedIn, .refreshing:
            route
        case .signedOut, .signingIn, .failed:
            route == .settings ? .settings : .authLanding
        }
    }

    private func syncRouteWithAuthState() {
        guard mainWindowController?.window?.isVisible == true else {
            return
        }
        switch authController.state {
        case .signedIn:
            if router.selectedRoute == .bootstrap || router.selectedRoute == .authLanding {
                router.open(.canvasHome)
            }
        case .signedOut, .failed:
            if router.selectedRoute != .settings && router.selectedRoute != .authLanding {
                router.open(.authLanding)
            }
        case .signingIn, .refreshing:
            break
        }
    }
}

@MainActor
final class MainWindowController: NSWindowController, NSWindowDelegate {
    private let onWindowClosed: () -> Void

    init(
        router: AppRouter,
        authController: AuthSessionController,
        appStateController: AppStateController,
        overlayController: OverlayController,
        onWindowClosed: @escaping () -> Void
    ) {
        self.onWindowClosed = onWindowClosed
        let view = MainWindowView(
            router: router,
            authController: authController,
            appStateController: appStateController,
            overlayController: overlayController
        )
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
