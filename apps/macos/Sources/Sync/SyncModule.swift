import CanvasCore
import Foundation
import Networking
import Persistence

public enum SyncModule {
    public static let name = "Sync"
}

public enum MacSyncConnectionState: Equatable, Sendable {
    case signedOut
    case disconnected
    case connecting
    case recovering
    case connected
    case backoff(seconds: Int)
    case error(String)

    public var title: String {
        switch self {
        case .signedOut: "Signed out"
        case .disconnected: "Offline"
        case .connecting: "Connecting"
        case .recovering: "Recovering"
        case .connected: "Connected"
        case let .backoff(seconds): "Retrying in \(seconds)s"
        case .error: "Error"
        }
    }
}

public struct MacSyncStatusSnapshot: Equatable, Sendable {
    public let pairSessionID: String?
    public let lastAppliedServerRevision: Int64
    public let latestKnownServerRevision: Int64
    public let pendingCount: Int
    public let inFlightCount: Int
    public let lastError: String?

    public init(
        pairSessionID: String? = nil,
        lastAppliedServerRevision: Int64 = 0,
        latestKnownServerRevision: Int64 = 0,
        pendingCount: Int = 0,
        inFlightCount: Int = 0,
        lastError: String? = nil
    ) {
        self.pairSessionID = pairSessionID
        self.lastAppliedServerRevision = lastAppliedServerRevision
        self.latestKnownServerRevision = latestKnownServerRevision
        self.pendingCount = pendingCount
        self.inFlightCount = inFlightCount
        self.lastError = lastError
    }

    public var isFresh: Bool {
        lastAppliedServerRevision >= latestKnownServerRevision
    }
}

public struct CanvasRecoveryInput: Equatable, Sendable {
    public let lastAppliedRevision: Int64
    public let latestRevision: Int64
    public let missedOperationCount: Int
    public let hasPendingLocalOperations: Bool
    public let reason: CanvasRecoveryReason

    public var revisionGap: Int64 {
        max(0, latestRevision - lastAppliedRevision)
    }
}

public enum CanvasRecoveryReason: Equatable, Sendable {
    case ready
    case resyncRequired
    case revisionGap
    case emptyTailGap
}

public struct CanvasRecoveryPolicy: Sendable {
    public static let maxTailOperations = 150
    public static let maxRevisionGap: Int64 = 150

    public init() {}

    public func shouldUseSnapshot(_ input: CanvasRecoveryInput) -> Bool {
        if input.hasPendingLocalOperations {
            return false
        }
        return input.reason == .resyncRequired ||
            input.reason == .revisionGap ||
            input.reason == .emptyTailGap ||
            input.missedOperationCount > Self.maxTailOperations ||
            input.revisionGap > Self.maxRevisionGap
    }

    public func compactTailOperations(_ operations: [CanvasOperation]) -> [CanvasOperation] {
        let sorted = operations.sortedByRevision()
        guard let lastClearIndex = sorted.lastIndex(where: { $0.type == .clearCanvas }) else {
            return sorted
        }
        return Array(sorted[lastClearIndex...])
    }
}

public enum CanvasSyncMessage: Equatable, Sendable {
    case ready(pairSessionID: String, userID: String, latestRevision: Int64, missedOperations: [CanvasOperation])
    case ack(clientOperationID: String, serverRevision: Int64, operation: CanvasOperation?)
    case ackBatch(batchID: String, ackedClientOperationIDs: [String], ackedThroughRevision: Int64, operations: [CanvasOperation])
    case serverOperation(CanvasOperation)
    case serverOperationBatch([CanvasOperation])
    case flowControl(mode: String, maxAppendHz: Int, reason: String?)
    case resyncRequired
    case pong
    case error(String)
    case closed
}

public enum CanvasSendResult: Equatable, Sendable {
    case accepted
    case rejected
}

@MainActor
public protocol CanvasSyncTransport: AnyObject {
    var onMessage: ((CanvasSyncMessage) -> Void)? { get set }

