import Foundation
import Testing
@testable import ProductCore

private func freshDefaults() -> UserDefaults {
    let suite = "WhatsNewTests-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return defaults
}

private let note = """
---
version: 1.1.0
released_at: 2026-11-06
title: "Photos on the canvas"
hero_image: /whats-new/ios/assets/1.1.0/hero.jpg
---

## Add a photo

Tap the camera, then **place** it
anywhere.

- One
- Two
  carries on
1. First
2. Second
"""

/// Answers with fixed notes, or fails, and counts what it was asked.
private actor StubSource: WhatsNewSource {
    enum Reply: Sendable { case note(String?), failure(Int) }
    var reply: Reply
    var pages: [String?: WhatsNewPage] = [:]
    private(set) var noteRequests: [String] = []
    private(set) var pageRequests: [String?] = []

    init(_ reply: Reply) { self.reply = reply }
    func set(_ reply: Reply) { self.reply = reply }
    func set(pages: [String?: WhatsNewPage]) { self.pages = pages }

    func whatsNewNote(platform: WhatsNewPlatform, version: String) async throws -> String? {
        noteRequests.append("\(platform.rawValue)/\(version)")
        switch reply {
        case .note(let raw): return raw
        case .failure(0): throw URLError(.notConnectedToInternet)
        case .failure(let status): throw BackendError.rejected(status, "")
        }
    }
    func latestWhatsNewNote(platform: WhatsNewPlatform) async throws -> String? { note }
    func whatsNewNotes(platform: WhatsNewPlatform, cursor: String?, limit: Int) async throws -> WhatsNewPage {
        pageRequests.append(cursor)
        if case .failure(let status) = reply { throw BackendError.rejected(status, "") }
        return pages[cursor] ?? WhatsNewPage(items: [], nextCursor: nil)
    }
}

@Test func aNoteReadsItsFrontmatterAndBody() throws {
    let entry = WhatsNewEntry.parse(note)
    #expect(entry.version == "1.1.0")
    #expect(entry.title == "Photos on the canvas")
    #expect(entry.callToAction == WhatsNewEntry.defaultCallToAction)
    #expect(entry.subtitle == "Released on 6 November, 2026 • Version 1.1.0")
    #expect(entry.markdown.hasPrefix("## Add a photo"))
    let hero = entry.heroImageURL(relativeTo: URL(string: "http://localhost:8080/")!)
    #expect(hero?.absoluteString == "http://localhost:8080/whats-new/ios/assets/1.1.0/hero.jpg")
}

@Test func aNoteWithoutFrontmatterIsAllBody() {
    let entry = WhatsNewEntry.parse("Just words", fallbackVersion: "2.0.0")
    #expect(entry.version == "2.0.0")
    #expect(entry.markdown == "Just words")
    #expect(entry.subtitle == "Version 2.0.0")
    #expect(entry.heroImageURL(relativeTo: URL(string: "https://api.mulberry.my")!) == nil)
}

@Test func aNoteBodySplitsIntoBlocks() {
    #expect(WhatsNewEntry.parse(note).blocks == [
        .heading(level: 2, text: "Add a photo"),
        .paragraph("Tap the camera, then **place** it anywhere."),
        .bullets(["One", "Two carries on"]),
        .numbered(start: 1, items: ["First", "Second"])
    ])
}

@Test func onlyVersionKeysNameNotes() {
    #expect(WhatsNewEntry.isVersionKey("1.1.0"))
    #expect(WhatsNewEntry.isVersionKey("1.1.9-dev"))
    #expect(!WhatsNewEntry.isVersionKey("../1.0"))
    #expect(!WhatsNewEntry.isVersionKey("latest"))
}

@MainActor @Test func aVersionsNoteShowsOnceThenNeverAgain() async {
    let defaults = freshDefaults()
    let source = StubSource(.note(note))
    let model = WhatsNewModel(platform: .ios, version: "1.1.0", defaults: defaults) { source }

    let due = await model.dueNote()
    #expect(due?.version == "1.1.0")
    #expect(model.currentVersionHasNote)
    // Not yet closed: it would show again.
    #expect(await model.dueNote() != nil)
    model.markSeen("1.1.0")
    #expect(await model.dueNote() == nil)
    #expect(await source.noteRequests == ["ios/1.1.0", "ios/1.1.0"])

    // The next version has its own note.
    let next = WhatsNewModel(platform: .ios, version: "1.2.0", defaults: defaults) { source }
    #expect(await next.dueNote() != nil)
}

@MainActor @Test func aVersionWithoutANoteCountsAsSeen() async {
    let defaults = freshDefaults()
    let source = StubSource(.note(nil))
    let model = WhatsNewModel(platform: .ios, version: "1.1.0", defaults: defaults) { source }
    #expect(await model.dueNote() == nil)
    // A note published afterwards never shows on its own.
    await source.set(.note(note))
    #expect(await model.dueNote() == nil)
    #expect(await source.noteRequests.count == 1)
}

