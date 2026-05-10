import AppKit
import AuthenticationServices
import CryptoKit
import Foundation
import Network
import Networking
import Security

public enum AuthModule {
    public static let name = "Auth"
}

public struct AuthSession: Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let userID: String
    public let userEmail: String?
    public let userDisplayName: String?
    public let userPhotoURL: String?
    public let bootstrap: BootstrapDTO
}

public struct AuthFailure: Sendable {
    public let message: String

    public init(message: String) {
        self.message = message
    }
}

public enum AuthSessionState: Sendable {
    case signedOut
    case signingIn
    case refreshing
    case signedIn(AuthSession)
    case failed(AuthFailure)

    public var isSignedIn: Bool {
        if case .signedIn = self { return true }
        return false
    }

    public var isBusy: Bool {
        switch self {
        case .signingIn, .refreshing: true
        default: false
        }
    }

    public var statusTitle: String {
        switch self {
        case .signedOut: "Signed out"
        case .signingIn: "Signing in..."
        case .refreshing: "Refreshing session..."
        case let .signedIn(session): session.userDisplayName ?? session.userEmail ?? "Signed in"
        case .failed: "Sign-in failed"
        }
    }

    public var statusDetail: String {
        switch self {
        case .signedOut:
            "Open Mulberry to sign in"
        case .signingIn:
            "Waiting for Google"
        case .refreshing:
            "Checking saved session"
        case let .signedIn(session):
            session.userEmail ?? "Mulberry account active"
        case let .failed(failure):
            failure.message
        }
    }
}

@MainActor
public final class AuthSessionController: ObservableObject {
    @Published public private(set) var state: AuthSessionState = .signedOut

    private let configuration: MacAppConfiguration
    private let apiClient: AuthAPIClient
    private let oauthClient: GoogleOAuthClient
    private let keychainStore: KeychainSessionStore
    private let metadataStore: UserDefaultsAuthMetadataStore

    public init(
        configuration: MacAppConfiguration,
        presentationAnchorProvider: @escaping @MainActor () -> ASPresentationAnchor? = {
            NSApp.keyWindow ?? NSApp.windows.first
        }
    ) {
        self.configuration = configuration
        self.apiClient = AuthAPIClient(configuration: configuration)
        self.oauthClient = GoogleOAuthClient(
            configuration: configuration,
            presentationAnchorProvider: presentationAnchorProvider
        )
        self.keychainStore = KeychainSessionStore()
        self.metadataStore = UserDefaultsAuthMetadataStore()
    }

    public var currentAccessToken: String? {
        if case let .signedIn(session) = state {
            return session.accessToken
        }
        return try? keychainStore.loadTokens()?.accessToken
    }

    public func restoreSessionOnLaunch() {
        Task {
            await refreshStoredSession()
        }
    }

    public func signIn() async {
        guard configuration.googleClientID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            state = .failed(AuthFailure(message: "Set MULBERRY_MAC_GOOGLE_CLIENT_ID to enable Google Sign-In."))
            return
        }

