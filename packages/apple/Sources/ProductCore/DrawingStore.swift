import Foundation

/// Every change is saved before it is shown or handed to the network. A document is kept in
/// memory once read, and each change appends one line to its journal, so saving costs the size
/// of the edit rather than the size of the drawing. The journal is folded into the document's
/// snapshot once it outgrows it. Atomic snapshot replacement, and a journal whose torn final line
/// is ignored, mean a terminated app leaves every change it reported as saved.
///
/// One store owns its directory: the app shares a single instance between its callers.
public actor DrawingStore {
    private let directory: URL
    private var documents: [String: StoredDocument] = [:]

    public init(directory: URL) { self.directory = directory }

    public func load(userId: String, pairSessionId: String) throws -> DrawingDocument {
        try opened(userId: userId, pairSessionId: pairSessionId).document
    }

    /// Replaces the saved document with this one.
    public func save(_ document: DrawingDocument) throws {
        let name = try fileName(userId: document.userId, pairSessionId: document.pairSessionId)
        documents[name] = try writeSnapshot(document, name: name)
    }

    public func remove(userId: String, pairSessionId: String) throws {
        let name = try fileName(userId: userId, pairSessionId: pairSessionId)
        documents[name] = nil
        for url in [snapshotURL(name), journalURL(name)] where FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.removeItem(at: url)
        }
    }

    public func finishInterruptedStrokes(userId: String, pairSessionId: String) throws {
        let finishes = try load(userId: userId, pairSessionId: pairSessionId).interruptedStrokeFinishes
        try commit(.finishInterruptedStrokes(finishes), userId: userId, pairSessionId: pairSessionId)
    }

    // Each read/modify/write runs in one actor turn, so a network acknowledgment
    // cannot overwrite an edit made while a previous save was in flight.
    public func enqueue(userId: String, pairSessionId: String, operations: [DrawingOperation]) throws -> DrawingDocument {
        try commit(.enqueue(operations), userId: userId, pairSessionId: pairSessionId)
    }

    public func receive(userId: String, pairSessionId: String, operations: [DrawingOperation], rebuild: Bool = false) throws -> DrawingDocument {
        try commit(rebuild ? .rebuild(operations) : .receive(operations), userId: userId, pairSessionId: pairSessionId)
    }

    // MARK: Snapshot and journal

    private struct StoredDocument {
        var document: DrawingDocument
        /// The snapshot's size, and the journal's since it was written, which decide when to compact.
        var snapshotBytes: Int
        var journalBytes: Int
    }

    /// A change, with the document version it produced. Replay skips changes the snapshot already
    /// holds, so a compaction cut short between writing the snapshot and clearing the journal is safe.
    private struct JournalEntry: Codable {
        let version: UInt64
        let change: DrawingDocument.Change
    }

    /// The journal is folded into the snapshot once it is larger than this, or than the snapshot.
    private static let minimumCompactionBytes = 64 * 1024

    @discardableResult
    private func commit(_ change: DrawingDocument.Change, userId: String, pairSessionId: String) throws -> DrawingDocument {
        let name = try fileName(userId: userId, pairSessionId: pairSessionId)
        var stored = try opened(userId: userId, pairSessionId: pairSessionId)
        var next = stored.document
        try next.apply(change)
        // Every change that alters a document advances its version; one that does not is not saved.
        guard next.version != stored.document.version else { return next }
        stored.journalBytes += try appendToJournal(JournalEntry(version: next.version, change: change), name: name)
        stored.document = next
        if stored.journalBytes > max(stored.snapshotBytes, Self.minimumCompactionBytes) {
            stored = try writeSnapshot(next, name: name)
        }
        documents[name] = stored
        return next
    }

    private func opened(userId: String, pairSessionId: String) throws -> StoredDocument {
        let name = try fileName(userId: userId, pairSessionId: pairSessionId)
        if let stored = documents[name] { return stored }
        var document = DrawingDocument(userId: userId, pairSessionId: pairSessionId)
        var snapshotBytes = 0
        if FileManager.default.fileExists(atPath: snapshotURL(name).path) {
            let data = try Data(contentsOf: snapshotURL(name))
            document = try JSONDecoder().decode(DrawingDocument.self, from: data)
            guard document.userId == userId, document.pairSessionId == pairSessionId else { throw DrawingError.wrongSession }
            snapshotBytes = data.count
        }
        let journal = try readJournal(name: name)
        for entry in journal.entries where entry.version > document.version {
            try document.apply(entry.change)
            guard document.version == entry.version else { throw CocoaError(.fileReadCorruptFile) }
        }
        let stored = StoredDocument(document: document, snapshotBytes: snapshotBytes, journalBytes: journal.bytes)
        documents[name] = stored
        return stored
    }

    /// The journal's complete entries. A final line without its newline was cut off mid-write, before
    /// its change was reported saved, so it is dropped.
    private func readJournal(name: String) throws -> (entries: [JournalEntry], bytes: Int) {
        let url = journalURL(name)
        guard FileManager.default.fileExists(atPath: url.path) else { return ([], 0) }
        let data = try Data(contentsOf: url)
        guard let end = data.lastIndex(of: UInt8(ascii: "\n")).map({ $0 + 1 }) else {
            try FileManager.default.removeItem(at: url)
            return ([], 0)
        }
        if end < data.endIndex {
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            try handle.truncate(atOffset: UInt64(end))
        }
        let decoder = JSONDecoder()
        let entries = try data[..<end].split(separator: UInt8(ascii: "\n")).map { try decoder.decode(JournalEntry.self, from: Data($0)) }
        return (entries, end)
    }

    /// Appends one entry as a single line, and returns the bytes written.
    private func appendToJournal(_ entry: JournalEntry, name: String) throws -> Int {
        var line = try JSONEncoder().encode(entry)
        line.append(UInt8(ascii: "\n"))
        let url = journalURL(name)
        if !FileManager.default.fileExists(atPath: url.path) {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            guard FileManager.default.createFile(atPath: url.path, contents: nil, attributes: Self.fileAttributes) else {
                throw CocoaError(.fileWriteUnknown)
            }
            try excludeFromBackup(url)
        }
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: line)
        return line.count
    }

    /// Writes the whole document, then clears the journal it now holds.
    private func writeSnapshot(_ document: DrawingDocument, name: String) throws -> StoredDocument {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(document)
        #if os(iOS)
        try data.write(to: snapshotURL(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: snapshotURL(name), options: .atomic)
        #endif
        try excludeFromBackup(snapshotURL(name))
        if FileManager.default.fileExists(atPath: journalURL(name).path) { try FileManager.default.removeItem(at: journalURL(name)) }
        return StoredDocument(document: document, snapshotBytes: data.count, journalBytes: 0)
    }

    private static var fileAttributes: [FileAttributeKey: Any]? {
        #if os(iOS)
        [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        #else
        nil
        #endif
    }

    private func excludeFromBackup(_ url: URL) throws {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var excluded = url
        try excluded.setResourceValues(values)
    }

    private func fileName(userId: String, pairSessionId: String) throws -> String {
        guard let user = UUID(uuidString: userId), let pair = UUID(uuidString: pairSessionId) else { throw DrawingError.wrongSession }
        return "\(user.uuidString)-\(pair.uuidString)"
    }

    private func snapshotURL(_ name: String) -> URL { directory.appendingPathComponent("\(name).json") }
    private func journalURL(_ name: String) -> URL { directory.appendingPathComponent("\(name).journal") }
}
