import UserNotifications

enum WallpaperNotificationCleanup {
    static func removeCompleted(through revision: Int) async {
        let center = UNUserNotificationCenter.current()
        let delivered = await center.deliveredNotifications()
        let candidates = delivered.map { item in
            WallpaperNotification(identifier: item.request.identifier,
                revision: item.request.content.userInfo["revision"] as? Int,
                isWallpaperUpdate: item.request.content.userInfo["kind"] as? String == "wallpaper_update")
        }
        let identifiers = NotificationCleanupPolicy.identifiers(through: revision, from: candidates)
        center.removeDeliveredNotifications(withIdentifiers: identifiers)
        // Removal is asynchronous. Record a readback without blocking completion on it.
        let remaining = await center.deliveredNotifications()
        let remainingCount = remaining.filter { identifiers.contains($0.request.identifier) }.count
        await AppServices.shared.log("notification_cleanup", revision: revision,
            detail: "Requested removal of \(identifiers.count) completed update(s); \(remainingCount) still present at immediate readback. Newer and unrelated notifications kept.")
    }
}