@MainActor @Test func noConnectionTriesAgainLaterWithLongerWaits() async {
    let defaults = freshDefaults()
    let source = StubSource(.failure(0))
    let model = WhatsNewModel(platform: .ios, version: "1.1.0", defaults: defaults) { source }
    let start = Date(timeIntervalSince1970: 1_800_000_000)

    #expect(await model.dueNote(now: start) == nil)
    #expect(model.state.nextRetryAt == start.addingTimeInterval(30))
    #expect(await model.dueNote(now: start.addingTimeInterval(29)) == nil)
    #expect(await source.noteRequests.count == 1)

    #expect(await model.dueNote(now: start.addingTimeInterval(30)) == nil)
    #expect(model.state.nextRetryAt == start.addingTimeInterval(90))
    await source.set(.note(note))
    #expect(await model.dueNote(now: start.addingTimeInterval(90)) != nil)
    // As on Android, attempts stop at 10, so the longest wait is 30 s × 2⁹.
    #expect(WhatsNewModel.retryDelay(attempt: 10) == 30 * 512)
}

@MainActor @Test func aServerFailureRetriesButARejectionAsksAfresh() async {
    #expect(WhatsNewModel.isRetryable(BackendError.rejected(503, "")))
    #expect(!WhatsNewModel.isRetryable(BackendError.rejected(400, "")))

    let defaults = freshDefaults()
    let source = StubSource(.failure(400))
    let model = WhatsNewModel(platform: .ios, version: "1.1.0", defaults: defaults) { source }
    #expect(await model.dueNote() == nil)
    #expect(model.state == WhatsNewModel.PromptState())
    #expect(await model.dueNote() == nil)
    #expect(await source.noteRequests.count == 2)
}

@MainActor @Test func historyPagesThroughNotesWithoutRepeats() async {
    let source = StubSource(.note(nil))
    func item(_ version: String) -> WhatsNewPage.Item { .init(version: version, rawMarkdown: "---\ntitle: \(version)\n---\nbody") }
    await source.set(pages: [
        nil: WhatsNewPage(items: [item("1.2.0"), item("1.1.0")], nextCursor: "2"),
        "2": WhatsNewPage(items: [item("1.1.0"), item("1.0.0")], nextCursor: nil)
    ])
    let history = WhatsNewModel(platform: .ios, version: "1.2.0", defaults: freshDefaults()) { source }.history()

    await history.load()
    #expect(history.entries.map(\.version) == ["1.2.0", "1.1.0"])
    #expect(history.hasMore)
    await history.load()
    #expect(history.entries.map(\.version) == ["1.2.0", "1.1.0", "1.0.0"])
    #expect(!history.hasMore)
    await history.load()
    #expect(await source.pageRequests == [nil, "2"])
}

@MainActor @Test func historyExplainsAFailureInItsOwnWords() async {
    let history = WhatsNewModel(platform: .ios, version: "1.2.0", defaults: freshDefaults()) { StubSource(.failure(500)) }.history()
    await history.load()
    #expect(history.entries.isEmpty)
    #expect(history.errorMessage == "Mulberry is having trouble right now. Try again in a moment.")
}

@Test func theClientReadsNotesFromItsPlatformsFolder() async throws {
    actor Transport: BackendTransport {
        var paths: [String] = []
        func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
            let path = request.url!.path
            paths.append(path + (request.url!.query.map { "?" + $0 } ?? ""))
            let status = path.hasSuffix("/1.0.0.md") ? 404 : 200
            let body = path.hasSuffix(".md") ? Data("note".utf8) : Data(#"{"items":[{"version":"1.1.0","rawMarkdown":"x"}],"nextCursor":null}"#.utf8)
            return (status == 404 ? Data("Not found.".utf8) : body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
        }
    }
    let transport = Transport()
    let client = BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: NoSession(), session: nil, transport: transport)
    #expect(try await client.whatsNewNote(platform: .ios, version: "1.1.0") == "note")
    #expect(try await client.whatsNewNote(platform: .ios, version: "1.0.0") == nil)
    #expect(try await client.latestWhatsNewNote(platform: .macos) == "note")
    #expect(try await client.whatsNewNotes(platform: .ios, cursor: "3", limit: 3).items.map(\.version) == ["1.1.0"])
    #expect(await transport.paths == ["/whats-new/ios/1.1.0.md", "/whats-new/ios/1.0.0.md", "/whats-new/macos/latest.md", "/whats-new/ios?limit=3&cursor=3"])
}

private struct NoSession: SessionTokenStore {
    func load() throws -> SessionTokens? { nil }
    func save(_ tokens: SessionTokens) throws {}
    func clear() throws {}
}
