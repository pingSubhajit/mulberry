import UIKit
import UserNotifications
import FirebaseCore
import FirebaseMessaging

/// One FCM token belongs to one signed-in account. No token is registered before
/// APNs succeeds; a free personal-team build remains usable for local tests.
@MainActor
final class PushRegistration: NSObject, MessagingDelegate {
    static let shared = PushRegistration()
    private var token: String?
    private var registered: String?
    private var registering = false
    private var signingOut = false
    private var hasAPNsToken = false
    private var requestedAPNs = false

    static var deviceId: String {
        if let value = UserDefaults.standard.string(forKey: "deviceInstanceId") { return value }
        let value = UUID().uuidString
        UserDefaults.standard.set(value, forKey: "deviceInstanceId")
        return value
    }

    func configure() {
        guard FirebaseApp.app() == nil,
              let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
              let options = FirebaseOptions(contentsOfFile: path),
              options.bundleID == Bundle.main.bundleIdentifier else { return }
        FirebaseApp.configure(options: options)
        Messaging.messaging().delegate = self
    }

    func receivedAPNsToken(_ data: Data) {
        guard FirebaseApp.app() != nil else { return }
        hasAPNsToken = true
        Messaging.messaging().apnsToken = data
        Task { await refresh() }
    }

    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        Task { @MainActor in
            self.token = fcmToken
            await self.refresh()
        }
    }

    func refresh() async {
        guard !registering, !signingOut, FirebaseApp.app() != nil,
              let api = try? AppRuntime.api(), let user = await api.signedInUserId else { return }
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        guard [.authorized, .provisional, .ephemeral].contains(settings.authorizationStatus) else { return }
        if !hasAPNsToken {
            if !requestedAPNs {
                requestedAPNs = true
                UIApplication.shared.registerForRemoteNotifications()
            }
            return
        }
        registering = true
        defer { registering = false }
        do {
            let value: String
            if let token { value = token } else { value = try await Messaging.messaging().token(); token = value }
            guard !signingOut, user == (await api.signedInUserId) else { return }
            let key = "\(user):\(value)"
            guard registered != key else { return }
            let environment = Bundle.main.object(forInfoDictionaryKey: "MulberryAppEnvironment") as? String ?? "prod"
            try await api.registerPushToken(value, environment: environment)
            guard !signingOut, user == (await api.signedInUserId) else {
                try? await api.unregisterPushToken(value)
                return
            }
            registered = key
            UserDefaults.standard.removeObject(forKey: "pushRegistrationError")
        } catch {
            UserDefaults.standard.set("Remote updates could not connect. They will retry when Mulberry opens.", forKey: "pushRegistrationError")
        }
    }

    func signOut() async {
        signingOut = true
        defer { signingOut = false; registered = nil; token = nil }
        if let token, let api = try? AppRuntime.api() { try? await api.unregisterPushToken(token) }
        if FirebaseApp.app() != nil { try? await Messaging.messaging().deleteToken() }
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }
}
