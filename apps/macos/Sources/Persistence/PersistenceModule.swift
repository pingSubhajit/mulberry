import Foundation
import CanvasCore
import GRDB

public enum PersistenceModule {
    public static let name = "Persistence"
}

public struct PersistedSessionState: Sendable, Equatable {
    public let userID: String?
    public let userEmail: String?
    public let userDisplayName: String?
    public let pairSessionID: String?

    public init(
        userID: String?,
        userEmail: String?,
        userDisplayName: String?,
        pairSessionID: String?
    ) {
        self.userID = userID
        self.userEmail = userEmail
        self.userDisplayName = userDisplayName
        self.pairSessionID = pairSessionID
    }
}

public struct CachedBootstrapState: Sendable, Equatable {
    public let payloadJSON: String
    public let updatedAt: Date

    public init(payloadJSON: String, updatedAt: Date) {
        self.payloadJSON = payloadJSON
        self.updatedAt = updatedAt
    }
}

public struct SyncMetadataState: Sendable, Equatable {
    public let pairSessionID: String?
    public let lastAppliedServerRevision: Int64
    public let latestKnownServerRevision: Int64
    public let lastError: String?

    public init(
        pairSessionID: String?,
        lastAppliedServerRevision: Int64,
        latestKnownServerRevision: Int64,
        lastError: String?
    ) {
        self.pairSessionID = pairSessionID
        self.lastAppliedServerRevision = lastAppliedServerRevision
        self.latestKnownServerRevision = latestKnownServerRevision
        self.lastError = lastError
    }
}

public enum PersistAcceptedOperationResult: Sendable, Equatable {
    case inserted
    case duplicate
    case conflict(String)
}

public struct PendingOperationRecord: Sendable, Equatable {
    public let operation: CanvasOperation
    public let sequenceNumber: Int64
    public let status: String
    public let batchID: String?
    public let attemptCount: Int
    public let lastSentAt: Date?

    public init(
        operation: CanvasOperation,
        sequenceNumber: Int64,
        status: String,
        batchID: String?,
        attemptCount: Int,
        lastSentAt: Date?
    ) {
        self.operation = operation
        self.sequenceNumber = sequenceNumber
        self.status = status
        self.batchID = batchID
        self.attemptCount = attemptCount
        self.lastSentAt = lastSentAt
    }
}

public struct PendingOperationSummary: Sendable, Equatable {
    public let pending: Int
    public let inFlight: Int

    public init(pending: Int, inFlight: Int) {
        self.pending = pending
        self.inFlight = inFlight
    }

    public var total: Int { pending + inFlight }
}

public struct PersistedCanvasSnapshot: Sendable, Equatable {
    public let pairSessionID: String
    public let snapshotRevision: Int64
    public let latestRevision: Int64
    public let state: CanvasState
    public let updatedAt: Date

    public init(
        pairSessionID: String,
        snapshotRevision: Int64,
        latestRevision: Int64,
        state: CanvasState,
        updatedAt: Date
    ) {
        self.pairSessionID = pairSessionID
        self.snapshotRevision = snapshotRevision
        self.latestRevision = latestRevision
        self.state = state
        self.updatedAt = updatedAt
    }
}

public final class MulberryDatabase: @unchecked Sendable {
    private let writer: DatabaseQueue
    private let operationEncoder = JSONEncoder()
    private let operationDecoder = JSONDecoder()
    private let snapshotEncoder = JSONEncoder()
    private let snapshotDecoder = JSONDecoder()

    public convenience init(fileManager: FileManager = .default) throws {
        try self.init(databaseURL: Self.defaultDatabaseURL(fileManager: fileManager), fileManager: fileManager)
    }

    public init(databaseURL: URL, fileManager: FileManager = .default) throws {
        try fileManager.createDirectory(
            at: databaseURL.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )

        var configuration = Configuration()
        configuration.prepareDatabase { database in
            try database.execute(sql: "PRAGMA foreign_keys = ON")
        }

        writer = try DatabaseQueue(path: databaseURL.path, configuration: configuration)
        try Self.makeMigrator().migrate(writer)
    }

