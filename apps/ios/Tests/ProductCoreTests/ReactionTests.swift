import Foundation
import Testing
@testable import ProductCore

private struct ReactionTransport: BackendTransport {
    let body: String
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        (Data(body.utf8), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

private final class SignedInStore: SessionTokenStore, @unchecked Sendable {
    func load() -> SessionTokens? { SessionTokens(accessToken: "access", refreshToken: "refresh", userId: "11111111-1111-4111-8111-111111111111") }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}

private func client(_ body: String) throws -> BackendClient {
    try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: SignedInStore(), transport: ReactionTransport(body: body))
}

@Test func pendingReactionsDecodeTheServerBatch() async throws {
    let batch = try await client(#"{"pending":{"generation":4,"heartCount":2,"hugCount":0,"kissCount":0,"smileCount":0,"laughCount":0,"sparkleCount":1}}"#).pendingReactions()
    #expect(batch?.generation == 4)
    #expect(batch?.count(.heart) == 2)
    #expect(batch?.total == 3)
    #expect(try await client(#"{"pending":null}"#).pendingReactions() == nil)
}

@Test func leaseStatusesDecode() async throws {
    #expect(try await client(#"{"status":"CLAIMED","leaseExpiresAt":"2026-10-29T00:00:30Z"}"#).leaseReactionPlayback(generation: 1, deviceId: "a") == .claimed)
    #expect(try await client(#"{"status":"LEASED_BY_OTHER"}"#).leaseReactionPlayback(generation: 1, deviceId: "a") == .leasedByOther)
}

@Test func oneKindPlaysBigWithUpToThreeCopiesAndCaptionsSpam() {
    let few = ReactionArrangement(PendingReactionBatch(generation: 1, counts: [.kiss: 3]))
    #expect(few.glyphs.count == 3)
    #expect(few.glyphs.allSatisfy { $0.type == .kiss })
    #expect(few.glyphs.first?.scale == 1.4)
    #expect(few.caption == nil)

    let spam = ReactionArrangement(PendingReactionBatch(generation: 1, counts: [.heart: 9]))
    #expect(spam.glyphs.count == 4)
    #expect(spam.caption == "You have been spammed with love.")
}

@Test func mixedKindsPlayOneOfEach() {
    let mixed = ReactionArrangement(PendingReactionBatch(generation: 1, counts: [.heart: 5, .laugh: 1, .sparkle: 2]))
    #expect(mixed.glyphs.map(\.type) == [.heart, .laugh, .sparkle])
    #expect(mixed.glyphs.allSatisfy { $0.scale == 1.15 })
    #expect(mixed.caption == nil)
}
