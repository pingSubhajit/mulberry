import Foundation

public struct DrawingSyncMessage: Decodable, Sendable {
    public let type: String
    public let pairSessionId: String?
    public let latestRevision: Int64?
    public let missedOperations: [DrawingOperation]?
    public let operation: DrawingOperation?
    public let operations: [DrawingOperation]?
    public let message: String?
    public let mode: String?
    public let maxAppendHz: Int?
}

public enum DrawingSyncEvent: Sendable {
    case changed(DrawingDocument)
    case connection(DrawingConnection)
    case appendRate(Int)
}

public enum DrawingConnection: Equatable, Sendable {
    case connecting, connected, offline(String), pairingChanged
}

public protocol DrawingSocketTransport: Sendable {
    func send(_ text: String) async throws
    func receive() async throws -> Data
    func close() async
}

public actor DrawingWebSocket: DrawingSocketTransport {
    private let task: URLSessionWebSocketTask
    public init(url: URL, session: URLSession = .shared) {
        task = session.webSocketTask(with: url)
        // READY replays everything a device missed in one message. URLSession's 1 MB default
        // rejects a new device's first sync of a long-lived canvas.
        task.maximumMessageSize = 64 * 1024 * 1024
        task.resume()
    }
    public func send(_ text: String) async throws { try await task.send(.string(text)) }
    public func receive() async throws -> Data {
        switch try await task.receive() {
        case .string(let text): Data(text.utf8)
        case .data(let data): data
        @unknown default: throw BackendError.invalidResponse
        }
    }
    public func close() { task.cancel(with: .goingAway, reason: nil) }
}