        state = .signingIn
        do {
            let idToken = try await oauthClient.signIn()
            let response = try await apiClient.authenticateWithGoogle(idToken: idToken)
            let session = try persist(response: response)
            state = .signedIn(session)
        } catch {
            AuthDiagnostics.log("Google sign-in failed: \(diagnosticMessage(for: error))")
            state = .failed(AuthFailure(message: userFacingMessage(for: error)))
        }
    }

    public func refreshStoredSession() async {
        let tokens: SessionTokens?
        do {
            tokens = try keychainStore.loadTokens()
        } catch {
            clearLocalSession()
            state = .signedOut
            return
        }

        guard let tokens else {
            state = .signedOut
            return
        }

        state = .refreshing
        do {
            let response = try await apiClient.refresh(refreshToken: tokens.refreshToken)
            let session = try persist(response: response)
            state = .signedIn(session)
        } catch {
            clearLocalSession()
            state = .signedOut
        }
    }

    public func refreshForAuthenticatedRetry() async throws -> String {
        guard let refreshToken = try keychainStore.loadTokens()?.refreshToken else {
            throw APIError.missingToken
        }
        let response = try await apiClient.refresh(refreshToken: refreshToken)
        let session = try persist(response: response)
        state = .signedIn(session)
        return session.accessToken
    }

    public func signOut() async {
        let accessToken = currentAccessToken
        if let accessToken {
            try? await apiClient.logout(accessToken: accessToken)
        }
        clearLocalSession()
        state = .signedOut
    }

    private func persist(response: AuthResponseDTO) throws -> AuthSession {
        let tokens = SessionTokens(accessToken: response.accessToken, refreshToken: response.refreshToken)
        try keychainStore.save(tokens)
        let metadata = AuthSessionMetadata(
            userID: response.userId,
            userEmail: response.bootstrapState.userEmail,
            userDisplayName: response.bootstrapState.userDisplayName,
            userPhotoURL: response.bootstrapState.userPhotoUrl
        )
        metadataStore.save(metadata)
        return AuthSession(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            userID: response.userId,
            userEmail: metadata.userEmail,
            userDisplayName: metadata.userDisplayName,
            userPhotoURL: metadata.userPhotoURL,
            bootstrap: response.bootstrapState
        )
    }

    private func clearLocalSession() {
        try? keychainStore.clear()
        metadataStore.clear()
        // TODO: Phase 7+ should clear local pending operations and sync cursors here.
        // TODO: Phase 6+ should clear local canvas snapshots and cached partner state here.
        // TODO: Phase 11 should unregister and clear notification tokens here.
    }

    private func userFacingMessage(for error: Error) -> String {
        if let oauthError = error as? GoogleOAuthError {
            return oauthError.errorDescription ?? "Google Sign-In failed."
        }
        if let localizedError = error as? LocalizedError, let description = localizedError.errorDescription {
            return description
        }
        return diagnosticMessage(for: error)
    }

    private func diagnosticMessage(for error: Error) -> String {
        let nsError = error as NSError
        if nsError.domain != NSCocoaErrorDomain || nsError.code != 0 {
            return "Google Sign-In failed: \(nsError.domain) \(nsError.code) - \(nsError.localizedDescription)"
        }
        return "Google Sign-In failed: \(String(reflecting: type(of: error))) - \(String(describing: error))"
    }
}

@MainActor
public final class GoogleOAuthClient: NSObject, ASWebAuthenticationPresentationContextProviding {
    private let configuration: MacAppConfiguration
    private let presentationAnchorProvider: @MainActor () -> ASPresentationAnchor?
    private var activeSession: ASWebAuthenticationSession?

    public init(
        configuration: MacAppConfiguration,
        presentationAnchorProvider: @escaping @MainActor () -> ASPresentationAnchor?
    ) {
        self.configuration = configuration
        self.presentationAnchorProvider = presentationAnchorProvider
        super.init()
    }

    public func signIn() async throws -> String {
        let verifier = try randomURLSafeString(byteCount: 32)
        let state = try randomURLSafeString(byteCount: 32)
        let nonce = try randomURLSafeString(byteCount: 32)
        let challenge = codeChallenge(for: verifier)
        let callbackServer = LoopbackOAuthCallbackServer(callbackPath: configuration.callbackPath)
        let redirectURI = try await callbackServer.start()
        let authURL = try authorizationURL(
            redirectURI: redirectURI.absoluteString,
            state: state,
            nonce: nonce,
            codeChallenge: challenge
        )

        let callbackURL = try await callbackURL(for: authURL, callbackServer: callbackServer)
        let components = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)
        let queryItems = components?.queryItems ?? []
        if queryItems.value(named: "state") != state {
            throw GoogleOAuthError.invalidCallback
        }
        if let error = queryItems.value(named: "error") {
            throw GoogleOAuthError.authorizationFailed(error)
        }
        guard let code = queryItems.value(named: "code"), code.isEmpty == false else {
            throw GoogleOAuthError.invalidCallback
        }