    public static func defaultDatabaseURL(fileManager: FileManager = .default) throws -> URL {
        let baseURL = try fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return baseURL
            .appendingPathComponent("Mulberry", isDirectory: true)
            .appendingPathComponent("Mulberry.sqlite")
    }

    public func saveBootstrap(payloadJSON: String, session: PersistedSessionState) throws {
        try writer.write { database in
            try database.execute(
                sql: """
                INSERT INTO session_state (
                    id,
                    user_id,
                    user_email,
                    user_display_name,
                    pair_session_id,
                    updated_at
                ) VALUES ('singleton', ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    user_id = excluded.user_id,
                    user_email = excluded.user_email,
                    user_display_name = excluded.user_display_name,
                    pair_session_id = excluded.pair_session_id,
                    updated_at = excluded.updated_at
                """,
                arguments: [
                    session.userID,
                    session.userEmail,
                    session.userDisplayName,
                    session.pairSessionID,
                    Date()
                ]
            )

            try database.execute(
                sql: """
                INSERT INTO bootstrap_cache (id, payload_json, updated_at)
                VALUES ('singleton', ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    payload_json = excluded.payload_json,
                    updated_at = excluded.updated_at
                """,
                arguments: [payloadJSON, Date()]
            )
        }
    }

    public func cachedBootstrap() throws -> CachedBootstrapState? {
        try writer.read { database in
            guard let row = try Row.fetchOne(
                database,
                sql: "SELECT payload_json, updated_at FROM bootstrap_cache WHERE id = 'singleton'"
            ) else {
                return nil
            }
            return CachedBootstrapState(
                payloadJSON: row["payload_json"],
                updatedAt: row["updated_at"]
            )
        }
    }

    public func clearSessionState() throws {
        try writer.write { database in
            try database.execute(sql: "DELETE FROM bootstrap_cache")
            try database.execute(sql: "DELETE FROM session_state")
            try database.execute(sql: "DELETE FROM presence_surfaces")
            try database.execute(sql: "DELETE FROM sync_metadata")
            try database.execute(sql: "DELETE FROM pending_operations")
            try database.execute(sql: "DELETE FROM canvas_operations")
            try database.execute(sql: "DELETE FROM canvas_snapshot")
        }
    }

    public func installationID() throws -> String {
        try writer.write { database in
            if let row = try Row.fetchOne(
                database,
                sql: "SELECT value FROM app_metadata WHERE key = 'installation_id'"
            ) {
                return row["value"]
            }

            let id = UUID().uuidString.lowercased()
            try database.execute(
                sql: "INSERT INTO app_metadata (key, value) VALUES ('installation_id', ?)",
                arguments: [id]
            )
            return id
        }
    }

    public func syncMetadata() throws -> SyncMetadataState {
        try writer.read { database in
            guard let row = try Row.fetchOne(
                database,
                sql: """
                SELECT pair_session_id, last_applied_server_revision, latest_known_server_revision, last_error
                FROM sync_metadata
                WHERE id = 'singleton'
                """
            ) else {
                return SyncMetadataState(
                    pairSessionID: nil,
                    lastAppliedServerRevision: 0,
                    latestKnownServerRevision: 0,
                    lastError: nil
                )
            }

            return SyncMetadataState(
                pairSessionID: row["pair_session_id"],
                lastAppliedServerRevision: row["last_applied_server_revision"],
                latestKnownServerRevision: row["latest_known_server_revision"],
                lastError: row["last_error"]
            )
        }
    }

    public func resetSyncScope(pairSessionID: String) throws {
        try writer.write { database in
            try database.execute(sql: "DELETE FROM pending_operations")
            try database.execute(sql: "DELETE FROM canvas_operations WHERE pair_session_id != ?", arguments: [pairSessionID])
            try database.execute(sql: "DELETE FROM canvas_snapshot WHERE pair_session_id != ?", arguments: [pairSessionID])
            try upsertSyncMetadata(
                database,
                pairSessionID: pairSessionID,
                lastAppliedServerRevision: 0,
                latestKnownServerRevision: 0,
                lastError: nil
            )
        }
    }

