import Foundation

private struct Tokens: SessionTokenStore {
    func load() -> SessionTokens? { .init(accessToken: "test", refreshToken: "test", userId: "user-a") }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}

private actor Transport: BackendTransport {
    var days = 0
    var offline = false
    var hold = false
    var entered = false
    var pending: CheckedContinuation<Void, Never>?
    func configure(days: Int, offline: Bool = false, hold: Bool = false) { self.days = days; self.offline = offline; self.hold = hold; entered = false }
    func hasEntered() -> Bool { entered }
    func release() { pending?.resume(); pending = nil }
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let days = days
        entered = true
        if hold { await withCheckedContinuation { pending = $0 } }
        if offline { throw URLError(.notConnectedToInternet) }
        let data = try JSONSerialization.data(withJSONObject: [
            "today": ServerDate.localDay(), "currentStreakDays": days, "previousStreakDays": days,
            "hasActivityToday": days > 0, "lastActivityDay": ServerDate.localDay(), "week": []
        ])
        return (data, HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}

@main struct StreakModelChecks {
    @MainActor static func main() async throws {
        let suite = "StreakChecks.\(UUID())"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let transport = Transport()
        let api = try BackendClient(baseURL: URL(string: "https://test.invalid")!, tokenStore: Tokens(), transport: transport)
        let model = StreakModel(defaults: defaults)
        var failures = 0
        func check(_ passed: Bool, _ name: String) {
            print("\(passed ? "PASS" : "FAIL"): \(name)")
            if !passed { failures += 1 }
        }
        model.bind(userId: "a", pairSessionId: "pair", baselineDays: 0)
        await model.refresh(api: api)
        check(model.streak?.currentStreakDays == 0 && model.confettiToken == nil, "Initial server load has no celebration")
        await transport.configure(days: 1)
        await model.refresh(api: api)
        let token = model.confettiToken
        check(token != nil && model.progress.showsBanner(), "Confirmed first drawing triggers banner and confetti")
        await model.refresh(api: api)
        check(model.confettiToken == token, "Repeated refresh does not replay confetti")
        let reopened = StreakModel(defaults: defaults)
        reopened.bind(userId: "a", pairSessionId: "pair")
        check(reopened.streak?.currentStreakDays == 1 && reopened.progress.showsBanner(), "Relaunch restores streak and unexpired banner")
        check(reopened.confettiToken == nil, "Relaunch does not replay saved confetti")
        reopened.dismissBanner()
        let dismissed = StreakModel(defaults: defaults)
        dismissed.bind(userId: "a", pairSessionId: "pair")
        check(!dismissed.progress.showsBanner(), "Tapped banner stays dismissed after relaunch")
        await transport.configure(days: 2, offline: true)
        await dismissed.refresh(api: api)
        check(dismissed.streak?.currentStreakDays == 1 && dismissed.errorMessage != nil, "Offline keeps saved count and reports stale data")
        check(dismissed.confettiToken == nil, "Offline does not invent a streak level up")
        await transport.configure(days: 2)
        await dismissed.refresh(api: api)
        check(dismissed.streak?.currentStreakDays == 2 && dismissed.confettiToken != nil, "Reconnect celebrates server-confirmed increase")
        await transport.configure(days: 3, hold: true)
        let pending = Task { await dismissed.refresh(api: api) }
        while !(await transport.hasEntered()) { await Task.yield() }
        dismissed.bind(userId: "b", pairSessionId: "new-pair")
        await transport.release()
        await pending.value
        check(dismissed.streak == nil && dismissed.confettiToken == nil, "Old request cannot update another account or pair")
        check(!dismissed.progress.showsBanner() && !dismissed.loading, "Pair change clears banner and loading state")
        dismissed.bind(userId: nil, pairSessionId: nil)
        check(dismissed.streak == nil && dismissed.errorMessage == nil, "Sign-out clears visible streak state")
        guard failures == 0 else { fatalError("\(failures) checks failed") }
    }
}
