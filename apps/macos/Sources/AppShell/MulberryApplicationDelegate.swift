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
public final class MulberryApplicationDelegate: NSObject, NSApplicationDelegate, NSMenuItemValidation {
    private let configuration = MacAppConfiguration.current
    private let store = MacLocalStore()
    private lazy var api: BackendClient = {
        do {
            return try MacBackend.makeClient(configuration: configuration)
        } catch {
            fatalError("Unable to open Mulberry's saved session: \(error)")
        }
    }()
    private let windowState = MainWindowState()
    private var mainMenuController: MainMenuController?
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
    /// The right click's menu; a left click opens the lock screen.
    private var statusMenu = NSMenu()
    private let lockScreenModel = LockScreenModel()
    private var lockScreenDrawingKey: LockScreenDrawingKey?
    private lazy var lockScreen = LockScreenPanelController(
        model: lockScreenModel,
        reactions: reactions,
        statusButton: { [unowned self] in statusItem?.button }
    )
    private var mainWindowController: MainWindowController?
    private var settingsWindowController: SettingsWindowController?
    private let preferences = MacPreferences()
    private let launchAtLogin = LaunchAtLogin()
    private let streaks = StreakModel()
    private lazy var reactions = ReactionsModel(api: { [unowned self] in api }, deviceID: store.installationID)
    private var reactionIsland: ReactionIslandController?
    private var reactionPolling: Task<Void, Never>?
    private var surfaceObservers: [NSObjectProtocol] = []
    private lazy var accountSettings = AccountSettings(api: { [unowned self] in api }) { [weak self] bootstrap in
        guard let self else { return }
        appStateController.apply(bootstrap, syncController: syncController)
    }
    private var preferencesCancellable: AnyCancellable?
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
        quickDrawController.start(shortcut: preferences.quickDrawShortcut)
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
                self.updateReactionSurface()
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
                self.bindReactions()
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
        installReactions()
        preferencesCancellable = preferences.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async { self?.applyPreferences() }
        }
        applyPreferences()
    }

    /// The menu bar icon, the Dock icon and Quick Draw's shortcut follow Settings.
    private func applyPreferences() {
        statusItem?.isVisible = preferences.showsMenuBarIcon
        quickDrawController?.setShortcut(preferences.quickDrawShortcut)
        updateActivationPolicy()
        refreshStatusMenu()
    }

    /// In the Dock while a window is open, or always when Settings says so.
    private func updateActivationPolicy() {
        let windowOpen = mainWindowController?.window?.isVisible == true || settingsWindowController?.isVisible == true
        let policy: NSApplication.ActivationPolicy = preferences.showsInDock || windowOpen ? .regular : .accessory
        if NSApp.activationPolicy() != policy { NSApp.setActivationPolicy(policy) }
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
                reactions: reactions,
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
            stickerImages: stickerLoader.images,
            stickerTimeline: stickerLoader.timeline
        )
        refreshLockScreen()
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
        let controller = MainMenuController(
            preferences: preferences,
            launchAtLogin: launchAtLogin,
            overlayController: overlayController,
            appStateController: appStateController,
            actions: MainMenuController.Actions(
                showCanvas: { [unowned self] in openMulberry() },
                openSettings: { [unowned self] pane in showSettings(pane) },
                toggleQuickDraw: { [unowned self] in quickDraw() },
                sendReaction: { [unowned self] reaction in send(reaction) },
                exportDrawing: { [unowned self] in exportDrawing() },
                canExportDrawing: { [unowned self] in !canvasIsEmpty },
                canSendReactions: { [unowned self] in reactions.canSend },
                setDryBrush: { [unowned self] enabled in Task { _ = await accountSettings.setDryBrush(enabled) } },
                sendFeedback: { [unowned self] in showSettings(.about, subpage: .feedback) },
                canSendFeedback: { [unowned self] in authController.state.isSignedIn }
            )
        )
        controller.install()
        mainMenuController = controller
    }

    // MARK: Canvas commands from the Edit menu

    /// The canvas the Edit menu acts on: Quick Draw's while it is open, else the window's while
    /// it is the key window.
    private var activeCanvasModel: CanvasEditorModel? {
        if overlayController.isQuickDrawActive { return quickDrawEditorModel }
        if let window = mainWindowController?.window, window.isKeyWindow { return windowEditorModel }
        return nil
    }

    private var canvasIsEmpty: Bool {
        syncController.drawing.strokes.isEmpty && syncController.drawing.elements.isEmpty
    }

    /// Text being typed answers Undo and Command-Delete itself, before the chain reaches here.
    private var typingInText: Bool {
        NSApp.keyWindow?.firstResponder is NSText
    }

    @objc func undo(_ sender: Any?) {
        activeCanvasModel?.undo()
    }

    @objc func redo(_ sender: Any?) {
        activeCanvasModel?.redo()
    }

    @objc func clearCanvas(_ sender: Any?) {
        activeCanvasModel?.engine.requestClearCanvas()
    }

    public func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        guard let model = activeCanvasModel, !model.isTyping else {
            return ![#selector(undo(_:)), #selector(redo(_:)), #selector(clearCanvas(_:))].contains(menuItem.action)
        }
        switch menuItem.action {
        case #selector(undo(_:)): return model.engine.canUndo
        case #selector(redo(_:)): return model.engine.canRedo
        // Disabled, Command-Delete passes on to a text field to delete to the line's start.
        case #selector(clearCanvas(_:)): return !typingInText && !canvasIsEmpty
        default: return true
        }
    }

    /// Draws the shared canvas at the phones' 9:20 wallpaper size and saves it as a PNG.
    private func exportDrawing() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.png]
        panel.nameFieldStringValue = "Mulberry Drawing \(Date().formatted(.iso8601.year().month().day())).png"
        panel.canCreateDirectories = true
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let input = CanvasRenderInput(
            state: syncController.drawing,
            viewport: CGRect(x: 0, y: 0, width: 1080, height: 2400),
            strokeRenderMode: CanvasStrokeRenderMode(rawBackendValue: appStateController.loadState.bootstrap?.canvasStrokeRenderMode),
            stickerImages: stickerLoader.images
        )
        do {
            guard let png = CanvasOffscreenRenderer.renderPNG(input: input)?.data else { throw CocoaError(.fileWriteUnknown) }
            try png.write(to: url, options: .atomic)
        } catch {
            NSAlert(error: error).runModal()
        }
    }

    private func send(_ reaction: ReactionType) {
        Task { await reactions.send(reaction) }
    }

    // MARK: Reactions

    /// The partner's reactions: played over a canvas on screen, else shown in the notch or under
    /// the menu bar icon until a canvas opens. The Mac looks every five seconds while paired, as
    /// the iPhone does while open, until reaction pushes can reach it.
    private func installReactions() {
        reactionIsland = ReactionIslandController(
            reactions: reactions,
            partner: { [unowned self] in
                let bootstrap = appStateController.loadState.bootstrap
                return (bootstrap?.partnerDisplayName?.nonEmpty ?? "Your partner", bootstrap?.partnerPhotoUrl)
            },
            statusButton: { [unowned self] in preferences.showsMenuBarIcon ? statusItem?.button : nil },
            openCanvas: { [unowned self] in openMulberry() }
        )
        let center = NotificationCenter.default
        for name in [NSApplication.didBecomeActiveNotification, NSApplication.didResignActiveNotification,
                     NSWindow.didBecomeKeyNotification, NSWindow.didMiniaturizeNotification,
                     NSWindow.didDeminiaturizeNotification, NSWindow.willCloseNotification,
                     NSWindow.didChangeOcclusionStateNotification] {
            surfaceObservers.append(center.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                // After the window has finished closing or hiding.
                DispatchQueue.main.async { MainActor.assumeIsolated { self?.updateReactionSurface() } }
            })
        }
        bindReactions()
    }

    private func bindReactions() {
        let bootstrap = appStateController.loadState.bootstrap
        let paired = bootstrap?.isPaired == true
        let user = authController.state.isSignedIn ? bootstrap?.userId : nil
        reactions.bind(userId: user, pairSessionId: paired ? bootstrap?.pairSessionId : nil)
        streaks.bind(userId: user, pairSessionId: paired ? bootstrap?.pairSessionId : nil, baselineDays: bootstrap?.currentStreakDays)
        if paired, reactionPolling == nil {
            #if DEBUG
            Task { await reactions.runDebugArrivals() }
            Task {
                try? await Task.sleep(for: .seconds(3))
                streaks.runDebugLevelUp()
            }
            #endif
            // Reactions every five seconds; the streak every thirty, so its level-up lands soon
            // after the day's first drawing or reaction.
            reactionPolling = Task { [weak self] in
                var tick = 0
                while !Task.isCancelled {
                    await self?.reactions.check()
                    if tick % 6 == 0, let self { await self.streaks.refresh(api: self.api) }
                    tick += 1
                    try? await Task.sleep(for: .seconds(5))
                }
            }
        } else if !paired {
            reactionPolling?.cancel()
            reactionPolling = nil
        }
        updateReactionSurface()
    }

    /// Which canvas is in front of the person: Quick Draw while it is open, else the window
    /// while Mulberry is the active app and the window shows.
    private func updateReactionSurface() {
        let surface: ReactionsModel.Surface?
        let paired = appStateController.loadState.bootstrap?.isPaired == true
        if overlayController.isQuickDrawActive {
            surface = .quickDraw
        } else if NSApp.isActive, let window = mainWindowController?.window, window.isVisible, !window.isMiniaturized,
                  window.occlusionState.contains(.visible), paired {
            surface = .window
        } else if lockScreen.isOpen, paired {
            surface = .menuBar
        } else {
            surface = nil
        }
        if reactions.surface != surface { reactions.surface = surface }
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
        // A click opens the lock screen; a right click, or a Control-click, the menu.
        item.button?.target = self
        item.button?.action = #selector(statusItemClicked)
        item.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
        statusItem = item
        lockScreen.visibilityChanged = { [unowned self] in
            updateReactionSurface()
            updateSyncDemand()
            refreshLockScreen()
            // The home screen's streak widget, fresh as the lock screen opens.
            if lockScreen.isOpen, appStateController.loadState.bootstrap?.isPaired == true {
                Task { [weak self] in
                    guard let self else { return }
                    await streaks.refresh(api: api)
                    refreshLockScreen()
                }
            }
        }
        lockScreenModel.actions = LockScreenModel.Actions(
            quickDraw: { [unowned self] in if !overlayController.isQuickDrawActive { quickDraw() } },
            openCanvas: { [unowned self] in openMulberry() },
            openStreak: { [unowned self] in showMainWindow(showingStreak: true) },
            toggleOverlay: { [unowned self] in toggleOverlay() },
            openSettings: { [unowned self] in showSettings(nil) },
            send: { [unowned self] reaction in send(reaction) }
        )
        refreshStatusMenu()
    }

    @objc private func statusItemClicked() {
        let event = NSApp.currentEvent
        if event?.type == .rightMouseUp || event?.modifierFlags.contains(.control) == true {
            lockScreen.close()
            statusItem?.menu = statusMenu
            statusItem?.button?.performClick(nil)
            statusItem?.menu = nil
        } else {
            lockScreen.toggle()
        }
    }

    /// The lock screen's partner, streak and overlay, and the drawing on it.
    private func refreshLockScreen() {
        let bootstrap = appStateController.loadState.bootstrap
        let model = lockScreenModel
        let account: LockScreenModel.Account = !authController.state.isSignedIn ? .signedOut : bootstrap?.isPaired == true ? .paired : .unpaired
        if model.account != account { model.account = account }
        let partner = bootstrap?.partnerDisplayName?.nonEmpty ?? "Your partner"
        if model.partnerName != partner { model.partnerName = partner }
        if model.partnerPhotoURL != bootstrap?.partnerPhotoUrl { model.partnerPhotoURL = bootstrap?.partnerPhotoUrl }
        if model.streak != streaks.streak { model.streak = streaks.streak }
        if model.overlayShown != overlayController.isVisible { model.overlayShown = overlayController.isVisible }
        guard lockScreen.isOpen else { return }
        let drawing = syncController.drawing
        let latest = (drawing.strokes.map(\.createdAt) + drawing.canvasElements.map { element in
            switch element {
            case let .text(text): text.createdAt
            case let .sticker(sticker): sticker.createdAt
            }
        }).max()
        let updated = latest.map { Date(timeIntervalSince1970: TimeInterval($0) / 1_000) }
        if model.updatedAt != updated { model.updatedAt = updated }
        // Drawn again only when the drawing, its stroke style or its sticker artwork changes.
        let mode = CanvasStrokeRenderMode(rawBackendValue: bootstrap?.canvasStrokeRenderMode)
        let key = LockScreenDrawingKey(drawing: drawing, mode: mode, stickers: stickerLoader.images.count)
        guard key != lockScreenDrawingKey else { return }
        lockScreenDrawingKey = key
        let input = CanvasRenderInput(state: drawing, viewport: .zero, strokeRenderMode: mode,
                                      surface: .passiveOverlay, stickerImages: stickerLoader.images)
        model.drawing = CanvasOffscreenRenderer.renderImage(input: input, size: LockScreenView.canvasSize,
                                                            scale: statusItem?.button?.window?.backingScaleFactor ?? 2)
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

    /// The right click's menu, for the keyboard and for quitting: the lock screen holds the rest.
    private func refreshStatusMenu() {
        let menu = NSMenu()
        menu.addItem(menuItem("Open Mulberry", action: #selector(openMulberry)))
        menu.addItem(quickDrawMenuItem())
        menu.addItem(.separator())
        menu.addItem(menuItem("Settings…", action: #selector(openSettings), keyEquivalent: ","))
        menu.addItem(menuItem("Quit Mulberry", action: #selector(quitMulberry), keyEquivalent: "q"))
        statusMenu = menu
        refreshLockScreen()
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
        let shortcut = preferences.quickDrawShortcut
        return menuItem(
            title,
            action: #selector(quickDraw),
            keyEquivalent: shortcut.key,
            modifierMask: shortcut.modifiers
        )
    }

    @objc private func openMulberry() {
        showMainWindow()
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

    @objc private func toggleOverlay() {
        overlayController.toggle()
        updateSyncDemand()
        refreshStatusMenu()
    }

    @objc private func openSettings() {
        showSettings(nil)
    }

    private func showSettings(_ pane: SettingsPane?, subpage: SettingsSubpage? = nil) {
        if settingsWindowController == nil {
            settingsWindowController = SettingsWindowController(environment: SettingsEnvironment(
                authController: authController,
                appStateController: appStateController,
                syncController: syncController,
                overlayController: overlayController,
                preferences: preferences,
                launchAtLogin: launchAtLogin,
                account: accountSettings,
                streaks: streaks,
                recordingShortcut: { [weak self] recording in
                    recording ? self?.quickDrawController?.suspendShortcut() : self?.quickDrawController?.resumeShortcut()
                }
            )) { [weak self] in
                // After the window has closed.
                DispatchQueue.main.async { self?.updateActivationPolicy() }
            }
        }
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        settingsWindowController?.show(pane, subpage: subpage)
    }

    @objc private func quitMulberry() {
        NSApp.terminate(nil)
    }

    private func showMainWindow(showingStreak: Bool = false) {
        if mainWindowController == nil {
            mainWindowController = MainWindowController(
                windowState: windowState,
                authController: authController,
                appStateController: appStateController,
                syncController: syncController,
                overlayController: overlayController,
                stickerLoader: stickerLoader,
                editorModel: windowEditorModel,
                streaks: streaks,
                reactions: reactions,
                openSettings: { [weak self] in self?.showSettings(nil) }
            ) { [weak self] in
                self?.returnToAccessoryMode()
            }
        }

        windowState.showsStreak = showingStreak
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        mainWindowController?.showWindow(nil)
        mainWindowController?.window?.makeKeyAndOrderFront(nil)
        updateSyncDemand()
        updateReactionSurface()
        Task { await streaks.refresh(api: api) }
    }

    private func returnToAccessoryMode() {
        DispatchQueue.main.async { self.updateActivationPolicy() }
        updateSyncDemand()
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

        // The lock screen shows the drawing live too.
        if mainWindowController?.window?.isVisible == true || overlayController.isQuickDrawActive || lockScreen.isOpen {
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
        windowState: MainWindowState,
        authController: AuthSessionController,
        appStateController: AppStateController,
        syncController: CanvasSyncController,
        overlayController: OverlayController,
        stickerLoader: StickerImageLoader,
        editorModel: CanvasEditorModel,
        streaks: StreakModel,
        reactions: ReactionsModel,
        openSettings: @escaping () -> Void,
        onWindowClosed: @escaping () -> Void
    ) {
        self.onWindowClosed = onWindowClosed
        let view = MainWindowView(
            windowState: windowState,
            authController: authController,
            appStateController: appStateController,
            syncController: syncController,
            overlayController: overlayController,
            stickerLoader: stickerLoader,
            editorModel: editorModel,
            streaks: streaks,
            reactions: reactions,
            openSettings: openSettings
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

/// What the lock screen's picture of the drawing was drawn from.
private struct LockScreenDrawingKey: Equatable {
    let drawing: DrawingState
    let mode: CanvasStrokeRenderMode
    let stickers: Int
}
