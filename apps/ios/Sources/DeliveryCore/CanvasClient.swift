import Foundation

public struct CanvasManifest: Codable, Equatable, Sendable {
    public let revision: Int
    public let imagePath: String
    public let publishedAt: String
}

public struct PreparedCanvas: Sendable {
    public let revision: Int
    public let fileURL: URL
    public let publishedAt: String
}

public enum DeliveryError: Error, LocalizedError {
    case invalidServer, invalidResponse, invalidImage, invalidRevision, serverStatus(Int)
    public var errorDescription: String? {
        switch self {
        case .invalidServer: "Use an http(s) server URL without credentials, query, or fragment."
        case .invalidResponse: "The server returned an invalid canvas manifest."
        case .invalidImage: "The server did not return a valid PNG under 8 MB."
        case .invalidRevision: "The image is not a Mulberry revision file."
        case .serverStatus(let status): "The mock server returned HTTP \(status)."
        }
    }
}

public struct CanvasClient: Sendable {
    public let baseURL: URL
    private let session: URLSession

    public init(server: String, session: URLSession = .shared) throws {
        guard let url = URL(string: server), ["http", "https"].contains(url.scheme),
              url.host != nil, url.user == nil, url.password == nil,
              url.query == nil, url.fragment == nil else { throw DeliveryError.invalidServer }
        self.baseURL = url
        self.session = session
    }

    public func manifest() async throws -> CanvasManifest {
        let (data, _) = try await request(path: "canvas/latest")
        let manifest = try JSONDecoder().decode(CanvasManifest.self, from: data)
        // Revision URLs are immutable and must stay on the configured mock server.
        guard manifest.revision > 0,
              manifest.imagePath == "/canvas/\(manifest.revision).png" else {
            throw DeliveryError.invalidResponse
        }
        return manifest
    }

    public func image(for manifest: CanvasManifest) async throws -> Data {
        let (data, response) = try await request(path: "canvas/\(manifest.revision).png")
        guard response.mimeType == "image/png", data.count <= 8 * 1024 * 1024,
              data.starts(with: [137, 80, 78, 71, 13, 10, 26, 10]) else {
            throw DeliveryError.invalidImage
        }
        return data
    }

    public func publish() async throws -> CanvasManifest {
        let (data, _) = try await request(path: "publish", method: "POST")
        return try JSONDecoder().decode(CanvasManifest.self, from: data)
    }

    public func report(_ event: DeliveryEvent) async throws {
        _ = try await request(path: "events", method: "POST", body: JSONEncoder().encode(event))
    }

    private func request(path: String, method: String = "GET", body: Data? = nil) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: baseURL.appendingPathComponent(path), cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpMethod = method
        request.httpBody = body
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw DeliveryError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else { throw DeliveryError.serverStatus(http.statusCode) }
        return (data, http)
    }
}
