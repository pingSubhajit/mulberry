import Foundation
import ProductCore

/// Sends reactions to the partner, and plays the partner's over the canvas, as the iPhone does.
/// Only one of the recipient's devices plays each batch: it leases the batch first, and confirms
/// it once the playback has been seen.
///
/// While no canvas is on screen the Mac only looks: reactions waiting to be seen show in the
/// notch or under the menu bar icon, as the iPhone shows them in the Dynamic Island, and play
/// when a canvas next opens.
@MainActor
final class ReactionsModel: ObservableObject {
    /// Where a canvas is on screen to play reactions over.
    enum Surface { case window, quickDraw }

    struct Arrival: Identifiable, Equatable {
        let id = UUID()
        let batch: PendingReactionBatch
        /// Played from what the notch showed rather than claimed from the server, so nothing to confirm.
        var local = false
    }

    struct Sent: Identifiable, Equatable {
        let id = UUID()
        let type: ReactionType
    }

    /// The partner's reactions not seen on a canvas yet: what the notch or the menu bar shows.
    struct Waiting: Equatable {
        var counts: [ReactionType: Int]
        /// The reaction that arrived last.
        var latest: ReactionType
        /// The reaction sent back from the notch since these arrived, if any.
        var repliedWith: ReactionType?

        var total: Int { counts.values.reduce(0, +) }
        /// Each kind that arrived, in Android's order, with its count.
        var kinds: [(type: ReactionType, count: Int)] {
            ReactionType.allCases.compactMap { type in counts[type].flatMap { $0 > 0 ? (type, $0) : nil } }
        }
        var batch: PendingReactionBatch { PendingReactionBatch(generation: 0, counts: counts) }
    }

    /// A batch this Mac has claimed, for the canvas to play.
    @Published private(set) var arrival: Arrival?
    /// A reaction this Mac just sent, for the canvas to show leaving.
    @Published private(set) var sent: Sent?
    @Published private(set) var waiting: Waiting?
    /// Bumped each time more reactions are noticed, so the notch can announce them.
    @Published private(set) var announcement = 0
    @Published private(set) var lastUsed: ReactionType = .heart

    /// Set while a canvas is on screen; reactions wait for it, as on the iPhone and Android.
    @Published var surface: Surface? {
        didSet {
            guard surface != oldValue else { return }
            if surface == nil { interrupt() } else { Task { await check() } }
        }
    }

    private let api: () -> BackendClient
    private let deviceID: String
    private var user: String?
    private var pair: String?
    private var lastSent = Date.distantPast
    private var checking = false
    /// The last batch the server reported, to tell which reaction is new.
    private var lastSeen: PendingReactionBatch?

    init(api: @escaping () -> BackendClient, deviceID: String) {
        self.api = api
        self.deviceID = deviceID
    }

    func bind(userId: String?, pairSessionId: String?) {
        guard userId != user || pairSessionId != pair else { return }
        user = userId
        pair = pairSessionId
        arrival = nil
        sent = nil
        waiting = nil
        lastSeen = nil
        lastUsed = userId.flatMap { UserDefaults.standard.string(forKey: Self.lastUsedKey($0)) }.flatMap(ReactionType.init) ?? .heart
    }

    var canSend: Bool { user != nil && pair != nil }

    /// Android's 800 ms gap stops a double click from sending twice; the server limits bursts too.
    @discardableResult
    func send(_ type: ReactionType) async -> Bool {
        guard let user, pair != nil, Date().timeIntervalSince(lastSent) >= 0.8 else { return false }
        lastSent = .now
        lastUsed = type
        UserDefaults.standard.set(type.rawValue, forKey: Self.lastUsedKey(user))
        do {
            try await api().sendReaction(type)
            guard self.user == user else { return false }
            // Shown leaving only over a canvas on screen, never queued for later.
            if surface != nil { sent = Sent(type: type) }
            return true
        } catch {
            return false
        }
    }

