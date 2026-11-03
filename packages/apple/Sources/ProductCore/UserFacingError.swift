import Foundation

/// A failure Mulberry explains in its own words. Only errors that conform show their own
/// description; any other error, from the system or a server, gets the caller's plain fallback,
/// so people never read raw system or server text.
public protocol PresentableError: LocalizedError {}

/// What to tell people when something fails. The server sends a machine-readable `code` with each
/// failure people can see; the words for it live here, on the device, never in the server's reply.
public enum UserFacingError {
    public static let offline = "Can’t reach Mulberry. Check your connection and try again."

    /// What to say about `error`, or nil when there is nothing to say, as for a cancelled request.
    /// `fallback` names what failed, as "Couldn’t send your reaction. Try again."
    public static func message(for error: Error, fallback: String) -> String? {
        if error is CancellationError { return nil }
        if let url = error as? URLError { return url.code == .cancelled ? nil : offline }
        if let presentable = error as? PresentableError { return presentable.errorDescription ?? fallback }
        return fallback
    }

    /// The words for a server failure `code`, or nil when Mulberry has none.
    public static func message(forCode code: String) -> String? {
        switch code {
        case "OWN_INVITE": "That’s your own invite code. Share it with your partner."
        case "INVITE_EXPIRED", "INVITE_NO_LONGER_VALID": "That invite code has expired. Ask your partner for a new one."
        case "INVITE_NOT_FOUND": "That invite code wasn’t found. Check it and try again."
        case "INVITE_CODE_REQUIRED": "Enter the 6-digit code your partner sent you."
        case "ALREADY_PAIRED": "You’re already paired. Unpair in Settings to join a new invite."
        case "NOT_PAIRED": "You’re not paired yet. Invite your partner to start."
        case "ONBOARDING_INCOMPLETE": "Add your partner’s name and your anniversary, then create an invite."
        case "PROFILE_PHOTO_TOO_LARGE": "That photo is too large. Choose one under 10 MB."
        case "PROFILE_PHOTO_UNSUPPORTED": "Choose a JPEG, PNG or WebP photo."
        case "PROFILE_PHOTO_UNREADABLE": "That photo couldn’t be opened. Choose another one."
        case "TOO_MANY_INVITE_ATTEMPTS": "Too many tries. Wait an hour, then enter the code again."
        case "TOO_MANY_REACTIONS": "That’s a lot of love. Wait a moment, then send more."
        default: nil
        }
    }
}

/// A message Mulberry wrote itself, for failures that are not the server's.
public struct AppMessage: PresentableError, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}
