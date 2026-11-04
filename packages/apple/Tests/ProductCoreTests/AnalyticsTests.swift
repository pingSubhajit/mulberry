import Foundation
import Testing
@testable import ProductCore

private let baseURL = URL(string: "https://example.invalid")!
private let signedIn = SessionTokens(accessToken: "access", refreshToken: "refresh", userId: "11111111-1111-4111-8111-111111111111")

private final class MemoryTokenStore: SessionTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var value: SessionTokens?
    init(_ value: SessionTokens?) { self.value = value }
    func load() -> SessionTokens? { lock.withLock { value } }
    func save(_ tokens: SessionTokens) { lock.withLock { value = tokens } }
    func clear() { lock.withLock { value = nil } }
}

/// Answers each batch with the next reply, keeping the last one; `nil` is a network failure.
private actor ScriptedSender: AnalyticsSender {
    private(set) var batches: [[AnalyticsRecord]] = []
    private var replies: [AnalyticsDelivery]
    init(_ replies: [AnalyticsDelivery] = [.drop]) { self.replies = replies }
    func sendAnalytics(_ events: [AnalyticsRecord]) async -> AnalyticsDelivery {
        batches.append(events)
        return replies.count > 1 ? replies.removeFirst() : replies[0]
    }
}

private actor StatusTransport: BackendTransport {
    private(set) var requests: [URLRequest] = []
    private let status: Int?
    init(status: Int?) { self.status = status }
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        guard let status else { throw URLError(.notConnectedToInternet) }
        let body = status == 202 ? Data(#"{"accepted":1,"rejected":0}"#.utf8) : Data(#"{"message":"no"}"#.utf8)
        return (body, HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!)
    }
}

private func temporaryFile() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("analytics-\(UUID().uuidString)", isDirectory: true)
        .appendingPathComponent("events.json")
}

private func record(_ index: Int) -> AnalyticsRecord {
    AnalyticsRecord(.onboardingStepViewed(.welcome), at: Date(timeIntervalSince1970: TimeInterval(index)))
}

@Test func eventsCarryTheContractsNamesAndValues() throws {
    let date = Date(timeIntervalSince1970: 1_800_000_000)
    let viewed = AnalyticsRecord(.onboardingStepViewed(.inviteCodeShown), at: date)
    #expect(viewed.event == .onboardingStepViewed)
    #expect(viewed.properties == ["step": "invite_code_shown"])
    #expect(viewed.occurredAt == "2027-01-15T08:00:00.000Z")
    #expect(viewed.id == viewed.id.lowercased() && UUID(uuidString: viewed.id) != nil)

    let failed = AnalyticsEvent.wallpaperSetupStep(surface: .iosShortcut, step: .runTest, outcome: .failed, failureReason: .testNoResponse)
    #expect(failed.name.rawValue == "mulberry_wallpaper_setup_step")
    #expect(failed.properties == ["surface_type": "IOS_SHORTCUT", "step": "run_test", "outcome": "failed", "failure_reason": "test_no_response"])
    // A reason goes only with a failure.
    let skipped = AnalyticsEvent.wallpaperSetupStep(surface: .macOverlay, step: .enableOverlay, outcome: .skipped, failureReason: .shortcutError)
    #expect(skipped.properties == ["surface_type": "MACOS_OVERLAY", "step": "enable_overlay", "outcome": "skipped"])

    #expect(AnalyticsEvent.pushOpened(.reaction).properties == ["push_type": "REACTION"])
    #expect(PushType(payloadType: "DRAW_REMINDER") == .drawReminder)
    #expect(PushType(payloadType: "CANVAS_UPDATED") == .other)
    #expect(PushType(payloadType: nil) == .other)

    let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(viewed)) as? [String: Any]
    #expect(Set(json?.keys ?? [:].keys) == ["id", "event", "occurredAt", "properties"])
    #expect(json?["event"] as? String == "mulberry_onboarding_step_viewed")
}

@Test func valuesMatchTheTrackingPlan() {
    #expect(OnboardingStep.allCases.map(\.rawValue) == [
        "welcome", "sign_in", "profile_name", "partner_details", "pairing_choice", "invite_code_shown", "redeem_code_entry",
        "invite_review", "waiting_for_partner", "paired_celebration", "notifications_permission", "wallpaper_setup", "onboarding_finished",
    ])
    #expect(WallpaperSetupStep.allCases.map(\.rawValue) == [
        "intro", "choose_background", "set_wallpaper", "install_shortcut", "create_automation", "grant_permission", "enable_overlay", "run_test", "finished",
    ])
    #expect(WallpaperSetupOutcome.allCases.map(\.rawValue) == ["viewed", "completed", "failed", "skipped"])
    #expect(PushType.allCases.map(\.rawValue) == [
        "CANVAS_NUDGE", "DRAW_REMINDER", "REACTION", "PAIRING_CONFIRMED", "PAIRING_DISCONNECTED", "PARTNER_VISIBILITY_CHANGED", "ANNIVERSARY_CHANGED", "OTHER",
    ])
    for reason in WallpaperSetupFailure.allCases {
        #expect(reason.rawValue.count <= 64 && reason.rawValue.allSatisfy { $0.isLowercase || $0 == "_" })
    }
}