    /// The notch's reply: sends the latest reaction back, then shows it as sent until more arrive.
    func reply() async {
        guard let type = waiting?.latest, waiting?.repliedWith == nil else { return }
        if await send(type) { waiting?.repliedWith = type }
    }

    /// With a canvas on screen, claims the partner's waiting reactions for this Mac to play.
    /// Without one, only looks, so another of the recipient's devices can still play them.
    func check() async {
        guard !checking, arrival == nil, let user, let pair else { return }
        checking = true
        defer { checking = false }
        let batch = try? await api().pendingReactions(quick: true)
        guard self.user == user, self.pair == pair else { return }
        if let batch, batch.total > 0 { notice(batch) }

        guard surface != nil else { return }
        if let batch, batch.total > 0,
           let status = try? await api().leaseReactionPlayback(generation: batch.generation, deviceId: deviceID),
           status == .claimed {
            guard surface != nil, self.user == user, self.pair == pair, arrival == nil else { return }
            arrival = Arrival(batch: batch)
        } else if let waiting, surface != nil, arrival == nil {
            // Another device already played the batch, but this Mac still shows reactions in the
            // notch: play those, then let them go.
            arrival = Arrival(batch: waiting.batch, local: true)
        }
    }

    /// Playback finished on screen: the batch is played on every device.
    func played(_ played: Arrival) async {
        guard arrival == played else { return }
        arrival = nil
        waiting = nil
        if !played.local {
            try? await api().confirmReactionPlayback(generation: played.batch.generation, deviceId: deviceID)
        }
        // More may have arrived while this batch played.
        await check()
    }

    func finishedSending(_ done: Sent) {
        if sent == done { sent = nil }
    }

    /// Reactions the server holds that this Mac has not shown yet.
    private func notice(_ batch: PendingReactionBatch) {
        guard batch != lastSeen else { return }
        let previous = lastSeen?.generation == batch.generation ? lastSeen : nil
        lastSeen = batch
        // The newest is the kind whose count grew; a new batch's is its most frequent.
        let grown = ReactionType.allCases.filter { batch.count($0) > (previous?.count($0) ?? 0) }
        let latest = grown.max { batch.count($0) < batch.count($1) } ?? waiting?.latest ?? .heart
        let counts = Dictionary(uniqueKeysWithValues: ReactionType.allCases.compactMap { type in
            batch.count(type) > 0 ? (type, batch.count(type)) : nil
        })
        waiting = Waiting(counts: counts, latest: latest, repliedWith: nil)
        announcement += 1
    }

    /// The canvas went away mid-playback; the lease lapses, and this Mac claims it again later.
    private func interrupt() {
        arrival = nil
    }

    private static func lastUsedKey(_ user: String) -> String { "reactionLastUsed.\(user)" }

    #if DEBUG
    /// For trying the notch and the menu bar card without a partner, as the iPhone's
    /// `liveActivity=` launch argument: launch with `MULBERRY_REACTIONS=HEART:2,KISS:1`. Stages
    /// separated by `;` arrive six seconds apart and add up, as more reactions would:
    /// `MULBERRY_REACTIONS=HEART:1;SPARKLE:1;HUG:2`. Opening the canvas plays what showed.
    func runDebugArrivals() async {
        guard let value = ProcessInfo.processInfo.environment["MULBERRY_REACTIONS"], user != nil, pair != nil else { return }
        var counts: [ReactionType: Int] = [:]
        for (index, stage) in value.split(separator: ";").enumerated() {
            try? await Task.sleep(for: .seconds(index == 0 ? 3 : 6))
            for part in stage.split(separator: ",") {
                let pair = part.split(separator: ":")
                guard let type = ReactionType(rawValue: pair[0].uppercased()) else { continue }
                counts[type, default: 0] += pair.count > 1 ? Int(pair[1]) ?? 1 : 1
            }
            notice(PendingReactionBatch(generation: -1, counts: counts))
        }
    }
    #endif
}
