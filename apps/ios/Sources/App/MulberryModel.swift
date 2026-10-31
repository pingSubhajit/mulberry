import SwiftUI

@MainActor
final class MulberryModel: ObservableObject {
    let streaks = StreakModel()
    let firstDrawing = FirstDrawingModel()
    let reactions = ReactionsModel()
    let canvas = CanvasModel()
    @Published var showStreak = false
    @Published var showPairingConfirmation = false
    @Published private(set) var bootstrap: BootstrapState?
    @Published private(set) var restoring = true
    @Published private(set) var busy = false
    @Published var errorMessage: String?
    @Published var canvasSaveError: String?
    var retryCanvasSave: (() -> Void)?
    @Published var draft = ProfileDraft()
    @Published var showInviteEntry = false
    @Published var createdInvite: CreatedInvite?
    @Published var showWallpaperOnboarding = false
    @Published var showSettings = false
    @Published var drawingConnection: DrawingConnection = .connecting
    @Published var appendHz = 30
    @Published private(set) var canvasClearVersion = 0
    private var sync: DrawingSync?
    private var syncObserver: Task<Void, Never>?
    private var restoringStarted = false
    private var refreshing = false
    private var active = true
    private var backgrounded = false
    private let wallpaperService: ProductWallpaperService
    init(wallpaperService: ProductWallpaperService? = nil) { self.wallpaperService = wallpaperService ?? .shared }
    private var sessionEpoch = 0
    private var prewarmedElements: [JSONValue] = []

    var userId: String? { bootstrap?.userId }
    var api: BackendClient { get throws { try AppRuntime.api() } }

    func restore() async {
        guard !restoringStarted else { return }
        restoringStarted = true
        defer { restoring = false }
        do {
            let api = try api
            guard let user = await api.signedInUserId else { return }
            if let data = UserDefaults.standard.data(forKey: "bootstrap.\(user)"),
               let cached = try? JSONDecoder().decode(BootstrapState.self, from: data) {
                // Restore local editing immediately. DrawingSync still verifies the
                // live account and pair before it sends any saved operations.
                guard cached.userId == user else { throw DrawingError.wrongSession }
                await apply(cached)
                restoring = false
                Task { await self.refresh() }
                return
            }
            await apply(try await api.bootstrap())
        } catch { await handle(error) }
    }

    func signIn() async {
        await perform {
            let idToken = try await GoogleAuthentication.signIn()
            let state = try await self.api.signIn(googleIDToken: idToken)
            self.sessionEpoch += 1
            await self.apply(state)
        }
    }

    func refresh() async {
        guard !refreshing, !busy, bootstrap != nil else { return }
        refreshing = true
        let epoch = sessionEpoch
        defer { refreshing = false }
        do {
            let state = try await api.bootstrap()
            guard epoch == sessionEpoch else { return }
            await apply(state)
            if let api = try? api { await streaks.refresh(api: api) }
        } catch { if epoch == sessionEpoch { await handle(error) } }
    }

    func saveDraft() {
        guard let userId, let data = try? JSONEncoder().encode(draft) else { return }
        UserDefaults.standard.set(data, forKey: "profileDraft.\(userId)")
    }