@Test func unsentEventsSurviveARelaunch() async throws {
    let file = temporaryFile()
    let first = AnalyticsQueue(file: file, sender: ScriptedSender([.keep]), sendDelay: .seconds(60))
    let records = [record(1), record(2)]
    for item in records { await first.add(item) }
    let relaunched = AnalyticsQueue(file: file, sender: ScriptedSender(), sendDelay: .seconds(60))
    #expect(await relaunched.unsent == records)
}

@Test func theQueueKeepsTheNewestTwoHundred() async throws {
    let queue = AnalyticsQueue(file: temporaryFile(), sender: ScriptedSender([.keep]), sendDelay: .seconds(60))
    let records = (0..<230).map(record)
    for item in records { await queue.add(item) }
    #expect(await queue.unsent == Array(records.suffix(200)))
}

@Test func batchesGoFiftyAtATimeAndDropOnceAnswered() async throws {
    let file = temporaryFile()
    let sender = ScriptedSender([.drop])
    let queue = AnalyticsQueue(file: file, sender: sender, sendDelay: .seconds(60))
    let records = (0..<120).map(record)
    for item in records { await queue.add(item) }
    await queue.flush()
    #expect(await sender.batches.map(\.count) == [50, 50, 20])
    #expect(await sender.batches.flatMap { $0 } == records)
    #expect(await queue.unsent.isEmpty)
    #expect(await AnalyticsQueue(file: file, sender: sender).unsent.isEmpty)
}

@Test func aBatchThatCannotGoWaitsForTheNextTry() async throws {
    let sender = ScriptedSender([.drop, .keep])
    let queue = AnalyticsQueue(file: temporaryFile(), sender: sender, sendDelay: .seconds(60))
    let records = (0..<120).map(record)
    for item in records { await queue.add(item) }
    await queue.flush()
    #expect(await sender.batches.map(\.count) == [50, 50])
    // The second batch is kept whole, with the ids it will be retried under.
    #expect(await queue.unsent == Array(records.suffix(70)))
}

@Test func aNewEventIsSentAFewSecondsLater() async throws {
    let sender = ScriptedSender()
    let queue = AnalyticsQueue(file: temporaryFile(), sender: sender, sendDelay: .milliseconds(50))
    let records = [record(1), record(2)]
    for item in records { await queue.add(item) }
    #expect(await sender.batches.isEmpty)
    for _ in 0..<100 where await !queue.unsent.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
    #expect(await sender.batches == [records])
}

@Test func theServersRepliesDecideWhetherABatchStays() async throws {
    let events = [record(1)]
    for (status, delivery) in [(202, AnalyticsDelivery.drop), (400, .drop), (401, .drop), (426, .drop), (500, .keep), (503, .keep), (nil, .keep)] {
        let transport = StatusTransport(status: status)
        let store = MemoryTokenStore(signedIn)
        let client = BackendClient(baseURL: baseURL, tokenStore: store, session: signedIn, transport: transport)
        #expect(await client.sendAnalytics(events) == delivery, "status \(String(describing: status))")
        // Never a refresh, a retry or a sign-out.
        #expect(await transport.requests.map { $0.url?.path } == ["/analytics/events"])
        #expect(store.load() == signedIn)
        #expect(await client.signedInUserId == signedIn.userId)
    }
}

@Test func analyticsGoesThroughTheShortTransportWithTheBatch() async throws {
    let long = StatusTransport(status: 202)
    let short = StatusTransport(status: 202)
    let device = ClientDevice(family: .ipad, anonymousID: "0b6e3a8e-2a1e-4c64-9e43-4f1c8d2b7a11")
    let signedOut = BackendClient(baseURL: baseURL, tokenStore: MemoryTokenStore(nil), session: nil, transport: long, shortTransport: short, device: device)
    let events = [record(1), record(2)]
    #expect(await signedOut.sendAnalytics(events) == .drop)
    #expect(await long.requests.isEmpty)
    let request = try #require(await short.requests.first)
    #expect(request.httpMethod == "POST")
    #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
    #expect(request.value(forHTTPHeaderField: "X-Analytics-Anonymous-Id") == device.anonymousID)
    #expect(request.value(forHTTPHeaderField: "Content-Type") == "application/json")
    struct Body: Decodable { let events: [AnalyticsRecord] }
    #expect(try JSONDecoder().decode(Body.self, from: try #require(request.httpBody)).events == events)

    let signedInClient = BackendClient(baseURL: baseURL, tokenStore: MemoryTokenStore(signedIn), session: signedIn, transport: long, shortTransport: short)
    _ = await signedInClient.sendAnalytics(events)
    #expect(await short.requests.last?.value(forHTTPHeaderField: "Authorization") == "Bearer access")
}

@Test func theAnonymousIdIsMadeOnceAndKept() throws {
    let defaults = try #require(UserDefaults(suiteName: "analytics-\(UUID().uuidString)"))
    let id = AnalyticsInstall.anonymousID(defaults: defaults)
    #expect(id == id.lowercased() && UUID(uuidString: id) != nil)
    #expect(AnalyticsInstall.anonymousID(defaults: defaults) == id)
}
