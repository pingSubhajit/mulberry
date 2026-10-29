import Foundation

public enum DeliveryError: Error, LocalizedError {
    case invalidRevision
    public var errorDescription: String? { "The image is not a Mulberry wallpaper file." }
}

public struct DeliveryEvent: Codable, Identifiable, Sendable {
    public let id: UUID
    public let timestamp: Date
    public let stage: String
    public let revision: Int?
    public let detail: String

    public init(stage: String, revision: Int? = nil, detail: String, timestamp: Date = Date()) {
        id = UUID()
        self.timestamp = timestamp
        self.stage = stage
        self.revision = revision
        self.detail = detail
    }
}

/// One process owns this actor. App Intents run in the app, not a separate extension.
/// The file survives app termination so background runs can be inspected later.
public actor DeliveryJournal {
    public let fileURL: URL
    private var events: [DeliveryEvent]

    public init(directory: URL) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("delivery-events.json")
        if FileManager.default.fileExists(atPath: fileURL.path) {
            events = try JSONDecoder().decode([DeliveryEvent].self, from: Data(contentsOf: fileURL))
        } else {
            events = []
        }
    }

    public func append(_ event: DeliveryEvent) throws {
        var updated = Array((events + [event]).suffix(500))
        updated.sort { $0.timestamp < $1.timestamp }
        try JSONEncoder().encode(updated).write(to: fileURL, options: .atomic)
        events = updated
    }

    public func all() -> [DeliveryEvent] { events }
}