    func submitProfile() async {
        await perform {
            let state = try await self.api.saveProfile(.init(displayName: self.draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines), partnerDisplayName: self.draft.partnerDisplayName.trimmingCharacters(in: .whitespacesAndNewlines), anniversaryDate: self.draft.anniversaryDate))
            if let user = self.userId { UserDefaults.standard.removeObject(forKey: "profileDraft.\(user)") }
            await self.apply(state)
        }
    }

    func redeem(_ code: String) async {
        await perform {
            let response = try await self.api.redeemInvite(code: code)
            self.showInviteEntry = false
            await self.apply(response.bootstrapState)
        }
    }

    func acceptInvite() async {
        guard let id = bootstrap?.invite?.inviteId else { return }
        await perform { await self.apply(try await self.api.acceptInvite(id: id).bootstrapState) }
    }

    func declineInvite() async {
        guard let id = bootstrap?.invite?.inviteId else { return }
        await perform { await self.apply(try await self.api.declineInvite(id: id)) }
    }

    func createInvite() async {
        await perform { self.createdInvite = try await self.api.createInvite() }
    }

    func disconnect() async {
        await perform {
            let state = try await self.api.disconnect()
            self.createdInvite = nil
            await self.apply(state)
        }
    }

    func finishWallpaperOnboarding() {
        guard let userId else { return }
        UserDefaults.standard.set(true, forKey: "wallpaperVisited.\(userId)")
        showWallpaperOnboarding = false
    }

    func updateName(_ name: String) async { await perform { await self.apply(try await self.api.updateDisplayName(name)) } }
    func updatePhoto(_ jpeg: Data) async { await perform { await self.apply(try await self.api.updatePhoto(jpeg: jpeg)) } }
    func updatePartner(name: String, anniversary: String, jpeg: Data?) async {
        await perform { await self.apply(try await self.api.updatePartner(name: name, anniversary: anniversary, jpeg: jpeg)) }
    }
    func setDryBrush(_ enabled: Bool) async {
        await perform { await self.apply(try await self.api.setStrokeRenderMode(enabled ? "dry" : "round")) }
    }

    func enqueue(_ operations: [DrawingOperation]) async throws {
        guard let sync, bootstrap?.isPaired == true else { throw DrawingError.wrongSession }
        let document = try await sync.enqueue(operations)
        try acceptDrawing(document)
        firstDrawing.saved(operations, paired: true, streaks: streaks) { [weak self] in
            guard let self, let api = try? self.api else { return }
            await self.streaks.refresh(api: api)
        }
        if backgrounded { await wallpaperService.scheduleLocalUpdate() }
    }

    func clearCanvas() async {
        await perform {
            try await self.enqueue([.init(type: "CLEAR_CANVAS")])
            self.canvasClearVersion += 1
        }
    }

    func setActive(_ value: Bool, updateWallpaper: Bool = false) async {
        active = value
        backgrounded = updateWallpaper
        if value {
            await sync?.start()
            await refresh()
        } else {
            await sync?.stop()
            if updateWallpaper { await wallpaperService.scheduleLocalUpdate() }
        }
    }

    func signOut() async {
        guard !busy else { return }
        busy = true
        sessionEpoch += 1
        await sync?.stop()
        syncObserver?.cancel(); syncObserver = nil; sync = nil
        _ = try? await api.setPresence(deviceId: PushRegistration.deviceId, configured: false, enabled: false, canSeeLatestDrawings: false, details: ["signedOut": .bool(true)])
        await PushRegistration.shared.signOut()
        do { try await api.signOut() }
        catch {
            if (try? await api.signedInUserId) != nil {
                errorMessage = error.localizedDescription
                if let state = bootstrap { await apply(state) }
                busy = false
                return
            }
        }
        GoogleAuthentication.signOut()
        resetSignedOut()
    }

    /// Deletes the account on the server, then everything this device kept for it. A failure leaves
    /// the person signed in, so they never believe an account is gone when it is not.
    func deleteAccount() async {
        guard !busy, let user = userId else { return }
        busy = true; errorMessage = nil
        let pair = bootstrap?.pairSessionId
        do { try await api.deleteAccount() }
        catch {
            busy = false
            await handle(error)
            return
        }
        sessionEpoch += 1
        await sync?.stop()
        syncObserver?.cancel(); syncObserver = nil; sync = nil
        await PushRegistration.shared.signOut()
        await ReactionActivities.endAll()
        if let pair { try? await AppRuntime.drawings.remove(userId: user, pairSessionId: pair) }
        wallpaperService.forget(user: user)
        // Every per-account setting is keyed by the account's id.
        for key in UserDefaults.standard.dictionaryRepresentation().keys where key.contains(user) {
            UserDefaults.standard.removeObject(forKey: key)
        }
        await GoogleAuthentication.disconnect()
        resetSignedOut()
    }

    private func resetSignedOut() {
        HomeQuickActions.shared.pending = nil
        streaks.bind(userId: nil, pairSessionId: nil)
        firstDrawing.bind(userId: nil, hasDrawn: nil)
        reactions.bind(userId: nil, pairSessionId: nil)
        showStreak = false; showPairingConfirmation = false
        bootstrap = nil; canvas.reset(); draft = ProfileDraft()
        createdInvite = nil; showSettings = false; showInviteEntry = false
        showWallpaperOnboarding = false; errorMessage = nil; busy = false
        canvasSaveError = nil; retryCanvasSave = nil
    }

    private func apply(_ state: BootstrapState) async {
        let previousPair = bootstrap?.pairSessionId
        let previousUser = bootstrap?.userId
        if previousPair != state.pairSessionId || previousUser != state.userId {
            canvasSaveError = nil; retryCanvasSave = nil
        }
        let pairingConfirmed = PairingCelebration.shouldPresent(previous: bootstrap, current: state)
        bootstrap = state
        streaks.bind(userId: state.userId, pairSessionId: state.isPaired ? state.pairSessionId : nil, baselineDays: state.currentStreakDays)
        firstDrawing.bind(userId: state.userId, hasDrawn: state.hasDrawn)
        #if DEBUG
        if previousUser != state.userId { firstDrawing.runDebugFirstDrawing() }
        #endif
        reactions.bind(userId: state.userId, pairSessionId: state.isPaired ? state.pairSessionId : nil)
        if !state.isPaired { showStreak = false; showPairingConfirmation = false }
        if pairingConfirmed {
            showPairingConfirmation = true
            showSettings = false
        }
        if previousUser != state.userId {
            loadDraft(for: state)
            showWallpaperOnboarding = state.userId.map { !UserDefaults.standard.bool(forKey: "wallpaperVisited.\($0)") } ?? false
        }
        if let user = state.userId, let data = try? JSONEncoder().encode(state) { UserDefaults.standard.set(data, forKey: "bootstrap.\(user)") }
        if previousUser != state.userId || previousPair != state.pairSessionId || (sync == nil && state.isPaired) {
            await sync?.stop(); syncObserver?.cancel()
            sync = nil; canvas.reset()
            if let user = state.userId, let pair = state.pairSessionId, state.isPaired, let api = try? api {
                do { try await AppRuntime.drawings.finishInterruptedStrokes(userId: user, pairSessionId: pair) }
                catch { errorMessage = "Your interrupted drawing could not be recovered. \(error.localizedDescription)" }
                do { try acceptDrawing(await AppRuntime.drawings.load(userId: user, pairSessionId: pair)) }
                catch { errorMessage = "Your saved drawing could not be opened. \(error.localizedDescription)" }
                let newSync = DrawingSync(api: api, store: AppRuntime.drawings, userId: user, pairSessionId: pair)
                sync = newSync
                syncObserver = Task { [weak self] in
                    for await event in newSync.events {
                        guard !Task.isCancelled, let self, self.bootstrap?.pairSessionId == pair, self.userId == user else { break }
                        switch event {
                        case .changed(let document):
                            do { try self.acceptDrawing(document) }
                            catch { self.errorMessage = error.localizedDescription }
                        case .connection(let connection):
                            self.drawingConnection = connection
                            if connection == .pairingChanged { await self.refresh() }
                        case .appendRate(let rate): self.appendHz = rate
                        }
                    }
                }
                if active { await newSync.start() }
            }
        }
        if state.isPaired { createdInvite = nil; showInviteEntry = false }
    }

    private func loadDraft(for state: BootstrapState) {
        if let user = state.userId, let data = UserDefaults.standard.data(forKey: "profileDraft.\(user)"), let saved = try? JSONDecoder().decode(ProfileDraft.self, from: data) { draft = saved }
        else { draft = .init(displayName: state.userDisplayName ?? "", partnerDisplayName: state.partnerDisplayName ?? "", anniversaryDate: state.anniversaryDate ?? "") }
    }

    private func acceptDrawing(_ document: DrawingDocument) throws {
        guard document.userId == userId, document.pairSessionId == bootstrap?.pairSessionId else { throw DrawingError.wrongSession }
        guard canvas.accept(document) else { return }
        prewarmStickers()
    }

    /// Decodes the drawing's sticker artwork as soon as the drawing loads, so the canvas usually
    /// opens with every sticker already in memory.
    private func prewarmStickers() {
        // Runs for every stroke point; decode the elements only when they change.
        let drawing = canvas.state
        guard let user = userId, drawing.elements != prewarmedElements else { return }
        prewarmedElements = drawing.elements
        let stickers = Set(drawing.canvasElements.compactMap(\.sticker?.sticker)).filter {
            StickerAssetStore.shared.cachedImage($0, variant: .full, user: user, maxPixelSize: StickerAssetStore.canvasPixels) == nil
        }
        guard !stickers.isEmpty else { return }
        Task { await StickerAssetStore.shared.prewarm(stickers, variant: .full, user: user, maxPixelSize: StickerAssetStore.canvasPixels) }
    }

    private func perform(_ operation: () async throws -> Void) async {
        guard !busy else { return }
        busy = true; errorMessage = nil
        defer { busy = false }
        do { try await operation() }
        catch { await handle(error) }
    }

    private func handle(_ error: Error) async {
        if GoogleAuthentication.isCancellation(error) { return }
        // Leaving the app cancels the 5-second refresh mid-request; that is not a failure to show.
        if error is CancellationError || (error as? URLError)?.code == .cancelled { return }
        if (try? await api.signedInUserId) == nil && bootstrap != nil {
            await sync?.stop(); syncObserver?.cancel(); sync = nil
            streaks.bind(userId: nil, pairSessionId: nil)
            firstDrawing.bind(userId: nil, hasDrawn: nil)
            reactions.bind(userId: nil, pairSessionId: nil)
            showStreak = false; showPairingConfirmation = false
            bootstrap = nil; canvas.reset()
        }
        let message = error.localizedDescription
        switch message {
        case "You cannot redeem your own invite": errorMessage = "That’s your invite code. Share it with your partner."
        case "Invite code has expired", "Invite code is no longer valid": errorMessage = "That invite code has expired. Ask your partner to generate a new one."
        case "Invite code not found": errorMessage = "That invite code wasn’t found. Double-check the code and try again."
        default: errorMessage = message
        }
    }
}
