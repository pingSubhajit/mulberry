import Foundation

/// The six reactions Android sends, in the order its rail shows them.
public enum ReactionType: String, CaseIterable, Codable, Sendable, Identifiable {
    case heart = "HEART", hug = "HUG", kiss = "KISS", smile = "SMILE", laugh = "LAUGH", sparkle = "SPARKLE"

    public var id: String { rawValue }

    public var emoji: String {
        switch self {
        case .heart: "❤️"
        case .hug: "🤗"
        case .kiss: "💋"
        case .smile: "😊"
        case .laugh: "😂"
        case .sparkle: "✨"
        }
    }

    /// Android's shortcut labels, used for the quick action and accessibility.
    public var sendLabel: String {
        switch self {
        case .heart: "Send love"
        case .hug: "Send a hug"
        case .kiss: "Send a kiss"
        case .smile: "Send a smile"
        case .laugh: "Send laughs"
        case .sparkle: "Send sparkles"
        }
    }

    /// The plural Android's playback uses when one kind arrives six or more times.
    public var spamNoun: String {
        switch self {
        case .heart: "love"
        case .hug: "hugs"
        case .kiss: "kisses"
        case .smile: "smiles"
        case .laugh: "laughs"
        case .sparkle: "sparkles"
        }
    }
}

/// Reactions from the partner that no device has played yet. The server coalesces them per type.
public struct PendingReactionBatch: Codable, Equatable, Sendable {
    public let generation: Int
    public let heartCount: Int
    public let hugCount: Int
    public let kissCount: Int
    public let smileCount: Int
    public let laughCount: Int
    public let sparkleCount: Int

    public init(generation: Int, counts: [ReactionType: Int]) {
        self.generation = generation
        heartCount = counts[.heart] ?? 0; hugCount = counts[.hug] ?? 0; kissCount = counts[.kiss] ?? 0
        smileCount = counts[.smile] ?? 0; laughCount = counts[.laugh] ?? 0; sparkleCount = counts[.sparkle] ?? 0
    }

    public func count(_ type: ReactionType) -> Int {
        switch type {
        case .heart: heartCount
        case .hug: hugCount
        case .kiss: kissCount
        case .smile: smileCount
        case .laugh: laughCount
        case .sparkle: sparkleCount
        }
    }

    public var total: Int { ReactionType.allCases.reduce(0) { $0 + count($1) } }
}

/// Whether this device may play a batch. Only one of the recipient's devices plays each one.
public enum ReactionLeaseStatus: String, Codable, Sendable {
    case claimed = "CLAIMED", noPending = "NO_PENDING", leasedByOther = "LEASED_BY_OTHER", staleGeneration = "STALE_GENERATION"
}

/// Android's wallpaper playback, laid out on a unit square: one big reaction with up to three
/// smaller copies when every reaction is the same kind, otherwise one of each kind.
public struct ReactionArrangement: Equatable, Sendable {
    public struct Glyph: Equatable, Sendable {
        public let type: ReactionType
        public let x: Double
        public let y: Double
        public let scale: Double
    }
    public let glyphs: [Glyph]
    /// Shown when one kind arrives six or more times.
    public let caption: String?

    public init(_ batch: PendingReactionBatch) {
        let kinds = ReactionType.allCases.filter { batch.count($0) > 0 }
        if kinds.count <= 1 {
            let type = kinds.first ?? .heart
            let anchors = [(0.38, 0.46), (0.62, 0.46), (0.42, 0.62), (0.58, 0.62)]
            let small = max(0, min(4, batch.total) - 1)
            glyphs = [Glyph(type: type, x: 0.5, y: 0.52, scale: 1.4)]
                + anchors.prefix(small).map { Glyph(type: type, x: $0.0, y: $0.1, scale: 0.92) }
            caption = batch.total >= 6 ? "You have been spammed with \(type.spamNoun)." : nil
        } else {
            let anchors = [(0.5, 0.42), (0.35, 0.5), (0.65, 0.5), (0.42, 0.62), (0.58, 0.62), (0.5, 0.7)]
            glyphs = zip(kinds.prefix(6), anchors).map { Glyph(type: $0, x: $1.0, y: $1.1, scale: 1.15) }
            caption = nil
        }
    }
}
