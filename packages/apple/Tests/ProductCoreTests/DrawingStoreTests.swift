import Foundation
import Testing
@testable import ProductCore

private let me = "11111111-1111-4111-8111-111111111111"
private let partner = "33333333-3333-4333-8333-333333333333"
private let pair = "22222222-2222-4222-8222-222222222222"

private func confirmed(_ operation: DrawingOperation, by actor: String, revision: Int64) -> DrawingOperation {
    DrawingOperation(clientOperationId: operation.id, actorUserId: actor, pairSessionId: pair, type: operation.type, strokeId: operation.strokeId,
                     payload: operation.payload, clientCreatedAt: operation.clientCreatedAt, serverRevision: revision)
}

private func stroke(_ x: Double, points: Int = 3) -> DrawingStroke {
    DrawingStroke(colorArgb: 0xFF5BB7E8, width: 0.01, points: (0..<points).map { .init(x: x, y: Double($0) / Double(points)) }, finished: true)
}

private func temporaryDirectory() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
}

/// The store names a document's files after its user and pair.
private func file(_ suffix: String, in directory: URL) -> URL {
    directory.appendingPathComponent("\(UUID(uuidString: me)!.uuidString)-\(UUID(uuidString: pair)!.uuidString).\(suffix)")
}

private func journal(in directory: URL) -> URL { file("journal", in: directory) }

@Test func journalledEditsSurviveRestart() async throws {
    let directory = temporaryDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    let operations = try DrawingOperation.restore(stroke(0.2)) + DrawingOperation.restore(stroke(0.6))
    for operation in operations { _ = try await store.enqueue(userId: me, pairSessionId: pair, operations: [operation]) }
    let acknowledged = Array(operations.prefix(3)).enumerated().map { confirmed($0.element, by: me, revision: Int64($0.offset + 1)) }
    let saved = try await store.receive(userId: me, pairSessionId: pair, operations: acknowledged)
    #expect(FileManager.default.fileExists(atPath: journal(in: directory).path))

    let reopened = try await DrawingStore(directory: directory).load(userId: me, pairSessionId: pair)
    #expect(reopened == saved)
    #expect(reopened.pending == Array(operations.dropFirst(3)))
    #expect(reopened.visible.finishedStrokes.count == 2)
}

@Test func tornJournalLineIsDroppedAndLaterEditsStillSave() async throws {
    let directory = temporaryDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    let first = try DrawingOperation.restore(stroke(0.2))
    let saved = try await store.enqueue(userId: me, pairSessionId: pair, operations: first)
    // The app was terminated part way through appending the next change.
    let handle = try FileHandle(forWritingTo: journal(in: directory))
    try handle.seekToEnd()
    try handle.write(contentsOf: Data(#"{"version":2,"change":{"enqueue":{"_0":[{"clientOpe"#.utf8))
    try handle.close()

    let reopened = DrawingStore(directory: directory)
    #expect(try await reopened.load(userId: me, pairSessionId: pair) == saved)
    let second = try DrawingOperation.restore(stroke(0.6))
    let next = try await reopened.enqueue(userId: me, pairSessionId: pair, operations: second)
    #expect(try await DrawingStore(directory: directory).load(userId: me, pairSessionId: pair) == next)
}

@Test func compactionCutShortDoesNotApplyChangesTwice() async throws {
    let directory = temporaryDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    let document = try await store.enqueue(userId: me, pairSessionId: pair, operations: DrawingOperation.restore(stroke(0.2)))
    let journalled = try Data(contentsOf: journal(in: directory))
    // The snapshot was written, but the journal it now holds was never cleared.
    try await store.save(document)
    try journalled.write(to: journal(in: directory))

    let reopened = try await DrawingStore(directory: directory).load(userId: me, pairSessionId: pair)
    #expect(reopened == document)
    #expect(reopened.pending.count == 3)
}

@Test func journalIsFoldedIntoSnapshotOnceItOutgrowsIt() async throws {
    let directory = temporaryDirectory()
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    var latest = DrawingDocument(userId: me, pairSessionId: pair)
    for index in 0..<120 {
        latest = try await store.enqueue(userId: me, pairSessionId: pair, operations: DrawingOperation.restore(stroke(Double(index) / 120, points: 40)))
    }
    let journalBytes = (try? FileManager.default.attributesOfItem(atPath: journal(in: directory).path)[.size] as? Int) ?? 0
    let snapshot = file("json", in: directory)
    let snapshotBytes = try #require(FileManager.default.attributesOfItem(atPath: snapshot.path)[.size] as? Int)
    #expect(journalBytes <= max(snapshotBytes, 64 * 1024))
    #expect(try await DrawingStore(directory: directory).load(userId: me, pairSessionId: pair) == latest)
}

@Test func visibleDrawingMatchesAReplayOfPendingEdits() throws {
    var document = DrawingDocument(userId: me, pairSessionId: pair)
    let first = try DrawingOperation.restore(stroke(0.2))
    try document.enqueue(first)
    try document.enqueue(DrawingOperation.restore(stroke(0.6)))
    try document.receive(first.enumerated().map { confirmed($0.element, by: me, revision: Int64($0.offset + 1)) })
    try document.enqueue([.init(type: "DELETE_STROKE", strokeId: first[0].strokeId)])
    // Decoding replays the pending edits onto the confirmed drawing from scratch.
    let replayed = try JSONDecoder().decode(DrawingDocument.self, from: JSONEncoder().encode(document))
    #expect(replayed == document)
    #expect(document.visible.finishedStrokes.count == 1)
}

/// Two quick strokes here, the second begun before the first is saved, with the partner's stroke
/// landing between them on the server. Both devices end with the same canvas.
@Test func overlappingLocalStrokesConvergeWithAPartnersStroke() throws {
    let mine = [stroke(0.2), stroke(0.4)]
    let theirs = stroke(0.8)
    let first = try DrawingOperation.restore(mine[0]), second = try DrawingOperation.restore(mine[1])
    let partnerOperations = try DrawingOperation.restore(theirs)
    var here = DrawingDocument(userId: me, pairSessionId: pair)
    var there = DrawingDocument(userId: partner, pairSessionId: pair)
    try here.enqueue(first)
    try here.enqueue(second)
    try there.enqueue(partnerOperations)
    let shownBeforeSaving = here.visible.finishedStrokes

    let order: [(DrawingOperation, String)] = [
        (first[0], me), (partnerOperations[0], partner), (first[1], me), (first[2], me),
        (partnerOperations[1], partner), (second[0], me), (partnerOperations[2], partner), (second[1], me), (second[2], me)
    ]
    let server = order.enumerated().map { confirmed($0.element.0, by: $0.element.1, revision: Int64($0.offset + 1)) }
    // Acknowledgements and the partner's operations arrive in batches, out of order within each.
    for batch in [Array(server[0..<4]), Array(server[4..<6]).reversed(), Array(server[6...])] {
        try here.receive(batch)
        try there.receive(batch)
    }

    #expect(shownBeforeSaving == mine)
    #expect(here.pending.isEmpty && there.pending.isEmpty)
    #expect(here.confirmed == there.confirmed)
    #expect(here.visible == there.visible)
    #expect(here.visible.finishedStrokes == [mine[0], theirs, mine[1]])
}
