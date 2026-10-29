import Combine
import Foundation
import ProductCore
import Sync
import Testing

private let user = "11111111-1111-4111-8111-111111111111"
private let partner = "33333333-3333-4333-8333-333333333333"
private let pair = "22222222-2222-4222-8222-222222222222"

private struct Tokens: SessionTokenStore {
    func load() -> SessionTokens? { .init(accessToken: "test", refreshToken: "test", userId: user) }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}

/// The backend's canvas log for one pair, reachable over REST and the live socket.
private actor CanvasServer {
    private(set) var log: [DrawingOperation] = []

    func record(_ operations: [DrawingOperation], actor: String) -> [DrawingOperation] {
        operations.map { operation in
            if let existing = log.first(where: { $0.id == operation.id }) { return existing }
            let saved = DrawingOperation(
                clientOperationId: operation.id, actorUserId: actor, pairSessionId: pair, type: operation.type,
                strokeId: operation.strokeId, payload: operation.payload, clientCreatedAt: operation.clientCreatedAt,
                serverRevision: Int64(log.count + 1)
            )
            log.append(saved)
            return saved
        }
    }

    func operations(after revision: Int64) -> [DrawingOperation] {
        log.filter { ($0.serverRevision ?? 0) > revision }
    }
}

private struct ServerTransport: BackendTransport {
    let server: CanvasServer

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let url = try #require(request.url)
        let body: Data
        switch url.path {
        case "/bootstrap":
            body = Data("""
            {"authStatus":"SIGNED_IN","onboardingCompleted":true,"hasWallpaperConfigured":false,"canvasStrokeRenderMode":"dry",
             "userId":"\(user)","currentStreakDays":0,"pairingStatus":"PAIRED","pairSessionId":"\(pair)",
             "ownPresence":{"canSeeLatestDrawings":false,"surfaces":[]},"partnerPresence":{"canSeeLatestDrawings":false,"surfaces":[]}}
            """.utf8)
        case "/canvas/ops":
            let after = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
                .first { $0.name == "afterRevision" }?.value.flatMap { Int64($0) } ?? 0
            body = try JSONEncoder().encode(["operations": await server.operations(after: after)])
        default:
            throw URLError(.badURL)
        }
        return (body, HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

private actor ServerSocket: DrawingSocketTransport {
    private let server: CanvasServer
    private var queue: [Data] = []
    private var waiting: CheckedContinuation<Data, any Error>?
    private var closed = false

    init(server: CanvasServer) { self.server = server }

    func send(_ text: String) async throws {
        let message = try JSONDecoder().decode(JSONValue.self, from: Data(text.utf8))
        switch message["type"]?.string {
        case "HELLO":
            let after = Int64(message["lastAppliedServerRevision"].flatMap { try? $0.decode(Double.self) } ?? 0)
            push(try JSONEncoder().encode([
                "type": JSONValue.string("READY"), "pairSessionId": .string(pair),
                "missedOperations": try .encoded(await server.operations(after: after))
            ]))
        case "CLIENT_OP_BATCH":
            struct Batch: Decodable { let operations: [DrawingOperation] }
            let batch = try JSONDecoder().decode(Batch.self, from: Data(text.utf8))
            let saved = await server.record(batch.operations, actor: user)
            push(try JSONEncoder().encode(["type": JSONValue.string("ACK_BATCH"), "operations": try .encoded(saved)]))
        default:
            break
        }
    }

    func receive() async throws -> Data {
        if !queue.isEmpty { return queue.removeFirst() }
        if closed { throw CancellationError() }
        return try await withCheckedThrowingContinuation { waiting = $0 }
    }

    func close() {
        closed = true
        waiting?.resume(throwing: CancellationError())
        waiting = nil
    }

    private func push(_ data: Data) {
        if let waiting {
            self.waiting = nil
            waiting.resume(returning: data)
        } else {
            queue.append(data)
        }
    }
}

@MainActor
private func makeController(server: CanvasServer, directory: URL) throws -> CanvasSyncController {
    let api = try BackendClient(baseURL: URL(string: "https://mulberry.invalid")!, tokenStore: Tokens(), transport: ServerTransport(server: server))
    return CanvasSyncController(
        api: api,
        store: DrawingStore(directory: directory),
        makeSocket: { _ in ServerSocket(server: server) },
        overlayRecoveryInterval: .milliseconds(50)
    )
}

@MainActor
private func waitUntil(_ condition: () -> Bool) async throws {
    for _ in 0..<200 where !condition() {
        try await Task.sleep(for: .milliseconds(20))
    }
    #expect(condition())
}

private func stroke(_ x: Double) -> DrawingStroke {
    DrawingStroke(colorArgb: 0xFFB31329, width: 0.02, points: [.init(x: x, y: 0.2), .init(x: x, y: 0.6)], finished: true)
}

@MainActor
@Test(.timeLimit(.minutes(1)))
func overlayCatchesUpWithThePartnersDrawingWithoutALiveConnection() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let server = CanvasServer()
    let drawn = stroke(0.3)
    _ = await server.record(try DrawingOperation.restore(drawn), actor: partner)
    let controller = try makeController(server: server, directory: directory)

    controller.updateSession(userID: user, pairSessionID: pair, shouldSync: true)
    controller.updateDemand(.overlayRecovery)
    try await waitUntil { controller.drawing.finishedStrokes == [drawn] }

    #expect(controller.status.lastAppliedServerRevision == 3)
    #expect(controller.connectionState == .disconnected)
    #expect(controller.canReportOverlayCanSeeLatestDrawings(isOverlayVisible: true))
    #expect(controller.canReportOverlayCanSeeLatestDrawings(isOverlayVisible: false) == false)

    // A later stroke from the partner arrives on the next catch-up.
    let next = stroke(0.6)
    _ = await server.record(try DrawingOperation.restore(next), actor: partner)
    try await waitUntil { controller.drawing.finishedStrokes == [drawn, next] }
    controller.stop()
}

@MainActor
@Test(.timeLimit(.minutes(1)))
func editsMadeWhileIdleAreSavedThenSentWhenTheWindowConnects() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let server = CanvasServer()
    let controller = try makeController(server: server, directory: directory)
    controller.updateSession(userID: user, pairSessionID: pair, shouldSync: true)
    try await waitUntil { controller.connectionState == .disconnected }

