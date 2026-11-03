import Foundation
import os
import ProductCore

public enum SyncModule {
    public static let name = "Sync"
}

public enum MacSyncConnectionState: Equatable, Sendable {
    case signedOut
    case disconnected
    case connecting
    case connected
    case error(String)

    public var title: String {
        switch self {
        case .signedOut: "Signed out"
        case .disconnected: "Offline"
        case .connecting: "Connecting"
        case .connected: "Connected"
        case .error: "Error"
        }
    }
}

/// How fresh the canvas needs to be right now. The main window and Quick Draw keep a live
/// connection; the passive overlay catches up every few seconds; otherwise nothing runs.
public enum CanvasSyncDemand: Equatable, Sendable {
    case foregroundWebSocket
    case overlayRecovery
    case idle

    public var title: String {
        switch self {
        case .foregroundWebSocket: "Foreground WebSocket"
        case .overlayRecovery: "Overlay recovery"
        case .idle: "Idle"
        }
    }
}

public struct MacSyncStatusSnapshot: Equatable, Sendable {
    public let pairSessionID: String?
    public let lastAppliedServerRevision: Int64
    public let pendingCount: Int
    public let lastSuccessfulRecoveryAt: Date?
    public let lastError: String?

    public init(
        pairSessionID: String? = nil,
        lastAppliedServerRevision: Int64 = 0,
        pendingCount: Int = 0,
        lastSuccessfulRecoveryAt: Date? = nil,
        lastError: String? = nil
    ) {
        self.pairSessionID = pairSessionID
        self.lastAppliedServerRevision = lastAppliedServerRevision
        self.pendingCount = pendingCount
        self.lastSuccessfulRecoveryAt = lastSuccessfulRecoveryAt
        self.lastError = lastError
    }
}

/// Runs ProductCore's canvas sync for the Mac. Edits are saved with ProductCore's `DrawingStore`
/// before they are sent, exactly as on iPhone and iPad, so the outbox survives a quit or a crash.
/// They go over the live connection while one is open, and otherwise over HTTP, whatever the demand.
@MainActor
public final class CanvasSyncController: ObservableObject {
    private static let log = Logger(subsystem: "com.subhajit.mulberry", category: "CanvasSync")
    @Published public private(set) var connectionState: MacSyncConnectionState = .signedOut
    @Published public private(set) var demand: CanvasSyncDemand = .idle
    @Published public private(set) var status = MacSyncStatusSnapshot()
    /// The canvas as this Mac shows it: the last saved document, plus edits made here that are
    /// still being saved.
    @Published public private(set) var drawing = DrawingState()
    /// The last saved document's canvas, before this Mac's unsaved edits.
    private var savedDrawing = DrawingState()
    /// Edits made here and not yet in a saved document, in the order they were made. They show at
    /// once; saving them takes a disk write, and the canvas would otherwise show the old state,
    /// such as a moved sticker back in its old place, until the write finished.
    private var unsaved: [DrawingOperation] = []
    /// The latest save. Each waits for the one before it, so edits are saved, and sent, in the
    /// order they were made: a stroke's points never arrive before the stroke.
    private var saving: Task<Void, Never>?

    public static let defaultOverlayRecoveryInterval: Duration = .seconds(3)
    public static let defaultOverlayFreshnessWindow: TimeInterval = 6

    /// The server moved this account to another pairing, or out of one.
    public var onPairingChanged: (() -> Void)?

    private struct Session: Equatable {
        let userID: String
        let pairSessionID: String
    }

    private let api: BackendClient
    private let store: DrawingStore
    private let makeSocket: @Sendable (URL) -> any DrawingSocketTransport
    private let overlayRecoveryInterval: Duration
    private let overlayFreshnessWindow: TimeInterval
    private var session: Session?
    private var live: DrawingSync?
    private var liveObserver: Task<Void, Never>?
    private var overlayRecoveryTask: Task<Void, Never>?
    private var documentVersion: UInt64?
    private var hasLoadedDocument = false
    private var lastSuccessfulRecoveryAt: Date?
    private var lastError: String?
    private var sendingWaiting = false
    /// Sending saved edits again after it failed, waiting longer each time.
    private var waitingRetry: Task<Void, Never>?
    private var waitingRetryDelay = CanvasSyncController.firstWaitingRetryDelay
    private static let firstWaitingRetryDelay: Duration = .seconds(5)
    private static let longestWaitingRetryDelay: Duration = .seconds(300)

