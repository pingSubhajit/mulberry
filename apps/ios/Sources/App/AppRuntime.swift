import Foundation

@MainActor
enum AppRuntime {
    static let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("Mulberry", isDirectory: true)
    static let drawings = DrawingStore(directory: directory.appendingPathComponent("Drawings", isDirectory: true))
    private static var backend: BackendClient?
    static func api() throws -> BackendClient {
        if let backend { return backend }
        guard let value = Bundle.main.object(forInfoDictionaryKey: "MulberryAPIBaseURL") as? String,
              let url = URL(string: value), ["https", "http"].contains(url.scheme), url.host != nil else {
            throw BackendError.rejected(0, "This build is missing its server address.")
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
                                       shortTransport: URLSessionTransport(session: URLSession(configuration: shortConfiguration)))
        backend = client
        return client
    }
}