/// Foreground real-time sync. The socket is authenticated to one specific pair;
/// queued edits are never posted to a token's possibly changed default pairing.
public actor DrawingSync {
    public nonisolated let events: AsyncStream<DrawingSyncEvent>
    private let continuation: AsyncStream<DrawingSyncEvent>.Continuation
    private let api: BackendClient
    private let store: DrawingStore
    private let userId: String
    private let pairSessionId: String
    private let makeSocket: @Sendable (URL) -> any DrawingSocketTransport
    private var socket: (any DrawingSocketTransport)?
    private var runner: Task<Void, Never>?
    private var pulse: Task<Void, Never>?
    private var generation = 0
    private var ready = false
    private var sending = false
    private var inFlight = Set<String>()
    private var acknowledgementDeadline: Date?
    private var lastPublishedVersion: UInt64?

    public init(api: BackendClient, store: DrawingStore, userId: String, pairSessionId: String, makeSocket: @escaping @Sendable (URL) -> any DrawingSocketTransport = { DrawingWebSocket(url: $0) }) {
        self.api = api; self.store = store; self.userId = userId; self.pairSessionId = pairSessionId; self.makeSocket = makeSocket
        let channel = AsyncStream<DrawingSyncEvent>.makeStream(bufferingPolicy: .bufferingNewest(32))
        events = channel.stream; continuation = channel.continuation
    }

    public func start() {
        guard runner == nil else { return }
        generation += 1
        let epoch = generation
        runner = Task { await run(epoch: epoch) }
    }

    public func stop() async {
        generation += 1
        ready = false
        sending = false
        runner?.cancel(); runner = nil
        pulse?.cancel(); pulse = nil
        let old = socket; socket = nil
        inFlight = []; acknowledgementDeadline = nil
        await old?.close()
    }

    @discardableResult
    public func enqueue(_ operations: [DrawingOperation]) async throws -> DrawingDocument {
        // Save before publishing optimistic UI or sending anything to the socket.
        let saved = try await store.enqueue(userId: userId, pairSessionId: pairSessionId, operations: operations)
        publish(saved)
        Task { await flush(epoch: generation) }
        return saved
    }

    private func run(epoch: Int) async {
        var retrySeconds = 1
        while !Task.isCancelled && generation == epoch {
            continuation.yield(.connection(.connecting))
            do {
                let bootstrap = try await api.bootstrap()
                guard bootstrap.userId == userId && bootstrap.pairSessionId == pairSessionId && bootstrap.isPaired else {
                    continuation.yield(.connection(.pairingChanged))
                    break
                }
                guard generation == epoch else { break }
                try await publishLatest()
                let document = try await store.load(userId: userId, pairSessionId: pairSessionId)
                var components = URLComponents(url: api.baseURL.appendingPathComponent("canvas/sync"), resolvingAgainstBaseURL: false)!
                components.scheme = components.scheme == "https" ? "wss" : "ws"
                let connection = makeSocket(components.url!)
                socket = connection
                ready = false; inFlight = []; acknowledgementDeadline = nil
                let hello: [String: JSONValue] = [
                    "type": .string("HELLO"), "accessToken": .string(try await api.accessToken()),
                    "pairSessionId": .string(pairSessionId), "lastAppliedServerRevision": .number(Double(document.confirmed.revision))
                ]
                guard generation == epoch else { await connection.close(); break }
                try await connection.send(String(decoding: JSONEncoder().encode(hello), as: UTF8.self))
                pulse = Task { await heartbeat(connection: connection, epoch: epoch) }
                while !Task.isCancelled && generation == epoch {
                    let message = try JSONDecoder().decode(DrawingSyncMessage.self, from: await connection.receive())
                    guard generation == epoch else { break }
                    try await handle(message, epoch: epoch)
                    if ready { retrySeconds = 1 }
                }
            } catch {
                guard generation == epoch && !Task.isCancelled else { break }
                continuation.yield(.connection(.offline(error.localizedDescription)))
            }
            ready = false
            pulse?.cancel(); pulse = nil
            let old = socket; socket = nil
            await old?.close()
            guard generation == epoch && !Task.isCancelled else { break }
            do { try await Task.sleep(for: .seconds(retrySeconds)) } catch { break }
            retrySeconds = min(retrySeconds * 2, 30)
        }
        if generation == epoch {
            ready = false
            pulse?.cancel(); pulse = nil
            let old = socket; socket = nil
            await old?.close()
            runner = nil
        }
    }

    private func handle(_ message: DrawingSyncMessage, epoch: Int) async throws {
        switch message.type {
        case "READY":
            guard message.pairSessionId == pairSessionId else { throw DrawingError.wrongSession }
            try await receive(message.missedOperations ?? [])
            ready = true
            continuation.yield(.connection(.connected))
        case "ACK", "SERVER_OP":
            guard let operation = message.operation else { throw BackendError.invalidResponse }
            try await receive([operation])
        case "ACK_BATCH", "SERVER_OP_BATCH":
            guard let operations = message.operations else { throw BackendError.invalidResponse }
            try await receive(operations)
        case "FLOW_CONTROL": continuation.yield(.appendRate(max(1, min(message.maxAppendHz ?? 30, 30))))
        case "ERROR": throw BackendError.rejected(400, message.message ?? "The canvas could not sync.")
        case "PONG": break
        default: break
        }
        await flush(epoch: epoch)
    }

    private func receive(_ operations: [DrawingOperation]) async throws {
        let current: DrawingDocument
        do {
            current = try await store.receive(userId: userId, pairSessionId: pairSessionId, operations: operations)
        } catch DrawingError.revisionGap {
            let behind = try await store.load(userId: userId, pairSessionId: pairSessionId)
            let missing = try await api.canvasOperations(after: behind.confirmed.revision)
            _ = try await store.receive(userId: userId, pairSessionId: pairSessionId, operations: missing)
            // Retry the event too; the HTTP response may have raced a newer socket event.
            current = try await store.receive(userId: userId, pairSessionId: pairSessionId, operations: operations)
        }
        inFlight.formIntersection(Set(current.pending.map(\.id)))
        if inFlight.isEmpty { acknowledgementDeadline = nil }
        publish(current)
    }

    private func flush(epoch: Int) async {
        guard ready, !sending, inFlight.isEmpty, let socket, generation == epoch else { return }
        sending = true
        defer { if generation == epoch { sending = false } }
        do {
            let document = try await store.load(userId: userId, pairSessionId: pairSessionId)
            guard ready && generation == epoch && !document.pending.isEmpty else { return }
            let batch = DrawingBatch(operations: Array(document.pending.prefix(128)))
            guard case .object(var payload) = try JSONValue.encoded(batch) else { throw BackendError.invalidResponse }
            payload["type"] = .string("CLIENT_OP_BATCH")
            inFlight = Set(batch.operations.map(\.id))
            acknowledgementDeadline = Date().addingTimeInterval(20)
            try await socket.send(String(decoding: JSONEncoder().encode(payload), as: UTF8.self))
            // An ACK can arrive while send() is suspended. Drain remaining edits
            // immediately instead of waiting for the next heartbeat in that case.
            if generation == epoch && inFlight.isEmpty {
                sending = false
                await flush(epoch: epoch)
            }
        } catch {
            continuation.yield(.connection(.offline(error.localizedDescription)))
            await socket.close()
        }
    }

    private func heartbeat(connection: any DrawingSocketTransport, epoch: Int) async {
        while !Task.isCancelled && generation == epoch {
            do {
                try await Task.sleep(for: .seconds(10))
                guard generation == epoch else { return }
                if let deadline = acknowledgementDeadline, deadline < Date() { await connection.close(); return }
                try await connection.send(#"{"type":"PING"}"#)
            } catch {
                if generation == epoch && !Task.isCancelled { await connection.close() }
                return
            }
        }
    }

    private func publishLatest() async throws {
        publish(try await store.load(userId: userId, pairSessionId: pairSessionId))
    }

    private func publish(_ document: DrawingDocument) {
        guard lastPublishedVersion == nil || document.version >= lastPublishedVersion! else { return }
        lastPublishedVersion = document.version
        continuation.yield(.changed(document))
    }
}