    func connect(accessToken: String, pairSessionID: String, lastAppliedServerRevision: Int64)
    func sendBatch(batchID: String, operations: [CanvasOperation]) -> CanvasSendResult
    func sendPing()
    func disconnect()
}

@MainActor
public final class CanvasSyncController: ObservableObject {
    @Published public private(set) var connectionState: MacSyncConnectionState = .signedOut
    @Published public private(set) var status = MacSyncStatusSnapshot()
    @Published public private(set) var canvasState = CanvasState()
    @Published public private(set) var diagnostics: [CanvasDiagnostic] = []

    private let configuration: MacAppConfiguration
    private let database: MulberryDatabase
    private let apiClient: CanvasAPIClient
    private let authorizer: AuthenticatedRequestAuthorizer
    private let transport: CanvasSyncTransport
    private let recoveryPolicy: CanvasRecoveryPolicy
    private let reducer = CanvasReducer()
    private var revisionBuffer: [Int64: CanvasOperation] = [:]
    private var localOptimisticOperationIDs = Set<String>()
    private var inFlightBatchIDs = Set<String>()
    private var sendTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var activeUserID: String?
    private var activePairSessionID: String?
    private var desiredSync = false
    private var currentBackoffSeconds = 1
    private var usesRealCanvasState = false

    public init(
        configuration: MacAppConfiguration,
        database: MulberryDatabase,
        authorizer: AuthenticatedRequestAuthorizer,
        transport: CanvasSyncTransport? = nil,
        recoveryPolicy: CanvasRecoveryPolicy = CanvasRecoveryPolicy()
    ) {
        self.configuration = configuration
        self.database = database
        self.authorizer = authorizer
        self.apiClient = CanvasAPIClient(configuration: configuration)
        self.transport = transport ?? URLSessionCanvasSyncTransport(configuration: configuration)
        self.recoveryPolicy = recoveryPolicy
        self.transport.onMessage = { [weak self] message in
            Task { @MainActor in
                await self?.handle(message)
            }
        }
        refreshStatusFromStore()
    }

    public func updateSession(userID: String?, pairSessionID: String?, shouldSync: Bool) {
        desiredSync = shouldSync
        guard shouldSync, let userID, let pairSessionID else {
            stop(reason: shouldSync ? "missing_session" : "signed_out")
            return
        }

        activeUserID = userID
        if activePairSessionID != pairSessionID {
            activePairSessionID = pairSessionID
            revisionBuffer.removeAll()
            localOptimisticOperationIDs.removeAll()
            inFlightBatchIDs.removeAll()
            try? database.resetInFlightPendingOperations()
            let metadata = try? database.syncMetadata()
            if metadata?.pairSessionID != pairSessionID {
                try? database.resetSyncScope(pairSessionID: pairSessionID)
            }
            rebuildCanvasStateFromPersistence(pairSessionID: pairSessionID)
        }

        if case .connected = connectionState {
            return
        }
        if case .connecting = connectionState {
            return
        }
        connect(reason: "session_update")
    }

    public func stop(reason: String = "stop") {
        desiredSync = false
        activeUserID = nil
        activePairSessionID = nil
        sendTask?.cancel()
        reconnectTask?.cancel()
        pingTask?.cancel()
        transport.disconnect()
        revisionBuffer.removeAll()
        inFlightBatchIDs.removeAll()
        try? database.resetInFlightPendingOperations()
        connectionState = reason == "signed_out" ? .signedOut : .disconnected
        refreshStatusFromStore()
    }

    public func reset() {
        stop(reason: "signed_out")
        try? database.updateSyncMetadata(
            pairSessionID: nil,
            lastAppliedServerRevision: 0,
            latestKnownServerRevision: 0,
            clearsLastError: true
        )
        canvasState = CanvasState()
        diagnostics = []
        usesRealCanvasState = false
        refreshStatusFromStore()
    }