    public func updateSyncMetadata(
        pairSessionID: String?,
        lastAppliedServerRevision: Int64? = nil,
        latestKnownServerRevision: Int64? = nil,
        lastError: String? = nil,
        clearsLastError: Bool = false
    ) throws {
        try writer.write { database in
            let current = try self.syncMetadata(in: database)
            try upsertSyncMetadata(
                database,
                pairSessionID: pairSessionID ?? current.pairSessionID,
                lastAppliedServerRevision: lastAppliedServerRevision ?? current.lastAppliedServerRevision,
                latestKnownServerRevision: latestKnownServerRevision ?? current.latestKnownServerRevision,
                lastError: clearsLastError ? nil : (lastError ?? current.lastError)
            )
        }
    }

    public func enqueuePendingOperation(_ operation: CanvasOperation) throws {
        let operationJSON = try encodeOperation(operation)
        try writer.write { database in
            let row = try Row.fetchOne(database, sql: "SELECT COALESCE(MAX(sequence_number), 0) AS sequence_number FROM pending_operations")
            let nextSequence: Int64 = (row?["sequence_number"] ?? 0) + 1
            try database.execute(
                sql: """
                INSERT OR IGNORE INTO pending_operations (
                    client_operation_id,
                    sequence_number,
                    operation_json,
                    status,
                    created_at
                ) VALUES (?, ?, ?, 'PENDING', ?)
                """,
                arguments: [
                    operation.clientOperationId,
                    nextSequence,
                    operationJSON,
                    Date()
                ]
            )
        }
    }

    public func pendingOperationRecords() throws -> [PendingOperationRecord] {
        try writer.read { database in
            let rows = try Row.fetchAll(
                database,
                sql: """
                SELECT operation_json, sequence_number, status, batch_id, attempt_count, last_sent_at
                FROM pending_operations
                ORDER BY sequence_number ASC, created_at ASC
                """
            )
            return try rows.map(pendingOperationRecord(from:))
        }
    }

    public func nextPendingOperations(maxOperations: Int, maxPayloadBytes: Int) throws -> [CanvasOperation] {
        try writer.read { database in
            let rows = try Row.fetchAll(
                database,
                sql: """
                SELECT operation_json, sequence_number, status, batch_id, attempt_count, last_sent_at
                FROM pending_operations
                WHERE status = 'PENDING'
                ORDER BY sequence_number ASC, created_at ASC
                LIMIT ?
                """,
                arguments: [maxOperations]
            )

            var selected: [CanvasOperation] = []
            var sizeBytes = 0
            for row in rows {
                let record = try pendingOperationRecord(from: row)
                let operationJSON: String = row["operation_json"]
                let bytes = operationJSON.utf8.count
                if selected.isEmpty == false && sizeBytes + bytes > maxPayloadBytes {
                    break
                }
                selected.append(record.operation)
                sizeBytes += bytes
            }
            return selected
        }
    }

    public func markPendingOperationsInFlight(
        clientOperationIDs: [String],
        batchID: String,
        sentAt: Date = Date()
    ) throws {
        guard clientOperationIDs.isEmpty == false else { return }
        let placeholders = clientOperationIDs.map { _ in "?" }.joined(separator: ",")
        var arguments: StatementArguments = [batchID, sentAt]
        arguments += StatementArguments(clientOperationIDs)
        try writer.write { database in
            try database.execute(
                sql: """
                UPDATE pending_operations
                SET status = 'IN_FLIGHT',
                    batch_id = ?,
                    last_sent_at = ?,
                    attempt_count = attempt_count + 1
                WHERE client_operation_id IN (\(placeholders))
                """,
                arguments: arguments
            )
        }
    }

    public func acknowledgePendingOperations(clientOperationIDs: [String]) throws {
        guard clientOperationIDs.isEmpty == false else { return }
        try writer.write { database in
            try database.execute(
                sql: "DELETE FROM pending_operations WHERE client_operation_id IN (\(clientOperationIDs.map { _ in "?" }.joined(separator: ",")))",
                arguments: StatementArguments(clientOperationIDs)
            )
        }
    }

