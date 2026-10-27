import GoogleSignIn
import UIKit

@MainActor
enum GoogleAuthentication {
    static func signIn() async throws -> String {
        guard let clientID = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String,
              clientID.hasSuffix(".apps.googleusercontent.com"),
              let serverID = Bundle.main.object(forInfoDictionaryKey: "GIDServerClientID") as? String,
              serverID.hasSuffix(".apps.googleusercontent.com") else {
            throw GoogleConfigurationError.missingClient
        }
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              let root = scene.windows.first(where: \.isKeyWindow)?.rootViewController else {
            throw GoogleConfigurationError.noWindow
        }
        var presenter = root
        while let presented = presenter.presentedViewController { presenter = presented }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID, serverClientID: serverID)
        let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
        guard let token = result.user.idToken?.tokenString, !token.isEmpty else { throw BackendError.invalidResponse }
        return token
    }

    static func handle(_ url: URL) -> Bool { GIDSignIn.sharedInstance.handle(url) }
    static func signOut() { GIDSignIn.sharedInstance.signOut() }
}

private enum GoogleConfigurationError: LocalizedError {
    case missingClient, noWindow
    var errorDescription: String? {
        switch self {
        case .missingClient: "Google sign-in is not configured in this build."
        case .noWindow: "Please return to Mulberry and try signing in again."
        }
    }
}