    public func submitLocalOperation(_ operation: CanvasOperation) {
        guard activePairSessionID != nil else { return }
        do {
            try database.enqueuePendingOperation(operation)
            localOptimisticOperationIDs.insert(operation.clientOperationId)
            let result = reducer.apply(operation, to: canvasState)
            canvasState = result.state
            appendDiagnostics(result.diagnostics)
            refreshStatusFromStore()
            schedulePendingSend()
        } catch {
            recordError("Unable to queue local canvas operation: \(error.localizedDescription)")
        }
    }

    public func recoverNow() {
        Task {
            await recoverFromServer(reason: .resyncRequired)
        }
    }

    public func submitDebugStroke() {
        let strokeID = UUID().uuidString.lowercased()
        let now = Date()
        let createdAt = Int64(now.timeIntervalSince1970 * 1_000)
        let timestamp = DateFormatter.syncISO8601.string(from: now)
        let localDate = DateFormatter.localDate.string(from: now)
        let points = [
            CanvasPoint(x: 0.34, y: 0.42),
            CanvasPoint(x: 0.42, y: 0.34),
            CanvasPoint(x: 0.52, y: 0.44),
            CanvasPoint(x: 0.64, y: 0.32)
        ]

        submitLocalOperation(CanvasOperation(
            clientOperationId: UUID().uuidString.lowercased(),
            type: .addStroke,
            strokeId: strokeID,
            payload: .addStroke(AddStrokePayload(
                id: strokeID,
                colorArgb: 0xFFE2556C,
                width: 0.018,
                createdAt: createdAt,
                firstPoint: points[0]
            )),
            clientCreatedAt: timestamp,
            clientLocalDate: localDate
        ))
        submitLocalOperation(CanvasOperation(
            clientOperationId: UUID().uuidString.lowercased(),
            type: .appendPoints,
            strokeId: strokeID,
            payload: .appendPoints(AppendPointsPayload(points: Array(points.dropFirst()))),
            clientCreatedAt: timestamp,
            clientLocalDate: localDate
        ))
        submitLocalOperation(CanvasOperation(
            clientOperationId: UUID().uuidString.lowercased(),
            type: .finishStroke,
            strokeId: strokeID,
            payload: .finishStroke,
            clientCreatedAt: timestamp,
            clientLocalDate: localDate
        ))
    }

    public func canReportOverlayCanSeeLatestDrawings(isOverlayVisible: Bool) -> Bool {
        guard isOverlayVisible, usesRealCanvasState, diagnostics.contains(where: { $0.severity == .error }) == false else {
            return false
        }
        guard case .connected = connectionState else {
            return false
        }
        return status.isFresh
    }

    private func connect(reason: String) {
        guard desiredSync, let pairSessionID = activePairSessionID else { return }
        Task {
            guard let accessToken = await authorizer.currentAccessToken() else {
                recordError("No access token is available for canvas sync.")
                return
            }
            reconnectTask?.cancel()
            sendTask?.cancel()
            pingTask?.cancel()
            try? database.resetInFlightPendingOperations()
            refreshStatusFromStore()
            let lastApplied = (try? database.syncMetadata().lastAppliedServerRevision) ?? 0
            connectionState = .connecting
            transport.connect(
                accessToken: accessToken,
                pairSessionID: pairSessionID,
                lastAppliedServerRevision: lastApplied
            )
            startPingLoop()
        }
    }

