import SwiftUI
import UIKit

extension Notification.Name {
    /// A reaction push reached this device while Mulberry was running.
    static let mulberryReactionPush = Notification.Name("mulberryReactionPush")
}

/// Sends reactions to the partner, and plays the partner's over the canvas. Like Android's
/// wallpaper, only one of the recipient's devices plays each batch: it leases the batch first,
/// and confirms it once the playback has been seen.
@MainActor
final class ReactionsModel: ObservableObject {
    struct Arrival: Identifiable, Equatable {
        let id = UUID()
        let batch: PendingReactionBatch
        /// Played from the Live Activity rather than claimed from the server, so nothing to confirm.
        var local = false
    }

    struct Sent: Identifiable, Equatable {
        let id = UUID()
        let type: ReactionType
    }

    /// A batch this device has claimed, for the canvas to play.
    @Published private(set) var arrival: Arrival?
    /// A reaction this device just sent, for the canvas to show leaving.
    @Published private(set) var sent: Sent?
    @Published private(set) var lastUsed: ReactionType = .heart
    /// Set while the canvas is on screen; reactions wait for it, as Android's wait for the home screen.
    var watching = false {
        didSet { if watching && !oldValue { Task { await check() } } else if !watching { interrupt() } }
    }
    private var user: String?
    private var pair: String?
    private var lastSent = Date.distantPast
    private var checking = false
    private var pushObserver: NSObjectProtocol?

    init() {
        pushObserver = NotificationCenter.default.addObserver(forName: .mulberryReactionPush, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.check() }
        }
    }

    func bind(userId: String?, pairSessionId: String?) {
        guard userId != user || pairSessionId != pair else { return }
        user = userId; pair = pairSessionId
        arrival = nil; sent = nil
        lastUsed = userId.flatMap { UserDefaults.standard.string(forKey: Self.lastUsedKey($0)) }.flatMap(ReactionType.init) ?? .heart
    }

    /// Android's 800 ms gap stops a double tap from sending twice; the server limits bursts too.
    func send(_ type: ReactionType) async -> Bool {
        guard let user, pair != nil, Date().timeIntervalSince(lastSent) >= 0.8, let api = try? AppRuntime.api() else { return false }
        lastSent = .now
        lastUsed = type
        UserDefaults.standard.set(type.rawValue, forKey: Self.lastUsedKey(user))
        do {
            try await api.sendReaction(type)
            guard self.user == user else { return false }
            sent = Sent(type: type)
            return true
        } catch { return false }
    }

    /// Claims the partner's waiting reactions for this device, if the canvas is showing.
    func check() async {
        guard watching, arrival == nil, !checking, let user, let pair, let api = try? AppRuntime.api() else { return }
        checking = true
        defer { checking = false }
        if let batch = try? await api.pendingReactions(quick: true), batch.total > 0,
           let status = try? await api.leaseReactionPlayback(generation: batch.generation, deviceId: PushRegistration.deviceId),
           status == .claimed {
            guard watching, self.user == user, self.pair == pair, arrival == nil else { return }
            arrival = Arrival(batch: batch)
        } else if let shown = ReactionActivities.unplayed, watching, self.user == user, arrival == nil {
            // Another device already played the batch, or the server had none, but this one still
            // shows reactions in the Dynamic Island: play those, then let the activity go.
            arrival = Arrival(batch: shown, local: true)
        }
    }

    /// Playback finished on screen: the batch is played on every device.
    func played(_ played: Arrival) async {
        guard arrival == played else { return }
        arrival = nil
        // Seen on the canvas, so the Dynamic Island and the Lock Screen can let them go.
        await ReactionActivities.endAll()
        if !played.local {
            try? await AppRuntime.api().confirmReactionPlayback(generation: played.batch.generation, deviceId: PushRegistration.deviceId)
        }
        // More may have arrived while this batch played.
        await check()
    }

    /// The canvas went away mid-playback; the lease lapses, and this device claims it again later.
    private func interrupt() { arrival = nil }

    func finishedSending(_ done: Sent) { if sent == done { sent = nil } }

    private static func lastUsedKey(_ user: String) -> String { "reactionLastUsed.\(user)" }
}
