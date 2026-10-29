import Foundation

public protocol SessionTokenStore: Sendable {
    func load() throws -> SessionTokens?
    func save(_ tokens: SessionTokens) throws
    func clear() throws
}

public protocol BackendTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

public struct URLSessionTransport: BackendTransport {
    private let session: URLSession
    public init(session: URLSession = .shared) { self.session = session }
    public func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw BackendError.invalidResponse }
        return (data, response)
    }
}

public enum BackendError: Error, LocalizedError, Equatable, Sendable {
    case signedOut, sessionChanged, invalidResponse
    case rejected(Int, String)

    public var errorDescription: String? {
        switch self {
        case .signedOut: "Please sign in again."
        case .sessionChanged: "Your account changed. Please try again."
        case .invalidResponse: "Mulberry could not read the server's reply. Please try again."
        case .rejected(_, let message): message
        }
    }
}

/// Serializes token rotation. Concurrent requests share one refresh, and an old request
/// can never restore a session after sign-out or account switching.
public actor BackendClient {
    public let baseURL: URL
    private let transport: any BackendTransport
    private let shortTransport: any BackendTransport
    private let tokenStore: any SessionTokenStore
    private var tokens: SessionTokens?
    private var generation = 0
    private var refreshTask: Task<SessionTokens, Error>?

    public init(baseURL: URL, tokenStore: any SessionTokenStore, transport: any BackendTransport = URLSessionTransport(), shortTransport: (any BackendTransport)? = nil) throws {
        self.baseURL = baseURL
        self.tokenStore = tokenStore
        self.transport = transport
        self.shortTransport = shortTransport ?? transport
        tokens = try tokenStore.load()
    }

    public var signedInUserId: String? { tokens?.userId }

    public func signIn(googleIDToken: String) async throws -> BootstrapState {
        generation += 1
        let epoch = generation
        refreshTask?.cancel()
        refreshTask = nil
        let response: AuthenticationResponse = try await json("auth/google", method: "POST", body: ["idToken": googleIDToken], authenticated: false)
        guard generation == epoch else { throw BackendError.sessionChanged }
        try tokenStore.save(response.tokens)
        tokens = response.tokens
        return response.bootstrapState
    }

    public func signOut() async throws {
        let previous = tokens
        generation += 1
        refreshTask?.cancel()
        refreshTask = nil
        try tokenStore.clear()
        tokens = nil
        guard let previous else { return }
        // Local sign-out is immediate even if revoking the old remote session fails.
        var request = makeRequest("auth/logout", method: "POST")
        request.setValue("Bearer \(previous.accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await transport.data(for: request)
        try Self.validate(data, response)
    }

    public func accessToken() async throws -> String {
        guard let tokens else { throw BackendError.signedOut }
        return tokens.accessToken
    }

    public func bootstrap(quick: Bool = false) async throws -> BootstrapState { try await get("bootstrap", quick: quick) }
    public func streak(today: String = ServerDate.localDay()) async throws -> StreakResponse {
        try await get("streak", query: [.init(name: "today", value: today)])
    }
    public func saveProfile(_ draft: ProfileDraft) async throws -> BootstrapState {
        try await json("me/profile", method: "PUT", body: draft)
    }
    public func updateDisplayName(_ name: String) async throws -> BootstrapState {
        try await json("me/display-name", method: "PUT", body: ["displayName": name])
    }
    public func updatePartner(name: String, anniversary: String, jpeg: Data? = nil) async throws -> BootstrapState {
        let fields = ["partnerDisplayName": name, "anniversaryDate": anniversary]
        if let jpeg { return try await upload("me/partner-profile-with-photo", jpeg: jpeg, fields: fields) }
        return try await json("me/partner-profile", method: "PUT", body: fields)
    }
    public func updatePhoto(jpeg: Data, partner: Bool = false) async throws -> BootstrapState {
        try await upload(partner ? "me/partner-profile-photo" : "me/profile-photo", jpeg: jpeg)
    }
    public func createInvite() async throws -> CreatedInvite { try await post("invites") }
    public func redeemInvite(code: String) async throws -> RedeemedInvite {
        try await json("invites/redeem", method: "POST", body: ["code": code])
    }
    public func acceptInvite(id: String) async throws -> AcceptedInvite { try await post("invites/\(try pathID(id))/accept") }
    public func declineInvite(id: String) async throws -> BootstrapState { try await post("invites/\(try pathID(id))/decline") }
    public func disconnect() async throws -> BootstrapState { try await post("pairing/disconnect") }
    public func setStrokeRenderMode(_ value: String) async throws -> BootstrapState {
        try await json("me/canvas-stroke-render-mode", method: "PUT", body: ["canvasStrokeRenderMode": value])
    }
    public func wallpapers(cursor: String? = nil) async throws -> WallpaperCatalogPage {
        var query = [URLQueryItem(name: "limit", value: "24")]
        if let cursor { query.append(URLQueryItem(name: "cursor", value: cursor)) }
        return try await get("wallpapers", query: query, authenticated: false)
    }
    public func cannyToken() async throws -> String {
        struct Token: Decodable { let token: String }
        let result: Token = try await get("feedback/canny-sso-token")
        return result.token
    }
    public func stickerPacks() async throws -> [StickerPackSummary] {
        let result: StickerPackList = try await get("stickers/packs")
        return result.items
    }
    public func stickerPack(_ packKey: String, version: Int? = nil) async throws -> StickerPackDetail {
        try await get("stickers/packs/\(packKey)", query: version.map { [.init(name: "version", value: String($0))] } ?? [])
    }
    /// A fresh signed URL, for when a catalog URL has expired.
    public func stickerAssetURL(_ sticker: StickerReference, variant: StickerAssetVariant, quick: Bool = false) async throws -> StickerAssetURL {
        try await get("stickers/assets/url", query: [
            .init(name: "packKey", value: sticker.packKey), .init(name: "version", value: String(sticker.packVersion)),
            .init(name: "stickerId", value: sticker.stickerId), .init(name: "variant", value: variant.rawValue)
        ], quick: quick)
    }
    public func canvasSnapshot() async throws -> DrawingSnapshot { try await get("canvas/snapshot") }
    public func canvasOperations(after revision: Int64, quick: Bool = false) async throws -> [DrawingOperation] {
        let result: DrawingOperations = try await get("canvas/ops", query: [.init(name: "afterRevision", value: String(revision))], quick: quick)
        return result.operations
    }
    public func submit(_ batch: DrawingBatch) async throws -> [DrawingOperation] {
        let result: DrawingOperations = try await json("canvas/ops/batch", method: "POST", body: batch)
        return result.operations
    }

    public func setPresence(deviceId: String, configured: Bool, enabled: Bool, canSeeLatestDrawings: Bool, details: [String: JSONValue]) async throws -> BootstrapState {
        let body: [String: JSONValue] = [
            "deviceInstanceId": .string(deviceId), "configured": .bool(configured), "enabled": .bool(enabled),
            "canSeeLatestDrawings": .bool(canSeeLatestDrawings), "details": .object(details)
        ]
        return try await json("me/presence-surfaces/IOS_SHORTCUT", method: "PUT", body: body)
    }

    public func registerPushToken(_ token: String, environment: String) async throws {
        let _: JSONValue = try await json("devices/fcm-token", method: "POST", body: ["token": token, "platform": "IOS", "appEnvironment": environment])
    }

    public func unregisterPushToken(_ token: String) async throws {
        let _: JSONValue = try await json("devices/fcm-token", method: "DELETE", body: ["token": token])
    }

    public func sendReaction(_ type: ReactionType) async throws {
        let _: JSONValue = try await json("reactions/send", method: "POST", body: ["reactionType": type.rawValue])
    }

    public func pendingReactions(quick: Bool = false) async throws -> PendingReactionBatch? {
        struct Pending: Decodable, Sendable { let pending: PendingReactionBatch? }
        let result: Pending = try await get("reactions/pending", quick: quick)
        return result.pending
    }

    public func leaseReactionPlayback(generation: Int, deviceId: String) async throws -> ReactionLeaseStatus {
        struct Lease: Decodable, Sendable { let status: ReactionLeaseStatus }
        let result: Lease = try await json("reactions/lease", method: "POST", body: ReactionPlayback(generation: generation, deviceId: deviceId))
        return result.status
    }

    public func confirmReactionPlayback(generation: Int, deviceId: String) async throws {
        let _: JSONValue = try await json("reactions/confirm", method: "POST", body: ReactionPlayback(generation: generation, deviceId: deviceId))
    }

    private func get<Response: Decodable & Sendable>(_ path: String, query: [URLQueryItem] = [], authenticated: Bool = true, quick: Bool = false) async throws -> Response {
        try await decode(makeRequest(path, query: query), authenticated: authenticated, quick: quick)
    }
    private func post<Response: Decodable & Sendable>(_ path: String) async throws -> Response {
        try await decode(makeRequest(path, method: "POST"))
    }
    private func json<Response: Decodable & Sendable, Body: Encodable>(_ path: String, method: String, body: Body, authenticated: Bool = true) async throws -> Response {
        var request = makeRequest(path, method: method)
        request.httpBody = try JSONEncoder().encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await decode(request, authenticated: authenticated)
    }
    private func upload(_ path: String, jpeg: Data, fields: [String: String] = [:]) async throws -> BootstrapState {
        let boundary = "mulberry-\(UUID().uuidString)"
        var body = Data()
        for key in fields.keys.sorted() {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(key)\"\r\n\r\n\(fields[key]!)\r\n".utf8))
        }
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"image\"; filename=\"profile.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".utf8))
        body.append(jpeg)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        var request = makeRequest(path, method: "PUT")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        return try await decode(request)
    }

    private func decode<Response: Decodable & Sendable>(_ request: URLRequest, authenticated: Bool = true, quick: Bool = false) async throws -> Response {
        let transport = quick ? shortTransport : transport
        let epoch = generation
        var authorized = request
        if quick { authorized.timeoutInterval = 3 }
        let usedToken = tokens?.accessToken
        if authenticated {
            guard let usedToken else { throw BackendError.signedOut }
            authorized.setValue("Bearer \(usedToken)", forHTTPHeaderField: "Authorization")
        }
        var (data, response) = try await transport.data(for: authorized)
        guard generation == epoch else { throw BackendError.sessionChanged }
        if authenticated && response.statusCode == 401 {
            let replacement = try await refresh(rejectedAccessToken: usedToken!, transport: transport)
            guard generation == epoch else { throw BackendError.sessionChanged }
            authorized.setValue("Bearer \(replacement)", forHTTPHeaderField: "Authorization")
            (data, response) = try await transport.data(for: authorized)
            guard generation == epoch else { throw BackendError.sessionChanged }
        }
        try Self.validate(data, response)
        return try JSONDecoder().decode(Response.self, from: response.statusCode == 204 ? Data("null".utf8) : data)
    }

    private func refresh(rejectedAccessToken: String, transport: any BackendTransport) async throws -> String {
        guard let current = tokens else { throw BackendError.signedOut }
        if current.accessToken != rejectedAccessToken { return current.accessToken }
        let epoch = generation
        let task: Task<SessionTokens, Error>
        if let pending = refreshTask { task = pending }
        else {
            var request = makeRequest("auth/refresh", method: "POST")
            request.httpBody = try JSONEncoder().encode(["refreshToken": current.refreshToken])
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            let transport = transport
            task = Task {
                let (data, response) = try await transport.data(for: request)
                try Self.validate(data, response)
                let result = try JSONDecoder().decode(AuthenticationResponse.self, from: data)
                guard result.userId == current.userId else { throw BackendError.sessionChanged }
                return result.tokens
            }
            refreshTask = task
        }
        do {
            let updated = try await task.value
            guard generation == epoch else { throw BackendError.sessionChanged }
            try tokenStore.save(updated)
            tokens = updated
            refreshTask = nil
            return updated.accessToken
        } catch {
            if generation == epoch {
                refreshTask = nil
                if case BackendError.rejected(let code, _) = error, code == 401 {
                    try tokenStore.clear()
                    tokens = nil
                    generation += 1
                }
            }
            throw error
        }
    }

    private func makeRequest(_ path: String, method: String = "GET", query: [URLQueryItem] = []) -> URLRequest {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }
    private func pathID(_ id: String) throws -> String {
        guard UUID(uuidString: id) != nil else { throw BackendError.invalidResponse }
        return id
    }
    private nonisolated static func validate(_ data: Data, _ response: HTTPURLResponse) throws {
        guard (200..<300).contains(response.statusCode) else {
            struct Failure: Decodable { let message: String?; let error: String? }
            let failure = try? JSONDecoder().decode(Failure.self, from: data)
            throw BackendError.rejected(response.statusCode, failure?.message ?? failure?.error ?? "Mulberry could not finish this request. Please try again.")
        }
    }
}

private struct ReactionPlayback: Encodable, Sendable {
    let generation: Int
    let deviceId: String
}