    let drawn = stroke(0.4)
    controller.submit(try DrawingOperation.restore(drawn))
    try await waitUntil { controller.status.pendingCount == 3 }
    #expect(controller.drawing.finishedStrokes == [drawn])
    let saved = try await DrawingStore(directory: directory).load(userId: user, pairSessionId: pair)
    #expect(saved.pending.count == 3)
    #expect(await server.log.isEmpty)

    controller.updateDemand(.foregroundWebSocket)
    try await waitUntil { controller.connectionState == .connected && controller.status.pendingCount == 0 }
    #expect(await server.log.count == 3)
    #expect(controller.drawing.finishedStrokes == [drawn])
    #expect(controller.status.lastAppliedServerRevision == 3)
    controller.stop()
}

@MainActor
@Test(.timeLimit(.minutes(1)))
func editsShowAtOnceAndNeverFlashBackWhileTheyAreSaved() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let server = CanvasServer()
    let controller = try makeController(server: server, directory: directory)
    controller.updateSession(userID: user, pairSessionID: pair, shouldSync: true)
    try await waitUntil { controller.connectionState == .disconnected }

    let sticker = CanvasStickerElement(center: .init(x: 0.2, y: 0.2), sticker: StickerReference(packKey: "pack", packVersion: 1, stickerId: "one"))
    var centres: [Double] = []
    let watcher = controller.$drawing.sink { state in
        if let shown = state.canvasElements.first(where: { $0.id == sticker.id })?.sticker { centres.append(shown.center.x) }
    }
    defer { watcher.cancel() }

    // Placed, then moved twice in quick succession, as a drag and a nudge would.
    controller.submit([.add(.sticker(sticker))])
    #expect(controller.drawing.canvasElements.first?.sticker?.center.x == 0.2)
    var moved = sticker
    moved.center = .init(x: 0.5, y: 0.2)
    controller.submit([.update(.sticker(moved))])
    #expect(controller.drawing.canvasElements.first?.sticker?.center.x == 0.5)
    moved.center = .init(x: 0.6, y: 0.2)
    controller.submit([.update(.sticker(moved))])
    #expect(controller.drawing.canvasElements.first?.sticker?.center.x == 0.6)

    try await waitUntil { controller.status.pendingCount == 3 }
    #expect(controller.drawing.canvasElements.first?.sticker?.center.x == 0.6)
    // Never back to an earlier place once moved on, while the saves caught up.
    var furthest = 0.0
    for centre in centres {
        #expect(centre >= furthest)
        furthest = max(furthest, centre)
    }
    controller.stop()
}
