import Foundation
import Testing
@testable import ProductCore

private struct SyncBootstrapTransport: BackendTransport {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        (try fixture("bootstrap"), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

private struct SyncTokens: SessionTokenStore {
    func load() -> SessionTokens? { .init(accessToken: "test", refreshToken: "test", userId: "11111111-1111-4111-8111-111111111111") }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}

private actor SimulatedDrawingServer {
    var accepted: [DrawingOperation] = []
    var batchCount = 0
    var loseFirstAcknowledgement: Bool
    init(loseFirstAcknowledgement: Bool) { self.loseFirstAcknowledgement = loseFirstAcknowledgement }

    func hello() throws -> Data {
        try JSONEncoder().encode(["type": JSONValue.string("READY"), "pairSessionId": .string("22222222-2222-4222-8222-222222222222"), "latestRevision": .number(Double(accepted.count)), "missedOperations": try .encoded(accepted)])
    }

    func submit(_ data: Data) throws -> Data? {
        struct Batch: Decodable { let operations: [DrawingOperation] }
        let batch = try JSONDecoder().decode(Batch.self, from: data)
        batchCount += 1
        var acknowledged: [DrawingOperation] = []
        for operation in batch.operations {
            if let existing = accepted.first(where: { $0.id == operation.id }) { acknowledged.append(existing); continue }
            let saved = DrawingOperation(clientOperationId: operation.id, actorUserId: "11111111-1111-4111-8111-111111111111", pairSessionId: "22222222-2222-4222-8222-222222222222", type: operation.type, strokeId: operation.strokeId, payload: operation.payload, clientCreatedAt: operation.clientCreatedAt, serverRevision: Int64(accepted.count + 1))
            accepted.append(saved); acknowledged.append(saved)
        }
        if loseFirstAcknowledgement { loseFirstAcknowledgement = false; return nil }
        return try JSONEncoder().encode(["type": JSONValue.string("ACK_BATCH"), "operations": try .encoded(acknowledged)])
    }
}

private actor SimulatedDrawingSocket: DrawingSocketTransport {
    private let server: SimulatedDrawingServer
    private var queue: [Data] = []
    private var waiting: CheckedContinuation<Data, any Error>?
    private var closed = false
    init(server: SimulatedDrawingServer) { self.server = server }

    func send(_ text: String) async throws {
        let data = Data(text.utf8)
        let value = try JSONDecoder().decode(JSONValue.self, from: data)
        switch value["type"]?.string {
        case "HELLO": push(try await server.hello())
        case "CLIENT_OP_BATCH":
            if let acknowledgement = try await server.submit(data) { push(acknowledgement) }
            else { close() }
        case "PING": push(Data(#"{"type":"PONG"}"#.utf8))
        default: throw BackendError.invalidResponse
        }
    }

    func receive() async throws -> Data {
        if !queue.isEmpty { return queue.removeFirst() }
        if closed { throw CancellationError() }
        return try await withCheckedThrowingContinuation { waiting = $0 }
    }

    func close() {
        closed = true
        waiting?.resume(throwing: CancellationError()); waiting = nil
    }
    private func push(_ data: Data) {
        if let waiting { self.waiting = nil; waiting.resume(returning: data) }
        else { queue.append(data) }
    }
}

@Test(.timeLimit(.minutes(1)), arguments: [false, true])
func websocketSavesConcurrentEditsAndRecoversLostAcknowledgement(loseAcknowledgement: Bool) async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let api = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: SyncTokens(), transport: SyncBootstrapTransport())
    let store = DrawingStore(directory: directory)
    let server = SimulatedDrawingServer(loseFirstAcknowledgement: loseAcknowledgement)
    let sync = DrawingSync(api: api, store: store, userId: "11111111-1111-4111-8111-111111111111", pairSessionId: "22222222-2222-4222-8222-222222222222", makeSocket: { _ in SimulatedDrawingSocket(server: server) })
    let first = DrawingStroke(colorArgb: 0xFF112233, width: 0.02, points: [.init(x: 0.1, y: 0.2), .init(x: 0.2, y: 0.3)], finished: true)
    let second = DrawingStroke(colorArgb: 0xFF334455, width: 0.01, points: [.init(x: 0.4, y: 0.5)], finished: true)
    let completed = Task {
        for await event in sync.events {
            if case .changed(let document) = event,
               document.confirmed.finishedStrokes.count == 2 && document.pending.isEmpty { return document }
        }
        throw CancellationError()
    }
    await sync.start()
    async let addFirst = sync.enqueue(try DrawingOperation.restore(first))
    async let addSecond = sync.enqueue(try DrawingOperation.restore(second))
    _ = try await (addFirst, addSecond)
    let document = try await completed.value
    await sync.stop()
    #expect(document.confirmed.finishedStrokes.contains(first))
    #expect(document.confirmed.finishedStrokes.contains(second))
    #expect(document.confirmed.revision == 5)
    #expect(await server.accepted.count == 5)
    let saved = try await store.load(userId: document.userId, pairSessionId: document.pairSessionId)
    #expect(saved.pending.isEmpty)
    #expect(saved.confirmed.finishedStrokes.count == 2)
}
