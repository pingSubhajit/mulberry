import CanvasCore
import Foundation
import Networking
import Persistence
import Sync

@main
enum SyncFixtureCheck {
    @MainActor
    static func main() async throws {
        try await readyMissedOperationsApplyRemoteTail()
        try await debugLocalOperationsAreDurableBeforeSendAndClearedOnAck()
        print("SyncFixtureCheck passed")
    }

    @MainActor
    private static func readyMissedOperationsApplyRemoteTail() async throws {
        let fakeTransport = FakeCanvasSyncTransport()
        let controller = try makeController(transport: fakeTransport)
        controller.updateSession(userID: "mac-user", pairSessionID: "pair", shouldSync: true)
        try await pause()

        fakeTransport.emit(.ready(
            pairSessionID: "pair",
            userID: "mac-user",
            latestRevision: 3,
            missedOperations: remoteStrokeOperations(actorUserID: "partner")
        ))
        try await pause()

        try expect(controller.connectionState == .connected, "controller did not connect after READY")
        try expect(controller.status.lastAppliedServerRevision == 3, "last applied revision did not advance")
        try expect(controller.canvasState.committedStrokes.count == 1, "remote stroke was not committed")
        try expect(controller.canvasState.committedStrokes.first?.points.count == 4, "remote append points were not applied")
    }

    @MainActor
    private static func debugLocalOperationsAreDurableBeforeSendAndClearedOnAck() async throws {
        let fakeTransport = FakeCanvasSyncTransport()
        let controller = try makeController(transport: fakeTransport)
        controller.updateSession(userID: "mac-user", pairSessionID: "pair", shouldSync: true)
        try await pause()
        fakeTransport.emit(.ready(pairSessionID: "pair", userID: "mac-user", latestRevision: 0, missedOperations: []))
        try await pause()

        controller.submitDebugStroke()
        try await pause(milliseconds: 80)

        let batch = try require(fakeTransport.sentBatches.first, "debug stroke did not send a batch")
        try expect(batch.operations.count == 3, "debug stroke should send add/append/finish operations")
        try expect(controller.status.pendingCount == 0, "sent operations should not remain pending")
        try expect(controller.status.inFlightCount == 3, "sent operations should be in flight before ACK")

        let accepted = batch.operations.enumerated().map { index, operation in
            acceptedOperation(from: operation, actorUserID: "mac-user", pairSessionID: "pair", revision: Int64(index + 1))
        }
        fakeTransport.emit(.ackBatch(
            batchID: batch.batchID,
            ackedClientOperationIDs: batch.operations.map(\.clientOperationId),
            ackedThroughRevision: 3,
            operations: accepted
        ))
        try await pause()

        try expect(controller.status.pendingCount == 0, "ACK did not clear pending operations")
        try expect(controller.status.inFlightCount == 0, "ACK did not clear in-flight operations")
        try expect(controller.status.lastAppliedServerRevision == 3, "ACK did not advance revision")
        try expect(controller.canvasState.committedStrokes.count == 1, "optimistic local stroke was lost")
    }

    @MainActor
    private static func makeController(transport: FakeCanvasSyncTransport) throws -> CanvasSyncController {
        let database = try MulberryDatabase(databaseURL: temporaryDatabaseURL())
        let authorizer = AuthenticatedRequestAuthorizer(
            currentAccessToken: { "access-token" },
            refreshAccessToken: { "access-token" }
        )
        return CanvasSyncController(
            configuration: MacAppConfiguration(apiBaseURL: URL(string: "http://127.0.0.1:8080/")!, googleClientID: ""),
            database: database,
            authorizer: authorizer,
            transport: transport
        )
    }
}

@MainActor
private final class FakeCanvasSyncTransport: CanvasSyncTransport {
    struct SentBatch {
        let batchID: String
        let operations: [CanvasOperation]
    }

    var onMessage: ((CanvasSyncMessage) -> Void)?
    var connectedPairSessionID: String?
    var connectedLastAppliedRevision: Int64?
    var sentBatches: [SentBatch] = []

    func connect(accessToken: String, pairSessionID: String, lastAppliedServerRevision: Int64) {
        connectedPairSessionID = pairSessionID
        connectedLastAppliedRevision = lastAppliedServerRevision
    }

    func sendBatch(batchID: String, operations: [CanvasOperation]) -> CanvasSendResult {
        sentBatches.append(SentBatch(batchID: batchID, operations: operations))
        return .accepted
    }

    func sendPing() {}

    func disconnect() {}

    func emit(_ message: CanvasSyncMessage) {
        onMessage?(message)
    }
}

private func temporaryDatabaseURL() throws -> URL {
    let directory = FileManager.default.temporaryDirectory
        .appendingPathComponent("mulberry-sync-check-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    return directory.appendingPathComponent("test.sqlite")
}

private func remoteStrokeOperations(actorUserID: String) -> [CanvasOperation] {
    let strokeID = "stroke-remote"
    return [
        CanvasOperation(
            clientOperationId: "remote-1",
            actorUserId: actorUserID,
            pairSessionId: "pair",
            type: .addStroke,
            strokeId: strokeID,
            payload: .addStroke(AddStrokePayload(
                id: strokeID,
                colorArgb: 0xFF111111,
                width: 0.02,
                createdAt: 1,
                firstPoint: CanvasPoint(x: 0.2, y: 0.2)
            )),
            clientCreatedAt: "2026-01-01T00:00:00.000Z",
            serverRevision: 1,
            createdAt: "2026-01-01T00:00:00.000Z"
        ),
        CanvasOperation(
            clientOperationId: "remote-2",
            actorUserId: actorUserID,
            pairSessionId: "pair",
            type: .appendPoints,
            strokeId: strokeID,
            payload: .appendPoints(AppendPointsPayload(points: [
                CanvasPoint(x: 0.3, y: 0.3),
                CanvasPoint(x: 0.4, y: 0.25),
                CanvasPoint(x: 0.5, y: 0.35)
            ])),
            clientCreatedAt: "2026-01-01T00:00:00.010Z",
            serverRevision: 2,
            createdAt: "2026-01-01T00:00:00.010Z"
        ),
        CanvasOperation(
            clientOperationId: "remote-3",
            actorUserId: actorUserID,
            pairSessionId: "pair",
            type: .finishStroke,
            strokeId: strokeID,
            payload: .finishStroke,
            clientCreatedAt: "2026-01-01T00:00:00.020Z",
            serverRevision: 3,
            createdAt: "2026-01-01T00:00:00.020Z"
        )
    ]
}

private func acceptedOperation(
    from operation: CanvasOperation,
    actorUserID: String,
    pairSessionID: String,
    revision: Int64
) -> CanvasOperation {
    CanvasOperation(
        clientOperationId: operation.clientOperationId,
        actorUserId: actorUserID,
        pairSessionId: pairSessionID,
        type: operation.type,
        strokeId: operation.strokeId,
        payload: operation.payload,
        clientCreatedAt: operation.clientCreatedAt,
        clientLocalDate: operation.clientLocalDate,
        serverRevision: revision,
        createdAt: "2026-01-01T00:00:00.000Z"
    )
}

private func pause(milliseconds: UInt64 = 30) async throws {
    try await Task.sleep(nanoseconds: milliseconds * 1_000_000)
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
