import AppKit
import Auth
import Combine
import Networking
import Overlay
import Persistence
import QuickDraw
import SwiftUI
import Sync

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
    private lazy var syncController = CanvasSyncController(
        configuration: configuration,
        database: database,
        authorizer: makeAuthorizer()
    )
    private let overlayController = OverlayController()
    private var quickDrawController: QuickDrawController?
    private var overlayStateCancellable: AnyCancellable?
    private var authStateCancellable: AnyCancellable?
    private var appStateCancellable: AnyCancellable?
    private var syncStateCancellable: AnyCancellable?
    private var statusItem: NSStatusItem?
    private var mainWindowController: MainWindowController?
    private var lastOverlayPresenceReportSignature: OverlayPresenceReportSignature?

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
                guard let self else { return }
                self.updateSyncDemand()
                self.refreshStatusMenu()
                self.reportCurrentOverlayPresence()
            }
        }
        authStateCancellable = authController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                if let self {
                    self.appStateController.acceptAuthState(
                        self.authController.state,
                        overlayController: self.overlayController,
                        syncController: self.syncController
                    )
                }
                self?.syncRouteWithAuthState()
                self?.refreshStatusMenu()
            }
        }
        appStateCancellable = appStateController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.syncCurrentSession()
                self.updateSyncDemand()
                self.refreshStatusMenu()
            }
        }
        syncStateCancellable = syncController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.overlayController.updateCanvasState(
                    self.syncController.canvasState,
                    diagnostics: self.syncController.diagnostics
                )
                self.refreshStatusMenu()
                self.reportCurrentOverlayPresence()
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
        syncController.stop(reason: "terminate")
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
        menu.addItem(disabledItem(statusDetailLine(bootstrap: bootstrap)))
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
        updateSyncDemand()
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
        updateSyncDemand()
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
                syncController: syncController,
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
        updateSyncDemand()
    }

    private func returnToAccessoryMode() {
        NSApp.setActivationPolicy(.accessory)
        updateSyncDemand()
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

    private func makeAuthorizer() -> AuthenticatedRequestAuthorizer {
        AuthenticatedRequestAuthorizer(
            currentAccessToken: { [weak authController] in
                await MainActor.run {
                    authController?.currentAccessToken
                }
            },
            refreshAccessToken: { [weak authController] in
                guard let authController else {
                    throw APIError.missingToken
                }
                return try await authController.refreshForAuthenticatedRetry()
            }
        )
    }

    private func statusDetailLine(bootstrap: MacBootstrapState?) -> String {
        guard authController.state.isSignedIn, bootstrap?.isPaired == true else {
            return authController.state.statusDetail
        }
        let revision = syncController.status.lastAppliedServerRevision
        let latest = syncController.status.latestKnownServerRevision
        if let lastError = syncController.status.lastError, syncController.connectionState != .connected {
            return "\(syncController.connectionState.title): \(lastError)"
        }
        return "\(syncController.demand.title) · \(syncController.connectionState.title) · rev \(revision)/\(latest)"
    }

    private func syncCurrentSession() {
        guard case let .signedIn(session) = authController.state,
              let bootstrap = appStateController.loadState.bootstrap,
              bootstrap.isPaired,
              let pairSessionID = bootstrap.pairSessionID
        else {
            syncController.updateSession(userID: nil, pairSessionID: nil, shouldSync: false)
            lastOverlayPresenceReportSignature = nil
            return
        }
        syncController.updateSession(
            userID: session.userID,
            pairSessionID: pairSessionID,
            shouldSync: true
        )
        updateSyncDemand()
    }

    private func updateSyncDemand() {
        guard authController.state.isSignedIn,
              appStateController.loadState.bootstrap?.isPaired == true
        else {
            syncController.updateDemand(.idle)
            return
        }

        if mainWindowController?.window?.isVisible == true || overlayController.isQuickDrawActive {
            syncController.updateDemand(.foregroundWebSocket)
        } else if overlayController.isVisible {
            syncController.updateDemand(.overlayRecovery)
        } else {
            syncController.updateDemand(.idle)
        }
    }

    private func reportCurrentOverlayPresence() {
        guard let bootstrap = appStateController.loadState.bootstrap else { return }
        let signature = OverlayPresenceReportSignature(
            pairSessionID: bootstrap.pairSessionID,
            overlayVisible: overlayController.isVisible,
            selectedDisplayName: overlayController.selectedDisplayName,
            canSeeLatestDrawings: syncController.canReportOverlayCanSeeLatestDrawings(
                isOverlayVisible: overlayController.isVisible
            ),
            demand: syncController.demand,
            connectionState: syncController.connectionState,
            lastAppliedServerRevision: syncController.status.lastAppliedServerRevision,
            latestKnownServerRevision: syncController.status.latestKnownServerRevision,
            lastSuccessfulRecoveryAt: syncController.status.lastSuccessfulRecoveryAt
        )
        guard signature != lastOverlayPresenceReportSignature else { return }
        lastOverlayPresenceReportSignature = signature

        Task {
            await appStateController.reportOverlayPresenceIfNeeded(
                bootstrap: BootstrapDTO(
                    authStatus: bootstrap.authStatus,
                    onboardingCompleted: bootstrap.onboardingCompleted,
                    userId: bootstrap.userID,
                    userEmail: bootstrap.userEmail,
                    userDisplayName: bootstrap.userDisplayName,
                    partnerDisplayName: bootstrap.partnerDisplayName,
                    currentStreakDays: bootstrap.currentStreakDays,
                    pairingStatus: bootstrap.pairingStatus,
                    pairSessionId: bootstrap.pairSessionID
                ),
                overlayController: overlayController,
                syncController: syncController
            )
        }
    }
}

private struct OverlayPresenceReportSignature: Equatable {
    let pairSessionID: String?
    let overlayVisible: Bool
    let selectedDisplayName: String
    let canSeeLatestDrawings: Bool
    let demand: CanvasSyncDemand
    let connectionState: MacSyncConnectionState
    let lastAppliedServerRevision: Int64
    let latestKnownServerRevision: Int64
    let lastSuccessfulRecoveryAt: Date?
}

@MainActor
final class MainWindowController: NSWindowController, NSWindowDelegate {
    private let onWindowClosed: () -> Void

    init(
        router: AppRouter,
        authController: AuthSessionController,
        appStateController: AppStateController,
        syncController: CanvasSyncController,
        overlayController: OverlayController,
        onWindowClosed: @escaping () -> Void
    ) {
        self.onWindowClosed = onWindowClosed
        let view = MainWindowView(
            router: router,
            authController: authController,
            appStateController: appStateController,
            syncController: syncController,
            overlayController: overlayController
        )
        let hostingController = NSHostingController(rootView: view)
        let window = NSWindow(contentViewController: hostingController)
        window.title = "Mulberry"
        let contentSize = NSSize(width: 920, height: 640)
        window.setContentSize(contentSize)
        window.contentAspectRatio = contentSize
        window.minSize = NSSize(width: 760, height: 529)
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
        DispatchQueue.main.async { [onWindowClosed] in
            onWindowClosed()
        }
    }
}