    public init(
        api: BackendClient,
        store: DrawingStore,
        makeSocket: @escaping @Sendable (URL) -> any DrawingSocketTransport = { DrawingWebSocket(url: $0) },
        overlayRecoveryInterval: Duration = CanvasSyncController.defaultOverlayRecoveryInterval,
        overlayFreshnessWindow: TimeInterval = CanvasSyncController.defaultOverlayFreshnessWindow
    ) {
        self.api = api
        self.store = store
        self.makeSocket = makeSocket
        self.overlayRecoveryInterval = overlayRecoveryInterval
        self.overlayFreshnessWindow = overlayFreshnessWindow
    }

    public func updateSession(userID: String?, pairSessionID: String?, shouldSync: Bool) {
        guard shouldSync, let userID, let pairSessionID else {
            stop(signedOut: !shouldSync)
            return
        }
        let next = Session(userID: userID, pairSessionID: pairSessionID)
        guard next != session else { return }
        stopRunningSync()
        stopWaitingRetry()
        session = next
        clearDrawing()
        documentVersion = nil
        hasLoadedDocument = false
        lastSuccessfulRecoveryAt = nil
        lastError = nil
        refreshStatus(pending: 0)
        Task { await open(next) }
    }

    public func updateDemand(_ nextDemand: CanvasSyncDemand) {
        guard demand != nextDemand else { return }
        demand = nextDemand
        reconcileDemand()
    }

    /// Leaves saved edits in place; they are sent the next time this account syncs this pair.
    public func stop() {
        stop(signedOut: false)
    }

    /// Signed out: forget the canvas on screen. Saved documents stay scoped to their account.
    public func reset() {
        stop(signedOut: true)
        clearDrawing()
        lastSuccessfulRecoveryAt = nil
        lastError = nil
        refreshStatus(pending: 0)
    }

    /// The account was deleted: forget the canvas on screen and the edits saved for it.
    public func erase(userID: String, pairSessionID: String) async {
        reset()
        try? await store.remove(userId: userID, pairSessionId: pairSessionID)
    }

    /// Saves local edits first, then sends them when connected.
    public func submit(_ operations: [DrawingOperation]) {
        guard let session, operations.isEmpty == false else { return }
        let live = live
        // Shown now, and saved in the background.
        unsaved.append(contentsOf: operations)
        publishDrawing()
        let ids = Set(operations.map(\.id))
        let previous = saving
        saving = Task {
            await previous?.value
            do {
                let document: DrawingDocument
                if let live {
                    document = try await live.enqueue(operations)
                } else {
                    document = try await store.enqueue(userId: session.userID, pairSessionId: session.pairSessionID, operations: operations)
                }
                guard self.session == session else { return }
                unsaved.removeAll { ids.contains($0.id) }
                accept(document)
                publishDrawing()
                if live == nil { await sendWaiting(session) }
            } catch {
                guard self.session == session else { return }
                // Not saved, so not shown either.
                unsaved.removeAll { ids.contains($0.id) }
                publishDrawing()
                recordError("Your last change couldn’t be saved. Try again.", cause: error)
            }
        }
    }

    /// Fetches the whole canvas again from the server and rebuilds this Mac's copy from it, keeping
    /// the edits made here that are still to be sent.
    public func recoverNow() {
        guard let session else { return }
        Task { await rebuild(session) }
    }

    /// The Mac woke, or its network returned: connect now rather than at the live connection's next
    /// try, catch up, and send the edits that waited.
    public func reconnect() {
        guard let session, hasLoadedDocument else { return }
        if live != nil {
            stopLiveSync()
            startLiveSync(session)
        } else {
            stopWaitingRetry()
            Task { await catchUp(session) }
        }
    }

    /// Await the actual catch-up before recording a remote update as applied.
    public func recoverAfterNotification() async -> Int64? {
        guard let session, hasLoadedDocument else { return nil }
        await catchUp(session)
        guard self.session == session, lastError == nil else { return nil }
        return status.lastAppliedServerRevision
    }

    public func canReportOverlayCanSeeLatestDrawings(isOverlayVisible: Bool) -> Bool {
        guard isOverlayVisible, hasLoadedDocument, lastError == nil else { return false }
        switch demand {
        case .foregroundWebSocket:
            return connectionState == .connected
        case .overlayRecovery:
            guard let lastSuccessfulRecoveryAt else { return false }
            return Date().timeIntervalSince(lastSuccessfulRecoveryAt) <= overlayFreshnessWindow
        case .idle:
            return false
        }
    }

    private func open(_ session: Session) async {
        do {
            // Like reopening the iOS editor: finish this Mac's own strokes cut off by a quit.
            try await store.finishInterruptedStrokes(userId: session.userID, pairSessionId: session.pairSessionID)
            let document = try await store.load(userId: session.userID, pairSessionId: session.pairSessionID)
            guard self.session == session else { return }
            accept(document)
            hasLoadedDocument = true
            reconcileDemand()
            if live == nil { await sendWaiting(session) }
        } catch {
            guard self.session == session else { return }
            recordError("Your saved doodles couldn’t be opened.", cause: error)
        }
    }

