import Foundation
import Testing
import ProductCore
@testable import StickerAssets

private let user = "11111111-1111-4111-8111-111111111111"
private let base = URL(string: "https://api.test")!

private final class TokenStore: SessionTokenStore, @unchecked Sendable {
    func load() -> SessionTokens? { SessionTokens(accessToken: "token", refreshToken: "refresh", userId: user) }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}

/// Answers the sticker endpoints, holding or failing a pack's details on request.
private actor CatalogTransport: BackendTransport {
    private(set) var detailRequests: [String: Int] = [:]
    private(set) var assetURLRequests = 0
    private var held: [String: CheckedContinuation<Void, Never>] = [:]
    private var holding: Set<String> = []
    private var failing: Set<String> = []
    let stickerCount: Int

    init(stickerCount: Int = 6) { self.stickerCount = stickerCount }

    func hold(_ pack: String) { holding.insert(pack) }
    func fail(_ pack: String) { failing.insert(pack) }
    func release(_ pack: String) { holding.remove(pack); held.removeValue(forKey: pack)?.resume() }
    func isHolding(_ pack: String) -> Bool { held[pack] != nil }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let path = request.url!.path
        if path == "/stickers/assets/url" {
            assetURLRequests += 1
            return reply(request, ["url": "https://cdn.test/fresh/asset.png", "expiresInSeconds": 600])
        }
        let pack = String(path.dropFirst("/stickers/packs/".count))
        detailRequests[pack, default: 0] += 1
        if holding.contains(pack) { await withCheckedContinuation { held[pack] = $0 } }
        if failing.contains(pack) { return reply(request, ["message": "unavailable"], status: 503) }
        return reply(request, detail(pack, link: "fresh", stickers: stickerCount))
    }

    private func reply(_ request: URLRequest, _ body: [String: Any], status: Int = 200) -> (Data, HTTPURLResponse) {
        (try! JSONSerialization.data(withJSONObject: body), HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

private func summary(_ pack: String) -> [String: Any] {
    ["packKey": pack, "packVersion": 1, "title": pack.capitalized, "description": "", "sortOrder": 0, "featured": false,
     "coverThumbnailUrl": "https://cdn.test/fresh/\(pack)/cover-thumb.png", "coverFullUrl": "https://cdn.test/fresh/\(pack)/cover-full.png"]
}

/// A pack's details whose links are `link` ("fresh" downloads, "expired" is refused).
private func detail(_ pack: String, link: String, stickers: Int) -> [String: Any] {
    var value = summary(pack)
    value["stickers"] = (0..<stickers).map { index -> [String: Any] in
        ["stickerId": "s\(index)", "width": 512, "height": 512, "sortOrder": stickers - index,
         "thumbnailUrl": "https://cdn.test/\(link)/\(pack)/s\(index)-thumb.png", "fullUrl": "https://cdn.test/\(link)/\(pack)/s\(index)-full.png"]
    }
    return value
}

private func decode<Value: Decodable>(_ type: Value.Type, _ value: [String: Any]) throws -> Value {
    try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value))
}

/// Serves artwork for "fresh" links and refuses "expired" ones, as expired signed links are.
private final class ArtworkProtocol: URLProtocol {
    nonisolated(unsafe) static var downloads: [String] = []
    private static let lock = NSLock()

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "cdn.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let url = request.url!
        Self.lock.withLock { Self.downloads.append(url.path) }
        let ok = url.path.hasPrefix("/fresh/")
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: ok ? 200 : 403, httpVersion: nil, headerFields: nil)!,
                            cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("png".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

@MainActor
private struct Harness {
    let transport: CatalogTransport
    let store: StickerAssetStore
    let catalog: StickerCatalog
    let root: URL

    init(stickerCount: Int = 6) {
        let transport = CatalogTransport(stickerCount: stickerCount)
        let client = BackendClient(baseURL: base, tokenStore: TokenStore(), session: TokenStore().load(), transport: transport)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ArtworkProtocol.self]
        root = FileManager.default.temporaryDirectory.appendingPathComponent("StickerCatalogTests-\(UUID().uuidString)", isDirectory: true)
        store = StickerAssetStore(api: { client }, root: root.appendingPathComponent("Stickers"), session: URLSession(configuration: configuration))
        catalog = StickerCatalog(user: user, store: store, directory: root.appendingPathComponent("Catalog"))
        self.transport = transport
    }
}

