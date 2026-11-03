import AuthenticationServices
import CryptoKit
import Foundation

/// The ways into a Mulberry account. One account can have both.
public enum SignInMethod: String, CaseIterable, Sendable {
    case google, apple
}

/// What Sign in with Apple hands the app, as the backend's `/auth/apple` takes it.
public struct AppleIDCredential: Encodable, Sendable {
    public let identityToken: String
    /// Lets the server revoke the person's Apple tokens if they delete their account.
    public let authorizationCode: String?
    /// The raw nonce; the token carries its SHA-256.
    public let nonce: String
    /// Apple shares the person's name only the first time they sign in to Mulberry.
    public let displayName: String?
}

public enum AppleSignInError: PresentableError, Sendable {
    case missingIdentityToken
    case failed
    /// Apple's own error, kept for diagnostics; people see the same plain message.
    case authorization(code: Int)

    public var errorDescription: String? {
        "Sign in with Apple didn’t finish. Try again."
    }
}

/// Apple's own Sign in with Apple sheet, over `anchor`. Asks for the person's name and email, and
/// binds the identity token to a fresh nonce so it can't be replayed.
@MainActor
public final class AppleSignIn: NSObject {
    private let anchor: ASPresentationAnchor
    private var controller: ASAuthorizationController?
    private var continuation: CheckedContinuation<ASAuthorizationAppleIDCredential, Error>?

    private init(anchor: ASPresentationAnchor) {
        self.anchor = anchor
    }

    public static func authorize(over anchor: ASPresentationAnchor) async throws -> AppleIDCredential {
        let nonce = try randomNonce()
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        request.nonce = sha256Hex(nonce)

        let credential = try await AppleSignIn(anchor: anchor).perform(request)
        guard let tokenData = credential.identityToken, let identityToken = String(data: tokenData, encoding: .utf8) else {
            throw AppleSignInError.missingIdentityToken
        }
        let name = credential.fullName.map { PersonNameComponentsFormatter().string(from: $0) }
        return AppleIDCredential(
            identityToken: identityToken,
            authorizationCode: credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) },
            nonce: nonce,
            displayName: name?.isEmpty == false ? name : nil
        )
    }

    /// The person closed Apple's sheet themselves: nothing went wrong.
    public nonisolated static func isCancellation(_ error: Error) -> Bool {
        (error as? ASAuthorizationError)?.code == .canceled
    }

    private func perform(_ request: ASAuthorizationAppleIDRequest) async throws -> ASAuthorizationAppleIDCredential {
        try await withCheckedThrowingContinuation { continuation in
            self.continuation = continuation
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            self.controller = controller
            controller.performRequests()
        }
    }

    private func finish(_ result: Result<ASAuthorizationAppleIDCredential, Error>) {
        continuation?.resume(with: result)
        continuation = nil
        controller = nil
    }

    private static func randomNonce() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw AppleSignInError.failed
        }
        return Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func sha256Hex(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

extension AppleSignIn: ASAuthorizationControllerDelegate {
    public func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        if let credential = authorization.credential as? ASAuthorizationAppleIDCredential {
            finish(.success(credential))
        } else {
            finish(.failure(AppleSignInError.failed))
        }
    }

    public func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        if Self.isCancellation(error) {
            finish(.failure(error))
        } else {
            finish(.failure(AppleSignInError.authorization(code: (error as NSError).code)))
        }
    }
}

extension AppleSignIn: ASAuthorizationControllerPresentationContextProviding {
    public func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        anchor
    }
}
