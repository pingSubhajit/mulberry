import FirebaseCrashlytics

/// Crash reports go to Firebase Crashlytics, beside Android's. Reports carry no account details,
/// matching the privacy manifest, which declares crash data as not linked to the user.
enum CrashReporting {
    /// Developer options' check that reports arrive: the crash is sent when Mulberry next opens.
    static func testCrash() -> Never {
        fatalError("Mulberry test crash from Developer options")
    }

    /// A non-fatal report, sent straight away.
    static func testError() {
        Crashlytics.crashlytics().record(error: NSError(domain: "my.mulberry.test", code: 1,
                                                        userInfo: [NSLocalizedDescriptionKey: "Mulberry test error from Developer options"]))
    }
}
