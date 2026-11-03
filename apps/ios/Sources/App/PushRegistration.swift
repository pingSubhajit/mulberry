import UIKit
import UserNotifications
import FirebaseCore
import FirebaseMessaging

/// One FCM token belongs to one signed-in account. No token is registered before
/// APNs succeeds; a free personal-team build remains usable for local tests.
///
/// The token is registered whether or not notifications are allowed: silent pushes and the
/// reactions Live Activity reach the device without that permission.
@MainActor
final class PushRegistration: NSObject, MessagingDelegate {
    static let shared = PushRegistration()
    private var token: String?
    private var registered: String?
    private var registering = false
    private var signingOut = false
    private var hasAPNsToken = false
    private var requestedAPNs = false

    /// The reactions Live Activity's tokens: the app's push-to-start token, and the push token of
    /// the activity on screen, if any.
    private struct LiveActivityTokens: Equatable {
        var pushToStart: String?
        var activity: String?
    }
    private var liveActivity = LiveActivityTokens()
    /// What the server has for this device's registration.
    private var reportedLiveActivity: (registration: String, tokens: LiveActivityTokens)?
    /// Each report waits for the one before, so the server ends with the latest tokens.
    private var liveActivityReport: Task<Void, Never>?

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
            if registered != key {
                let environment = Bundle.main.object(forInfoDictionaryKey: "MulberryAppEnvironment") as? String ?? "prod"
                try await api.registerPushToken(value, environment: environment)
                guard !signingOut, user == (await api.signedInUserId) else {
                    try? await api.unregisterPushToken(value)
                    return
                }
                registered = key
                UserDefaults.standard.removeObject(forKey: "pushRegistrationError")
            }
            reportLiveActivityTokens()
            await TimeTogetherWidgetPush.report(api: api, registration: value)
        } catch {
            UserDefaults.standard.set("Remote updates could not connect. They will retry when Mulberry opens.", forKey: "pushRegistrationError")
        }
    }

    /// A reactions Live Activity token arrived or went away.
    func liveActivityTokenChanged(_ change: BackendClient.LiveActivityToken) {
        switch change {
        case let .pushToStart(value): liveActivity.pushToStart = value
        case let .activity(value): liveActivity.activity = value
        }
        reportLiveActivityTokens()
    }

    /// Sends the server the Live Activity tokens it does not have yet, once this device's push
    /// token is registered. One that fails is sent again at the next refresh.
    private func reportLiveActivityTokens() {
        let previous = liveActivityReport
        liveActivityReport = Task {
            await previous?.value
            guard !signingOut, let registration = registered, let token, let api = try? AppRuntime.api() else { return }
            let current = liveActivity
            let known = reportedLiveActivity?.registration == registration ? reportedLiveActivity?.tokens : nil
            do {
                if known == nil || known?.pushToStart != current.pushToStart {
                    try await api.updateLiveActivityToken(.pushToStart(current.pushToStart), pushToken: token)
                }
                if known == nil || known?.activity != current.activity {
                    try await api.updateLiveActivityToken(.activity(current.activity), pushToken: token)
                }
                guard registered == registration else { return }
                reportedLiveActivity = (registration, current)
            } catch {}
        }
    }

    func signOut() async {
        signingOut = true
        defer { signingOut = false; registered = nil; token = nil; reportedLiveActivity = nil; TimeTogetherWidgetPush.forget() }
        if let token, let api = try? AppRuntime.api() { try? await api.unregisterPushToken(token) }
        if FirebaseApp.app() != nil { try? await Messaging.messaging().deleteToken() }
        UNUserNotificationCenter.current().removeAllPendingNotificationRequests()
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }
}
