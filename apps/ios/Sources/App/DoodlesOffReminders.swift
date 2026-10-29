import UserNotifications

/// While doodles are hidden from the wallpaper, Mulberry reminds the user an hour later, then
/// 6 hours after that, then 24, then every 72, as Android does. Tapping one opens Settings.
@MainActor
enum DoodlesOffReminders {
    static let type = "DOODLES_OFF"
    private static let prefix = "doodlesOff."
    /// iOS only keeps so many pending, so each run tops up the next few from when doodles were hidden.
    private static let ahead = 6

    /// Schedules the reminders while doodles are hidden and the user is paired, and removes them otherwise.
    static func sync(user: String?, hidden: Bool, paired: Bool) async {
        let center = UNUserNotificationCenter.current()
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(prefix) }
        guard let user, hidden, paired else {
            center.removePendingNotificationRequests(withIdentifiers: pending)
            let delivered = await center.deliveredNotifications().map(\.request.identifier).filter { $0.hasPrefix(prefix) }
            center.removeDeliveredNotifications(withIdentifiers: delivered)
            if let user { UserDefaults.standard.removeObject(forKey: key(user)) }
            return
        }
        let since = UserDefaults.standard.object(forKey: key(user)) as? Date ?? {
            let now = Date()
            UserDefaults.standard.set(now, forKey: key(user))
            return now
        }()
        let settings = await center.notificationSettings()
        guard [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) else { return }
        for (index, hours) in upcoming(since: since) {
            let id = "\(prefix)\(index)"
            guard !pending.contains(id) else { continue }
            let content = UNMutableNotificationContent()
            content.title = "Doodles on wallpaper turned off"
            content.body = "Turn on “Show doodles on wallpaper” to show strokes on your wallpaper."
            content.userInfo = ["type": type]
            content.threadIdentifier = "mulberry-account"
            // Quiet, like the server's reminders: no banner or sound, only Notification Center.
            content.interruptionLevel = .passive
            let delay = since.addingTimeInterval(hours * 3600).timeIntervalSinceNow
            try? await center.add(UNNotificationRequest(identifier: id, content: content,
                                                        trigger: UNTimeIntervalNotificationTrigger(timeInterval: delay, repeats: false)))
        }
    }

    /// The next few reminders still to come, as their place in the sequence and hours after hiding.
    static func upcoming(since: Date, now: Date = .now) -> [(Int, Double)] {
        var result: [(Int, Double)] = [], index = 0, hours = 1.0
        while result.count < ahead {
            if since.addingTimeInterval(hours * 3600) > now { result.append((index, hours)) }
            hours += index == 0 ? 6 : index == 1 ? 24 : 72
            index += 1
        }
        return result
    }

    private static func key(_ user: String) -> String { "doodlesHiddenSince.\(user)" }
}
