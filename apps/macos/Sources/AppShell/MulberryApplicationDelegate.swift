import AppKit
import Auth
import CanvasRendering
import Combine
import Networking
import Overlay
import Persistence
import ProductCore
import QuickDraw
import SwiftUI
import StickerAssets
import Stickers
import Sync

@MainActor
public final class MulberryApplicationDelegate: NSObject, NSApplicationDelegate {
    private let configuration = MacAppConfiguration.current
    private let store = MacLocalStore()
    private lazy var api: BackendClient = {
        do {
            return try MacBackend.makeClient(configuration: configuration)
        } catch {
            fatalError("Unable to open Mulberry's saved session: \(error)")
        }
    }()
    private let router = AppRouter()
    private lazy var authController = AuthSessionController(
        configuration: configuration,
        api: api,
        store: store,
        presentationAnchorProvider: { [weak self] in
            self?.mainWindowController?.window ?? NSApp.keyWindow ?? NSApp.windows.first
        }
    )
    private lazy var appStateController = AppStateController(
        authController: authController,
        store: store
    )
    private lazy var syncController = CanvasSyncController(
        api: api,
        store: DrawingStore(directory: MacLocalStore.drawingsDirectory)
    )
    /// The window's canvas and Quick Draw's each keep their own tools and undo history.
    private lazy var windowEditorModel = CanvasEditorModel { [weak self] in self?.syncController.submit($0) }
    private lazy var quickDrawEditorModel = CanvasEditorModel { [weak self] in self?.syncController.submit($0) }
    private lazy var stickerLoader: StickerImageLoader = {
        let client = api
        return StickerImageLoader(api: { client })
    }()
    private let overlayController = OverlayController()
    private var quickDrawController: QuickDrawController?
    private var overlayStateCancellable: AnyCancellable?
    private var authStateCancellable: AnyCancellable?
    private var appStateCancellable: AnyCancellable?
    private var syncStateCancellable: AnyCancellable?
    private var stickerCancellable: AnyCancellable?
    private var syncWasReachable = true
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
        installQuickDraw()
        quickDrawController.start()
        syncController.onPairingChanged = { [weak self] in
            guard let self else { return }
            Task {
                await self.appStateController.refreshBootstrapAndReportOverlay(
                    overlayController: self.overlayController,
                    syncController: self.syncController
                )
            }
        }
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
                self.refreshOverlayCanvas()
                self.refreshStatusMenu()
            }
        }
        syncStateCancellable = syncController.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.loadStickerArtwork()
                self.refreshOverlayCanvas()
                self.refreshStatusMenu()
                self.reportCurrentOverlayPresence()
            }
        }
        stickerCancellable = stickerLoader.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                self?.refreshOverlayCanvas()
            }
        }
        installStatusItem()
    }

    /// Quick Draw edits the shared canvas on the overlay with the window's editor, its tools in a
    /// panel beside the overlay.
    private func installQuickDraw() {
        overlayController.makeQuickDrawCanvas = { [unowned self] in
            NSHostingView(rootView: FullAppCanvasEditor(
                authController: authController,
                appStateController: appStateController,
                syncController: syncController,
                stickerLoader: stickerLoader,
                model: quickDrawEditorModel,
                layout: .quickDraw
            ))
        }
        overlayController.makeQuickDrawPalette = { [unowned self] side in
            NSHostingView(rootView: QuickDrawPalette(
                model: quickDrawEditorModel,
                syncController: syncController,
                side: side,
                dragOverlay: { [unowned self] mouseDown, dropped in
                    overlayController.dragQuickDraw(with: mouseDown, dropped: dropped)
                },
                done: { [unowned self] in quickDrawController?.exitQuickDraw() }
            ))
        }
        overlayController.quickDrawEscape = { [unowned self] in quickDrawEditorModel.handleEscape() }
        overlayController.quickDrawWillExit = { [unowned self] in quickDrawEditorModel.finishEditing() }
    }

    /// The overlay draws the shared canvas in the pair's stroke style.
    private func refreshOverlayCanvas() {
        overlayController.updateCanvasState(
            syncController.drawing,
            strokeRenderMode: CanvasStrokeRenderMode(
                rawBackendValue: appStateController.loadState.bootstrap?.canvasStrokeRenderMode
            ),
            stickerImages: stickerLoader.images
        )
    }

    private func loadStickerArtwork() {
        // Artwork that failed while offline is tried again once sync reaches the server.
        let reachable = syncController.status.lastError == nil
        if reachable && !syncWasReachable {
            stickerLoader.retryUnavailable()
        }
        syncWasReachable = reachable
        let user = authController.state.isSignedIn ? appStateController.loadState.bootstrap?.userId : nil
        stickerLoader.load(for: syncController.drawing, user: user)
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

    /// Opening Mulberry again from Finder, Spotlight or the Dock shows its window.
    public func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { openMulberry() }
        return true
    }

    public func applicationWillTerminate(_ notification: Notification) {
        syncController.stop()
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
                overlayController: overlayController,
                stickerLoader: stickerLoader,
                editorModel: windowEditorModel
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

    private func statusDetailLine(bootstrap: BootstrapState?) -> String {
        guard authController.state.isSignedIn, bootstrap?.isPaired == true else {
            return authController.state.statusDetail
        }
        let revision = syncController.status.lastAppliedServerRevision
        if let lastError = syncController.status.lastError, syncController.connectionState != .connected {
            return "\(syncController.connectionState.title): \(lastError)"
        }
        return "\(syncController.demand.title) · \(syncController.connectionState.title) · rev \(revision)"
    }

    private func syncCurrentSession() {
        guard case let .signedIn(session) = authController.state,
              let bootstrap = appStateController.loadState.bootstrap,
              bootstrap.isPaired,
              let pairSessionID = bootstrap.pairSessionId
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
            pairSessionID: bootstrap.pairSessionId,
            overlayVisible: overlayController.isVisible,
            selectedDisplayName: overlayController.selectedDisplayName,
            canSeeLatestDrawings: syncController.canReportOverlayCanSeeLatestDrawings(
                isOverlayVisible: overlayController.isVisible
            ),
            demand: syncController.demand,
            connectionState: syncController.connectionState,
            lastAppliedServerRevision: syncController.status.lastAppliedServerRevision,
            lastSuccessfulRecoveryAt: syncController.status.lastSuccessfulRecoveryAt
        )
        guard signature != lastOverlayPresenceReportSignature else { return }
        lastOverlayPresenceReportSignature = signature

        Task {
            await appStateController.reportOverlayPresenceIfNeeded(
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
        stickerLoader: StickerImageLoader,
        editorModel: CanvasEditorModel,
        onWindowClosed: @escaping () -> Void
    ) {
        self.onWindowClosed = onWindowClosed
        let view = MainWindowView(
            router: router,
            authController: authController,
            appStateController: appStateController,
            syncController: syncController,
            overlayController: overlayController,
            stickerLoader: stickerLoader,
            editorModel: editorModel
        )
        let hostingController = NSHostingController(rootView: view)
        // The window owns its size; SwiftUI's toolbar items appear in the window's toolbar.
        hostingController.sizingOptions = []
        hostingController.sceneBridgingOptions = [.toolbars]
        let window = NSWindow(
            contentRect: NSRect(origin: .zero, size: Self.defaultContentSize()),
            styleMask: [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView],
            backing: .buffered,
            defer: false
        )
        window.contentViewController = hostingController
        window.title = "Mulberry"
        window.titleVisibility = .hidden
        // Not titlebarAppearsTransparent: with it, macOS makes the window jump each time it is
        // dragged onto another display. The macOS 26 title bar is glass over the content anyway.
        // Clear, so the frosted glass behind the canvas shows the desktop softly.
        window.isOpaque = false
        window.backgroundColor = .clear
        // The canvas's own portrait shape, at any size.
        window.contentAspectRatio = Self.canvasAspect
        window.contentMinSize = Self.minimumSize
        window.setContentSize(Self.defaultContentSize())
        window.center()
        window.isReleasedWhenClosed = false
        // A portrait panel has no full-screen layout: no green button, and the menu item and
        // Control-Command-F leave the window as it is.
        window.collectionBehavior.insert(.fullScreenNone)
        window.standardWindowButton(.zoomButton)?.isHidden = true
        super.init(window: window)
        window.delegate = self
    }

    required init?(coder: NSCoder) {
        nil
    }

    /// 10:20: a little wider than the phones' 9:20 canvas, which sits centred inside.
    private static let canvasAspect = NSSize(width: 10, height: 20)

    /// Small enough for a 13-inch laptop, large enough for the toolbar's full row.
    private static let minimumSize = NSSize(width: 380, height: 760)

    /// Enforces the minimum here too: the hosted SwiftUI view can reset the window's
    /// minimum, and the aspect ratio alone lets the window shrink without limit.
    func windowWillResize(_ sender: NSWindow, to frameSize: NSSize) -> NSSize {
        let width = max(Self.minimumSize.width, frameSize.width)
        // With a full-size content view the frame and the content are the same size.
        return NSSize(width: width, height: width * Self.canvasAspect.height / Self.canvasAspect.width)
    }

    /// As tall as the screen comfortably allows, never smaller than the minimum.
    private static func defaultContentSize() -> NSSize {
        let available = NSScreen.main?.visibleFrame.height ?? 900
        let height = max(800, min(980, available - 40))
        return NSSize(width: height * canvasAspect.width / canvasAspect.height, height: height)
    }

    func windowWillClose(_ notification: Notification) {
        DispatchQueue.main.async { [onWindowClosed] in
            onWindowClosed()
        }
    }
}