    private func handle(_ message: CanvasSyncMessage) async {
        switch message {
        case let .ready(pairSessionID, userID, latestRevision, missedOperations):
            await handleReady(
                pairSessionID: pairSessionID,
                userID: userID,
                latestRevision: latestRevision,
                missedOperations: missedOperations
            )
        case let .ack(clientOperationID, _, operation):
            try? database.acknowledgePendingOperations(clientOperationIDs: [clientOperationID])
            inFlightBatchIDs.removeAll()
            if let operation {
                await persistAndApplyAcceptedOperations(
                    [operation],
                    skipVisualClientOperationIDs: Set([clientOperationID]),
                    allowRecovery: true
                )
            }
            schedulePendingSend()
        case let .ackBatch(batchID, ackedClientOperationIDs, _, operations):
            try? database.acknowledgePendingOperations(clientOperationIDs: ackedClientOperationIDs)
            inFlightBatchIDs.remove(batchID)
            await persistAndApplyAcceptedOperations(
                operations,
                skipVisualClientOperationIDs: Set(ackedClientOperationIDs),
                allowRecovery: true
            )
            schedulePendingSend()
        case let .serverOperation(operation):
            await enqueueAndDrainLiveOperations([operation])
        case let .serverOperationBatch(operations):
            await enqueueAndDrainLiveOperations(operations)
        case let .flowControl(mode, maxAppendHz, reason):
            appendDiagnostics([CanvasDiagnostic(
                code: "flow_control",
                message: "Server requested \(mode) append flow at \(maxAppendHz)Hz\(reason.map { " because \($0)" } ?? "").",
                severity: .info
            )])
        case .resyncRequired:
            await recoverFromServer(reason: .resyncRequired)
        case .pong:
            break
        case let .error(message):
            recordError(message)
            scheduleReconnect(reason: "socket_error")
        case .closed:
            if desiredSync {
                connectionState = .disconnected
                scheduleReconnect(reason: "socket_closed")
            }
        }
    }

    private func handleReady(
        pairSessionID: String,
        userID: String,
        latestRevision: Int64,
        missedOperations: [CanvasOperation]
    ) async {
        activePairSessionID = pairSessionID
        activeUserID = userID
        connectionState = .recovering
        currentBackoffSeconds = 1

        do {
            let metadata = try database.syncMetadata()
            try database.updateSyncMetadata(
                pairSessionID: pairSessionID,
                latestKnownServerRevision: latestRevision,
                clearsLastError: true
            )
            let reason: CanvasRecoveryReason = missedOperations.isEmpty && latestRevision > metadata.lastAppliedServerRevision
                ? .emptyTailGap
                : .ready
            let input = CanvasRecoveryInput(
                lastAppliedRevision: metadata.lastAppliedServerRevision,
                latestRevision: latestRevision,
                missedOperationCount: missedOperations.count,
                hasPendingLocalOperations: try database.hasPendingOperations(),
                reason: reason
            )
            if recoveryPolicy.shouldUseSnapshot(input) {
                await recoverFromSnapshotAndTail()
            } else {
                await applyRecoveryOperations(missedOperations)
            }
            connectionState = .connected
            try database.updateSyncMetadata(pairSessionID: pairSessionID, clearsLastError: true)
            refreshStatusFromStore()
            schedulePendingSend()
        } catch {
            recordError("Unable to recover canvas sync: \(error.localizedDescription)")
            scheduleReconnect(reason: "ready_recovery_failed")
        }
    }

    private func enqueueAndDrainLiveOperations(_ operations: [CanvasOperation]) async {
        let lastApplied = status.lastAppliedServerRevision
        for operation in operations where (operation.serverRevision ?? 0) > lastApplied {
            if let revision = operation.serverRevision {
                revisionBuffer[revision] = operation
            }
        }

        var ready: [CanvasOperation] = []
        var expected = status.lastAppliedServerRevision + 1
        while let operation = revisionBuffer.removeValue(forKey: expected) {
            ready.append(operation)
            expected += 1
        }

        if ready.isEmpty == false {
            await persistAndApplyAcceptedOperations(
                ready,
                skipVisualClientOperationIDs: Set<String>(),
                allowRecovery: true
            )
        }

        if let firstBuffered = revisionBuffer.keys.min(),
           firstBuffered > status.lastAppliedServerRevision + 1 {
            await recoverFromServer(reason: .revisionGap)
        }
    }

