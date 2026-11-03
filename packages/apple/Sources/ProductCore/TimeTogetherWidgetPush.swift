import Foundation
#if canImport(WidgetKit)
import WidgetKit
#endif

/// Tells the server where WidgetKit takes reloads for this device's widgets, and the secret the widget
/// fetches what to show with. When the anniversary changes or the pair ends, the server sends a
/// WidgetKit push to this token; WidgetKit reloads the widget, which asks the server itself. A reload
/// the app asks for from the background can wait for hours, and the app may never get its silent
/// push. Only the apps report it: the widget extension has no session.
public enum TimeTogetherWidgetPush {
    private static let reportedKey = "timeTogether.reportedWidgetPush.2"

    /// Sends the widget push token with this device's push registration, when either has changed.
    public static func report(api: BackendClient, registration: String) async {
        #if canImport(WidgetKit)
        TimeTogetherStore.saveAPIBaseURL(api.baseURL)
        guard let info = await WidgetCenter.shared.currentPushInfo,
              let bundleId = Bundle.main.bundleIdentifier,
              let secret = TimeTogetherStore.widgetSecret else { return }
        let token = info.token.map { String(format: "%02x", $0) }.joined()
        let production = Bundle.main.object(forInfoDictionaryKey: "MulberryAPNSEnvironment") as? String == "production"
        let environment = production ? "production" : "sandbox"
        let reported = "\(registration):\(token):\(environment):\(secret)"
        guard UserDefaults.standard.string(forKey: reportedKey) != reported else { return }
        do {
            try await api.updateWidgetPushToken(token, registration: registration, bundleId: bundleId, apnsEnvironment: environment, widgetSecret: secret)
            UserDefaults.standard.set(reported, forKey: reportedKey)
        } catch {
            // Sent again at the next push registration check.
        }
        #endif
    }

    /// A new account registers its own token; report the widget token again for it.
    public static func forget() {
        UserDefaults.standard.removeObject(forKey: reportedKey)
    }
}
