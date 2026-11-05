import SwiftUI
import UIKit

/// An invite that reached the app from a link, kept until the account can use it. With Mulberry
/// installed, the link opens the app directly. A fresh install can't carry it through the App
/// Store, so the invite page puts the link on the clipboard and onboarding offers to paste it.
@MainActor
final class InviteLinks: ObservableObject {
    static let shared = InviteLinks()
    /// The code waiting to be used. It lasts as long as the server keeps a code: a day.
    @Published private(set) var pending: String?
    private let defaults: UserDefaults
    private static let codeKey = "inboundInvite.code"
    private static let receivedKey = "inboundInvite.receivedAt"
    private static let lifetime: TimeInterval = 24 * 60 * 60
    private static let hosts: Set<String> = ["mulberry.my", "www.mulberry.my"]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let code = defaults.string(forKey: Self.codeKey),
           let received = defaults.object(forKey: Self.receivedKey) as? Date,
           Date.now.timeIntervalSince(received) < Self.lifetime {
            pending = code
        } else {
            forgetStored()
        }
    }

    /// Keeps the code from an invite link. Returns false for any other URL.
    @discardableResult
    func receive(_ url: URL) -> Bool {
        guard let code = Self.code(in: url) else { return false }
        receive(code: code)
        return true
    }

    func receive(code: String) {
        defaults.set(code, forKey: Self.codeKey)
        defaults.set(Date.now, forKey: Self.receivedKey)
        if pending != code { pending = code }
    }

    /// Forgets the code once it has been used or turned down. A newer one that arrived meanwhile stays.
    func finish(_ code: String) {
        guard pending == code else { return }
        pending = nil
        forgetStored()
    }

    private func forgetStored() {
        defaults.removeObject(forKey: Self.codeKey)
        defaults.removeObject(forKey: Self.receivedKey)
    }

    /// The link a partner opens to join.
    static func url(for code: String) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "mulberry.my"
        components.path = "/invite"
        components.queryItems = [URLQueryItem(name: "code", value: code)]
        return components.url ?? URL(string: "https://mulberry.my/invite")!
    }

    /// The code in `https://mulberry.my/invite?code=123456`.
    static func code(in url: URL) -> String? {
        guard url.scheme?.lowercased() == "https",
              let host = url.host()?.lowercased(), hosts.contains(host),
              ["/invite", "/invite/"].contains(url.path()),
              let raw = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                .queryItems?.first(where: { $0.name == "code" })?.value else { return nil }
        return normalized(raw)
    }

    /// The code in pasted text: the invite link, the whole message a partner shared, or the code alone.
    static func code(inPasted text: String) -> String? {
        let range = NSRange(text.startIndex..., in: text)
        if let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            for match in detector.matches(in: text, range: range) {
                if let url = match.url, let code = code(in: url) { return code }
            }
        }
        let compact = text.filter { !$0.isWhitespace }
        return compact.count == 6 ? normalized(compact) : nil
    }

    /// Six digits, as the server issues them.
    static func normalized(_ raw: String) -> String? {
        let digits = String(raw.filter { $0.isASCII && $0.isNumber }.prefix(6))
        return digits.count == 6 ? digits : nil
    }

    /// Whether the clipboard might hold an invite. This only looks at what kind of text it is, so
    /// iOS doesn't ask to paste; the content is read when the partner taps Paste.
    static func clipboardMayHoldInvite() async -> Bool {
        let pasteboard = UIPasteboard.general
        guard pasteboard.hasStrings || pasteboard.hasURLs else { return false }
        let found = try? await pasteboard.detectedPatterns(for: [\.probableWebURL, \.links, \.number])
        return !(found?.isEmpty ?? true)
    }
}

/// What the partner is told when an invite from a link can't be used.
struct InviteNotice: Equatable {
    let title: String
    let message: String

    static let notAnInvite = InviteNotice(title: "That isn’t an invite",
                                          message: "Copy the link your partner sent you, then tap Paste again.")

    /// The server's reason code, in the app's words. Nil when there is nothing to say: opening your own link.
    init?(code: String?) {
        switch code {
        case "OWN_INVITE": return nil
        case "INVITE_EXPIRED", "INVITE_NO_LONGER_VALID":
            self.init(title: "This invite has expired", message: "Ask your partner to send you a new one.")
        case "INVITE_NOT_FOUND":
            self.init(title: "This invite wasn’t found", message: "Ask your partner to send it again.")
        default:
            self.init(title: "Couldn’t open the invite", message: "Ask your partner to send it again.")
        }
    }

    init(title: String, message: String) {
        self.title = title
        self.message = message
    }
}

/// An invite from a link that arrived while already paired. It waits until the person unpairs to
/// join it or keeps their current pairing.
struct PairedInvite: Equatable {
    let code: String
    /// Why the last try to unpair failed.
    var error: String?

    /// The question, with the partner's name when there is one.
    static func message(partnerName: String?) -> String {
        if let partnerName = partnerName?.nonEmpty {
            return "You’re already paired with \(partnerName). To join a new invite, unpair first."
        }
        return "You’re already paired on Mulberry. To join a new invite, unpair first."
    }
}
