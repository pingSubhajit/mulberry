import CanvasCore
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
    public let ownPresence: PresenceSummaryDTO?
    public let partnerPresence: PresenceSummaryDTO?

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
        invite: InviteDTO? = nil,
        ownPresence: PresenceSummaryDTO? = nil,
        partnerPresence: PresenceSummaryDTO? = nil
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
        self.ownPresence = ownPresence
        self.partnerPresence = partnerPresence
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

public struct PresenceSummaryDTO: Codable, Sendable {
    public let canSeeLatestDrawings: Bool
    public let surfaces: [PresenceSurfaceDTO]
}

public struct PresenceSurfaceDTO: Codable, Sendable {
    public let surfaceType: String
    public let deviceInstanceId: String
    public let configured: Bool
    public let enabled: Bool
    public let canSeeLatestDrawings: Bool
    public let hasEverBeenAbleToSee: Bool
    public let details: [String: JSONValue]
    public let updatedAt: String
}

public enum JSONValue: Codable, Sendable, Equatable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .null
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .string(value):
            try container.encode(value)
        case let .number(value):
            try container.encode(value)
        case let .bool(value):
            try container.encode(value)
        case let .object(value):
            try container.encode(value)
        case let .array(value):
            try container.encode(value)
        case .null:
            try container.encodeNil()
        }
    }
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

public struct AuthenticatedRequestAuthorizer {
    public let currentAccessToken: () async -> String?
    public let refreshAccessToken: () async throws -> String

    public init(
        currentAccessToken: @escaping () async -> String?,
        refreshAccessToken: @escaping () async throws -> String
    ) {
        self.currentAccessToken = currentAccessToken
        self.refreshAccessToken = refreshAccessToken
    }
}

@MainActor
public final class MulberryAPIClient {
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

@MainActor
public final class BootstrapAPIClient {
    private let baseURL: URL
    private let apiClient: MulberryAPIClient
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    public init(configuration: MacAppConfiguration, apiClient: MulberryAPIClient = MulberryAPIClient()) {
        self.baseURL = configuration.normalizedAPIBaseURL
        self.apiClient = apiClient
    }

    public func getBootstrap(authorizer: AuthenticatedRequestAuthorizer) async throws -> BootstrapDTO {
        var request = URLRequest(url: baseURL.appendingPathComponent("bootstrap"))
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await apiClient.authenticatedData(for: request, authorizer: authorizer)
        guard (200..<300).contains(response.statusCode) else {
            throw APIError.httpStatus(response.statusCode, serverMessage(from: data))
        }
        return try decoder.decode(BootstrapDTO.self, from: data)
    }

