import AppKit
import ProductCore
import UserNotifications

/// The partner joining while this Mac waits for them: a notification, and a red dot on the menu
/// bar icon, both leading to the two photos meeting. Until then, opening Mulberry shows the two
/// photos first. Each pair is welcomed once.
///
/// Without push notifications the Mac finds out by asking: every minute while it waits, and as it
/// wakes or Mulberry comes forward.
@MainActor
final class PartnerArrival: NSObject, UNUserNotificationCenterDelegate {
    /// Opens the welcome, from the notification or the dot.
    var open: () -> Void = {}
    /// Asks the server for the account again.
    var refresh: () async -> Void = {}

    private let defaults: UserDefaults
    private weak var statusButton: NSStatusBarButton?
    private var dot: NSView?
    private var polling: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []
    private var previous: BootstrapState?
    private nonisolated static let category = "partnerJoined"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        super.init()
        UNUserNotificationCenter.current().delegate = self
        let workspace = NSWorkspace.shared.notificationCenter
        observers.append(workspace.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkSoon() }
        })
        observers.append(NotificationCenter.default.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.checkSoon() }
        })
    }

    func attach(to button: NSStatusBarButton?) {
        statusButton = button
        showDot(pending != nil)
    }

    /// The account changed. A pairing that was not there before, outside onboarding, is welcomed.
    /// - Parameter onboarding: whether onboarding is on screen, which welcomes the pair itself.
    func update(_ bootstrap: BootstrapState?, signedIn: Bool, onboarding: Bool) {
        defer { previous = bootstrap }
        guard signedIn, let bootstrap, let user = bootstrap.userId else {
            stopPolling()
            showDot(false)
            return
        }
        if bootstrap.isPaired {
            stopPolling()
            if let pair = bootstrap.pairSessionId, PairingCelebration.shouldPresent(previous: previous, current: bootstrap) {
                if onboarding { markWelcomed(user: user, pair: pair) } else { arrived(bootstrap, user: user, pair: pair) }
            }
        } else {
            // A pending welcome for a pair that has since ended goes with it.
            if pending != nil { clear() }
            if bootstrap.onboardingCompleted { startPolling() }
        }
    }

    /// The pair waiting to be welcomed, if any.
    var pending: (user: String, pair: String)? {
        guard let value = defaults.string(forKey: Self.pendingKey) else { return nil }
        let parts = value.split(separator: "|").map(String.init)
        return parts.count == 2 ? (parts[0], parts[1]) : nil
    }

    /// Whether the account and pair on screen are the ones waiting.
    func isPending(_ bootstrap: BootstrapState?) -> Bool {
        guard let pending, let bootstrap else { return false }
        return pending.user == bootstrap.userId && pending.pair == bootstrap.pairSessionId
    }

    /// The welcome has been seen.
    func welcomed() {
        if let pending { markWelcomed(user: pending.user, pair: pending.pair) }
        clear()
    }

    private func arrived(_ bootstrap: BootstrapState, user: String, pair: String) {
        guard !defaults.bool(forKey: Self.welcomedKey(user: user, pair: pair)) else { return }
        defaults.set("\(user)|\(pair)", forKey: Self.pendingKey)
        showDot(true)
        let content = UNMutableNotificationContent()
        content.title = "\(bootstrap.partnerDisplayName?.nonEmpty ?? "Your partner") joined you on Mulberry"
        content.body = "Draw them something to say hello."
        content.sound = .default
        content.categoryIdentifier = Self.category
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "partnerJoined.\(pair)", content: content, trigger: nil))
    }

    private func markWelcomed(user: String, pair: String) {
        defaults.set(true, forKey: Self.welcomedKey(user: user, pair: pair))
    }

    private func clear() {
        defaults.removeObject(forKey: Self.pendingKey)
        showDot(false)
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }

    private static let pendingKey = "partnerWelcome.pending"
    private static func welcomedKey(user: String, pair: String) -> String { "partnerWelcome.seen.\(user).\(pair)" }

    // MARK: Asking

    private func startPolling() {
        guard polling == nil else { return }
        polling = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(60))
                guard !Task.isCancelled else { return }
                await self?.refresh()
            }
        }
    }

    private func stopPolling() {
        polling?.cancel()
        polling = nil
    }

    private func checkSoon() {
        guard polling != nil else { return }
        Task { await refresh() }
    }

    // MARK: The dot

    private func showDot(_ shown: Bool) {
        guard let button = statusButton else { return }
        if shown, dot == nil {
            let size: CGFloat = 7
            // At the icon's top right corner, whichever way up the button draws.
            let top = button.isFlipped ? 2 : button.bounds.maxY - size - 2
            let view = NSView(frame: NSRect(x: button.bounds.maxX - size - 1, y: top, width: size, height: size))
            view.wantsLayer = true
            view.layer?.backgroundColor = NSColor(srgbRed: 0.70, green: 0.07, blue: 0.16, alpha: 1).cgColor
            view.layer?.cornerRadius = size / 2
            view.layer?.borderColor = NSColor.white.withAlphaComponent(0.9).cgColor
            view.layer?.borderWidth = 1
            view.autoresizingMask = [.minXMargin, button.isFlipped ? .maxYMargin : .minYMargin]
            button.addSubview(view)
            dot = view
            button.setAccessibilityLabel("Mulberry, your partner joined")
        } else if !shown, let dot {
            dot.removeFromSuperview()
            self.dot = nil
            button.setAccessibilityLabel("Mulberry")
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.notification.request.content.categoryIdentifier == Self.category else { return }
        await MainActor.run { open() }
    }
}
