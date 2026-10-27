import Foundation

/// The document is saved before a pending edit is handed to the network. Atomic
/// replacement means a terminated app leaves either the old or the new document.
public actor DrawingStore {
    private let directory: URL
    public init(directory: URL) { self.directory = directory }

    public func load(userId: String, pairSessionId: String) throws -> DrawingDocument {
        let url = try location(userId: userId, pairSessionId: pairSessionId)
        guard FileManager.default.fileExists(atPath: url.path) else { return .init(userId: userId, pairSessionId: pairSessionId) }
        let document = try JSONDecoder().decode(DrawingDocument.self, from: Data(contentsOf: url))
        guard document.userId == userId, document.pairSessionId == pairSessionId else { throw DrawingError.wrongSession }
        return document
    }

    public func save(_ document: DrawingDocument) throws {
        let url = try location(userId: document.userId, pairSessionId: document.pairSessionId)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(document)
        #if os(iOS)
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: url, options: .atomic)
        #endif
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var savedURL = url
        try savedURL.setResourceValues(values)
    }

    public func remove(userId: String, pairSessionId: String) throws {
        let url = try location(userId: userId, pairSessionId: pairSessionId)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }

    public func finishInterruptedStrokes(userId: String, pairSessionId: String) throws {
        var document = try load(userId: userId, pairSessionId: pairSessionId)
        try document.finishInterruptedStrokes()
        try save(document)
    }

    // Each read/modify/write runs in one actor turn, so a network acknowledgment
    // cannot overwrite an edit made while a previous save was in flight.
    public func enqueue(userId: String, pairSessionId: String, operations: [DrawingOperation]) throws -> DrawingDocument {
        var document = try load(userId: userId, pairSessionId: pairSessionId)
        try document.enqueue(operations)
        try save(document)
        return document
    }

    public func receive(userId: String, pairSessionId: String, operations: [DrawingOperation], rebuild: Bool = false) throws -> DrawingDocument {
        var document = try load(userId: userId, pairSessionId: pairSessionId)
        if rebuild { try document.rebuild(from: operations) }
        else { try document.receive(operations) }
        try save(document)
        return document
    }

    private func location(userId: String, pairSessionId: String) throws -> URL {
        guard let user = UUID(uuidString: userId), let pair = UUID(uuidString: pairSessionId) else { throw DrawingError.wrongSession }
        return directory.appendingPathComponent("\(user.uuidString)-\(pair.uuidString).json")
    }
}
