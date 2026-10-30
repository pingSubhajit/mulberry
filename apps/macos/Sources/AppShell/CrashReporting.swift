import AppKit
import FirebaseCore
import FirebaseCrashlytics

/// Crash reports go to Firebase Crashlytics, beside the iPhone's and Android's. The Mac shares the
/// iPhone app's bundle IDs, so it shares its Firebase app too. Reports carry no account details,
/// matching the privacy manifest, which declares crash data as not linked to the person.
@MainActor
enum CrashReporting {
    /// Starts reporting, when the build carries its Firebase client configuration.
    static func start() {
        // AppKit otherwise catches exceptions on the main thread and carries on; let them crash,
        // so they are reported.
        UserDefaults.standard.register(defaults: ["NSApplicationCrashOnExceptions": true])
        guard FirebaseApp.app() == nil,
              let path = Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist"),
              let options = FirebaseOptions(contentsOfFile: path),
              options.bundleID == Bundle.main.bundleIdentifier else { return }
        FirebaseApp.configure(options: options)
    }

    #if DEBUG
    /// Settings' check that reports arrive: the crash is sent when Mulberry next opens.
    static func testCrash() -> Never {
        fatalError("Mulberry test crash from Settings")
    }

    /// A non-fatal report, sent straight away.
    static func testError() {
        Crashlytics.crashlytics().record(error: NSError(domain: "my.mulberry.test", code: 1,
                                                        userInfo: [NSLocalizedDescriptionKey: "Mulberry test error from Settings"]))
    }
    #endif
}
