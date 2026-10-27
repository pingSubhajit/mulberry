import Foundation

/// Fetching an image never counts as applying a wallpaper.
/// Only the separate Shortcut receipt action records that Set Wallpaper returned.
public actor DeliveryService {
    private let client: CanvasClient
    private let journal: DeliveryJournal
    private let directory: URL

    public init(client: CanvasClient, journal: DeliveryJournal, directory: URL) {
        self.client = client
        self.journal = journal
        self.directory = directory
    }

    public func prepare(source: String) async throws -> PreparedCanvas {
        try await log(stage: "fetch_started", detail: source)
        do {
            let manifest = try await client.manifest()
            let data = try await client.image(for: manifest)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let file = directory.appendingPathComponent("mulberry-r\(manifest.revision).png")
            try data.write(to: file, options: .atomic)
            try await log(stage: "image_ready", revision: manifest.revision, detail: source)
            return PreparedCanvas(revision: manifest.revision, fileURL: file, publishedAt: manifest.publishedAt)
        } catch {
            try? await log(stage: "fetch_failed", detail: error.localizedDescription)
            throw error
        }
    }

    public func recordShortcutCompletion(filename: String) async throws {
        let revision = try Self.revision(from: filename)
        try await log(stage: "shortcut_reported_applied", revision: revision,
                      detail: "Set Wallpaper returned. Visual confirmation is separate.")
    }

    public func log(stage: String, revision: Int? = nil, detail: String) async throws {
        let event = DeliveryEvent(stage: stage, revision: revision, detail: detail)
        try await journal.append(event)
        // Remote telemetry is best effort; it must never stop local delivery.
        do { try await client.report(event) }
        catch {
            try? await journal.append(DeliveryEvent(stage: "telemetry_failed", revision: revision,
                                                   detail: error.localizedDescription))
        }
    }

    public static func revision(from filename: String) throws -> Int {
        guard filename.hasPrefix("mulberry-r"), filename.hasSuffix(".png"),
              let number = Int(filename.dropFirst(10).dropLast(4)), number > 0 else {
            throw DeliveryError.invalidRevision
        }
        return number
    }
}
