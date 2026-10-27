import UserNotifications

@MainActor protocol WallpaperNotifications {
    func add(_ request: UNNotificationRequest) async throws
    func delivered() async -> [UNNotificationRequest]
    func removeDelivered(_ identifiers: [String])
    func removePending(_ identifiers: [String])
}

@MainActor struct SystemWallpaperNotifications: WallpaperNotifications {
    func add(_ request: UNNotificationRequest) async throws { try await UNUserNotificationCenter.current().add(request) }
    func delivered() async -> [UNNotificationRequest] { await UNUserNotificationCenter.current().deliveredNotifications().map(\.request) }
    func removeDelivered(_ identifiers: [String]) { UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: identifiers) }
    func removePending(_ identifiers: [String]) { UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: identifiers) }
}
