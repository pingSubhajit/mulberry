import Foundation
import ProductCore

public enum NetworkingModule {
    public static let name = "Networking"
}

public struct MacAppConfiguration: Sendable {
    public let apiBaseURL: URL
    public let googleClientID: String
    public let googleClientSecret: String
    public let callbackPath: String

    public init(
        apiBaseURL: URL,
        googleClientID: String,
        googleClientSecret: String = "",
        callbackPath: String = "/oauth2redirect"
    ) {
        self.apiBaseURL = apiBaseURL
        self.googleClientID = googleClientID
        self.googleClientSecret = googleClientSecret
        self.callbackPath = callbackPath
    }

    public static var current: MacAppConfiguration {
        let environment = DotenvLoader.load().merging(ProcessInfo.processInfo.environment) { _, processValue in
            processValue
        }
        let apiBaseURL = environment["MULBERRY_MAC_API_BASE_URL"].flatMap(URL.init(string:)) ?? defaultAPIBaseURL
        return MacAppConfiguration(
            apiBaseURL: apiBaseURL,
            googleClientID: environment["MULBERRY_MAC_GOOGLE_CLIENT_ID"] ?? "",
            googleClientSecret: environment["MULBERRY_MAC_GOOGLE_CLIENT_SECRET"] ?? ""
        )
    }

    /// The local development server while developing; the production API in the App Store build.
    private static var defaultAPIBaseURL: URL {
        #if DEBUG
        URL(string: "http://127.0.0.1:8080/")!
        #else
        URL(string: "https://api.mulberry.my/")!
        #endif
    }

    public var normalizedAPIBaseURL: URL {
        if apiBaseURL.absoluteString.hasSuffix("/") {
            return apiBaseURL
        }
        return apiBaseURL.appendingPathComponent("")
    }

}

enum DotenvLoader {
    static func load(
        fileManager: FileManager = .default,
        bundle: Bundle = .main,
        currentDirectoryPath: String = FileManager.default.currentDirectoryPath
    ) -> [String: String] {
        for url in candidateURLs(
            fileManager: fileManager,
            bundle: bundle,
            currentDirectoryPath: currentDirectoryPath
        ) {
            guard fileManager.fileExists(atPath: url.path),
                  let contents = try? String(contentsOf: url, encoding: .utf8)
            else {
                continue
            }
            return parse(contents)
        }
        return [:]
    }

    private static func candidateURLs(
        fileManager: FileManager,
        bundle: Bundle,
        currentDirectoryPath: String
    ) -> [URL] {
        var urls = [
            URL(fileURLWithPath: currentDirectoryPath).appendingPathComponent(".env")
        ]
        if let resourceURL = bundle.resourceURL {
            urls.append(resourceURL.appendingPathComponent(".env"))
        }
        return urls
    }

    private static func parse(_ contents: String) -> [String: String] {
        contents
            .split(whereSeparator: \.isNewline)
            .reduce(into: [String: String]()) { result, rawLine in
                let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
                guard line.isEmpty == false, line.hasPrefix("#") == false else {
                    return
                }

                let assignment = line.hasPrefix("export ")
                    ? String(line.dropFirst("export ".count))
                    : line
                guard let separatorIndex = assignment.firstIndex(of: "=") else {
                    return
                }

                let key = assignment[..<separatorIndex].trimmingCharacters(in: .whitespacesAndNewlines)
                guard key.isEmpty == false else {
                    return
                }
                let rawValue = assignment[assignment.index(after: separatorIndex)...]
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                result[key] = unquote(rawValue)
            }
    }

    private static func unquote(_ value: String) -> String {
        guard value.count >= 2 else {
            return value
        }
        if (value.hasPrefix("\"") && value.hasSuffix("\""))
            || (value.hasPrefix("'") && value.hasSuffix("'")) {
            return String(value.dropFirst().dropLast())
        }
        return value
    }
}

/// The Mac's one backend client. Sharing a single instance means concurrent requests share one
/// token refresh, as on iPhone and iPad.
public enum MacBackend {
    /// Per build, as the iPhone's development and release builds each keep their own session.
    public static let keychainService = "\(Bundle.main.bundleIdentifier ?? "com.subhajit.mulberry").backend-session"

    /// A saved session that can no longer be read is dropped, so the person signs in again.
    public static func makeClient(configuration: MacAppConfiguration) throws -> BackendClient {
        let quick = URLSessionConfiguration.default
        quick.waitsForConnectivity = false
        quick.timeoutIntervalForRequest = 3
        quick.timeoutIntervalForResource = 3
        let tokens = SessionKeychain(service: keychainService)
        let make = {
            try BackendClient(
                baseURL: configuration.normalizedAPIBaseURL,
                tokenStore: tokens,
                shortTransport: URLSessionTransport(session: URLSession(configuration: quick))
            )
        }
        do {
            return try make()
        } catch {
            try tokens.clear()
            return try make()
        }
    }
}