    private func applyRecoveryOperations(_ operations: [CanvasOperation]) async {
        let ordered = operations
            .filter { ($0.serverRevision ?? 0) > status.lastAppliedServerRevision }
            .sortedByRevision()
        guard ordered.isEmpty == false else {
            usesRealCanvasState = true
            return
        }

        guard ordered.first?.serverRevision == status.lastAppliedServerRevision + 1 else {
            await recoverFromServer(reason: .revisionGap)
            return
        }

        await persistAndApplyAcceptedOperations(
            recoveryPolicy.compactTailOperations(ordered),
            skipVisualClientOperationIDs: localOptimisticOperationIDs,
            allowRecovery: false
        )
        usesRealCanvasState = true
    }

    private func persistAndApplyAcceptedOperations(
        _ operations: [CanvasOperation],
        skipVisualClientOperationIDs: Set<String>,
        allowRecovery: Bool
    ) async {
        guard operations.isEmpty == false else { return }
        do {
            var visualOperations: [CanvasOperation] = []
            var latestRevision = status.latestKnownServerRevision
            for operation in operations.sortedByRevision() {
                guard let serverRevision = operation.serverRevision else { continue }
                latestRevision = max(latestRevision, serverRevision)
                if serverRevision <= status.lastAppliedServerRevision {
                    continue
                }
                switch try database.persistAcceptedOperation(operation) {
                case .inserted, .duplicate:
                    if skipVisualClientOperationIDs.contains(operation.clientOperationId) == false {
                        visualOperations.append(operation)
                    }
                case let .conflict(message):
                    appendDiagnostics([CanvasDiagnostic(
                        code: "accepted_operation_conflict",
                        message: message,
                        severity: .error,
                        operationId: operation.clientOperationId
                    )])
                    if allowRecovery {
                        await recoverFromServer(reason: .revisionGap)
                    }
                    return
                }
            }

            if visualOperations.isEmpty == false {
                let result = reducer.apply(visualOperations, to: canvasState)
                canvasState = result.state
                appendDiagnostics(result.diagnostics)
            }

            let lastRevision = operations.compactMap(\.serverRevision).max() ?? status.lastAppliedServerRevision
            canvasState.revision = max(canvasState.revision, lastRevision)
            try database.updateSyncMetadata(
                pairSessionID: activePairSessionID,
                lastAppliedServerRevision: max(status.lastAppliedServerRevision, lastRevision),
                latestKnownServerRevision: max(latestRevision, lastRevision),
                clearsLastError: true
            )
            localOptimisticOperationIDs.subtract(skipVisualClientOperationIDs)
            refreshStatusFromStore()
        } catch {
            recordError("Unable to persist canvas operation: \(error.localizedDescription)")
        }
    }

    private func recoverFromServer(reason: CanvasRecoveryReason) async {
        guard activePairSessionID != nil else { return }
        connectionState = .recovering
        do {
            let metadata = try database.syncMetadata()
            let operations = try await apiClient.getCanvasOperations(
                afterRevision: metadata.lastAppliedServerRevision,
                authorizer: authorizer
            )
            let latestRevision = operations.compactMap(\.serverRevision).max() ?? metadata.lastAppliedServerRevision
            let effectiveReason: CanvasRecoveryReason =
                operations.isEmpty && latestRevision > metadata.lastAppliedServerRevision ? .emptyTailGap : reason
            let input = CanvasRecoveryInput(
                lastAppliedRevision: metadata.lastAppliedServerRevision,
                latestRevision: latestRevision,
                missedOperationCount: operations.count,
                hasPendingLocalOperations: try database.hasPendingOperations(),
                reason: effectiveReason
            )
            if recoveryPolicy.shouldUseSnapshot(input) {
                await recoverFromSnapshotAndTail()
            } else {
                await applyRecoveryOperations(operations)
            }
            if desiredSync {
                connectionState = .connected
            }
            refreshStatusFromStore()
        } catch {
            recordError("Unable to recover canvas operations: \(error.localizedDescription)")
            scheduleReconnect(reason: "rest_recovery_failed")
        }
    }

