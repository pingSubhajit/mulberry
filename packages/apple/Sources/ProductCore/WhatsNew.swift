import Combine
import Foundation

/// Which app's notes to read. Each app has its own release schedule and version numbers, so the
/// server keeps a folder of notes for each: `whats-new/ios`, `whats-new/macos`.
public enum WhatsNewPlatform: String, Sendable {
    case ios, macos
}

/// A page of notes from `GET /whats-new/:platform`, newest first.
public struct WhatsNewPage: Decodable, Sendable, Equatable {
    public struct Item: Decodable, Sendable, Equatable {
        public let version: String
        public let rawMarkdown: String
    }
    public let items: [Item]
    public let nextCursor: String?
}

/// Where the notes come from: the backend, or a stand-in in tests.
public protocol WhatsNewSource: Sendable {
    /// The note for `version`, or nil when that version has none.
    func whatsNewNote(platform: WhatsNewPlatform, version: String) async throws -> String?
    func latestWhatsNewNote(platform: WhatsNewPlatform) async throws -> String?
    func whatsNewNotes(platform: WhatsNewPlatform, cursor: String?, limit: Int) async throws -> WhatsNewPage
}

extension BackendClient: WhatsNewSource {}

/// One release's note: a frontmatter block, then markdown. The same files Android reads.
///
/// ```text
/// ---
/// version: 1.1.0
/// released_at: 2026-11-06
/// title: Photos on the canvas
/// hero_image: /whats-new/ios/assets/1.1.0/hero.jpg
/// cta_label: Sounds awesome!
/// ---
/// ```
public struct WhatsNewEntry: Sendable, Equatable, Identifiable {
    public static let defaultCallToAction = "Sounds awesome!"

    public let version: String?
    /// The release day, at midnight UTC.
    public let releasedOn: Date?
    public let title: String?
    /// A path on Mulberry's server, or a full URL.
    public let heroImage: String?
    public let callToAction: String
    public let markdown: String

    public var id: String { version ?? title ?? markdown }

    public init(version: String? = nil, releasedOn: Date? = nil, title: String? = nil, heroImage: String? = nil,
                callToAction: String = WhatsNewEntry.defaultCallToAction, markdown: String = "") {
        self.version = version; self.releasedOn = releasedOn; self.title = title
        self.heroImage = heroImage; self.callToAction = callToAction; self.markdown = markdown
    }

    /// Reads a note. `fallbackVersion` names it when its frontmatter doesn't.
    public static func parse(_ raw: String, fallbackVersion: String? = nil) -> WhatsNewEntry {
        let (fields, body) = splitFrontmatter(raw)
        return WhatsNewEntry(
            version: fields["version"] ?? fallbackVersion,
            releasedOn: fields["released_at"].flatMap { day.date(from: $0) },
            title: fields["title"],
            heroImage: fields["hero_image"],
            callToAction: fields["cta_label"] ?? defaultCallToAction,
            markdown: body.trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    /// "Released on 8 May, 2026 • Version 1.1.7", or as much of it as the note gives.
    public var subtitle: String? {
        let date = releasedOn.map { "Released on \(Self.display.string(from: $0))" }
        let version = version.map { "Version \($0)" }
        let parts = [date, version].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " • ")
    }

    /// The hero image's address, on Mulberry's server unless the note gives a full URL.
    public func heroImageURL(relativeTo baseURL: URL) -> URL? {
        guard let raw = heroImage?.trimmingCharacters(in: .whitespaces), !raw.isEmpty else { return nil }
        if raw.hasPrefix("https://") || raw.hasPrefix("http://") { return URL(string: raw) }
        var base = baseURL.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        return URL(string: base + (raw.hasPrefix("/") ? raw : "/" + raw))
    }

    /// The note's body, as blocks to lay out one under another.
    public var blocks: [WhatsNewBlock] { WhatsNewBlock.parse(markdown) }

    /// Whether `value` can name a note: the server's rule for version keys.
    public static func isVersionKey(_ value: String) -> Bool {
        value.range(of: #"^[0-9][0-9A-Za-z.+-]*$"#, options: .regularExpression) != nil && !value.contains("..")
    }

    private static func splitFrontmatter(_ raw: String) -> ([String: String], String) {
        let lines = raw.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n")
        guard lines.first?.trimmingCharacters(in: .whitespaces) == "---",
              let end = lines.dropFirst().firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "---" }) else {
            return ([:], raw)
        }
        var fields: [String: String] = [:]
        for line in lines[1..<end] {
            guard let colon = line.firstIndex(of: ":"), colon != line.startIndex else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespaces)
            var value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
            if value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") { value = String(value.dropFirst().dropLast()) }
            if !key.isEmpty, !value.isEmpty { fields[key] = value }
        }
        return (fields, lines[(end + 1)...].joined(separator: "\n"))
    }

    private static let day: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    /// As Android shows it: "8 May, 2026".
    private static let display: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "d MMMM, yyyy"
        return formatter
    }()
}

