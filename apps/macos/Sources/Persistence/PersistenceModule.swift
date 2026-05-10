import Foundation
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

public final class MulberryDatabase: @unchecked Sendable {
    private let writer: DatabaseQueue

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
        return migrator
    }
}
