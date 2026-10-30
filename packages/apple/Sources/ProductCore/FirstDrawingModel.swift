import Combine
import Foundation

/// The account's first drawing, on any device, celebrated once with the streak's confetti. The
/// two celebrations never add up: when the first drawing also grows the pair's streak, the streak's
/// level-up celebrates it and this stays quiet.
///
/// The server says whether the account has ever drawn; a drawing made here is also remembered on
/// this device, for the moments before the server's answer catches up.
@MainActor
public final class FirstDrawingModel: ObservableObject {
    @Published public private(set) var confettiToken: UUID?
    private let defaults: UserDefaults
    private let attempts: [Duration]
    private var userId: String?
    private var serverHasDrawn = true
    private var celebrating: Task<Void, Never>?

    /// - Parameter attempts: how long to wait before each look at the streak, while the drawing
    ///   reaches the server.
    public init(defaults: UserDefaults = .standard, attempts: [Duration] = [.seconds(1.5), .seconds(3), .seconds(5), .seconds(10)]) {
        self.defaults = defaults
        self.attempts = attempts
    }

    /// The signed-in account, and whether the server knows it has drawn. A server too old to say
    /// counts as drawn, so nobody is celebrated by mistake.
    public func bind(userId: String?, hasDrawn: Bool?) {
        if userId != self.userId {
            celebrating?.cancel()
            celebrating = nil
            confettiToken = nil
        }
        self.userId = userId
        serverHasDrawn = hasDrawn ?? true
    }

    /// Whether the account's next drawing is its first.
    public var awaitsFirstDrawing: Bool {
        guard let userId else { return false }
        return !serverHasDrawn && !defaults.bool(forKey: Self.key(userId))
    }

    /// Edits this device just saved. The first that draws something is celebrated, unless the
    /// streak grows with it.
    ///
    /// - Parameters:
    ///   - paired: whether the drawing is shared with a partner, and so counts toward a streak.
    ///   - streaks: the pair's streak, which celebrates instead when the drawing grows it.
    ///   - refreshStreak: asks the server for the streak again.
    public func saved(
        _ operations: [DrawingOperation],
        paired: Bool,
        streaks: StreakModel,
        refreshStreak: @escaping @MainActor () async -> Void
    ) {
        guard awaitsFirstDrawing, let userId, DrawingOperation.drawsSomething(operations) else { return }
        defaults.set(true, forKey: Self.key(userId))
        switch Self.decide(paired: paired, streak: streaks.streak, today: ServerDate.localDay()) {
        case .celebrate:
            celebrate()
        case .leaveToStreak, .unknown:
            // The streak's level-up is due: look for it as the drawing reaches the server, and
            // celebrate here only if it does not come.
            let bannerBefore = streaks.progress.bannerExpiresAt
            celebrating?.cancel()
            celebrating = Task { [weak self, attempts] in
                for wait in attempts {
                    try? await Task.sleep(for: wait)
                    guard !Task.isCancelled else { return }
                    await refreshStreak()
                    guard !Task.isCancelled else { return }
                    if streaks.progress.bannerExpiresAt != bannerBefore { return }
                    if let streak = streaks.streak, streak.today == ServerDate.localDay(), streak.hasActivityToday { break }
                }
                // The streak did not celebrate this drawing, or could not be reached.
                self?.celebrate()
            }
        }
    }

    public enum Decision: Equatable, Sendable {
        /// The streak cannot grow with this drawing: celebrate now.
        case celebrate
        /// Today has no drawing yet, so this one grows the streak.
        case leaveToStreak
        /// The streak for today is not known yet.
        case unknown
    }

    /// The streak grows with the pair's first drawing of the day, by either partner.
    public nonisolated static func decide(paired: Bool, streak: StreakResponse?, today: String) -> Decision {
        guard paired else { return .celebrate }
        guard let streak, streak.today == today else { return .unknown }
        return streak.hasActivityToday ? .celebrate : .leaveToStreak
    }

    private func celebrate() {
        let token = UUID()
        confettiToken = token
        celebrating = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, self?.confettiToken == token else { return }
            self?.confettiToken = nil
        }
    }

    private static func key(_ userId: String) -> String { "firstDrawing.\(userId)" }

    #if DEBUG
    /// For trying the celebration: launch with `MULBERRY_FIRST_DRAWING=1` to forget this device's
    /// record and treat the account as never having drawn.
    public func runDebugFirstDrawing() {
        guard ProcessInfo.processInfo.environment["MULBERRY_FIRST_DRAWING"] != nil, let userId else { return }
        defaults.removeObject(forKey: Self.key(userId))
        serverHasDrawn = false
    }
    #endif
}

public extension DrawingOperation {
    /// Operations that put something of the person's own on the canvas, as the server counts them
    /// toward the streak and the account's first drawing.
    static let drawingTypes: Set<String> = [
        "FINISH_STROKE", "ADD_TEXT_ELEMENT", "UPDATE_TEXT_ELEMENT", "ADD_STICKER_ELEMENT", "UPDATE_STICKER_ELEMENT",
    ]

    /// Whether the edits draw something. A stroke finished and deleted together, such as a palm's,
    /// does not.
    static func drawsSomething(_ operations: [DrawingOperation]) -> Bool {
        let deleted = Set(operations.filter { $0.type == "DELETE_STROKE" }.compactMap(\.strokeId))
        return operations.contains { operation in
            guard drawingTypes.contains(operation.type) else { return false }
            return operation.type != "FINISH_STROKE" || !deleted.contains(operation.strokeId ?? "")
        }
    }
}
