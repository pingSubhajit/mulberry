import Combine
import Foundation

/// The pair's streak, saved per account and pair so it opens on the last one loaded. The first
/// count seen is the baseline; only a later increase the server confirms celebrates, with the
/// level-up banner and confetti. Repeated refreshes do not.
@MainActor
public final class StreakModel: ObservableObject {
    @Published public private(set) var streak: StreakResponse?
    @Published public private(set) var progress = StreakProgress()
    @Published public private(set) var loading = false
    @Published public private(set) var errorMessage: String?
    @Published public private(set) var confettiToken: UUID?
    private let defaults: UserDefaults
    private var key: String?
    private var generation = 0
    private var confettiTask: Task<Void, Never>?

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func bind(userId: String?, pairSessionId: String?, baselineDays: Int? = nil) {
        let next = userId.flatMap { user in pairSessionId.map { "streak.\(user).\($0)" } }
        guard key != next else { return }
        generation += 1
        key = next
        loading = false
        errorMessage = nil
        confettiToken = nil
        confettiTask?.cancel()
        let saved = next.flatMap { defaults.data(forKey: $0) }.flatMap { try? JSONDecoder().decode(Saved.self, from: $0) }
        streak = saved?.streak
        progress = saved?.progress ?? StreakProgress()
        if saved == nil, next != nil, let baselineDays { progress.observe(days: baselineDays) }
    }

    public func refresh(api: BackendClient) async {
        guard let key, !loading else { return }
        let epoch = generation
        let day = ServerDate.localDay()
        loading = true
        defer { if generation == epoch { loading = false } }
        do {
            let result = try await api.streak(today: day)
            guard generation == epoch, !Task.isCancelled, day == ServerDate.localDay() else { return }
            streak = result
            errorMessage = nil
            if progress.observe(days: result.currentStreakDays) {
                let token = UUID()
                confettiToken = token
                confettiTask?.cancel()
                confettiTask = Task { [weak self] in
                    try? await Task.sleep(for: .seconds(4))
                    guard !Task.isCancelled, self?.confettiToken == token else { return }
                    self?.confettiToken = nil
                }
            }
            save(key: key)
        } catch {
            guard generation == epoch, !Task.isCancelled else { return }
            errorMessage = streak == nil
                ? "Could not load your streak. Check your connection and try again."
                : "Could not refresh. Showing your last saved streak."
        }
    }

    #if DEBUG
    /// For trying the level-up banner and confetti: launch with `MULBERRY_STREAK_LEVEL_UP=1`.
    public func runDebugLevelUp() {
        guard ProcessInfo.processInfo.environment["MULBERRY_STREAK_LEVEL_UP"] != nil, key != nil else { return }
        // Ends on today's count, so the next real refresh sees no rise to celebrate again.
        let days = max(1, streak?.currentStreakDays ?? progress.lastObservedDays ?? 1)
        var celebrated = StreakProgress()
        celebrated.observe(days: days - 1)
        celebrated.observe(days: days)
        progress = celebrated
        confettiToken = UUID()
    }
    #endif

    public func dismissBanner() {
        progress.dismissBanner()
        if let key { save(key: key) }
    }

    private func save(key: String) {
        if let data = try? JSONEncoder().encode(Saved(streak: streak, progress: progress)) {
            defaults.set(data, forKey: key)
        }
    }

    private struct Saved: Codable {
        let streak: StreakResponse?
        let progress: StreakProgress
    }
}
