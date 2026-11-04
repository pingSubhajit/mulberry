import UIKit
import UserNotifications

final class NotificationBridge: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        if let item = options.shortcutItem { HomeQuickActions.shared.receive(item) }
        // An invite link that launched the app. SwiftUI delivers it too; taking it twice is harmless.
        for activity in options.userActivities where activity.activityType == NSUserActivityTypeBrowsingWeb {
            if let url = activity.webpageURL { InviteLinks.shared.receive(url) }
        }
        let configuration = UISceneConfiguration(name: nil, sessionRole: session.role)
        configuration.delegateClass = QuickActionSceneDelegate.self
        return configuration
    }

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        AppRuntime.startAnalytics()
        PushRegistration.shared.configure()
        AppUpdateGate.shared.start()
        ReactionActivities.followTokens()
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
        // Only the user-enabled Shortcuts automation changes wallpaper. A reaction push asks the
        // canvas to fetch and play it while Mulberry runs; the Live Activity has its own push.
        if userInfo["type"] as? String == "REACTION" { NotificationCenter.default.post(name: .mulberryReactionPush, object: nil) }
        // The partner changed the anniversary, or the pair ended: the widget's store follows, a running
        // app fetches its account again, and the server's WidgetKit push that follows redraws the widget.
        if TimeTogetherStore.receivePush(userInfo) {
            NotificationCenter.default.post(name: .mulberryAnniversaryPush, object: nil)
            completionHandler(.newData)
            return
        }
        completionHandler(.noData)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        await record(notification, stage: "notification_presented")
        return [.list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        await record(response.notification, stage: "notification_opened")
        guard response.actionIdentifier == UNNotificationDefaultActionIdentifier else { return }
        let type = response.notification.request.content.userInfo["type"] as? String
        // Only the server's pushes; Mulberry's own reminders and tests are not counted as opened.
        if response.notification.request.trigger is UNPushNotificationTrigger { AppAnalytics.track(.pushOpened(PushType(payloadType: type))) }
        guard let route = AppRoute(notificationType: type) else { return }
        await MainActor.run { AppRoutes.shared.pending = route }
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
