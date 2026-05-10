import CanvasCore
import Foundation
import Persistence

let database = try makeDatabase()
let operation = makeOperation(id: "local-1", revision: nil)

try database.enqueuePendingOperation(operation)
var summary = try database.pendingOperationSummary()
try expect(summary == PendingOperationSummary(pending: 1, inFlight: 0), "pending operation was not queued")

try database.markPendingOperationsInFlight(clientOperationIDs: [operation.clientOperationId], batchID: "batch-1")
summary = try database.pendingOperationSummary()
try expect(summary == PendingOperationSummary(pending: 0, inFlight: 1), "pending operation was not marked in flight")

_ = try database.resetInFlightPendingOperations()
summary = try database.pendingOperationSummary()
try expect(summary == PendingOperationSummary(pending: 1, inFlight: 0), "in-flight operation was not reset")

try database.acknowledgePendingOperations(clientOperationIDs: [operation.clientOperationId])
summary = try database.pendingOperationSummary()
try expect(summary == PendingOperationSummary(pending: 0, inFlight: 0), "acknowledged operation was not removed")

let accepted = makeOperation(id: "server-1", revision: 1)
let firstAcceptedResult = try database.persistAcceptedOperation(accepted)
try expect(firstAcceptedResult == .inserted, "accepted operation did not insert")
let duplicateAcceptedResult = try database.persistAcceptedOperation(accepted)
try expect(duplicateAcceptedResult == .duplicate, "accepted operation duplicate was not detected")

var conflicting = accepted
conflicting.payload = .clearCanvas
let conflictAcceptedResult = try database.persistAcceptedOperation(conflicting)
try expect(
    conflictAcceptedResult == .conflict("Server revision 1 already exists with a different operation."),
    "accepted operation conflict was not detected"
)

let state = CanvasState(
    committedStrokes: [
        CanvasStroke(
            id: "stroke",
            colorArgb: 0xFF111111,
            width: 0.02,
            points: [CanvasPoint(x: 0.4, y: 0.4)],
            createdAt: 1
        )
    ],
    revision: 7
)
try database.saveCanvasSnapshot(pairSessionID: "pair", snapshotRevision: 7, latestRevision: 9, state: state)
let snapshot = try require(database.canvasSnapshot(pairSessionID: "pair"), "snapshot did not round-trip")
try expect(snapshot.state == state, "snapshot state did not round-trip")
try expect(snapshot.snapshotRevision == 7 && snapshot.latestRevision == 9, "snapshot revisions did not round-trip")

print("PersistenceFixtureCheck passed")

private func makeDatabase() throws -> MulberryDatabase {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("mulberry-persistence-check-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return try MulberryDatabase(databaseURL: directory.appendingPathComponent("test.sqlite"))
}

private func makeOperation(id: String, revision: Int64?) -> CanvasOperation {
    CanvasOperation(
        clientOperationId: id,
        actorUserId: revision == nil ? nil : "user",
        pairSessionId: revision == nil ? nil : "pair",
        type: .addStroke,
        strokeId: "stroke",
        payload: .addStroke(AddStrokePayload(
            id: "stroke",
            colorArgb: 0xFF111111,
            width: 0.02,
            createdAt: 1,
            firstPoint: CanvasPoint(x: 0.4, y: 0.4)
        )),
        clientCreatedAt: "2026-01-01T00:00:00.000Z",
        serverRevision: revision,
        createdAt: revision == nil ? nil : "2026-01-01T00:00:00.000Z"
    )
}

private func expect(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if condition() == false {
        throw CheckFailure(message)
    }
}

private func require<T>(_ value: T?, _ message: String) throws -> T {
    guard let value else {
        throw CheckFailure(message)
    }
    return value
}

private struct CheckFailure: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}
