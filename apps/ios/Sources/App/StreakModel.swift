import SwiftUI

@MainActor
final class StreakModel: ObservableObject {
    @Published private(set) var streak: StreakResponse?
    @Published private(set) var progress = StreakProgress()
    @Published private(set) var loading = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var confettiToken: UUID?
    private let defaults: UserDefaults
    private var key: String?
    private var generation = 0
    private var confettiTask: Task<Void, Never>?

    init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    func bind(userId: String?, pairSessionId: String?, baselineDays: Int? = nil) {
        let next = userId.flatMap { user in pairSessionId.map { "streak.\(user).\($0)" } }
        guard key != next else { return }
        generation += 1
        key = next
        loading = false; errorMessage = nil; confettiToken = nil
        confettiTask?.cancel()
        let saved = next.flatMap { defaults.data(forKey: $0) }.flatMap { try? JSONDecoder().decode(Saved.self, from: $0) }
        streak = saved?.streak
        progress = saved?.progress ?? StreakProgress()
        if saved == nil, next != nil, let baselineDays { progress.observe(days: baselineDays) }
    }

    func refresh(api: BackendClient) async {
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

    func dismissBanner() {
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