    public func resetInFlightPendingOperations(staleBefore: Date? = nil) throws -> Int {
        try writer.write { database in
            if let staleBefore {
                try database.execute(
                    sql: """
                    UPDATE pending_operations
                    SET status = 'PENDING',
                        batch_id = NULL,
                        last_sent_at = NULL
                    WHERE status = 'IN_FLIGHT'
                      AND last_sent_at IS NOT NULL
                      AND last_sent_at < ?
                    """,
                    arguments: [staleBefore]
                )
                return database.changesCount
            }

            try database.execute(
                sql: """
                UPDATE pending_operations
                SET status = 'PENDING',
                    batch_id = NULL,
                    last_sent_at = NULL
                WHERE status = 'IN_FLIGHT'
                """
            )
            return database.changesCount
        }
    }

    public func pendingOperationSummary() throws -> PendingOperationSummary {
        try writer.read { database in
            let pending = try Int.fetchOne(database, sql: "SELECT COUNT(*) FROM pending_operations WHERE status = 'PENDING'") ?? 0
            let inFlight = try Int.fetchOne(database, sql: "SELECT COUNT(*) FROM pending_operations WHERE status = 'IN_FLIGHT'") ?? 0
            return PendingOperationSummary(pending: pending, inFlight: inFlight)
        }
    }

    public func hasPendingOperations() throws -> Bool {
        try writer.read { database in
            (try Int.fetchOne(database, sql: "SELECT COUNT(*) FROM pending_operations") ?? 0) > 0
        }
    }