    private func recoverFromSnapshotAndTail() async {
        guard let pairSessionID = activePairSessionID else { return }
        do {
            guard try database.hasPendingOperations() == false else {
                await recoverFromServer(reason: .revisionGap)
                return
            }
            let snapshot = try await apiClient.getCanvasSnapshot(authorizer: authorizer)
            canvasState = snapshot.state
            diagnostics = []
            usesRealCanvasState = true
            try database.saveCanvasSnapshot(
                pairSessionID: pairSessionID,
                snapshotRevision: snapshot.snapshotRevision,
                latestRevision: snapshot.latestRevision,
                state: snapshot.state
            )
            try database.updateSyncMetadata(
                pairSessionID: pairSessionID,
                lastAppliedServerRevision: snapshot.snapshotRevision,
                latestKnownServerRevision: snapshot.latestRevision,
                clearsLastError: true
            )
            refreshStatusFromStore()
            if snapshot.latestRevision > snapshot.snapshotRevision {
                let tail = try await apiClient.getCanvasOperations(
                    afterRevision: snapshot.snapshotRevision,
                    authorizer: authorizer
                )
                await applyRecoveryOperations(tail)
            }
        } catch {
            recordError("Unable to recover canvas snapshot: \(error.localizedDescription)")
        }
    }

    private func schedulePendingSend() {
        sendTask?.cancel()
        sendTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 16_000_000)
            await MainActor.run {
                self?.sendPendingBatch()
            }
        }
    }

    private func sendPendingBatch() {
        guard case .connected = connectionState else { return }
        guard inFlightBatchIDs.isEmpty else {
            let staleBefore = Date().addingTimeInterval(-8)
            if (try? database.resetInFlightPendingOperations(staleBefore: staleBefore)) ?? 0 > 0 {
                inFlightBatchIDs.removeAll()
                scheduleReconnect(reason: "stale_inflight_timeout")
            }
            return
        }

        do {
            let operations = try database.nextPendingOperations(maxOperations: 32, maxPayloadBytes: 64 * 1024)
            guard operations.isEmpty == false else {
                refreshStatusFromStore()
                return
            }
            let batchID = UUID().uuidString.lowercased()
            switch transport.sendBatch(batchID: batchID, operations: operations) {
            case .accepted:
                try database.markPendingOperationsInFlight(
                    clientOperationIDs: operations.map(\.clientOperationId),
                    batchID: batchID
                )
                inFlightBatchIDs.insert(batchID)
                refreshStatusFromStore()
            case .rejected:
                schedulePendingSend()
            }
        } catch {
            recordError("Unable to send pending canvas operations: \(error.localizedDescription)")
        }
    }

    private func scheduleReconnect(reason: String) {
        guard desiredSync else { return }
        transport.disconnect()
        sendTask?.cancel()
        pingTask?.cancel()
        let delay = currentBackoffSeconds
        connectionState = .backoff(seconds: delay)
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000_000)
            await MainActor.run {
                guard let self else { return }
                self.currentBackoffSeconds = min(self.currentBackoffSeconds * 2, 60)
                self.connect(reason: reason)
            }
        }
    }

    private func startPingLoop() {
        pingTask?.cancel()
        pingTask = Task { [weak self] in
            while Task.isCancelled == false {
                try? await Task.sleep(nanoseconds: 25_000_000_000)
                await MainActor.run {
                    self?.transport.sendPing()
                }
            }
        }
    }

    private func rebuildCanvasStateFromPersistence(pairSessionID: String) {
        do {
            let snapshot = try database.canvasSnapshot(pairSessionID: pairSessionID)
            let baseState = snapshot?.state ?? CanvasState()
            let acceptedTail = try database.acceptedOperations(
                pairSessionID: pairSessionID,
                afterRevision: snapshot?.snapshotRevision ?? 0
            )
            var result = reducer.apply(acceptedTail, to: baseState)
            let pending = try database.pendingOperationRecords().map(\.operation)
            localOptimisticOperationIDs = Set(pending.map(\.clientOperationId))
            if pending.isEmpty == false {
                result = reducer.apply(pending, to: result.state)
            }
            canvasState = result.state
            diagnostics = result.diagnostics
            usesRealCanvasState = true
            refreshStatusFromStore()
        } catch {
            recordError("Unable to rebuild local canvas state: \(error.localizedDescription)")
        }
    }

    private func refreshStatusFromStore() {
        let metadata = try? database.syncMetadata()
        let summary = try? database.pendingOperationSummary()
        status = MacSyncStatusSnapshot(
            pairSessionID: metadata?.pairSessionID,
            lastAppliedServerRevision: metadata?.lastAppliedServerRevision ?? 0,
            latestKnownServerRevision: metadata?.latestKnownServerRevision ?? 0,
            pendingCount: summary?.pending ?? 0,
            inFlightCount: summary?.inFlight ?? 0,
            lastError: metadata?.lastError
        )
    }

    private func appendDiagnostics(_ newDiagnostics: [CanvasDiagnostic]) {
        guard newDiagnostics.isEmpty == false else { return }
        diagnostics.append(contentsOf: newDiagnostics)
        if diagnostics.count > 50 {
            diagnostics = Array(diagnostics.suffix(50))
        }
    }

    private func recordError(_ message: String) {
        try? database.updateSyncMetadata(pairSessionID: activePairSessionID, lastError: message)
        connectionState = .error(message)
        appendDiagnostics([CanvasDiagnostic(code: "sync_error", message: message, severity: .error)])
        refreshStatusFromStore()
    }
}