    public func updatePresenceSurface(
        surfaceType: String,
        request body: PresenceSurfaceUpdateRequest,
        authorizer: AuthenticatedRequestAuthorizer
    ) async throws -> BootstrapDTO {
        var request = URLRequest(url: baseURL.appendingPathComponent("me/presence-surfaces/\(surfaceType)"))
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try encoder.encode(body)
        let (data, response) = try await apiClient.authenticatedData(for: request, authorizer: authorizer)
        guard (200..<300).contains(response.statusCode) else {
            throw APIError.httpStatus(response.statusCode, serverMessage(from: data))
        }
        return try decoder.decode(BootstrapDTO.self, from: data)
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

public struct PresenceSurfaceUpdateRequest: Codable, Sendable {
    public let deviceInstanceId: String
    public let configured: Bool
    public let enabled: Bool
    public let canSeeLatestDrawings: Bool
    public let details: [String: JSONValue]

    public init(
        deviceInstanceId: String,
        configured: Bool,
        enabled: Bool,
        canSeeLatestDrawings: Bool,
        details: [String: JSONValue] = [:]
    ) {
        self.deviceInstanceId = deviceInstanceId
        self.configured = configured
        self.enabled = enabled
        self.canSeeLatestDrawings = canSeeLatestDrawings
        self.details = details
    }
}

public struct CanvasOpsResponseDTO: Codable, Sendable {
    public let operations: [CanvasOperation]
}

public struct CanvasOperationBatchRequestDTO: Encodable, Sendable {
    public let batchId: String
    public let operations: [ClientCanvasOperationRequestDTO]
    public let clientCreatedAt: String

    public init(batchId: String, operations: [CanvasOperation], clientCreatedAt: String) {
        self.batchId = batchId
        self.operations = operations.map(ClientCanvasOperationRequestDTO.init(operation:))
        self.clientCreatedAt = clientCreatedAt
    }
}

public struct ClientCanvasOperationRequestDTO: Encodable, Sendable {
    public let clientOperationId: String
    public let type: CanvasOperationType
    public let strokeId: String?
    public let payload: CanvasOperationPayload
    public let clientCreatedAt: String
    public let clientLocalDate: String?

    public init(operation: CanvasOperation) {
        self.clientOperationId = operation.clientOperationId
        self.type = operation.type
        self.strokeId = operation.strokeId
        self.payload = operation.payload
        self.clientCreatedAt = operation.clientCreatedAt
        self.clientLocalDate = operation.clientLocalDate ?? Self.localDate(from: operation.clientCreatedAt)
    }

    private enum CodingKeys: String, CodingKey {
        case clientOperationId
        case type
        case strokeId
        case payload
        case clientCreatedAt
        case clientLocalDate
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(clientOperationId, forKey: .clientOperationId)
        try container.encode(type, forKey: .type)
        try container.encodeIfPresent(strokeId, forKey: .strokeId)
        try container.encode(clientCreatedAt, forKey: .clientCreatedAt)
        try container.encodeIfPresent(clientLocalDate, forKey: .clientLocalDate)
        switch payload {
        case let .addStroke(payload):
            try container.encode(payload, forKey: .payload)
        case let .appendPoints(payload):
            try container.encode(payload, forKey: .payload)
        case .finishStroke, .deleteStroke, .clearCanvas, .deleteTextElement, .deleteStickerElement:
            try container.encode([String: String](), forKey: .payload)
        case let .addTextElement(element), let .updateTextElement(element):
            try container.encode(element, forKey: .payload)
        case let .addStickerElement(element), let .updateStickerElement(element):
            try container.encode(element, forKey: .payload)
        }
    }

    private static func localDate(from isoString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let date = formatter.date(from: isoString) ?? ISO8601DateFormatter().date(from: isoString) ?? Date()
        return DateFormatter.localDateFormatter.string(from: date)
    }
}

public struct CanvasSnapshotResponseDTO: Decodable, Sendable {
    public let pairSessionId: String
    public let revision: Int64
    public let snapshotRevision: Int64
    public let latestRevision: Int64
    public let snapshot: CanvasSnapshotPayloadDTO
    public let updatedAt: String?

    public var state: CanvasState {
        CanvasState(
            committedStrokes: snapshot.strokes.map(\.stroke),
            committedElements: snapshot.unifiedElements,
            revision: snapshotRevision
        )
    }
}

public struct CanvasSnapshotPayloadDTO: Decodable, Sendable {
    public let strokes: [CanvasSnapshotStrokeDTO]
    public let textElements: [CanvasSnapshotTextElementDTO]
    public let elements: [CanvasSnapshotElementDTO]?

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        strokes = try container.decodeIfPresent([CanvasSnapshotStrokeDTO].self, forKey: .strokes) ?? []
        textElements = try container.decodeIfPresent([CanvasSnapshotTextElementDTO].self, forKey: .textElements) ?? []
        elements = try container.decodeIfPresent([CanvasSnapshotElementDTO].self, forKey: .elements)
    }

    private enum CodingKeys: String, CodingKey {
        case strokes
        case textElements
        case elements
    }

    public var unifiedElements: [CanvasElement] {
        if let elements, elements.isEmpty == false {
            return elements.compactMap(\.element)
        }
        return textElements.map { .text($0.textElement) }
    }
}

public struct CanvasSnapshotStrokeDTO: Decodable, Sendable {
    public let id: String
    public let colorArgb: UInt32
    public let width: Float
    public let createdAt: Int64
    public let points: [CanvasPoint]

    public var stroke: CanvasStroke {
        CanvasStroke(id: id, colorArgb: colorArgb, width: width, points: points, createdAt: createdAt)
    }
}

public struct CanvasSnapshotTextElementDTO: Decodable, Sendable {
    public let id: String
    public let text: String
    public let createdAt: Int64
    public let center: CanvasPoint
    public let rotationRad: Float
    public let scale: Float
    public let boxWidth: Float
    public let colorArgb: UInt32
    public let backgroundPillEnabled: Bool
    public let font: CanvasTextFont
    public let alignment: CanvasTextAlign

