import AppKit
import AuthenticationServices
import CryptoKit
import Foundation
import Network
import Networking
import Persistence
import ProductCore
import Security

public enum AuthModule {
    public static let name = "Auth"
}

public struct AuthSession: Sendable {
    public let userID: String
    public let userEmail: String?
    public let userDisplayName: String?
    public let userPhotoURL: String?
    public let bootstrap: BootstrapState

    public init?(bootstrap: BootstrapState) {
        guard let userID = bootstrap.userId else { return nil }
        self.userID = userID
        userEmail = bootstrap.userEmail
        userDisplayName = bootstrap.userDisplayName
        userPhotoURL = bootstrap.userPhotoUrl
        self.bootstrap = bootstrap
    }
}

public struct AuthFailure: Sendable {
    public let message: String
    /// The person closed Google's sign-in themselves: nothing went wrong.
    public let cancelled: Bool

    public init(message: String, cancelled: Bool = false) {
        self.message = message
        self.cancelled = cancelled
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
}

/// Signs in with Google, then hands the session to ProductCore's `BackendClient`, which keeps the
/// tokens in the Keychain and refreshes them for every request.
@MainActor
public final class AuthSessionController: ObservableObject {
    @Published public private(set) var state: AuthSessionState = .signedOut

    public let api: BackendClient
    private let configuration: MacAppConfiguration
    private let store: MacLocalStore
    private let oauthClient: GoogleOAuthClient

    public init(
        configuration: MacAppConfiguration,
        api: BackendClient,
        store: MacLocalStore,
        presentationAnchorProvider: @escaping @MainActor () -> ASPresentationAnchor? = {
            NSApp.keyWindow ?? NSApp.windows.first
        }
    ) {
        self.configuration = configuration
        self.api = api
        self.store = store
        self.oauthClient = GoogleOAuthClient(
            configuration: configuration,
            presentationAnchorProvider: presentationAnchorProvider
        )
    }

    public func restoreSessionOnLaunch() {
        Task {
            await restoreSession()
        }
    }

    /// Opens straight onto the saved account state; the app then refreshes it from the server.
    /// Without one, it waits for the server, and a refused session signs out.
    public func restoreSession() async {
        guard let userID = await api.signedInUserId else {
            state = .signedOut
            return
        }
        if let cached = store.cachedBootstrap(userId: userID), let session = AuthSession(bootstrap: cached) {
            state = .signedIn(session)
            return
        }
        state = .refreshing
        do {
            let bootstrap = try await api.bootstrap()
            accept(bootstrap)
        } catch {
            if await api.signedInUserId == nil {
                state = .signedOut
            } else {
                state = .failed(AuthFailure(message: userFacingMessage(for: error)))
            }
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
            accept(try await api.signIn(googleIDToken: idToken))
        } catch {
            AuthDiagnostics.log("Google sign-in failed: \(diagnosticMessage(for: error))")
            let cancelled = if case GoogleOAuthError.cancelled = error { true } else { false }
            state = .failed(AuthFailure(message: userFacingMessage(for: error), cancelled: cancelled))
        }
    }

    /// Keeps the session's account details current as the app refreshes them.
    public func accept(_ bootstrap: BootstrapState) {
        guard let session = AuthSession(bootstrap: bootstrap) else {
            state = .signedOut
            return
        }
        store.cacheBootstrap(bootstrap)
        state = .signedIn(session)
    }

    /// The server refused the saved session, so this Mac is signed out.
    public func sessionEnded() {
        if case let .signedIn(session) = state {
            store.clearBootstrap(userId: session.userID)
        }
        state = .signedOut
    }

    /// The server deleted the account, and its session with it: forget the account and sign out.
    public func accountDeleted() {
        if case let .signedIn(session) = state {
            store.forgetAccount(userId: session.userID)
        }
        state = .signedOut
    }

    public func signOut() async {
        if case let .signedIn(session) = state {
            store.clearBootstrap(userId: session.userID)
        }
        // Local sign-out is immediate even if the server cannot revoke the session.
        try? await api.signOut()
        state = .signedOut
        // TODO: Phase 11 should unregister and clear notification tokens here.
    }

    private func userFacingMessage(for error: Error) -> String {
        if let oauthError = error as? GoogleOAuthError {
            return oauthError.errorDescription ?? "Google Sign-In failed."
        }
        if error is URLError {
            return "Could not reach Mulberry at \(configuration.normalizedAPIBaseURL.absoluteString). Start the backend and try again."
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
    private let queue = DispatchQueue(label: "com.subhajit.mulberry.oauth.loopback")
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
        throw GoogleOAuthError.couldNotStart
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