        let idToken = try await exchangeCodeForIDToken(
            code: code,
            verifier: verifier,
            redirectURI: redirectURI.absoluteString
        )
        try validateNonce(nonce, in: idToken)
        return idToken
    }

    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        presentationAnchorProvider() ?? NSWindow()
    }

    private func callbackURL(
        for authURL: URL,
        callbackServer: LoopbackOAuthCallbackServer
    ) async throws -> URL {
        let coordinator = OAuthCallbackCoordinator()
        let session = ASWebAuthenticationSession(
            url: authURL,
            callbackURLScheme: nil,
            completionHandler: Self.makeCompletionHandler(coordinator: coordinator)
        )
        session.presentationContextProvider = self
        activeSession = session

        Task {
            do {
                let callbackURL = try await callbackServer.waitForCallback()
                coordinator.complete(.success(callbackURL))
            } catch {
                coordinator.complete(.failure(error))
            }
        }

        if session.start() == false {
            activeSession = nil
            callbackServer.stop()
            throw GoogleOAuthError.couldNotStart
        }

        do {
            let callbackURL = try await coordinator.wait()
            activeSession?.cancel()
            activeSession = nil
            callbackServer.stop()
            return callbackURL
        } catch {
            activeSession = nil
            callbackServer.stop()
            throw error
        }
    }

    private func authorizationURL(
        redirectURI: String,
        state: String,
        nonce: String,
        codeChallenge: String
    ) throws -> URL {
        var components = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: configuration.googleClientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email profile"),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "nonce", value: nonce)
        ]
        guard let url = components.url else {
            throw GoogleOAuthError.invalidAuthorizationURL
        }
        return url
    }

    private func exchangeCodeForIDToken(
        code: String,
        verifier: String,
        redirectURI: String
    ) async throws -> String {
        var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var body = [
            "code": code,
            "client_id": configuration.googleClientID,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": redirectURI
        ]
        if configuration.googleClientSecret.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
            body["client_secret"] = configuration.googleClientSecret
        }
        request.httpBody = formBody(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw GoogleOAuthError.tokenExchangeFailed
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw GoogleOAuthError.tokenExchangeFailedWithMessage(
                "HTTP \(httpResponse.statusCode) \(tokenExchangeErrorMessage(from: data))"
            )
        }
        let tokenResponse = try JSONDecoder().decode(GoogleTokenResponse.self, from: data)
        guard tokenResponse.idToken.isEmpty == false else {
            throw GoogleOAuthError.missingIDToken
        }
        return tokenResponse.idToken
    }

    private func tokenExchangeErrorMessage(from data: Data) -> String {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else {
            return String(data: data, encoding: .utf8) ?? "Unknown Google token exchange error."
        }
        let error = object["error"] as? String
        let description = object["error_description"] as? String
        return [error, description]
            .compactMap { $0 }
            .joined(separator: ": ")
    }

    private func validateNonce(_ expectedNonce: String, in idToken: String) throws {
        let segments = idToken.split(separator: ".")
        guard segments.count >= 2 else {
            throw GoogleOAuthError.invalidIDToken
        }
        guard
            let payloadData = Data(base64URLSegment: String(segments[1])),
            let object = try JSONSerialization.jsonObject(with: payloadData) as? [String: Any],
            let nonce = object["nonce"] as? String,
            nonce == expectedNonce
        else {
            throw GoogleOAuthError.invalidIDToken
        }
    }

    private nonisolated static func makeCompletionHandler(
        coordinator: OAuthCallbackCoordinator
    ) -> (URL?, Error?) -> Void {
        { callbackURL, error in
            if let error = error as? ASWebAuthenticationSessionError,
               error.code == .canceledLogin {
                coordinator.complete(.failure(GoogleOAuthError.cancelled))
                return
            }
            if let error {
                coordinator.complete(.failure(error))
                return
            }
            if let callbackURL {
                coordinator.complete(.success(callbackURL))
            }
        }
    }
}

enum AuthDiagnostics {
    static func log(_ message: String) {
        let line = "[auth] \(message)\n"
        if let data = line.data(using: .utf8) {
            FileHandle.standardError.write(data)
        }
    }
}

private final class OAuthCallbackCoordinator: @unchecked Sendable {
    private let lock = NSLock()
    private var result: Result<URL, Error>?
    private var continuation: CheckedContinuation<URL, Error>?

    func wait() async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if let result {
                lock.unlock()
                continuation.resume(with: result)
                return
            }
            self.continuation = continuation
            lock.unlock()
        }
    }

    func complete(_ result: Result<URL, Error>) {
        lock.lock()
        guard self.result == nil else {
            lock.unlock()
            return
        }
        self.result = result
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }
}

private final class LoopbackOAuthCallbackServer: @unchecked Sendable {
    private let callbackPath: String
    private let queue = DispatchQueue(label: "com.mulberry.mac.oauth.loopback")
    private let lock = NSLock()
    private var listener: NWListener?
    private var port: UInt16?
    private var pendingCallbackURL: URL?
    private var callbackContinuation: CheckedContinuation<URL, Error>?

    init(callbackPath: String) {
        self.callbackPath = callbackPath
    }

