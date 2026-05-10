import Foundation

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
        let apiBaseURL = URL(string: environment["MULBERRY_MAC_API_BASE_URL"] ?? "http://127.0.0.1:8080/")
            ?? URL(string: "http://127.0.0.1:8080/")!
        return MacAppConfiguration(
            apiBaseURL: apiBaseURL,
            googleClientID: environment["MULBERRY_MAC_GOOGLE_CLIENT_ID"] ?? "",
            googleClientSecret: environment["MULBERRY_MAC_GOOGLE_CLIENT_SECRET"] ?? ""
        )
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

public struct BootstrapDTO: Codable, Sendable {
    public let authStatus: String?
    public let onboardingCompleted: Bool?
    public let hasWallpaperConfigured: Bool?
    public let canvasStrokeRenderMode: String?
    public let userId: String?
    public let userEmail: String?
    public let userPhotoUrl: String?
    public let userDisplayName: String?
    public let partnerPhotoUrl: String?
    public let partnerDisplayName: String?
    public let partnerWallpaperStatus: PartnerWallpaperStatusDTO?
    public let anniversaryDate: String?
    public let partnerProfileNextUpdateAt: String?
    public let pairedAt: String?
    public let currentStreakDays: Int?
    public let pairingStatus: String?
    public let pairSessionId: String?
    public let invite: InviteDTO?

    public init(
        authStatus: String? = nil,
        onboardingCompleted: Bool? = nil,
        hasWallpaperConfigured: Bool? = nil,
        canvasStrokeRenderMode: String? = nil,
        userId: String? = nil,
        userEmail: String? = nil,
        userPhotoUrl: String? = nil,
        userDisplayName: String? = nil,
        partnerPhotoUrl: String? = nil,
        partnerDisplayName: String? = nil,
        partnerWallpaperStatus: PartnerWallpaperStatusDTO? = nil,
        anniversaryDate: String? = nil,
        partnerProfileNextUpdateAt: String? = nil,
        pairedAt: String? = nil,
        currentStreakDays: Int? = nil,
        pairingStatus: String? = nil,
        pairSessionId: String? = nil,
        invite: InviteDTO? = nil
    ) {
        self.authStatus = authStatus
        self.onboardingCompleted = onboardingCompleted
        self.hasWallpaperConfigured = hasWallpaperConfigured
        self.canvasStrokeRenderMode = canvasStrokeRenderMode
        self.userId = userId
        self.userEmail = userEmail
        self.userPhotoUrl = userPhotoUrl
        self.userDisplayName = userDisplayName
        self.partnerPhotoUrl = partnerPhotoUrl
        self.partnerDisplayName = partnerDisplayName
        self.partnerWallpaperStatus = partnerWallpaperStatus
        self.anniversaryDate = anniversaryDate
        self.partnerProfileNextUpdateAt = partnerProfileNextUpdateAt
        self.pairedAt = pairedAt
        self.currentStreakDays = currentStreakDays
        self.pairingStatus = pairingStatus
        self.pairSessionId = pairSessionId
        self.invite = invite
    }
}

public struct PartnerWallpaperStatusDTO: Codable, Sendable {
    public let wallpaperSelectedOnHome: Bool?
    public let wallpaperSelectedOnLock: Bool?
    public let wallpaperSyncEnabled: Bool?
    public let canSeeLatestDrawings: Bool?
}

public struct InviteDTO: Codable, Sendable {
    public let id: String?
    public let code: String?
    public let expiresAt: String?
    public let inviterDisplayName: String?
}

public struct AuthResponseDTO: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let userId: String
    public let bootstrapState: BootstrapDTO
}

public enum APIError: Error, LocalizedError, Sendable {
    case invalidResponse
    case httpStatus(Int, String)
    case missingToken
    case networkUnavailable(URL, String)

    public var errorDescription: String? {
        switch self {
        case .invalidResponse:
            "The server returned an invalid response."
        case let .httpStatus(status, message):
            message.isEmpty ? "The server returned HTTP \(status)." : message
        case .missingToken:
            "No signed-in session is available."
        case let .networkUnavailable(baseURL, message):
            "Could not reach Mulberry backend at \(baseURL.absoluteString). Start the backend and try again. \(message)"
        }
    }
}

public final class AuthAPIClient: Sendable {
    private let baseURL: URL
    private let session: URLSession
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    public init(configuration: MacAppConfiguration, session: URLSession = .shared) {
        self.baseURL = configuration.normalizedAPIBaseURL
        self.session = session
    }

    public func authenticateWithGoogle(idToken: String) async throws -> AuthResponseDTO {
        try await sendJSON(
            path: "auth/google",
            method: "POST",
            body: GoogleAuthRequest(idToken: idToken)
        )
    }

    public func refresh(refreshToken: String) async throws -> AuthResponseDTO {
        try await sendJSON(
            path: "auth/refresh",
            method: "POST",
            body: RefreshRequest(refreshToken: refreshToken)
        )
    }

    public func logout(accessToken: String) async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent("auth/logout"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let (_, response) = try await session.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw APIError.httpStatus(httpResponse.statusCode, "")
        }
    }

    private func sendJSON<Response: Decodable, Body: Encodable>(
        path: String,
        method: String,
        body: Body
    ) async throws -> Response {
        var request = URLRequest(url: baseURL.appendingPathComponent(path))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try encoder.encode(body)
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.networkUnavailable(baseURL, error.localizedDescription)
        }
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw APIError.httpStatus(httpResponse.statusCode, serverMessage(from: data))
        }
        return try decoder.decode(Response.self, from: data)
    }

    private func serverMessage(from data: Data) -> String {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let message = object["message"] as? String
                ?? object["error_description"] as? String
                ?? object["error"] as? String
        else {
            return String(data: data, encoding: .utf8) ?? ""
        }
        return message
    }
}

public struct AuthenticatedRequestAuthorizer: Sendable {
    public let currentAccessToken: @Sendable () async -> String?
    public let refreshAccessToken: @Sendable () async throws -> String

    public init(
        currentAccessToken: @escaping @Sendable () async -> String?,
        refreshAccessToken: @escaping @Sendable () async throws -> String
    ) {
        self.currentAccessToken = currentAccessToken
        self.refreshAccessToken = refreshAccessToken
    }
}

public final class MulberryAPIClient: Sendable {
    private let session: URLSession

    public init(session: URLSession = .shared) {
        self.session = session
    }

    public func authenticatedData(
        for request: URLRequest,
        authorizer: AuthenticatedRequestAuthorizer
    ) async throws -> (Data, HTTPURLResponse) {
        guard let accessToken = await authorizer.currentAccessToken() else {
            throw APIError.missingToken
        }

        let firstResponse = try await send(request, accessToken: accessToken)
        if firstResponse.1.statusCode != 401 {
            return firstResponse
        }

        let refreshedToken = try await authorizer.refreshAccessToken()
        return try await send(request, accessToken: refreshedToken)
    }

    private func send(_ request: URLRequest, accessToken: String) async throws -> (Data, HTTPURLResponse) {
        var authorizedRequest = request
        authorizedRequest.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: authorizedRequest)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIError.invalidResponse
        }
        return (data, httpResponse)
    }
}

private struct GoogleAuthRequest: Codable {
    let idToken: String
}

private struct RefreshRequest: Codable {
    let refreshToken: String
}
