import Foundation
import Testing
@testable import ProductCore

private let user = "11111111-1111-4111-8111-111111111111"
private let pair = "22222222-2222-4222-8222-222222222222"

private func accepted(_ operation: DrawingOperation, revision: Int64, pairID: String = pair) -> DrawingOperation {
    DrawingOperation(clientOperationId: operation.id, actorUserId: user, pairSessionId: pairID, type: operation.type, strokeId: operation.strokeId, payload: operation.payload, clientCreatedAt: operation.clientCreatedAt, serverRevision: revision)
}

@Test func androidStrokeReplayAcknowledgesWithoutDuplicatingPoints() throws {
    let stroke = DrawingStroke(id: "stroke-android", colorArgb: 0xFFB81133, width: 0.012, createdAt: 1_793_102_400_000, points: [.init(x: 0.1, y: 0.2), .init(x: 0.4, y: 0.5), .init(x: 0.8, y: 0.9)], finished: true)
    let outgoing = try DrawingOperation.restore(stroke)
    var document = DrawingDocument(userId: user, pairSessionId: pair)
    try document.enqueue(outgoing)
    #expect(document.visible.finishedStrokes == [stroke])
    let incoming = outgoing.enumerated().map { accepted($0.element, revision: Int64($0.offset + 1)) }
    try document.receive(Array(incoming.reversed()))
    try document.receive(incoming)
    #expect(document.pending.isEmpty)
    #expect(document.confirmed.finishedStrokes == [stroke])
    #expect(document.confirmed.revision == 3)
}

@Test func gapDoesNotPartiallyCommitOrDropPendingDrawing() throws {
    let stroke = DrawingStroke(colorArgb: 0xFF111111, width: 0.01, points: [.init(x: 0.2, y: 0.3)])
    let outgoing = try DrawingOperation.restore(stroke)
    var document = DrawingDocument(userId: user, pairSessionId: pair)
    try document.enqueue(outgoing)
    let before = document
    #expect(throws: DrawingError.revisionGap(expected: 2, actual: 3)) {
        try document.receive([accepted(outgoing[0], revision: 1), accepted(outgoing[1], revision: 3)])
    }
    #expect(document == before)
}

@Test func differentPairCannotAcknowledgeOldPendingOperations() throws {
    var document = DrawingDocument(userId: user, pairSessionId: pair)
    let operation = DrawingOperation(type: "CLEAR_CANVAS")
    try document.enqueue([operation])
    #expect(throws: DrawingError.wrongSession) {
        try document.receive([accepted(operation, revision: 1, pairID: UUID().uuidString)])
    }
    #expect(document.pending == [operation])
}

@Test func clearDuringDrawingMatchesBackendFinishReconstruction() throws {
    let stroke = DrawingStroke(colorArgb: 0xFFFFFFFF, width: 0.025, points: [.init(x: 0, y: 0), .init(x: 1, y: 1)], finished: true)
    let outgoing = try DrawingOperation.restore(stroke)
    var document = DrawingDocument(userId: user, pairSessionId: pair)
    try document.receive([
        accepted(outgoing[0], revision: 1),
        accepted(DrawingOperation(type: "CLEAR_CANVAS"), revision: 2),
        accepted(outgoing[1], revision: 3),
        accepted(outgoing[2], revision: 4)
    ])
    #expect(document.confirmed.finishedStrokes == [stroke])
}

@Test func redoCreatesFreshOperationIDsAndPreservesGeometry() throws {
    let stroke = DrawingStroke(colorArgb: 0xFFFF0000, width: 0.02, points: (0..<200).map { .init(x: Double($0) / 200, y: 0.5) }, finished: true)
    let draw = try DrawingOperation.restore(stroke)
    let redo = try DrawingOperation.restore(stroke)
    #expect(Set(draw.map(\.id)).isDisjoint(with: Set(redo.map(\.id))))
    var state = DrawingState()
    for operation in draw { try state.apply(operation) }
    try state.apply(.init(type: "DELETE_STROKE", strokeId: stroke.id))
    #expect(state.strokes.isEmpty)
    for operation in redo { try state.apply(operation) }
    #expect(state.finishedStrokes == [stroke])
}