/// The block markdown the notes use. Each block's text is still inline markdown (bold, italics,
/// links), for `AttributedString(markdown:)` to style.
public enum WhatsNewBlock: Equatable, Sendable {
    case heading(level: Int, text: String)
    case paragraph(String)
    case bullets([String])
    case numbered(start: Int, items: [String])

    public static func parse(_ markdown: String) -> [WhatsNewBlock] {
        var blocks: [WhatsNewBlock] = []
        var paragraph: [String] = []
        var bullets: [String] = []
        var numbered: [String] = []
        var numberedStart = 1

        func flush() {
            if !paragraph.isEmpty { blocks.append(.paragraph(paragraph.joined(separator: " "))); paragraph = [] }
            if !bullets.isEmpty { blocks.append(.bullets(bullets)); bullets = [] }
            if !numbered.isEmpty { blocks.append(.numbered(start: numberedStart, items: numbered)); numbered = [] }
        }

        for rawLine in markdown.replacingOccurrences(of: "\r\n", with: "\n").components(separatedBy: "\n") {
            let indented = rawLine.hasPrefix("  ") || rawLine.hasPrefix("\t")
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { flush(); continue }
            if let heading = headingText(line) {
                flush(); blocks.append(.heading(level: heading.level, text: heading.text)); continue
            }
            if let item = bulletText(line) {
                if bullets.isEmpty { flush() }
                bullets.append(item); continue
            }
            if let item = numberedText(line) {
                if numbered.isEmpty { flush(); numberedStart = item.number }
                numbered.append(item.text); continue
            }
            // A line under a list item carries on that item.
            if indented || paragraph.isEmpty, let last = bullets.indices.last {
                bullets[last] += " " + line; continue
            }
            if indented || paragraph.isEmpty, let last = numbered.indices.last {
                numbered[last] += " " + line; continue
            }
            paragraph.append(line)
        }
        flush()
        return blocks
    }

    private static func headingText(_ line: String) -> (level: Int, text: String)? {
        let hashes = line.prefix { $0 == "#" }.count
        guard (1...6).contains(hashes) else { return nil }
        let rest = line.dropFirst(hashes)
        guard rest.first == " " else { return nil }
        return (hashes, rest.trimmingCharacters(in: .whitespaces))
    }

    private static func bulletText(_ line: String) -> String? {
        for marker in ["- ", "* ", "+ "] where line.hasPrefix(marker) {
            return line.dropFirst(2).trimmingCharacters(in: .whitespaces)
        }
        return nil
    }

    private static func numberedText(_ line: String) -> (number: Int, text: String)? {
        let digits = line.prefix { $0.isNumber }
        guard !digits.isEmpty, digits.count <= 9, let number = Int(digits) else { return nil }
        let rest = line.dropFirst(digits.count)
        guard rest.hasPrefix(". ") || rest.hasPrefix(") ") else { return nil }
        return (number, rest.dropFirst(2).trimmingCharacters(in: .whitespaces))
    }
}

/// Shows a version's note on its own once: the first time the app opens on that version, as on
/// Android. A version without a note counts as seen, so one added later never shows on its own to
/// people already on it. When the server can't be reached it tries again later, waiting 30 seconds
/// at first and doubling up to 6 hours. Shared by the iPhone, iPad and Mac.
@MainActor
public final class WhatsNewModel: ObservableObject {
    /// Whether this version has a note, for the dot on the Settings row.
    @Published public private(set) var currentVersionHasNote = false

    public let platform: WhatsNewPlatform
    /// `CFBundleShortVersionString`, the version the notes are named after.
    public let version: String
    private let source: @MainActor () throws -> any WhatsNewSource
    private let defaults: UserDefaults
    private var checking = false

    private static let stateKey = "whatsNewPromptState"
    static let baseRetry: TimeInterval = 30
    static let maximumRetry: TimeInterval = 6 * 60 * 60
    static let maximumAttempts = 10

    struct PromptState: Codable, Equatable {
        var lastSeenVersion: String?
        var pendingVersion: String?
        var nextRetryAt: Date?
        var retryAttempt = 0
    }

    public init(platform: WhatsNewPlatform, version: String, defaults: UserDefaults = .standard,
                source: @escaping @MainActor () throws -> any WhatsNewSource) {
        self.platform = platform; self.version = version; self.defaults = defaults; self.source = source
    }

