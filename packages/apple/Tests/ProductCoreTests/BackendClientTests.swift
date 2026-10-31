import Foundation
import Testing
@testable import ProductCore

private let savedSession = SessionTokens(accessToken: "expired", refreshToken: "refresh-old", userId: "11111111-1111-4111-8111-111111111111")

private final class TestTokenStore: SessionTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var value: SessionTokens?
    init(_ value: SessionTokens?) { self.value = value }
    func load() -> SessionTokens? { lock.withLock { value } }
    func save(_ tokens: SessionTokens) { lock.withLock { value = tokens } }
    func clear() { lock.withLock { value = nil } }
}

private actor TestTransport: BackendTransport {
    var requests: [URLRequest] = []
    var refreshStatus = 200
    var holdRefresh = false
    var deleteStatus = 204
    private var refreshContinuation: CheckedContinuation<Void, Never>?
    private var enteredRefresh: CheckedContinuation<Void, Never>?
    private var refreshStarted = false
    private let bootstrap: Data

    init() throws { bootstrap = try fixture("bootstrap") }
    func configureRefresh(status: Int = 200, hold: Bool = false) { refreshStatus = status; holdRefresh = hold }
    func configureDelete(status: Int) { deleteStatus = status }
    func waitForRefresh() async {
        if refreshStarted { return }
        await withCheckedContinuation { enteredRefresh = $0 }
    }
    func releaseRefresh() { refreshContinuation?.resume(); refreshContinuation = nil }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        let path = request.url!.path
        if path == "/auth/refresh" {
            refreshStarted = true
            enteredRefresh?.resume(); enteredRefresh = nil
            if holdRefresh { await withCheckedContinuation { refreshContinuation = $0 } }
            if refreshStatus != 200 { return response(request, status: refreshStatus, body: Data(#"{"message":"Session expired"}"#.utf8)) }
            let auth: [String: Any] = ["accessToken": "fresh", "refreshToken": "refresh-new", "userId": savedSession.userId, "bootstrapState": try JSONSerialization.jsonObject(with: bootstrap)]
            return response(request, body: try JSONSerialization.data(withJSONObject: auth))
        }
        if path == "/auth/logout" { return response(request, body: Data()) }
        if path == "/me" && request.httpMethod == "DELETE" {
            return response(request, status: deleteStatus, body: deleteStatus == 204 ? Data() : Data(#"{"message":"Try again"}"#.utf8))
        }
        if path == "/wallpapers" { return response(request, body: Data(#"{"items":[],"nextCursor":null}"#.utf8)) }
        if request.value(forHTTPHeaderField: "Authorization") == "Bearer expired" {
            return response(request, status: 401, body: Data(#"{"message":"Token expired"}"#.utf8))
        }
        return response(request, body: bootstrap)
    }

    private func response(_ request: URLRequest, status: Int = 200, body: Data) -> (Data, HTTPURLResponse) {
        (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

@Test func concurrentUnauthorizedRequestsRotateRefreshTokenOnce() async throws {
    let store = TestTokenStore(savedSession)
    let transport = try TestTransport()
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: store, transport: transport)
    let states = try await withThrowingTaskGroup(of: BootstrapState.self) { group in
        for _ in 0..<8 { group.addTask { try await client.bootstrap() } }
        var results: [BootstrapState] = []
        for try await state in group { results.append(state) }
        return results
    }
    #expect(states.count == 8)
    #expect(await transport.requests.filter { $0.url?.path == "/auth/refresh" }.count == 1)
    #expect(store.load()?.refreshToken == "refresh-new")
}

@Test func signOutWhileRefreshIsPendingCannotRestoreSession() async throws {
    let store = TestTokenStore(savedSession)
    let transport = try TestTransport()
    await transport.configureRefresh(hold: true)
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: store, transport: transport)
    let request = Task { try await client.bootstrap() }
    await transport.waitForRefresh()
    try await client.signOut()
    await transport.releaseRefresh()
    do { _ = try await request.value; Issue.record("Old request restored the session") }
    catch { #expect(error as? BackendError == .sessionChanged) }
    #expect(store.load() == nil)
    #expect(await client.signedInUserId == nil)
}

@Test func rejectedRefreshClearsExpiredSession() async throws {
    let store = TestTokenStore(savedSession)
    let transport = try TestTransport()
    await transport.configureRefresh(status: 401)
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: store, transport: transport)
    do { _ = try await client.bootstrap(); Issue.record("Expected revoked session") }
    catch { #expect(error as? BackendError == .rejected(401, "Session expired")) }
    #expect(store.load() == nil)
}

@Test func temporaryRefreshFailurePreservesLoginForRetry() async throws {
    let store = TestTokenStore(savedSession)
    let transport = try TestTransport()
    await transport.configureRefresh(status: 503)
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: store, transport: transport)
    do { _ = try await client.bootstrap(); Issue.record("Expected unavailable service") }
    catch { #expect(error as? BackendError == .rejected(503, "Session expired")) }
    #expect(store.load() == savedSession)
}

@Test func galleryCursorIsEncodedWithoutSendingSessionToken() async throws {
    let transport = try TestTransport()
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: TestTokenStore(savedSession), transport: transport)
    _ = try await client.wallpapers(cursor: "next/+&cursor=wrong")
    let request = await transport.requests[0]
    let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
    #expect(query.first { $0.name == "cursor" }?.value == "next/+&cursor=wrong")
    #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
}

@Test func photoAndPartnerDetailsAreOneMultipartRequest() async throws {
    let transport = try TestTransport()
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: TestTokenStore(.init(accessToken: "fresh", refreshToken: "r", userId: savedSession.userId)), transport: transport)
    _ = try await client.updatePartner(name: "Alex", anniversary: "2024-06-12", jpeg: Data([0xFF, 0xD8, 0xFF, 0xD9]))
    let requests = await transport.requests
    #expect(requests.count == 1)
    #expect(requests[0].url?.path == "/me/partner-profile-with-photo")
    let body = String(decoding: requests[0].httpBody!, as: UTF8.self)
    #expect(body.contains("name=\"partnerDisplayName\"\r\n\r\nAlex\r\n"))
    #expect(body.contains("name=\"anniversaryDate\"\r\n\r\n2024-06-12\r\n"))
    #expect(body.contains("name=\"image\"; filename=\"profile.jpg\""))
}

@Test func deletingTheAccountForgetsTheSession() async throws {
    let store = TestTokenStore(.init(accessToken: "fresh", refreshToken: "r", userId: savedSession.userId))
    let transport = try TestTransport()
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: store, transport: transport)
    try await client.deleteAccount()
    let request = await transport.requests[0]
    #expect(request.httpMethod == "DELETE")
    #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer fresh")
    #expect(store.load() == nil)
    #expect(await client.signedInUserId == nil)
}

@Test func failedAccountDeletionKeepsTheSession() async throws {
    let session = SessionTokens(accessToken: "fresh", refreshToken: "r", userId: savedSession.userId)
    let store = TestTokenStore(session)
    let transport = try TestTransport()
    await transport.configureDelete(status: 500)
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: store, transport: transport)
    do { try await client.deleteAccount(); Issue.record("Expected the deletion to fail") }
    catch { #expect(error as? BackendError == .rejected(500, "Try again")) }
    #expect(store.load() == session)
    #expect(await client.signedInUserId == savedSession.userId)
}
