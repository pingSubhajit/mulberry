import Foundation
import Testing
@testable import ProductCore

private func streak(_ days: Int = 1, today: String = "2026-10-27", previous: Int = 1,
                    last: String? = "2026-10-27", active: Bool = true) throws -> StreakResponse {
    try JSONDecoder().decode(StreakResponse.self, from: JSONSerialization.data(withJSONObject: [
        "today": today, "currentStreakDays": days, "previousStreakDays": previous,
        "hasActivityToday": active, "lastActivityDay": last as Any? ?? NSNull(),
        "week": [["day": "2026-10-25", "hasActivity": false], ["day": "2026-10-26", "hasActivity": false],
                 ["day": "2026-10-27", "hasActivity": active], ["day": "2026-10-28", "hasActivity": false],
                 ["day": "2026-10-29", "hasActivity": false], ["day": "2026-10-30", "hasActivity": false],
                 ["day": "2026-10-31", "hasActivity": false]]
    ]))
}

@Test func streakCopyCoversNewActiveAndRecentlyLostStates() throws {
    #expect(try streak(0, previous: 0, last: nil, active: false).title == "Start your streak")
    #expect(try streak().title == "1 day streak!")
    #expect(try streak(2).title == "2 days streak!")
    let lost = try streak(0, previous: 8, last: "2026-10-25", active: false)
    #expect(lost.lostRecently)
    #expect(lost.title == "Oops! Streak lost")
    #expect(lost.message.contains("8"))
    #expect(try !streak(0, previous: 8, last: "2026-10-24", active: false).lostRecently)
    #expect(try !streak(8, previous: 8, last: "2026-10-26", active: false).lostRecently)
    #expect(try streak(0, today: "2026-03-01", previous: 3, last: "2026-02-27", active: false).lostRecently)
}

@Test func levelUpCelebratesOnceAndBannerExpiresAfterOneHour() {
    var progress = StreakProgress()
    let now = Date(timeIntervalSince1970: 1000)
    #expect(!{ progress.observe(days: 4, now: now) }())
    #expect(!progress.showsBanner(at: now))
    #expect({ progress.observe(days: 5, now: now) }())
    #expect(!{ progress.observe(days: 5, now: now.addingTimeInterval(30)) }())
    #expect(progress.showsBanner(at: now.addingTimeInterval(3599)))
    #expect(!progress.showsBanner(at: now.addingTimeInterval(3600)))
}

@Test func bannerDismissalAndBaselineSurviveRelaunch() throws {
    var progress = StreakProgress()
    progress.observe(days: 0)
    #expect({ progress.observe(days: 1) }())
    var restored = try JSONDecoder().decode(StreakProgress.self, from: JSONEncoder().encode(progress))
    #expect(restored.showsBanner())
    #expect(!{ restored.observe(days: 1) }())
    restored.dismissBanner()
    var dismissed = try JSONDecoder().decode(StreakProgress.self, from: JSONEncoder().encode(restored))
    #expect(!dismissed.showsBanner())
    #expect(!{ dismissed.observe(days: 1) }())
    #expect({ dismissed.observe(days: 2) }())
}

@Test func brokenStreakClearsOldBannerAndRestartCelebrates() {
    var progress = StreakProgress()
    progress.observe(days: 8)
    progress.observe(days: 9)
    #expect(!{ progress.observe(days: 0) }())
    #expect(!progress.showsBanner())
    #expect({ progress.observe(days: 1) }())
}

private final class StreakTokens: SessionTokenStore, @unchecked Sendable {
    func load() -> SessionTokens? { .init(accessToken: "test", refreshToken: "test", userId: "test-user") }
    func save(_ tokens: SessionTokens) {}
    func clear() {}
}
private actor StreakTransport: BackendTransport {
    var request: URLRequest?
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        self.request = request
        return (try JSONEncoder().encode(streak()), HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!)
    }
}
@Test func streakRequestUsesLocalCalendarDayAndAuthenticatedEndpoint() async throws {
    let transport = StreakTransport()
    let client = try BackendClient(baseURL: URL(string: "https://example.invalid")!, tokenStore: StreakTokens(), transport: transport)
    let result = try await client.streak(today: "2026-10-27")
    let request = try #require(await transport.request)
    #expect(request.url?.path == "/streak")
    #expect(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems == [.init(name: "today", value: "2026-10-27")])
    #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test")
    #expect(result.week.count == 7)
    let date = ISO8601DateFormatter().date(from: "2026-10-27T21:00:00Z")!
    #expect(ServerDate.localDay(date, timeZone: TimeZone(identifier: "Asia/Kolkata")!) == "2026-10-28")
}

@Test func pairingCelebrationOnlyFollowsConfirmedPairingForSameUser() throws {
    let data = try fixture("bootstrap")
    let paired = try JSONDecoder().decode(BootstrapState.self, from: data)
    var json = try JSONSerialization.jsonObject(with: data) as! [String: Any]
    json["pairingStatus"] = "UNPAIRED"
    json["pairSessionId"] = NSNull()
    let unpaired = try JSONDecoder().decode(BootstrapState.self, from: JSONSerialization.data(withJSONObject: json))
    #expect(PairingCelebration.shouldPresent(previous: unpaired, current: paired))
    json["pairingStatus"] = "INVITE_PENDING_ACCEPTANCE"
    let pending = try JSONDecoder().decode(BootstrapState.self, from: JSONSerialization.data(withJSONObject: json))
    #expect(PairingCelebration.shouldPresent(previous: pending, current: paired))
    #expect(!PairingCelebration.shouldPresent(previous: nil, current: paired))
    #expect(!PairingCelebration.shouldPresent(previous: paired, current: paired))
    #expect(!PairingCelebration.shouldPresent(previous: unpaired, current: pending))
    #expect(!PairingCelebration.shouldPresent(previous: paired, current: unpaired))
    json["userId"] = "another-user"
    let otherUser = try JSONDecoder().decode(BootstrapState.self, from: JSONSerialization.data(withJSONObject: json))
    #expect(!PairingCelebration.shouldPresent(previous: otherUser, current: paired))
}

@Test func confettiMatchesAndroidParticlePhysicsAtSixtyFrames() throws {
    let burst = ConfettiBurst(seed: 42)
    #expect(burst.particles.count == 140)
    #expect(burst.particles.filter(\.rightSide).count == 70)
    // Compare the closed-form position against Konfetti's per-frame recurrence.
    for piece in burst.particles {
        var vx = piece.vx, vy = piece.vy, acceleration = 0.0
        var x = piece.rightSide ? 402.0 : 0.0, y = 700.0 * 0.42
        for _ in 0..<60 {
            acceleration += 0.02 / piece.mass
            vx *= 0.9
            vy = (vy + acceleration) * 0.9
            x += vx; y += vy
        }
        let frame = try #require(burst.frame(piece, elapsed: piece.delay + 1, width: 402, height: 700, displayScale: 3))
        #expect(abs(frame.x - x) < 0.00001)
        #expect(abs(frame.y - y) < 0.00001)
        #expect(frame.opacity == 1)
        #expect(frame.rotation >= 240 && frame.rotation <= 720)
        #expect((0...1).contains(frame.scaleX))
        #expect(burst.frame(piece, elapsed: 4, width: 402, height: 700, displayScale: 3) == nil)
    }
}