    private func reconcileDemand() {
        guard let session, hasLoadedDocument else {
            connectionState = session == nil ? .signedOut : .disconnected
            return
        }
        switch demand {
        case .foregroundWebSocket:
            overlayRecoveryTask?.cancel()
            overlayRecoveryTask = nil
            startLiveSync(session)
        case .overlayRecovery:
            stopLiveSync()
            connectionState = .disconnected
            startOverlayRecovery(session)
        case .idle:
            stopRunningSync()
            connectionState = .disconnected
            // Edits the live connection had not sent yet, as when Quick Draw closes offline.
            Task { await sendWaiting(session) }
        }
    }

    private func startLiveSync(_ session: Session) {
        guard live == nil else { return }
        let sync = DrawingSync(api: api, store: store, userId: session.userID, pairSessionId: session.pairSessionID, makeSocket: makeSocket)
        live = sync
        connectionState = .connecting
        liveObserver = Task { [weak self] in
            for await event in sync.events {
                guard let self, !Task.isCancelled, self.session == session else { break }
                self.handle(event)
            }
        }
        Task { await sync.start() }
    }

    private func handle(_ event: DrawingSyncEvent) {
        switch event {
        case let .changed(document):
            accept(document)
        case let .connection(connection):
            switch connection {
            case .connecting:
                connectionState = .connecting
            case .connected:
                lastError = nil
                lastSuccessfulRecoveryAt = Date()
                connectionState = .connected
            case let .offline(message):
                recordError(message)
            case .pairingChanged:
                connectionState = .disconnected
                onPairingChanged?()
            }
            refreshStatus(pending: status.pendingCount)
        case .appendRate:
            break
        }
    }

