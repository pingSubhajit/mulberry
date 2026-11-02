import Combine
import Foundation

/// The pair's streak, saved per account and pair so it opens on the last one loaded. The first
/// count seen is the baseline; only a later increase the server confirms celebrates, with the
/// level-up banner and confetti. Repeated refreshes do not.
///
/// A lost streak's bolt breaks once per account, on whichever device shows it first: the server
/// remembers it, and this device remembers it too until the server has heard, so an offline
/// viewing isn't replayed or forgotten.
@MainActor
public final class StreakModel: ObservableObject {
    @Published public private(set) var streak: StreakResponse?
    @Published public private(set) var progress = StreakProgress()
    @Published public private(set) var loading = false
    @Published public private(set) var errorMessage: String?
    @Published public private(set) var confettiToken: UUID?
    /// The last activity day of the lost streak whose breaking bolt this device has shown.
    @Published private var lostSeenDay: String?
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
        lostSeenDay = Self.debugLostStreak ? nil : saved?.lostSeenDay
        streak = streak.map(Self.debugLost)
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
            streak = Self.debugLost(result)
            errorMessage = nil
            // Shown here while offline: the server hears now.
            if let day = result.lastActivityDay, day == lostSeenDay, result.lostStreakSeen == false {
                Task { try? await api.markLostStreakSeen(day: day) }
            }
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

    /// Whether the bolt should break on screen: the streak was just lost and this account hasn't
    /// watched it break, here or on another device.
    public var breakPending: Bool {
        guard let streak, streak.lostRecently, let day = streak.lastActivityDay else { return false }
        return streak.lostStreakSeen != true && lostSeenDay != day
    }

    /// The bolt broke on screen, or would have but for Reduce Motion: it doesn't break again.
    public func breakShown(api: BackendClient) {
        guard let key, let day = streak?.lastActivityDay, lostSeenDay != day else { return }
        lostSeenDay = day
        save(key: key)
        guard !Self.debugLostStreak else { return }
        Task { try? await api.markLostStreakSeen(day: day) }
    }

    /// For trying the breaking bolt: launch with `MULBERRY_STREAK_LOST=1` and the streak shows as
    /// just lost, breaking once each launch.
    private static var debugLostStreak: Bool {
        #if DEBUG
        ProcessInfo.processInfo.environment["MULBERRY_STREAK_LOST"] != nil
        #else
        false
        #endif
    }

    private static func debugLost(_ streak: StreakResponse) -> StreakResponse {
        guard debugLostStreak else { return streak }
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyy-MM-dd"
        guard let today = formatter.date(from: streak.today),
              let earlier = formatter.calendar.date(byAdding: .day, value: -2, to: today) else { return streak }
        let lastDay = formatter.string(from: earlier)
        return StreakResponse(today: streak.today, currentStreakDays: 0, previousStreakDays: max(3, streak.previousStreakDays),
                              hasActivityToday: false, lastActivityDay: lastDay, lostStreakSeen: false,
                              week: streak.week.map { StreakWeekDay(day: $0.day, hasActivity: $0.day == lastDay || ($0.day < lastDay && $0.hasActivity)) })
    }

    public func dismissBanner() {
        progress.dismissBanner()
        if let key { save(key: key) }
    }

    private func save(key: String) {
        if let data = try? JSONEncoder().encode(Saved(streak: streak, progress: progress, lostSeenDay: lostSeenDay)) {
            defaults.set(data, forKey: key)
        }
    }

    private struct Saved: Codable {
        let streak: StreakResponse?
        let progress: StreakProgress
        let lostSeenDay: String?
    }
}
