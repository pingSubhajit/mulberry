import Foundation

public enum NotificationAccess: Sendable {
    case notRequested, denied, allowed, noPresentation
}

/// Observations, not an inspection of the user's private Shortcuts library.
public struct SetupHealth: Equatable, Sendable {
    public enum State: Sendable { case needsPermission, blocked, untested, waiting, incomplete, responded, settingsChanged }
    public let state: State
    public let title: String
    public let detail: String
    public let checkedAt: Date?

    public static func assess(access: NotificationAccess, events: [DeliveryEvent], now: Date = Date()) -> Self {
        if access == .denied {
            return Self(state: .blocked, title: "Notifications are off", detail: "Allow Mulberry notifications in Settings, then test again.", checkedAt: nil)
        }
        if access == .notRequested {
            return Self(state: .needsPermission, title: "Allow wallpaper updates", detail: "Mulberry uses a notification to start your wallpaper Shortcut.", checkedAt: nil)
        }
        if access == .noPresentation {
            return Self(state: .blocked, title: "Notification delivery needs a check", detail: "All notification display locations are off. Enable Notification Center, then test again.", checkedAt: nil)
        }
        guard let probe = events.last(where: { $0.stage == "local_notification_scheduled" }) else {
            return Self(state: .untested, title: "Setup has not been tested", detail: "Add the Shortcut, run it once, turn Automation on, then run the update test.", checkedAt: nil)
        }
        if let manual = events.last(where: { $0.stage == "manual_shortcut_requested" }), manual.timestamp > probe.timestamp {
            return Self(state: .untested, title: "Test automatic updates", detail: "A manual run does not check the notification trigger. Run the update test, then go Home without running the Shortcut yourself.", checkedAt: nil)
        }
        if let change = events.last(where: { $0.stage == "notification_settings_changed" }), change.timestamp > probe.timestamp {
            return Self(state: .settingsChanged, title: "Settings changed — test again", detail: "Notification settings changed since the last test.", checkedAt: probe.timestamp)
        }
        if let receipt = events.last(where: {
            $0.stage == "shortcut_reported_applied" && $0.revision == probe.revision && $0.timestamp >= probe.timestamp
        }) {
            return Self(state: .responded, title: "Last test completed", detail: "The Shortcut reached its final step. Check the wallpaper once. Test again after editing the Shortcut.", checkedAt: receipt.timestamp)
        }
        if now.timeIntervalSince(probe.timestamp) < 45 {
            return Self(state: .waiting, title: "Waiting for the Shortcut", detail: "Mulberry is running your Shortcut. This takes a few seconds.", checkedAt: nil)
        }
        return Self(state: .incomplete, title: "Test did not finish", detail: "Open the Shortcut and check Automation is on. Run it once to see any error or permission prompt, then test again. Delivery can also be delayed.", checkedAt: probe.timestamp)
    }
}

public struct WallpaperNotification: Sendable {
    public let identifier: String
    public let revision: Int?
    public let isWallpaperUpdate: Bool
    public init(identifier: String, revision: Int?, isWallpaperUpdate: Bool) {
        self.identifier = identifier
        self.revision = revision
        self.isWallpaperUpdate = isWallpaperUpdate
    }
}

public enum NotificationCleanupPolicy {
    /// Never remove newer drawings, unknown payloads, or other kinds of messages.
    public static func identifiers(through revision: Int, from notifications: [WallpaperNotification]) -> [String] {
        notifications.compactMap {
            guard $0.isWallpaperUpdate, let number = $0.revision, number > 0, number <= revision else { return nil }
            return $0.identifier
        }
    }
}