    func start() async throws -> URL {
        let listener = try NWListener(using: .tcp, on: .any)
        self.listener = listener
        listener.newConnectionHandler = { [weak self] connection in
            self?.handle(connection)
        }

        return try await withCheckedThrowingContinuation { continuation in
            listener.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    guard let port = listener.port?.rawValue else {
                        continuation.resume(throwing: GoogleOAuthError.couldNotStart)
                        return
                    }
                    self?.lock.lock()
                    self?.port = port
                    self?.lock.unlock()
                    continuation.resume(returning: URL(string: "http://127.0.0.1:\(port)\(self?.callbackPath ?? "/oauth2redirect")")!)
                case let .failed(error):
                    continuation.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    func waitForCallback() async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            if let pendingCallbackURL {
                self.pendingCallbackURL = nil
                lock.unlock()
                continuation.resume(returning: pendingCallbackURL)
                return
            }
            callbackContinuation = continuation
            lock.unlock()
        }
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func handle(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, _, _ in
            guard let self else {
                connection.cancel()
                return
            }
            let callbackURL = data.flatMap { self.callbackURL(from: $0) }
            let response = self.httpResponse(for: callbackURL != nil)
            connection.send(content: response, completion: .contentProcessed { _ in
                connection.cancel()
            })
            if let callbackURL {
                self.resolve(callbackURL)
            }
        }
    }

    private func callbackURL(from data: Data) -> URL? {
        guard
            let request = String(data: data, encoding: .utf8),
            let firstLine = request.components(separatedBy: "\r\n").first
        else {
            return nil
        }
        let parts = firstLine.split(separator: " ")
        guard parts.count >= 2 else {
            return nil
        }
        let target = String(parts[1])
        guard target.hasPrefix(callbackPath) else {
            return nil
        }

        lock.lock()
        let port = self.port
        lock.unlock()
        guard let port else {
            return nil
        }
        return URL(string: "http://127.0.0.1:\(port)\(target)")
    }

    private func resolve(_ callbackURL: URL) {
        lock.lock()
        let continuation = callbackContinuation
        callbackContinuation = nil
        if continuation == nil {
            pendingCallbackURL = callbackURL
        }
        lock.unlock()
        continuation?.resume(returning: callbackURL)
    }

    private func httpResponse(for success: Bool) -> Data {
        let title = success ? "Mulberry sign-in complete" : "Mulberry sign-in failed"
        let body = """
        <!doctype html><html><head><meta charset="utf-8"><title>\(title)</title></head>
        <body><h1>\(title)</h1><p>You can close this window and return to Mulberry.</p></body></html>
        """
        let status = success ? "200 OK" : "404 Not Found"
        return Data(
            """
            HTTP/1.1 \(status)\r
            Content-Type: text/html; charset=utf-8\r
            Content-Length: \(body.utf8.count)\r
            Connection: close\r
            \r
            \(body)
            """.utf8
        )
    }
}

public enum GoogleOAuthError: Error, LocalizedError, Sendable {
    case authorizationFailed(String)
    case cancelled
    case couldNotStart
    case invalidAuthorizationURL
    case invalidCallback
    case invalidIDToken
    case missingIDToken
    case tokenExchangeFailed
    case tokenExchangeFailedWithMessage(String)

    public var errorDescription: String? {
        switch self {
        case let .authorizationFailed(message):
            "Google authorization failed: \(message)"
        case .cancelled:
            "Google Sign-In was cancelled."
        case .couldNotStart:
            "Could not start Google Sign-In."
        case .invalidAuthorizationURL:
            "Google Sign-In is not configured correctly."
        case .invalidCallback:
            "Google Sign-In returned an invalid callback."
        case .invalidIDToken:
            "Google Sign-In returned an invalid ID token."
        case .missingIDToken:
            "Google Sign-In did not return an ID token."
        case .tokenExchangeFailed:
            "Google token exchange failed."
        case let .tokenExchangeFailedWithMessage(message):
            message.isEmpty ? "Google token exchange failed." : "Google token exchange failed: \(message)"
        }
    }
}

public struct SessionTokens: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String
}

public final class KeychainSessionStore {
    private static let invalidOwnerEditStatus: OSStatus = -25244

    private let service = "com.mulberry.mac.session"
    private let account = "mulberry-session-tokens"
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init() {}