@MainActor
public final class URLSessionCanvasSyncTransport: CanvasSyncTransport {
    public var onMessage: ((CanvasSyncMessage) -> Void)?

    private let configuration: MacAppConfiguration
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()
    private var task: URLSessionWebSocketTask?
    private var receiveTask: Task<Void, Never>?

    public init(configuration: MacAppConfiguration, session: URLSession = .shared) {
        self.configuration = configuration
        self.session = session
    }

    public func connect(accessToken: String, pairSessionID: String, lastAppliedServerRevision: Int64) {
        disconnect()
        let socketTask = session.webSocketTask(with: webSocketURL())
        task = socketTask
        socketTask.resume()
        send(HelloWireMessage(
            accessToken: accessToken,
            pairSessionId: pairSessionID,
            lastAppliedServerRevision: lastAppliedServerRevision
        ))
        receiveTask = Task { [weak self] in
            await self?.receiveLoop(socketTask)
        }
    }

    public func sendBatch(batchID: String, operations: [CanvasOperation]) -> CanvasSendResult {
        guard operations.isEmpty == false else { return .rejected }
        let message = ClientOperationBatchWireMessage(
            batchId: batchID,
            operations: operations.map(ClientCanvasOperationRequestDTO.init(operation:)),
            clientCreatedAt: DateFormatter.syncISO8601.string(from: Date())
        )
        return send(message) ? .accepted : .rejected
    }

    public func sendPing() {
        _ = send(PingWireMessage())
    }

    public func disconnect() {
        receiveTask?.cancel()
        receiveTask = nil
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
    }

    private func receiveLoop(_ socketTask: URLSessionWebSocketTask) async {
        while Task.isCancelled == false {
            do {
                let message = try await socketTask.receive()
                switch message {
                case let .string(raw):
                    onMessage?(try decodeIncomingMessage(raw))
                case let .data(data):
                    if let raw = String(data: data, encoding: .utf8) {
                        onMessage?(try decodeIncomingMessage(raw))
                    }
                @unknown default:
                    onMessage?(.error("Unsupported WebSocket frame."))
                }
            } catch {
                if Task.isCancelled == false {
                    onMessage?(.closed)
                }
                return
            }
        }
    }

    private func send<T: Encodable>(_ message: T) -> Bool {
        guard let task else { return false }
        do {
            let data = try encoder.encode(message)
            guard let raw = String(data: data, encoding: .utf8) else { return false }
            Task {
                try? await task.send(.string(raw))
            }
            return true
        } catch {
            return false
        }
    }