    /// The note to show on its own now, if this version has one nobody has seen yet. Showing it is
    /// up to the caller, who calls `markSeen` once it closes.
    public func dueNote(now: Date = .now) async -> WhatsNewEntry? {
        guard !checking else { return nil }
        checking = true
        defer { checking = false }

        var state = self.state
        if state.lastSeenVersion == version { return nil }
        if state.pendingVersion != version {
            state = PromptState(lastSeenVersion: state.lastSeenVersion, pendingVersion: version)
            self.state = state
        }
        if let next = state.nextRetryAt, now < next { return nil }

        do {
            guard let raw = try await source().whatsNewNote(platform: platform, version: version) else {
                currentVersionHasNote = false
                markSeen(version)
                return nil
            }
            currentVersionHasNote = true
            return WhatsNewEntry.parse(raw, fallbackVersion: version)
        } catch is CancellationError {
            return nil
        } catch {
            if Self.isRetryable(error) {
                let attempt = min(state.retryAttempt + 1, Self.maximumAttempts)
                state.retryAttempt = attempt
                state.nextRetryAt = now.addingTimeInterval(Self.retryDelay(attempt: attempt))
            } else {
                // Asks afresh next time, as a new version would.
                state.pendingVersion = nil; state.nextRetryAt = nil; state.retryAttempt = 0
            }
            self.state = state
            return nil
        }
    }

    /// Records that `version`'s note was seen, so it doesn't show on its own again.
    public func markSeen(_ version: String) {
        state = PromptState(lastSeenVersion: version)
    }

    /// Looks up whether this version has a note, for the Settings row.
    public func refreshCurrentNote() async {
        do {
            currentVersionHasNote = try await source().whatsNewNote(platform: platform, version: version) != nil
        } catch {
            // An error leaves the dot as it was.
        }
    }

    /// A list of this platform's notes, newest first, to page through.
    public func history(pageSize: Int = 3) -> WhatsNewHistory {
        WhatsNewHistory(platform: platform, pageSize: pageSize, source: source)
    }

    /// The newest note, whatever this version is, to preview it.
    public func latestNote() async throws -> WhatsNewEntry? {
        try await source().latestWhatsNewNote(platform: platform).map { WhatsNewEntry.parse($0) }
    }

    static func retryDelay(attempt: Int) -> TimeInterval {
        guard attempt > 0 else { return baseRetry }
        return min(baseRetry * pow(2, Double(min(attempt - 1, 10))), maximumRetry)
    }

    /// Whether trying again later could help: no connection, or a server failure.
    static func isRetryable(_ error: Error) -> Bool {
        if error is URLError { return true }
        if case BackendError.rejected(let status, _, _) = error { return status >= 500 }
        return false
    }

    var state: PromptState {
        get { defaults.data(forKey: Self.stateKey).flatMap { try? JSONDecoder().decode(PromptState.self, from: $0) } ?? PromptState() }
        set { defaults.set(try? JSONEncoder().encode(newValue), forKey: Self.stateKey) }
    }
}

/// This platform's notes, newest first, a page at a time.
@MainActor
public final class WhatsNewHistory: ObservableObject {
    @Published public private(set) var entries: [WhatsNewEntry] = []
    /// True until the first page arrives or fails.
    @Published public private(set) var loading = false
    @Published public private(set) var loadingMore = false
    @Published public private(set) var errorMessage: String?
    @Published public private(set) var nextCursor: String?
    @Published public private(set) var loaded = false

    private let platform: WhatsNewPlatform
    private let pageSize: Int
    private let source: @MainActor () throws -> any WhatsNewSource

    init(platform: WhatsNewPlatform, pageSize: Int, source: @escaping @MainActor () throws -> any WhatsNewSource) {
        self.platform = platform; self.pageSize = pageSize; self.source = source
    }

    public var hasMore: Bool { nextCursor != nil }

    /// Loads the first page, or the next one once the first is in.
    public func load() async {
        guard !loading, !loadingMore, !loaded || hasMore else { return }
        let first = !loaded
        if first { loading = true } else { loadingMore = true }
        errorMessage = nil
        defer { loading = false; loadingMore = false }
        do {
            let page = try await source().whatsNewNotes(platform: platform, cursor: first ? nil : nextCursor, limit: pageSize)
            let known = Set(entries.compactMap(\.version))
            entries += page.items
                .map { WhatsNewEntry.parse($0.rawMarkdown, fallbackVersion: $0.version) }
                .filter { $0.version.map { !known.contains($0) } ?? true }
            nextCursor = page.nextCursor
            loaded = true
        } catch {
            errorMessage = UserFacingError.message(for: error, fallback: "Couldn’t load what’s new. Try again.")
        }
    }
}
