import UIKit

@MainActor
enum AppleAuthentication {
    /// Apple's Sign in with Apple sheet, over Mulberry's window.
    static func signIn() async throws -> AppleIDCredential {
        guard let window = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive })?
            .windows.first(where: \.isKeyWindow) else {
            throw AppleSignInError.failed
        }
        return try await AppleSignIn.authorize(over: window)
    }
}