    private func decodeIncomingMessage(_ raw: String) throws -> CanvasSyncMessage {
        guard let data = raw.data(using: .utf8),
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String
        else {
            return .error("Invalid sync message.")
        }

        switch type {
        case "READY":
            let message = try decoder.decode(ReadyWireMessage.self, from: data)
            return .ready(
                pairSessionID: message.pairSessionId,
                userID: message.userId,
                latestRevision: message.latestRevision,
                missedOperations: message.missedOperations
            )
        case "ACK":
            let message = try decoder.decode(AckWireMessage.self, from: data)
            return .ack(
                clientOperationID: message.clientOperationId,
                serverRevision: message.serverRevision,
                operation: message.operation
            )
        case "ACK_BATCH":
            let message = try decoder.decode(AckBatchWireMessage.self, from: data)
            return .ackBatch(
                batchID: message.batchId,
                ackedClientOperationIDs: message.ackedClientOperationIds,
                ackedThroughRevision: message.ackedThroughRevision,
                operations: message.operations
            )
        case "SERVER_OP":
            return .serverOperation(try decoder.decode(ServerOperationWireMessage.self, from: data).operation)
        case "SERVER_OP_BATCH":
            return .serverOperationBatch(try decoder.decode(ServerOperationBatchWireMessage.self, from: data).operations)
        case "FLOW_CONTROL":
            let message = try decoder.decode(FlowControlWireMessage.self, from: data)
            return .flowControl(mode: message.mode, maxAppendHz: message.maxAppendHz, reason: message.reason)
        case "RESYNC_REQUIRED":
            return .resyncRequired
        case "PONG":
            return .pong
        case "ERROR":
            return .error((object["message"] as? String) ?? "Canvas sync error.")
        default:
            return .error("Unsupported sync message type \(type).")
        }
    }

    private func webSocketURL() -> URL {
        var raw = configuration.normalizedAPIBaseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if raw.hasPrefix("https://") {
            raw = raw.replacingOccurrences(of: "https://", with: "wss://", options: [.anchored])
        } else if raw.hasPrefix("http://") {
            raw = raw.replacingOccurrences(of: "http://", with: "ws://", options: [.anchored])
        }
        return URL(string: raw + "/canvas/sync")!
    }
}

private struct HelloWireMessage: Encodable {
    let type = "HELLO"
    let accessToken: String
    let pairSessionId: String
    let lastAppliedServerRevision: Int64
}

private struct ClientOperationBatchWireMessage: Encodable {
    let type = "CLIENT_OP_BATCH"
    let batchId: String
    let operations: [ClientCanvasOperationRequestDTO]
    let clientCreatedAt: String
}

private struct PingWireMessage: Encodable {
    let type = "PING"
}

private struct ReadyWireMessage: Decodable {
    let pairSessionId: String
    let userId: String
    let latestRevision: Int64
    let missedOperations: [CanvasOperation]
}

private struct AckWireMessage: Decodable {
    let clientOperationId: String
    let serverRevision: Int64
    let operation: CanvasOperation?
}

private struct AckBatchWireMessage: Decodable {
    let batchId: String
    let ackedClientOperationIds: [String]
    let ackedThroughRevision: Int64
    let operations: [CanvasOperation]
}

private struct ServerOperationWireMessage: Decodable {
    let operation: CanvasOperation
}

private struct ServerOperationBatchWireMessage: Decodable {
    let operations: [CanvasOperation]
}

private struct FlowControlWireMessage: Decodable {
    let mode: String
    let maxAppendHz: Int
    let reason: String?
}

private extension Array where Element == CanvasOperation {
    func sortedByRevision() -> [CanvasOperation] {
        sorted {
            switch ($0.serverRevision, $1.serverRevision) {
            case let (.some(left), .some(right)):
                left < right
            case (.some, .none):
                true
            case (.none, .some):
                false
            case (.none, .none):
                $0.clientCreatedAt < $1.clientCreatedAt
            }
        }
    }
}

private extension DateFormatter {
    static let syncISO8601: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return formatter
    }()

    static let localDate: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()
}
