import ActivityKit
import Foundation

/// The partner's reactions on the Lock Screen and in the Dynamic Island. The app and the widget
/// extension share it: the app starts and updates the activity, the extension draws it. The
/// server will send the same content-state JSON when it starts the activity by push.
struct ReactionActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// How many of each reaction arrived, keyed by the server's names, like "HEART".
        var counts: [String: Int]
        /// The reaction that arrived last, which the compact island shows.
        var latest: String
        /// The reaction sent back from the island since these arrived, if any.
        var repliedWith: String?

        var latestType: ReactionType { ReactionType(rawValue: latest) ?? .heart }
        var total: Int { counts.values.reduce(0, +) }
        /// Each kind that arrived, in Android's order, with its count.
        var kinds: [Kind] {
            ReactionType.allCases.compactMap { type in counts[type.rawValue].flatMap { $0 > 0 ? Kind(type: type, count: $0) : nil } }
        }
    }

    struct Kind: Hashable, Identifiable {
        let type: ReactionType
        let count: Int
        var id: ReactionType { type }
    }

    let partnerName: String
    /// A small JPEG of the partner's photo. Live Activities cannot load from the network, and the
    /// whole activity must stay under 4 KB, so the app sends a thumbnail of about 2 KB.
    var partnerPhoto: Data?
}
