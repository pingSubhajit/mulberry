import Foundation
import UIKit

@MainActor
enum AppRuntime {
    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Mulberry", isDirectory: true)
    static let drawings = DrawingStore(directory: directory.appendingPathComponent("Drawings", isDirectory: true))
    private static var backend: BackendClient?
    /// Debug builds don't name their build, so the server never turns them away.
    private static var clientBuild: ClientBuild? {
        #if DEBUG
        nil
        #else
        ClientBuild(platform: "ios", number: AppUpdateGate.build)
        #endif
    }
    /// Sent with every request, debug builds included.
    private static var device: ClientDevice {
        ClientDevice(family: UIDevice.current.userInterfaceIdiom == .pad ? .ipad : .iphone, anonymousID: AnalyticsInstall.anonymousID())
    }
    /// Starts the analytics queue, which sends what an earlier launch left.
    static func startAnalytics() {
        let file = directory.appendingPathComponent("Analytics", isDirectory: true).appendingPathComponent("events.json")
        AppAnalytics.start(AnalyticsQueue(file: file, sender: BackendAnalyticsSender { await MainActor.run { try? api() } }))
    }
    /// Sends what is waiting as the app goes to the background, in the time iOS gives for it.
    static func sendAnalyticsInBackground() {
        let background = BackgroundSend()
        background.id = UIApplication.shared.beginBackgroundTask(withName: "analytics") { MainActor.assumeIsolated { background.end() } }
        Task {
            await AppAnalytics.flush()
            background.end()
        }
    }
    static func api() throws -> BackendClient {
        if let backend { return backend }
        guard let value = Bundle.main.object(forInfoDictionaryKey: "MulberryAPIBaseURL") as? String,
              let url = URL(string: value), ["https", "http"].contains(url.scheme), url.host != nil else {
            throw AppMessage("This build of Mulberry can’t connect. Install the latest version.")
        }
        let configuration = URLSessionConfiguration.default
        // The first local-development request may wait for iOS's network prompt.
        configuration.waitsForConnectivity = true
        configuration.timeoutIntervalForResource = 45
        let shortConfiguration = URLSessionConfiguration.default
        shortConfiguration.waitsForConnectivity = false
        shortConfiguration.timeoutIntervalForRequest = 3
        shortConfiguration.timeoutIntervalForResource = 3
        let client = try BackendClient(baseURL: url, tokenStore: SessionKeychain(service: (Bundle.main.bundleIdentifier ?? "com.subhajit.mulberry") + ".session"), transport: URLSessionTransport(session: URLSession(configuration: configuration)),
                                       shortTransport: URLSessionTransport(session: URLSession(configuration: shortConfiguration)),
                                       clientBuild: clientBuild, device: device)
        backend = client
        return client
    }
}

@MainActor
private final class BackgroundSend {
    var id = UIBackgroundTaskIdentifier.invalid
    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