@Test func batchNeverSendsServerIdentityOrRevision() throws {
    let operation = accepted(.init(type: "CLEAR_CANVAS"), revision: 10)
    let value = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(DrawingBatch(operations: [operation])))
    guard case .array(let operations) = value["operations"] else { Issue.record("Missing operations"); return }
    #expect(operations[0]["actorUserId"] == nil)
    #expect(operations[0]["pairSessionId"] == nil)
    #expect(operations[0]["serverRevision"] == nil)
    #expect(operations[0]["type"] == .string("CLEAR_CANVAS"))
}

@Test func pendingDrawingSurvivesRestartAndIsIsolatedByPair() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    var document = DrawingDocument(userId: user, pairSessionId: pair)
    try document.enqueue(DrawingOperation.restore(.init(colorArgb: 0xFF112233, width: 0.01, points: [.init(x: 0.1, y: 0.3)])))
    try await store.save(document)
    let reopened = DrawingStore(directory: directory)
    #expect(try await reopened.load(userId: user, pairSessionId: pair) == document)
    #expect(try await reopened.load(userId: user, pairSessionId: UUID().uuidString).pending.isEmpty)
}

@Test func profileCooldownUsesServerTimestamp() throws {
    let state = try JSONDecoder().decode(BootstrapState.self, from: fixture("bootstrap"))
    #expect(state.isPaired)
    #expect(!state.canEditPartner(at: ServerDate.parse("2026-10-30T11:59:59Z")!))
    #expect(state.canEditPartner(at: ServerDate.parse("2026-10-30T12:00:00Z")!))
    #expect(state.partnerPresence.canSeeLatestDrawings)
}

@Test func interruptedStrokeRecoversAfterAcknowledgementWithoutFinishingPartnerStroke() async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    let local = DrawingStroke(colorArgb: 0xFF112233, width: 0.01, points: [.init(x: 0.1, y: 0.3), .init(x: 0.5, y: 0.6)])
    let startAndPoints = Array(try DrawingOperation.restore(local).dropLast())
    _ = try await store.enqueue(userId: user, pairSessionId: pair, operations: startAndPoints)
    _ = try await store.receive(userId: user, pairSessionId: pair,
                               operations: startAndPoints.enumerated().map { accepted($0.element, revision: Int64($0.offset + 1)) })
    let peer = try DrawingOperation.restore(.init(id: "peer-active", colorArgb: 0xFF000000, width: 0.02, points: [.init(x: 0.7, y: 0.8)]))[0]
    _ = try await store.receive(userId: user, pairSessionId: pair, operations: [
        .init(clientOperationId: peer.id, actorUserId: "other-user", pairSessionId: pair, type: peer.type, strokeId: peer.strokeId, payload: peer.payload, serverRevision: 3)
    ])
    let reopened = DrawingStore(directory: directory)
    try await reopened.finishInterruptedStrokes(userId: user, pairSessionId: pair)
    let recovered = try await reopened.load(userId: user, pairSessionId: pair)
    #expect(recovered.pending.map(\.type) == ["FINISH_STROKE"])
    #expect(recovered.pending.first?.strokeId == local.id)
    #expect(recovered.visible.finishedStrokes.first?.points == local.points)
    #expect(recovered.visible.strokes.first { $0.id == "peer-active" }?.finished == false)
    try await reopened.finishInterruptedStrokes(userId: user, pairSessionId: pair)
    #expect(try await reopened.load(userId: user, pairSessionId: pair).pending.count == 1)
}

func fixture(_ name: String) throws -> Data {
    try Data(contentsOf: Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")!)
}