    public func persistAcceptedOperation(_ operation: CanvasOperation) throws -> PersistAcceptedOperationResult {
        guard let pairSessionID = operation.pairSessionId,
              let serverRevision = operation.serverRevision
        else {
            return .conflict("Accepted operations must include pairSessionId and serverRevision.")
        }

        let operationJSON = try encodeOperation(operation)
        return try writer.write { database in
            let existingRevision = try Row.fetchOne(
                database,
                sql: """
                SELECT operation_json, client_operation_id
                FROM canvas_operations
                WHERE pair_session_id = ? AND server_revision = ?
                """,
                arguments: [pairSessionID, serverRevision]
            )
            if let existingRevision {
                let existingJSON: String = existingRevision["operation_json"]
                if try decodeOperation(existingJSON) == operation {
                    return .duplicate
                }
                return .conflict("Server revision \(serverRevision) already exists with a different operation.")
            }

            let existingClientID = try Row.fetchOne(
                database,
                sql: """
                SELECT operation_json, server_revision
                FROM canvas_operations
                WHERE pair_session_id = ? AND client_operation_id = ?
                """,
                arguments: [pairSessionID, operation.clientOperationId]
            )
            if let existingClientID {
                let existingJSON: String = existingClientID["operation_json"]
                if try decodeOperation(existingJSON) == operation {
                    return .duplicate
                }
                return .conflict("Client operation \(operation.clientOperationId) already exists with a different payload.")
            }

            try database.execute(
                sql: """
                INSERT INTO canvas_operations (
                    pair_session_id,
                    server_revision,
                    client_operation_id,
                    actor_user_id,
                    operation_json,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                arguments: [
                    pairSessionID,
                    serverRevision,
                    operation.clientOperationId,
                    operation.actorUserId,
                    operationJSON,
                    Date()
                ]
            )
            return .inserted
        }
    }

    public func acceptedOperations(pairSessionID: String) throws -> [CanvasOperation] {
        try writer.read { database in
            let rows = try Row.fetchAll(
                database,
                sql: """
                SELECT operation_json
                FROM canvas_operations
                WHERE pair_session_id = ?
                ORDER BY server_revision ASC
                """,
                arguments: [pairSessionID]
            )
            return try rows.map { row in
                let json: String = row["operation_json"]
                return try decodeOperation(json)
            }
        }
    }

    public func acceptedOperations(pairSessionID: String, afterRevision: Int64) throws -> [CanvasOperation] {
        try writer.read { database in
            let rows = try Row.fetchAll(
                database,
                sql: """
                SELECT operation_json
                FROM canvas_operations
                WHERE pair_session_id = ? AND server_revision > ?
                ORDER BY server_revision ASC
                """,
                arguments: [pairSessionID, afterRevision]
            )
            return try rows.map { row in
                let json: String = row["operation_json"]
                return try decodeOperation(json)
            }
        }
    }

    public func saveCanvasSnapshot(
        pairSessionID: String,
        snapshotRevision: Int64,
        latestRevision: Int64,
        state: CanvasState
    ) throws {
        let data = try snapshotEncoder.encode(CanvasSnapshotRecord(state: state))
        let json = String(decoding: data, as: UTF8.self)
        try writer.write { database in
            try database.execute(
                sql: """
                INSERT INTO canvas_snapshot (
                    pair_session_id,
                    snapshot_revision,
                    latest_revision,
                    state_json,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(pair_session_id) DO UPDATE SET
                    snapshot_revision = excluded.snapshot_revision,
                    latest_revision = excluded.latest_revision,
                    state_json = excluded.state_json,
                    updated_at = excluded.updated_at
                """,
                arguments: [pairSessionID, snapshotRevision, latestRevision, json, Date()]
            )
        }
    }

    public func canvasSnapshot(pairSessionID: String) throws -> PersistedCanvasSnapshot? {
        try writer.read { database in
            guard let row = try Row.fetchOne(
                database,
                sql: """
                SELECT snapshot_revision, latest_revision, state_json, updated_at
                FROM canvas_snapshot
                WHERE pair_session_id = ?
                """,
                arguments: [pairSessionID]
            ) else {
                return nil
            }
            let stateJSON: String = row["state_json"]
            let record = try snapshotDecoder.decode(CanvasSnapshotRecord.self, from: Data(stateJSON.utf8))
            return PersistedCanvasSnapshot(
                pairSessionID: pairSessionID,
                snapshotRevision: row["snapshot_revision"],
                latestRevision: row["latest_revision"],
                state: record.state,
                updatedAt: row["updated_at"]
            )
        }
    }

    private static func makeMigrator() -> DatabaseMigrator {
        var migrator = DatabaseMigrator()
        migrator.registerMigration("v1_app_state") { database in
            try database.create(table: "app_metadata", ifNotExists: true) { table in
                table.column("key", .text).primaryKey()
                table.column("value", .text)
            }

            try database.create(table: "session_state", ifNotExists: true) { table in
                table.column("id", .text).primaryKey()
                table.column("user_id", .text)
                table.column("user_email", .text)
                table.column("user_display_name", .text)
                table.column("pair_session_id", .text)
                table.column("updated_at", .datetime).notNull()
            }

            try database.create(table: "bootstrap_cache", ifNotExists: true) { table in
                table.column("id", .text).primaryKey()
                table.column("payload_json", .text).notNull()
                table.column("updated_at", .datetime).notNull()
            }

            try database.create(table: "presence_surfaces", ifNotExists: true) { table in
                table.column("owner", .text).notNull()
                table.column("surface_type", .text).notNull()
                table.column("device_instance_id", .text).notNull()
                table.column("configured", .boolean).notNull()
                table.column("enabled", .boolean).notNull()
                table.column("can_see_latest_drawings", .boolean).notNull()
                table.column("has_ever_been_able_to_see", .boolean).notNull()
                table.column("details_json", .text).notNull().defaults(to: "{}")
                table.column("updated_at", .datetime).notNull()
                table.primaryKey(["owner", "surface_type", "device_instance_id"])
            }
        }
        migrator.registerMigration("v2_canvas_sync") { database in
            try database.create(table: "sync_metadata", ifNotExists: true) { table in
                table.column("id", .text).primaryKey()
                table.column("pair_session_id", .text)
                table.column("last_applied_server_revision", .integer).notNull().defaults(to: 0)
                table.column("latest_known_server_revision", .integer).notNull().defaults(to: 0)
                table.column("last_error", .text)
                table.column("updated_at", .datetime).notNull()
            }

            try database.create(table: "canvas_operations", ifNotExists: true) { table in
                table.column("pair_session_id", .text).notNull()
                table.column("server_revision", .integer).notNull()
                table.column("client_operation_id", .text).notNull()
                table.column("actor_user_id", .text)
                table.column("operation_json", .text).notNull()
                table.column("created_at", .datetime).notNull()
                table.primaryKey(["pair_session_id", "server_revision"])
                table.uniqueKey(["pair_session_id", "client_operation_id"])
            }

            try database.create(index: "canvas_operations_client_id_idx", on: "canvas_operations", columns: ["client_operation_id"], ifNotExists: true)

            try database.create(table: "pending_operations", ifNotExists: true) { table in
                table.column("client_operation_id", .text).primaryKey()
                table.column("sequence_number", .integer).notNull()
                table.column("operation_json", .text).notNull()
                table.column("status", .text).notNull().defaults(to: "PENDING")
                table.column("batch_id", .text)
                table.column("attempt_count", .integer).notNull().defaults(to: 0)
                table.column("last_sent_at", .datetime)
                table.column("created_at", .datetime).notNull()
            }

            try database.create(index: "pending_operations_status_sequence_idx", on: "pending_operations", columns: ["status", "sequence_number"], ifNotExists: true)
            try database.create(index: "pending_operations_batch_id_idx", on: "pending_operations", columns: ["batch_id"], ifNotExists: true)

            try database.create(table: "canvas_snapshot", ifNotExists: true) { table in
                table.column("pair_session_id", .text).primaryKey()
                table.column("snapshot_revision", .integer).notNull()
                table.column("latest_revision", .integer).notNull()
                table.column("state_json", .text).notNull()
                table.column("updated_at", .datetime).notNull()
            }
        }
        return migrator
    }

    private func syncMetadata(in database: Database) throws -> SyncMetadataState {
        guard let row = try Row.fetchOne(
            database,
            sql: """
            SELECT pair_session_id, last_applied_server_revision, latest_known_server_revision, last_error
            FROM sync_metadata
            WHERE id = 'singleton'
            """
        ) else {
            return SyncMetadataState(
                pairSessionID: nil,
                lastAppliedServerRevision: 0,
                latestKnownServerRevision: 0,
                lastError: nil
            )
        }
        return SyncMetadataState(
            pairSessionID: row["pair_session_id"],
            lastAppliedServerRevision: row["last_applied_server_revision"],
            latestKnownServerRevision: row["latest_known_server_revision"],
            lastError: row["last_error"]
        )
    }

    private func upsertSyncMetadata(
        _ database: Database,
        pairSessionID: String?,
        lastAppliedServerRevision: Int64,
        latestKnownServerRevision: Int64,
        lastError: String?
    ) throws {
        try database.execute(
            sql: """
            INSERT INTO sync_metadata (
                id,
                pair_session_id,
                last_applied_server_revision,
                latest_known_server_revision,
                last_error,
                updated_at
            ) VALUES ('singleton', ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                pair_session_id = excluded.pair_session_id,
                last_applied_server_revision = excluded.last_applied_server_revision,
                latest_known_server_revision = excluded.latest_known_server_revision,
                last_error = excluded.last_error,
                updated_at = excluded.updated_at
            """,
            arguments: [
                pairSessionID,
                lastAppliedServerRevision,
                latestKnownServerRevision,
                lastError,
                Date()
            ]
        )
    }

    private func encodeOperation(_ operation: CanvasOperation) throws -> String {
        let data = try operationEncoder.encode(operation)
        return String(decoding: data, as: UTF8.self)
    }

    private func decodeOperation(_ json: String) throws -> CanvasOperation {
        try operationDecoder.decode(CanvasOperation.self, from: Data(json.utf8))
    }

    private func pendingOperationRecord(from row: Row) throws -> PendingOperationRecord {
        let json: String = row["operation_json"]
        return PendingOperationRecord(
            operation: try decodeOperation(json),
            sequenceNumber: row["sequence_number"],
            status: row["status"],
            batchID: row["batch_id"],
            attemptCount: row["attempt_count"],
            lastSentAt: row["last_sent_at"]
        )
    }
}

private struct CanvasSnapshotRecord: Codable {
    let state: CanvasState
}
