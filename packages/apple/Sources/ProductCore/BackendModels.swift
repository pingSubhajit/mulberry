import Foundation

/// The JSON payload is retained even for canvas elements not editable in iOS v1.
public enum JSONValue: Codable, Equatable, Sendable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }

    public static func encoded<T: Encodable>(_ value: T) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(value))
    }

    public func decode<T: Decodable>(_ type: T.Type) throws -> T {
        try JSONDecoder().decode(type, from: JSONEncoder().encode(self))
    }

    public subscript(key: String) -> JSONValue? {
        guard case .object(let values) = self else { return nil }
        return values[key]
    }

    public var string: String? {
        guard case .string(let value) = self else { return nil }
        return value
    }
}

public struct BootstrapState: Codable, Equatable, Sendable {
    public let authStatus: String
    public let onboardingCompleted: Bool
    public let hasWallpaperConfigured: Bool
    public let canvasStrokeRenderMode: String
    public let userId: String?
    public let userEmail: String?
    public let userPhotoUrl: String?
    public let userDisplayName: String?
    public let partnerPhotoUrl: String?
    public let partnerDisplayName: String?
    public let partnerWallpaperStatus: PartnerWallpaperStatus?
    public let anniversaryDate: String?
    public let partnerProfileNextUpdateAt: String?
    public let pairedAt: String?
    public let currentStreakDays: Int
    /// Whether the account has ever drawn, on any device. Missing from older servers.
    public let hasDrawn: Bool?
    public let pairingStatus: String
    public let pairSessionId: String?
    public let invite: PairInvite?
    public let ownPresence: PresenceSummary
    public let partnerPresence: PresenceSummary

    public var isPaired: Bool { pairingStatus == "PAIRED" && pairSessionId != nil }
    public var partnerProfileUnlockDate: Date? { partnerProfileNextUpdateAt.flatMap(ServerDate.parse) }
    public func canEditPartner(at date: Date = Date()) -> Bool {
        partnerProfileUnlockDate.map { date >= $0 } ?? true
    }
}

public struct PartnerWallpaperStatus: Codable, Equatable, Sendable {
    public let updatedAt: String
    public let wallpaperSyncEnabled: Bool
    public let wallpaperSelectedOnHome: Bool
    public let wallpaperSelectedOnLock: Bool
    public let canSeeLatestDrawings: Bool
    public let hasEverBeenAbleToSee: Bool
}

public struct PresenceSummary: Codable, Equatable, Sendable {
    public let canSeeLatestDrawings: Bool
    public let surfaces: [PresenceSurface]
}

/// The backend's names for the places a device can show the shared canvas.
public enum PresenceSurfaceType: String, Sendable {
    case iosShortcut = "IOS_SHORTCUT", macOverlay = "MACOS_OVERLAY"
}

public struct PresenceSurface: Codable, Equatable, Sendable {
    public let surfaceType: String
    public let deviceInstanceId: String
    public let configured: Bool
    public let enabled: Bool
    public let canSeeLatestDrawings: Bool
    public let hasEverBeenAbleToSee: Bool
    public let details: [String: JSONValue]
    public let updatedAt: String
}

public struct PairInvite: Codable, Equatable, Sendable {
    public let inviteId: String
    public let code: String
    public let inviterDisplayName: String
    public let recipientDisplayName: String
    public let status: String
}

public struct SessionTokens: Codable, Equatable, Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let userId: String

    public init(accessToken: String, refreshToken: String, userId: String) {
        self.accessToken = accessToken
        self.refreshToken = refreshToken
        self.userId = userId
    }
}

public struct AuthenticationResponse: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String
    public let userId: String
    public let bootstrapState: BootstrapState
    public var tokens: SessionTokens { .init(accessToken: accessToken, refreshToken: refreshToken, userId: userId) }
}

public struct CreatedInvite: Codable, Sendable {
    public let inviteId: String
    public let code: String
    public let expiresAt: String
}

public struct RedeemedInvite: Codable, Sendable {
    public let inviteId: String
    public let inviterDisplayName: String
    public let recipientDisplayName: String
    public let code: String
    public let status: String
    public let bootstrapState: BootstrapState
}

public struct AcceptedInvite: Codable, Sendable {
    public let pairSessionId: String
    public let bootstrapState: BootstrapState
}

public struct ProfileDraft: Codable, Equatable, Sendable {
    public var displayName: String
    public var partnerDisplayName: String
    public var anniversaryDate: String

    public init(displayName: String = "", partnerDisplayName: String = "", anniversaryDate: String = "") {
        self.displayName = displayName
        self.partnerDisplayName = partnerDisplayName
        self.anniversaryDate = anniversaryDate
    }
}

public struct WallpaperCatalogPage: Codable, Sendable {
    public let items: [CatalogWallpaper]
    public let nextCursor: String?
}

public struct CatalogWallpaper: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public let title: String
    public let description: String
    public let thumbnailUrl: URL
    public let previewUrl: URL
    public let fullImageUrl: URL
    public let width: Int
    public let height: Int
    public let dominantColor: String
}

public enum ServerDate {
    public static func parse(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }

    public static func string(_ date: Date = Date()) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    public static func localDay(_ date: Date = Date(), timeZone: TimeZone = .current) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = timeZone
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: date)
    }
}