    public func save(_ tokens: SessionTokens) throws {
        let data = try encoder.encode(tokens)
        let query = baseQuery(useDataProtectionKeychain: false)
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let updateStatus = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            let addStatus = SecItemAdd(saveQuery(data: data, useDataProtectionKeychain: false) as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainError.unhandledStatus(addStatus)
            }
        default:
            throw KeychainError.unhandledStatus(updateStatus)
        }
    }

    public func loadTokens() throws -> SessionTokens? {
        if let tokens = try loadTokens(useDataProtectionKeychain: false) {
            return tokens
        }
        return try loadTokens(useDataProtectionKeychain: true)
    }

    public func clear() throws {
        try clear(useDataProtectionKeychain: false)
        try clear(useDataProtectionKeychain: true)
    }

    private func baseQuery(useDataProtectionKeychain: Bool) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        if useDataProtectionKeychain {
            query[kSecUseDataProtectionKeychain as String] = true
        }
        return query
    }

    private func saveQuery(data: Data, useDataProtectionKeychain: Bool) -> [String: Any] {
        var query = baseQuery(useDataProtectionKeychain: useDataProtectionKeychain)
        query.merge([
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]) { _, new in new }
        return query
    }

    private func loadTokens(useDataProtectionKeychain: Bool) throws -> SessionTokens? {
        var query = baseQuery(useDataProtectionKeychain: useDataProtectionKeychain)
        query.merge([
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]) { _, new in new }
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound || isUnavailableLegacyKeychainStatus(status) {
            return nil
        }
        guard status == errSecSuccess else {
            throw KeychainError.unhandledStatus(status)
        }
        guard let data = result as? Data else {
            throw KeychainError.invalidData
        }
        return try decoder.decode(SessionTokens.self, from: data)
    }

    private func clear(useDataProtectionKeychain: Bool) throws {
        let query = baseQuery(useDataProtectionKeychain: useDataProtectionKeychain)
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound || isUnavailableLegacyKeychainStatus(status) else {
            throw KeychainError.unhandledStatus(status)
        }
    }

    private func isUnavailableLegacyKeychainStatus(_ status: OSStatus) -> Bool {
        status == errSecMissingEntitlement || status == Self.invalidOwnerEditStatus
    }
}

public enum KeychainError: Error, LocalizedError, Sendable {
    case invalidData
    case unhandledStatus(OSStatus)

    public var errorDescription: String? {
        switch self {
        case .invalidData:
            "The saved session could not be read."
        case let .unhandledStatus(status):
            "Keychain operation failed with status \(status)."
        }
    }
}

public struct AuthSessionMetadata: Codable, Sendable {
    public let userID: String
    public let userEmail: String?
    public let userDisplayName: String?
    public let userPhotoURL: String?
}

public final class UserDefaultsAuthMetadataStore {
    private let key = "com.mulberry.mac.auth.metadata"
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func save(_ metadata: AuthSessionMetadata) {
        let data = try? encoder.encode(metadata)
        defaults.set(data, forKey: key)
    }

    public func load() -> AuthSessionMetadata? {
        guard let data = defaults.data(forKey: key) else {
            return nil
        }
        return try? decoder.decode(AuthSessionMetadata.self, from: data)
    }

    public func clear() {
        defaults.removeObject(forKey: key)
    }
}

private struct GoogleTokenResponse: Codable {
    let idToken: String

    enum CodingKeys: String, CodingKey {
        case idToken = "id_token"
    }
}

private extension Array where Element == URLQueryItem {
    func value(named name: String) -> String? {
        first(where: { $0.name == name })?.value
    }
}

private func randomURLSafeString(byteCount: Int) throws -> String {
    var bytes = [UInt8](repeating: 0, count: byteCount)
    let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    guard status == errSecSuccess else {
        throw KeychainError.unhandledStatus(status)
    }
    return Data(bytes).base64URLEncodedString()
}

private func codeChallenge(for verifier: String) -> String {
    let digest = SHA256.hash(data: Data(verifier.utf8))
    return Data(digest).base64URLEncodedString()
}

private func formBody(_ values: [String: String]) -> Data {
    let body = values
        .map { key, value in
            "\(key.urlFormEncoded)=\(value.urlFormEncoded)"
        }
        .joined(separator: "&")
    return Data(body.utf8)
}

private extension Data {
    init?(base64URLSegment segment: String) {
        var base64 = segment
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - base64.count % 4) % 4
        base64.append(String(repeating: "=", count: padding))
        self.init(base64Encoded: base64)
    }

    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension String {
    var urlFormEncoded: String {
        addingPercentEncoding(withAllowedCharacters: .urlFormAllowed) ?? self
    }
}

private extension CharacterSet {
    static let urlFormAllowed: CharacterSet = {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=")
        return allowed
    }()
}