/// Waits for `condition`, failing after a couple of seconds.
@MainActor
private func eventually(_ condition: @MainActor () async -> Bool) async throws {
    for _ in 0..<400 {
        if await condition() { return }
        try await Task.sleep(for: .milliseconds(5))
    }
    Issue.record("Timed out waiting")
}

@Suite(.serialized) @MainActor
struct StickerCatalogTests {
    @Test func aLateAnswerNeverReplacesTheLastTappedPack() async throws {
        let harness = Harness()
        let spring = try decode(StickerPackSummary.self, summary("spring"))
        let cozy = try decode(StickerPackSummary.self, summary("cozy"))
        await harness.transport.hold("spring")

        harness.catalog.select(spring)
        #expect(harness.catalog.selectedPackID == spring.id)
        #expect(harness.catalog.awaitingPack)
        try await eventually { await harness.transport.isHolding("spring") }

        harness.catalog.select(cozy)
        #expect(harness.catalog.selectedPackID == cozy.id)
        try await eventually { harness.catalog.selected?.id == cozy.id }
        #expect(!harness.catalog.loading)

        await harness.transport.release("spring")
        try await eventually { await harness.transport.detailRequests["spring"] == 1 }
        try await Task.sleep(for: .milliseconds(50))
        #expect(harness.catalog.selected?.id == cozy.id)
        #expect(harness.catalog.selectedStickers.map(\.stickerId) == ["s5", "s4", "s3", "s2", "s1", "s0"])

        // The earlier pack's details were kept, so going back is instant.
        harness.catalog.select(spring)
        #expect(harness.catalog.selected?.id == spring.id)
        #expect(await harness.transport.detailRequests["spring"] == 1)
    }

    @Test func aFailedPackOnlyShowsItsErrorWhileSelected() async throws {
        let harness = Harness()
        let spring = try decode(StickerPackSummary.self, summary("spring"))
        let cozy = try decode(StickerPackSummary.self, summary("cozy"))
        await harness.transport.hold("spring")
        await harness.transport.fail("spring")

        harness.catalog.select(spring)
        try await eventually { await harness.transport.isHolding("spring") }
        harness.catalog.select(cozy)
        try await eventually { harness.catalog.selected?.id == cozy.id }
        await harness.transport.release("spring")
        try await Task.sleep(for: .milliseconds(50))
        #expect(harness.catalog.errorMessage == nil)

        harness.catalog.select(spring)
        #expect(harness.catalog.loading)
        try await eventually { harness.catalog.errorMessage != nil }
        #expect(!harness.catalog.awaitingPack)
    }

    @Test func expiredLinksRefreshThePackOnceForAllItsStickers() async throws {
        let harness = Harness()
        let stale = try decode(StickerPackDetail.self, detail("spring", link: "expired", stickers: 6))
        let files = await withTaskGroup(of: URL?.self) { group in
            for sticker in stale.stickers {
                group.addTask {
                    await harness.store.file(.init(packKey: "spring", packVersion: 1, stickerId: sticker.stickerId),
                                             variant: .thumbnail, user: user, hint: sticker.thumbnailUrl)
                }
            }
            var files: [URL?] = []
            for await file in group { files.append(file) }
            return files
        }
        #expect(files.allSatisfy { $0 != nil })
        #expect(await harness.transport.detailRequests["spring"] == 1)
        #expect(await harness.transport.assetURLRequests == 0)
    }

    @Test func rememberedLinksAreUsedBeforeAnOldHint() async throws {
        let harness = Harness()
        let fresh = try decode(StickerPackDetail.self, detail("cozy", link: "fresh", stickers: 2))
        await harness.store.remember(fresh, user: user, fetchedAt: .now)
        let file = await harness.store.file(.init(packKey: "cozy", packVersion: 1, stickerId: "s0"), variant: .thumbnail, user: user,
                                            hint: "https://cdn.test/expired/cozy/s0-thumb.png")
        #expect(file != nil)
        #expect(await harness.transport.detailRequests["cozy"] == nil)
        #expect(!ArtworkProtocol.downloads.contains("/expired/cozy/s0-thumb.png"))
    }
}
