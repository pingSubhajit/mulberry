import AppKit
import FirebaseCore
import FirebaseMessaging
import ProductCore
import UserNotifications

/// A message is a hint. Account and drawing data always come from the backend.
struct MacPushEvent: Sendable {
    let type: String
    let pairSessionID: String?
    let revision: Int64?
    init?(_ payload: [AnyHashable: Any]) {
        guard let type = payload["type"] as? String,
              ["CANVAS_UPDATED", "CANVAS_NUDGE", "PAIRING_CONFIRMED", "PAIRING_DISCONNECTED",
               "PARTNER_VISIBILITY_CHANGED", "REACTION", "DRAW_REMINDER"].contains(type) else { return nil }
        self.type = type
        pairSessionID = payload["pairSessionId"] as? String
        revision = (payload["latestRevision"] as? String).flatMap(Int64.init)
    }
}

/// Revoke the installation's token before its account signs out.
@MainActor
final class MacPushRegistration: NSObject, MessagingDelegate {
    private var enabled: Bool { Bundle.main.object(forInfoDictionaryKey: "MulberryRemotePushEnabled") as? String == "YES" }
    private let api: BackendClient
    private var token: String?
    private var registered: String?
    private var registering = false
    private var signingOut = false
    private var hasAPNsToken = false
    private var requestedAPNs = false
    init(api: BackendClient) { self.api = api }
    func configure() {
        guard enabled, FirebaseApp.app() != nil else { return }
        Messaging.messaging().delegate = self
    }
    func refresh() async {
        guard enabled, !registering, !signingOut, FirebaseApp.app() != nil,
              let user = await api.signedInUserId else { return }
        registering = true
        defer { registering = false }
        do {
            let center = UNUserNotificationCenter.current()
            var settings = await center.notificationSettings()
            if settings.authorizationStatus == .notDetermined {
                _ = try await center.requestAuthorization(options: [.alert, .sound, .badge])
                settings = await center.notificationSettings()
            }
            guard !signingOut, user == (await api.signedInUserId) else { return }
            if !hasAPNsToken {
                if !requestedAPNs { requestedAPNs = true; NSApp.registerForRemoteNotifications() }
                return
            }
            let value: String
            if let token { value = token } else { value = try await Messaging.messaging().token(); token = value }
            guard !signingOut, user == (await api.signedInUserId) else { return }
            let key = "\(user):\(value)"
            guard registered != key else { return }
            let environment = Bundle.main.object(forInfoDictionaryKey: "MulberryAppEnvironment") as? String ?? "prod"
            try await api.registerPushToken(value, environment: environment, platform: .macOS)
            guard !signingOut, user == (await api.signedInUserId) else { return }
            registered = key
            UserDefaults.standard.set(true, forKey: "pushRegistered")
            UserDefaults.standard.set(settings.authorizationStatus.rawValue, forKey: "pushAuthorizationStatus")
            UserDefaults.standard.removeObject(forKey: "pushRegistrationError")
        } catch {
            UserDefaults.standard.set("Remote updates could not connect. They will retry when Mulberry opens.", forKey: "pushRegistrationError")
        }
    }
    func receivedAPNsToken(_ data: Data) {
        guard enabled, FirebaseApp.app() != nil else { return }
        hasAPNsToken = true
        UserDefaults.standard.set(true, forKey: "apnsTokenPresent")
        let production = Bundle.main.object(forInfoDictionaryKey: "MulberryAPNSEnvironment") as? String == "production"
        Messaging.messaging().setAPNSToken(data, type: production ? .prod : .sandbox)
        Task { await refresh() }
    }
    func failedToRegister() {
        requestedAPNs = false
        UserDefaults.standard.set("Apple push registration failed. It will retry when Mulberry opens.", forKey: "pushRegistrationError")
    }
    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        Task { @MainActor in self.token = fcmToken; await self.refresh() }
    }
    func signOut() async {
        signingOut = true
        defer { signingOut = false; registered = nil; token = nil }
        if let token { try? await api.unregisterPushToken(token) }
        if FirebaseApp.app() != nil { try? await Messaging.messaging().deleteToken() }
        UserDefaults.standard.set(false, forKey: "pushRegistered")
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }
}