    private func startOverlayRecovery(_ session: Session) {
        guard overlayRecoveryTask == nil else { return }
        let interval = overlayRecoveryInterval
        overlayRecoveryTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.catchUp(session)
                try? await Task.sleep(for: interval)
            }
        }
    }

    /// Fetches what the partner drew since this Mac's last revision, as the iOS wallpaper
    /// Shortcut does, without opening a live connection.
    private func catchUp(_ session: Session) async {
        do {
            let document = try await store.load(userId: session.userID, pairSessionId: session.pairSessionID)
            let operations = try await api.canvasOperations(after: document.confirmed.revision, quick: true)
            guard self.session == session else { return }
            let updated = try await store.receive(userId: session.userID, pairSessionId: session.pairSessionID, operations: operations)
            guard self.session == session else { return }
            accept(updated)
            lastError = nil
            lastSuccessfulRecoveryAt = Date()
            if live == nil {
                connectionState = .disconnected
            }
            refreshStatus(pending: updated.pending.count)
            if live == nil, !updated.pending.isEmpty { await sendWaiting(session) }
        } catch DrawingError.wrongSession {
            guard self.session == session else { return }
            onPairingChanged?()
        } catch DrawingError.revisionGap, DrawingError.missingStroke {
            // This Mac's copy no longer follows the server's history: start it again from the whole.
            guard self.session == session else { return }
            await rebuild(session)
        } catch {
            guard self.session == session, !Task.isCancelled else { return }
            recordError(UserFacingError.message(for: error, fallback: "Your canvas couldn’t update. Try again.") ?? "Your canvas couldn’t update. Try again.", cause: error)
        }
    }

    /// The whole canvas from the server's history, with this Mac's unsent edits laid over it.
    private func rebuild(_ session: Session) async {
        do {
            let operations = try await api.canvasOperations(after: 0)
            guard self.session == session else { return }
            let rebuilt = try await store.receive(userId: session.userID, pairSessionId: session.pairSessionID,
                                                  operations: operations, rebuild: true)
            guard self.session == session else { return }
            accept(rebuilt)
            lastError = nil
            lastSuccessfulRecoveryAt = Date()
            refreshStatus(pending: rebuilt.pending.count)
            if live == nil, !rebuilt.pending.isEmpty { await sendWaiting(session) }
        } catch DrawingError.wrongSession {
            guard self.session == session else { return }
            onPairingChanged?()
        } catch {
            guard self.session == session, !Task.isCancelled else { return }
            recordError(UserFacingError.message(for: error, fallback: "Your canvas couldn’t reload. Try again.") ?? "Your canvas couldn’t reload. Try again.", cause: error)
        }
    }

    /// Sends the edits saved here while no live connection is open, over HTTP, so they reach the
    /// partner without a canvas opening again. One that fails is tried again later, and as the Mac
    /// wakes or its network returns.
    private func sendWaiting(_ session: Session) async {
        guard !sendingWaiting, live == nil, self.session == session else { return }
        sendingWaiting = true
        defer { sendingWaiting = false }
        do {
            var document = try await store.load(userId: session.userID, pairSessionId: session.pairSessionID)
            guard !document.pending.isEmpty else { return }
            // HTTP adds to the account's current pairing; these edits belong to this one.
            let bootstrap = try await api.bootstrap()
            guard self.session == session else { return }
            guard bootstrap.isPaired, bootstrap.userId == session.userID, bootstrap.pairSessionId == session.pairSessionID else {
                onPairingChanged?()
                return
            }
            while live == nil, self.session == session, !document.pending.isEmpty {
                let waiting = document.pending.count
                _ = try await api.submit(DrawingBatch(operations: Array(document.pending.prefix(128))))
                // The server's history from here holds them, and whatever the partner added between.
                let operations = try await api.canvasOperations(after: document.confirmed.revision)
                guard self.session == session else { return }
                document = try await store.receive(userId: session.userID, pairSessionId: session.pairSessionID, operations: operations)
                accept(document)
                // None were taken: leave them for the live connection rather than send them again now.
                guard document.pending.count < waiting else { break }
            }
            guard self.session == session else { return }
            stopWaitingRetry()
            lastError = nil
            lastSuccessfulRecoveryAt = Date()
            refreshStatus(pending: document.pending.count)
        } catch DrawingError.wrongSession {
            guard self.session == session else { return }
            onPairingChanged?()
        } catch {
            guard self.session == session, !Task.isCancelled else { return }
            recordError("Your doodles will be sent once Mulberry connects.", cause: error)
            retryWaitingLater(session)
        }
    }

    private func retryWaitingLater(_ session: Session) {
        guard waitingRetry == nil else { return }
        let delay = waitingRetryDelay
        waitingRetryDelay = min(delay * 2, Self.longestWaitingRetryDelay)
        waitingRetry = Task { [weak self] in
            try? await Task.sleep(for: delay)
            guard let self, !Task.isCancelled else { return }
            waitingRetry = nil
            await sendWaiting(session)
        }
    }

    private func stopWaitingRetry() {
        waitingRetry?.cancel()
        waitingRetry = nil
        waitingRetryDelay = Self.firstWaitingRetryDelay
    }

    private func accept(_ document: DrawingDocument) {
        guard let session, document.userId == session.userID, document.pairSessionId == session.pairSessionID else { return }
        if let documentVersion, document.version < documentVersion { return }
        savedDrawing = document.visible
        // Edits this document already holds are no longer only this Mac's.
        let saved = Set(document.pending.map(\.id))
        unsaved.removeAll { saved.contains($0.id) }
        publishDrawing()
        documentVersion = document.version
        refreshStatus(pending: document.pending.count)
    }

    /// The saved canvas with this Mac's edits that are still being saved laid over it.
    private func publishDrawing() {
        var state = savedDrawing
        for operation in unsaved { try? state.apply(operation) }
        if state != drawing { drawing = state }
    }

    private func clearDrawing() {
        savedDrawing = DrawingState()
        unsaved = []
        drawing = DrawingState()
    }

    private func stop(signedOut: Bool) {
        stopRunningSync()
        stopWaitingRetry()
        session = nil
        hasLoadedDocument = false
        documentVersion = nil
        demand = .idle
        connectionState = signedOut ? .signedOut : .disconnected
        refreshStatus(pending: 0)
    }

    private func stopRunningSync() {
        overlayRecoveryTask?.cancel()
        overlayRecoveryTask = nil
        stopLiveSync()
    }

    private func stopLiveSync() {
        liveObserver?.cancel()
        liveObserver = nil
        if let live {
            Task { await live.stop() }
        }
        live = nil
    }

    private func refreshStatus(pending: Int) {
        status = MacSyncStatusSnapshot(
            pairSessionID: session?.pairSessionID,
            lastAppliedServerRevision: drawing.revision,
            pendingCount: pending,
            lastSuccessfulRecoveryAt: lastSuccessfulRecoveryAt,
            lastError: lastError
        )
    }

    /// Shows `message` and logs what actually failed, so support can tell a server rejection
    /// from a dropped connection. People only ever see `message`.
    private func recordError(_ message: String, cause: Error? = nil) {
        if let cause {
            Self.log.error("\(message, privacy: .public) Cause: \(String(reflecting: cause), privacy: .public)")
        }
        lastError = message
        connectionState = .error(message)
        refreshStatus(pending: status.pendingCount)
    }
}
