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
    private lazy var windowEditorModel = CanvasEditorModel { [weak self] in self?.submit($0) }
    private lazy var quickDrawEditorModel = CanvasEditorModel { [weak self] in self?.submit($0) }
    /// The window's and Quick Draw's tools, shared and remembered per account and pair.
    private lazy var toolMemory = CanvasToolMemory(models: [windowEditorModel, quickDrawEditorModel])
    private lazy var stickerLoader: StickerImageLoader = {
        let client = api
        return StickerImageLoader(api: { client })
    }()
    private let overlayController = OverlayController()
    private var quickDrawController: QuickDrawController?
    private var overlayStateCancellable: AnyCancellable?
    private var authStateCancellable: AnyCancellable?
    private var appStateCancellable: AnyCancellable?
    private var drawingCancellable: AnyCancellable?
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
    private let firstDrawing = FirstDrawingModel()
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
    /// The first run's film, or the two photos meeting when a partner joins later.
    private var onboarding: OnboardingController?
    /// Whether this launch has settled if onboarding is due, once the saved session is known.
    private var onboardingConsidered = false
    private let partnerArrival = PartnerArrival()
    private lazy var invite = PartnerInvite(api: { [unowned self] in api })
    private var inviteCancellable: AnyCancellable?

    public override init() {
        super.init()
    }

    public func applicationDidFinishLaunching(_ notification: Notification) {
        if handOverToRunningCopy() { return NSApp.terminate(nil) }
        CrashReporting.start()
        NSApp.setActivationPolicy(.accessory)
        installMainMenu()
        listenForSecondCopies()
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
                self?.considerOnboarding()
                if case let .signedIn(session) = self?.authController.state { LastAccount.remember(session.bootstrap) }
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
                self.updatePartnerWaiting()
            }
        }
        // The drawing changes with every batch of points; the connection far less often.
        drawingCancellable = syncController.$drawing.sink { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.loadStickerArtwork()
                self.refreshOverlayCanvas()
            }
        }
        syncStateCancellable = Publishers.Merge3(
            syncController.$status.map { _ in () },
            syncController.$connectionState.map { _ in () },
            syncController.$demand.map { _ in () }
        ).sink { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.loadStickerArtwork()
                self.reportCurrentOverlayPresence()
            }
        }
        stickerCancellable = stickerLoader.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async {
                self?.refreshOverlayCanvas()
            }
        }
        installStatusItem()
        installPartnerArrival()
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
            )
            .overlay { QuickDrawCelebration(streaks: streaks, firstDrawing: firstDrawing) })
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

    /// The canvases' edits go to sync; the account's first drawing is celebrated as it is saved.
    private func submit(_ operations: [DrawingOperation]) {
        syncController.submit(operations)
        firstDrawing.saved(operations, paired: appStateController.loadState.bootstrap?.isPaired == true, streaks: streaks) { [weak self] in
            guard let self else { return }
            await streaks.refresh(api: api)
        }
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
        toolMemory.bind(user: user, pair: paired ? bootstrap?.pairSessionId : nil)
        streaks.bind(userId: user, pairSessionId: paired ? bootstrap?.pairSessionId : nil, baselineDays: bootstrap?.currentStreakDays)
        firstDrawing.bind(userId: user, hasDrawn: bootstrap?.hasDrawn)
        if paired, reactionPolling == nil {
            #if DEBUG
            Task { await reactions.runDebugArrivals() }
            Task {
                try? await Task.sleep(for: .seconds(3))
                streaks.runDebugLevelUp()
            }
            firstDrawing.runDebugFirstDrawing()
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

    // MARK: Onboarding

    private enum OnboardingProgress {
        static let startedKey = "onboarding.started"
        static let completedKey = "onboarding.completed"
    }

    /// Once the saved session is known: a Mac that has never finished onboarding plays it, from
    /// the start when signed out, or from the phone when it was quit part way. An account signed
    /// in from before onboarding existed carries on as it was.
    private func considerOnboarding() {
        guard !onboardingConsidered else { return }
        let defaults = UserDefaults.standard
        let signedIn: Bool
        switch authController.state {
        case .signedOut: signedIn = false
        case .signedIn, .failed: signedIn = true
        case .signingIn, .refreshing: return
        }
        onboardingConsidered = true
        #if DEBUG
        // `-onboarding intro` or `-onboarding phone` plays it again; `-onboarding joined` shows the
        // two photos as when a partner joins later.
        switch defaults.string(forKey: "onboarding") {
        case "intro": return startOnboarding(.intro)
        case "phone", "hearts": return startOnboarding(.phone)
        case "joined": return startOnboarding(.partnerJoined(.quickDraw))
        default: break
        }
        #endif
        guard !defaults.bool(forKey: OnboardingProgress.completedKey) else { return }
        if !signedIn {
            startOnboarding(.intro)
        } else if defaults.bool(forKey: OnboardingProgress.startedKey) {
            startOnboarding(.phone)
        } else {
            defaults.set(true, forKey: OnboardingProgress.completedKey)
        }
    }

    private func startOnboarding(_ start: OnboardingFlow.Start) {
        guard onboarding == nil else { return }
        lockScreen.close()
        mainWindowController?.close()
        if case .partnerJoined = start {} else { UserDefaults.standard.set(true, forKey: OnboardingProgress.startedKey) }
        let flow = OnboardingFlow(start: start, authController: authController, appStateController: appStateController,
                                  syncController: syncController, launchAtLogin: launchAtLogin, deviceID: store.installationID)
        let controller = OnboardingController(flow: flow, statusButton: { [unowned self] in statusItem?.button })
        onboarding = controller
        #if DEBUG
        flow.replay = { [unowned self, unowned flow, unowned controller] in
            flow.abandon()
            controller.close()
            onboarding = nil
            startOnboarding(.intro)
        }
        #endif
        controller.show(authController: authController) { [unowned self] ending in
            onboarding = nil
            if case .partnerJoined = start {} else { UserDefaults.standard.set(true, forKey: OnboardingProgress.completedKey) }
            if appStateController.loadState.bootstrap?.isPaired == true { partnerArrival.welcomed() }
            updatePartnerWaiting()
            updateActivationPolicy()
            switch ending {
            case .quickDraw:
                NSApp.activate(ignoringOtherApps: true)
                if !overlayController.isQuickDrawActive { quickDraw() }
            case .openApp:
                showMainWindow()
            case .quiet:
                break
            }
        }
    }

    /// The partner joined since onboarding: the two photos, then Quick Draw or the window.
    private func showPartnerWelcome(then ending: OnboardingFlow.Ending) {
        partnerArrival.welcomed()
        startOnboarding(.partnerJoined(ending))
    }

    private func installPartnerArrival() {
        partnerArrival.attach(to: statusItem?.button)
        partnerArrival.open = { [unowned self] in
            if partnerArrival.isPending(appStateController.loadState.bootstrap) {
                showPartnerWelcome(then: .quickDraw)
            } else {
                openMulberry()
            }
        }
        partnerArrival.refresh = { [weak self] in
            guard let self else { return }
            await appStateController.refreshBootstrapAndReportOverlay(overlayController: overlayController, syncController: syncController)
        }
        inviteCancellable = invite.$invite.sink { [weak self] invite in
            DispatchQueue.main.async { self?.lockScreenModel.inviteCode = invite?.code }
        }
    }

    /// Waiting for the partner: the code for them, and an eye out for their arrival.
    private func updatePartnerWaiting() {
        let bootstrap = appStateController.loadState.bootstrap
        let signedIn = authController.state.isSignedIn
        invite.bind(user: signedIn ? bootstrap?.userId : nil,
                    unpaired: bootstrap?.isPaired == false && bootstrap?.onboardingCompleted == true && bootstrap?.pairingStatus == "UNPAIRED")
        partnerArrival.update(bootstrap, signedIn: signedIn, onboarding: onboarding?.isShowing == true)
    }

    // MARK: One copy

    /// Asked by a second copy of this build to come forward in its place.
    private static let showWindowRequest = Notification.Name("com.subhajit.mulberry.showWindow")

    /// Only one copy of a build runs, with one menu bar icon and one overlay: a second, such as
    /// another copy of the app opened from elsewhere, gives the running one the focus, asks it to
    /// show its window, and quits.
    private func handOverToRunningCopy() -> Bool {
        guard let bundleID = Bundle.main.bundleIdentifier,
              let running = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
                .first(where: { $0 != NSRunningApplication.current && !$0.isTerminated }) else { return false }
        NSApp.yieldActivation(to: running)
        DistributedNotificationCenter.default().postNotificationName(Self.showWindowRequest, object: bundleID, userInfo: nil, deliverImmediately: true)
        return true
    }

    private func listenForSecondCopies() {
        DistributedNotificationCenter.default().addObserver(forName: Self.showWindowRequest, object: Bundle.main.bundleIdentifier, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.openMulberry() }
        }
    }

    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    /// Opening Mulberry again from Finder, Spotlight or the Dock shows its window.
    public func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if onboarding?.isShowing == true { return true }
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
            send: { [unowned self] reaction in send(reaction) },
            // In the sign-in window, which shows Google's progress and carries on from there.
            signIn: { [unowned self] in
                showMainWindow()
                Task { await authController.signIn() }
            }
        )
        refreshStatusMenu()
        #if DEBUG
        // With `-lockScreenAccount`, the lock screen opens by itself a moment after launch.
        // `-lockScreenInviteCode 482913` gives it a code to share, `-lockScreenHome YES` opens the
        // home screen and `-lockScreenRail YES` the reactions.
        let defaults = UserDefaults.standard
        if let preview = defaults.string(forKey: "lockScreenAccount") {
            Task { [weak self] in
                try? await Task.sleep(for: .seconds(2))
                guard let self else { return }
                NSApp.activate(ignoringOtherApps: true)
                lockScreen.open()
                if preview == "signedOut" { lockScreenModel.drawing = nil }
                if let code = defaults.string(forKey: "lockScreenInviteCode") { lockScreenModel.inviteCode = code }
                try? await Task.sleep(for: .seconds(0.5))
                if defaults.bool(forKey: "lockScreenHome") { lockScreenModel.setUnlocked(true) }
                if defaults.bool(forKey: "lockScreenRail") {
                    withAnimation(.spring(duration: 0.35, bounce: 0.3)) { lockScreenModel.railOpen = true }
                }
            }
        }
        #endif
    }

    @objc private func statusItemClicked() {
        let event = NSApp.currentEvent
        let menuClick = event?.type == .rightMouseUp || event?.modifierFlags.contains(.control) == true
        if !menuClick, onboarding?.isShowing == true {
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        // The dot: the partner has joined.
        if !menuClick, partnerArrival.isPending(appStateController.loadState.bootstrap) {
            return showPartnerWelcome(then: .quickDraw)
        }
        if menuClick {
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
        var account: LockScreenModel.Account = !authController.state.isSignedIn ? .signedOut : bootstrap?.isPaired == true ? .paired : .unpaired
        #if DEBUG
        // `-lockScreenAccount signedOut|unpaired|paired` shows the lock screen as that account would see it.
        if let preview = UserDefaults.standard.string(forKey: "lockScreenAccount").flatMap(LockScreenModel.Account.init(rawValue:)) {
            account = preview
        }
        #endif
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
                invite: invite,
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
        if onboarding?.isShowing == true {
            NSApp.activate(ignoringOtherApps: true)
            return
        }
        // The partner joined while Mulberry was away: the two of them meet first.
        if partnerArrival.isPending(appStateController.loadState.bootstrap) {
            return showPartnerWelcome(then: .openApp)
        }
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
                firstDrawing: firstDrawing,
                invite: invite,
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
            connectionState: syncController.connectionState
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

/// What the partner's devices show of this Mac's overlay. The revision and recovery time travel
/// with each report as diagnostics, but do not cause one: they change with every edit.
private struct OverlayPresenceReportSignature: Equatable {
    let pairSessionID: String?
    let overlayVisible: Bool
    let selectedDisplayName: String
    let canSeeLatestDrawings: Bool
    let demand: CanvasSyncDemand
    let connectionState: MacSyncConnectionState
}

@MainActor
final class MainWindowController: NSWindowController, NSWindowDelegate {
    private let onWindowClosed: () -> Void
    private let windowState: MainWindowState
    private var shapeCancellable: AnyCancellable?
    private var turning: Task<Void, Never>?

    init(
        windowState: MainWindowState,
        authController: AuthSessionController,
        appStateController: AppStateController,
        syncController: CanvasSyncController,
        overlayController: OverlayController,
        stickerLoader: StickerImageLoader,
        editorModel: CanvasEditorModel,
        streaks: StreakModel,
        firstDrawing: FirstDrawingModel,
        invite: PartnerInvite,
        reactions: ReactionsModel,
        openSettings: @escaping () -> Void,
        onWindowClosed: @escaping () -> Void
    ) {
        self.onWindowClosed = onWindowClosed
        self.windowState = windowState
        let view = MainWindowView(
            windowState: windowState,
            authController: authController,
            appStateController: appStateController,
            syncController: syncController,
            overlayController: overlayController,
            stickerLoader: stickerLoader,
            editorModel: editorModel,
            streaks: streaks,
            firstDrawing: firstDrawing,
            invite: invite,
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
        window.isReleasedWhenClosed = false
        // Neither shape has a full-screen layout: no green button, and the menu item and
        // Control-Command-F leave the window as it is.
        window.collectionBehavior.insert(.fullScreenNone)
        window.standardWindowButton(.zoomButton)?.isHidden = true
        super.init(window: window)
        window.delegate = self

        // Wide while signed out; the canvas's portrait once signed in.
        var signIn = Self.showsSignIn(authController.state)
        #if DEBUG
        // `-signInPreview YES` shows the sign-in window without signing out; Continue pretends.
        // `-signInPreviewAuto YES` presses it a few seconds in.
        if UserDefaults.standard.bool(forKey: "signInPreview") {
            signIn = true
            let previewSignIn = { [unowned self, unowned windowState] in
                windowState.previewBusy = true
                Task {
                    try? await Task.sleep(for: .seconds(1.2))
                    windowState.previewBusy = false
                    turn(toSignIn: false)
                }
            }
            windowState.previewSignIn = previewSignIn
            if UserDefaults.standard.bool(forKey: "signInPreviewAuto") {
                Task {
                    try? await Task.sleep(for: .seconds(4))
                    previewSignIn()
                }
            }
        }
        #endif
        windowState.showsSignIn = signIn
        windowState.signInLeaving = false
        shape(signIn, animated: false)
        window.center()
        // Even in the preview, a real sign-out after the pretend sign-in turns the window back.
        shapeCancellable = authController.$state
            .map(Self.showsSignIn)
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] signIn in
                DispatchQueue.main.async { self?.turn(toSignIn: signIn) }
            }
    }

    private static func showsSignIn(_ state: AuthSessionState) -> Bool {
        switch state {
        case .signedOut, .signingIn, .failed: true
        case .signedIn, .refreshing: false
        }
    }

    /// Signing out covers the canvas, then widens the window; signing in lets the welcome go,
    /// narrows the window into the canvas's shape, then uncovers the canvas.
    private func turn(toSignIn signIn: Bool) {
        turning?.cancel()
        let animated = window?.isVisible == true && !NSWorkspace.shared.accessibilityDisplayShouldReduceMotion
        if signIn {
            windowState.signInLeaving = false
            withAnimation(.easeOut(duration: 0.25)) { windowState.showsSignIn = true }
            shape(true, animated: animated)
        } else {
            windowState.signInLeaving = true
            turning = Task { [weak self] in
                if animated { try? await Task.sleep(for: .seconds(0.2)) }
                guard let self, !Task.isCancelled else { return }
                shape(false, animated: animated)
                if animated { try? await Task.sleep(for: .seconds(Self.turnDuration)) }
                guard !Task.isCancelled else { return }
                // Still leaving as it fades: the welcome stays hidden until the sign-in window
                // comes back, rather than flashing up in the fading panel.
                withAnimation(.easeOut(duration: 0.35)) { windowState.showsSignIn = false }
            }
        }
    }

    /// Sizes the window for either shape about its middle, kept on its screen.
    private func shape(_ signIn: Bool, animated: Bool) {
        guard let window else { return }
        let size = signIn ? Self.signInSize : Self.defaultContentSize()
        if signIn {
            // Before shrinking: the canvas's minimum is taller than the sign-in window.
            window.styleMask.remove(.resizable)
            window.contentMinSize = Self.signInSize
            window.appearance = NSAppearance(named: .darkAqua)
        }
        let current = window.frame
        var frame = window.frameRect(forContentRect: NSRect(origin: .zero, size: size))
        frame.origin = NSPoint(x: current.midX - frame.width / 2, y: current.midY - frame.height / 2)
        if let visible = (window.screen ?? NSScreen.main)?.visibleFrame {
            frame.origin.x = min(max(frame.minX, visible.minX), visible.maxX - frame.width)
            frame.origin.y = min(max(frame.minY, visible.minY), visible.maxY - frame.height)
        }
        guard animated else {
            window.setFrame(frame, display: true)
            if !signIn { settleIntoCanvas() }
            return
        }
        NSAnimationContext.runAnimationGroup { context in
            context.duration = Self.turnDuration
            context.timingFunction = CAMediaTimingFunction(controlPoints: 0.32, 0.72, 0, 1)
            window.animator().setFrame(frame, display: true)
        } completionHandler: { [weak self] in
            MainActor.assumeIsolated { if !signIn { self?.settleIntoCanvas() } }
        }
    }

    /// The canvas's own portrait shape, at any size, unless signed out again on the way.
    private func settleIntoCanvas() {
        guard let window, !windowState.showsSignIn || windowState.signInLeaving else { return }
        window.styleMask.insert(.resizable)
        window.contentAspectRatio = Self.canvasAspect
        window.contentMinSize = Self.minimumSize
        window.appearance = nil
    }

    private static let turnDuration = 0.5

    /// Landscape, about the size of a Mac's own welcome windows.
    private static let signInSize = NSSize(width: 880, height: 550)

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
        guard !windowState.showsSignIn else { return frameSize }
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
