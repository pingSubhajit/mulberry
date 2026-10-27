import UIKit
import UserNotifications

final class NotificationBridge: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if let item = options.shortcutItem { HomeQuickActions.shared.receive(item) }
        let configuration = UISceneConfiguration(name: nil, sessionRole: session.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        PushRegistration.shared.configure()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        UserDefaults.standard.set(deviceToken.map { String(format: "%02x", $0) }.joined(), forKey: "apnsToken")
        UserDefaults.standard.removeObject(forKey: "apnsRegistrationError")
        PushRegistration.shared.receivedAPNsToken(deviceToken)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        UserDefaults.standard.set(error.localizedDescription, forKey: "apnsRegistrationError")
    }

    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any], fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        // Only the user-enabled Shortcuts automation changes wallpaper.
        completionHandler(.noData)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await record(notification, stage: "notification_presented")
        return [.list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await record(response.notification, stage: "notification_opened")
    }

    private func record(_ notification: UNNotification, stage: String) async {
        let info = notification.request.content.userInfo
        guard let user = info["userId"] as? String,
              user == (try? await AppRuntime.api().signedInUserId),
              let pair = info["pairSessionId"] as? String,
              let journal = try? ProductWallpaperService.shared.journal(user: user, pair: pair == "unpaired" ? nil : pair) else { return }
        try? await journal.append(.init(stage: stage, revision: info["probe"] as? Int,
                                       detail: "Notification callback only; no wallpaper completion claimed."))
    }
}