    public var textElement: CanvasTextElement {
        CanvasTextElement(
            id: id,
            text: text,
            createdAt: createdAt,
            center: center,
            rotationRad: rotationRad,
            scale: scale,
            boxWidth: boxWidth,
            colorArgb: colorArgb,
            backgroundPillEnabled: backgroundPillEnabled,
            font: font,
            alignment: alignment
        )
    }
}

public struct CanvasSnapshotElementDTO: Decodable, Sendable {
    public let kind: String
    public let id: String
    public let createdAt: Int64
    public let center: CanvasPoint
    public let rotationRad: Float
    public let scale: Float
    public let text: String?
    public let boxWidth: Float?
    public let colorArgb: UInt32?
    public let backgroundPillEnabled: Bool?
    public let font: CanvasTextFont?
    public let alignment: CanvasTextAlign?
    public let packKey: String?
    public let packVersion: Int?
    public let stickerId: String?

    public var element: CanvasElement? {
        switch kind {
        case "TEXT":
            return .text(CanvasTextElement(
                id: id,
                text: text ?? "",
                createdAt: createdAt,
                center: center,
                rotationRad: rotationRad,
                scale: scale,
                boxWidth: boxWidth ?? 0.7,
                colorArgb: colorArgb ?? 0xFF111111,
                backgroundPillEnabled: backgroundPillEnabled ?? false,
                font: font ?? .poppins,
                alignment: alignment ?? .center
            ))
        case "STICKER":
            guard let packKey, let stickerId, let packVersion, packVersion > 0 else {
                return nil
            }
            return .sticker(CanvasStickerElement(
                id: id,
                createdAt: createdAt,
                center: center,
                rotationRad: rotationRad,
                scale: scale,
                packKey: packKey,
                packVersion: packVersion,
                stickerId: stickerId
            ))
        default:
            return nil
        }
    }
}

@MainActor
public final class CanvasAPIClient {
    private let baseURL: URL
    private let apiClient: MulberryAPIClient
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    public init(configuration: MacAppConfiguration, apiClient: MulberryAPIClient = MulberryAPIClient()) {
        self.baseURL = configuration.normalizedAPIBaseURL
        self.apiClient = apiClient
    }

    public func getCanvasOperations(
        afterRevision: Int64,
        authorizer: AuthenticatedRequestAuthorizer
    ) async throws -> [CanvasOperation] {
        var components = URLComponents(url: baseURL.appendingPathComponent("canvas/ops"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "afterRevision", value: String(afterRevision))]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await apiClient.authenticatedData(for: request, authorizer: authorizer)
        guard (200..<300).contains(response.statusCode) else {
            throw APIError.httpStatus(response.statusCode, serverMessage(from: data))
        }
        return try decoder.decode(CanvasOpsResponseDTO.self, from: data).operations
    }

    public func postCanvasOperationBatch(
        batchID: String,
        operations: [CanvasOperation],
        authorizer: AuthenticatedRequestAuthorizer
    ) async throws -> [CanvasOperation] {
        var request = URLRequest(url: baseURL.appendingPathComponent("canvas/ops/batch"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try encoder.encode(CanvasOperationBatchRequestDTO(
            batchId: batchID,
            operations: operations,
            clientCreatedAt: DateFormatter.iso8601WithMilliseconds.string(from: Date())
        ))
        let (data, response) = try await apiClient.authenticatedData(for: request, authorizer: authorizer)
        guard (200..<300).contains(response.statusCode) else {
            throw APIError.httpStatus(response.statusCode, serverMessage(from: data))
        }
        return try decoder.decode(CanvasOpsResponseDTO.self, from: data).operations
    }

    public func getCanvasSnapshot(authorizer: AuthenticatedRequestAuthorizer) async throws -> CanvasSnapshotResponseDTO {
        var request = URLRequest(url: baseURL.appendingPathComponent("canvas/snapshot"))
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await apiClient.authenticatedData(for: request, authorizer: authorizer)
        guard (200..<300).contains(response.statusCode) else {
            throw APIError.httpStatus(response.statusCode, serverMessage(from: data))
        }
        return try decoder.decode(CanvasSnapshotResponseDTO.self, from: data)
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

private extension DateFormatter {
    static let iso8601WithMilliseconds: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
        return formatter
    }()

    static let localDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .current
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()
}

private struct GoogleAuthRequest: Codable {
    let idToken: String
}

private struct RefreshRequest: Codable {
    let refreshToken: String
}
