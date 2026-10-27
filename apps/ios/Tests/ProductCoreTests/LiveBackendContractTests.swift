import Foundation
import Testing
@testable import ProductCore

private final class ContractTokenStore: SessionTokenStore, @unchecked Sendable {
    private let lock = NSLock()
    private var tokens: SessionTokens?
    func load() -> SessionTokens? { lock.withLock { tokens } }
    func save(_ value: SessionTokens) { lock.withLock { tokens = value } }
    func clear() { lock.withLock { tokens = nil } }
}

@Test(.enabled(if: ProcessInfo.processInfo.environment["MULBERRY_CONTRACT_SERVER"] != nil), .timeLimit(.minutes(1)))
func swiftClientPairsDrawsAndUnpairsThroughRealBackendRoutes() async throws {
    let url = URL(string: ProcessInfo.processInfo.environment["MULBERRY_CONTRACT_SERVER"]!)!
    #expect(url.host == "127.0.0.1")
    let first = try BackendClient(baseURL: url, tokenStore: ContractTokenStore())
    let second = try BackendClient(baseURL: url, tokenStore: ContractTokenStore())
    let suffix = UUID().uuidString.lowercased()
    let firstLogin = try await first.signIn(googleIDToken: "dev-google:first-\(suffix)@example.invalid:Alex")
    #expect(!firstLogin.onboardingCompleted)
    let profile = try await first.saveProfile(.init(displayName: "Alex", partnerDisplayName: "Sam", anniversaryDate: "2024-06-12"))
    #expect(profile.onboardingCompleted)
    _ = try await second.signIn(googleIDToken: "dev-google:second-\(suffix)@example.invalid:Sam")
    try await first.registerPushToken("contract-ios-token-\(suffix)", environment: "development")
    try await first.unregisterPushToken("contract-ios-token-\(suffix)")
    let invite = try await first.createInvite()
    #expect(invite.code.count == 6)
    let redeemed = try await second.redeemInvite(code: invite.code)
    #expect(redeemed.inviteId == invite.inviteId)
    #expect(redeemed.bootstrapState.invite?.inviteId == invite.inviteId)
    let paired = try await second.acceptInvite(id: invite.inviteId)
    #expect(paired.bootstrapState.isPaired)
    let firstPaired = try await first.bootstrap()
    #expect(firstPaired.pairSessionId == paired.pairSessionId)

    let stroke = DrawingStroke(colorArgb: 0xFFB31329, width: 0.02, points: [.init(x: 0.1, y: 0.2), .init(x: 0.7, y: 0.8)], finished: true)
    let operations = try DrawingOperation.restore(stroke)
    let accepted = try await first.submit(.init(operations: operations))
    #expect(accepted.count == 3)
    let remoteOperations = try await second.canvasOperations(after: 0)
    var remoteDocument = DrawingDocument(userId: paired.bootstrapState.userId!, pairSessionId: paired.pairSessionId)
    try remoteDocument.receive(remoteOperations)
    #expect(remoteDocument.confirmed.finishedStrokes == [stroke])
    let snapshot = try await second.canvasSnapshot()
    #expect(snapshot.snapshot.strokes == [stroke])
    #expect(snapshot.snapshotRevision == 3)

    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    defer { try? FileManager.default.removeItem(at: directory) }
    let store = DrawingStore(directory: directory)
    let firstSync = DrawingSync(api: first, store: store, userId: firstPaired.userId!, pairSessionId: paired.pairSessionId)
    let secondSync = DrawingSync(api: second, store: store, userId: paired.bootstrapState.userId!, pairSessionId: paired.pairSessionId)
    let connected = ConnectionGate()
    let firstReceived = Task { try await waitForTwoStrokes(firstSync, gate: connected) }
    let secondReceived = Task { try await waitForTwoStrokes(secondSync, gate: connected) }
    await firstSync.start()
    await secondSync.start()
    await connected.waitForBoth()
    let liveStroke = DrawingStroke(colorArgb: 0xFFFF6A2A, width: 0.01, points: [.init(x: 0.6, y: 0.1), .init(x: 0.2, y: 0.6)], finished: true)
    try await firstSync.enqueue(DrawingOperation.restore(liveStroke))
    let localSynced = try await firstReceived.value
    let remoteSynced = try await secondReceived.value
    await firstSync.stop()
    await secondSync.stop()
    #expect(localSynced.confirmed.finishedStrokes.contains(liveStroke))
    #expect(remoteSynced.confirmed.finishedStrokes.contains(liveStroke))
    #expect(remoteSynced.confirmed.revision == 6)

    _ = try await second.setPresence(deviceId: "contract-iphone", configured: true, enabled: true, canSeeLatestDrawings: true, details: ["lastCompletedRevision": .number(3)])
    let visibility = try await first.bootstrap()
    #expect(visibility.partnerPresence.canSeeLatestDrawings)
    #expect(visibility.partnerWallpaperStatus == nil)
    let updated = try await first.updatePartner(name: "Sammy", anniversary: "2024-06-12")
    #expect(updated.partnerDisplayName == "Sammy")
    #expect(!updated.canEditPartner())
    do {
        _ = try await first.updatePartner(name: "Sam", anniversary: "2024-06-12")
        Issue.record("Backend did not enforce the partner cooldown")
    } catch BackendError.rejected(let code, _) { #expect(code == 409) }
    #expect(try await first.cannyToken().split(separator: ".").count == 3)
    #expect(try await first.wallpapers().items.isEmpty)
    let disconnected = try await second.disconnect()
    #expect(!disconnected.isPaired)
    #expect(try await first.bootstrap().pairSessionId == nil)
    // Unpairing clears partner details. The re-invite form must save them
    // before requesting another code, without applying the paired cooldown.
    do {
        _ = try await first.createInvite()
        Issue.record("Invite unexpectedly allowed without partner details")
    } catch BackendError.rejected(let code, _) { #expect(code == 400) }
    let readyToInvite = try await first.updatePartner(name: "Sam", anniversary: "2024-06-12")
    #expect(readyToInvite.canEditPartner())
    let newInvite = try await first.createInvite()
    _ = try await second.redeemInvite(code: newInvite.code)
    let declined = try await second.declineInvite(id: newInvite.inviteId)
    #expect(!declined.isPaired)
    #expect(declined.invite == nil)
    try await first.signOut()
    #expect(await first.signedInUserId == nil)
}

private actor ConnectionGate {
    private var count = 0
    private var waiter: CheckedContinuation<Void, Never>?
    func signal() {
        count += 1
        if count >= 2 { waiter?.resume(); waiter = nil }
    }
    func waitForBoth() async {
        if count >= 2 { return }
        await withCheckedContinuation { waiter = $0 }
    }
}

private func waitForTwoStrokes(_ sync: DrawingSync, gate: ConnectionGate) async throws -> DrawingDocument {
    for await event in sync.events {
        if case .connection(.connected) = event { await gate.signal() }
        if case .changed(let document) = event,
           document.pending.isEmpty && document.confirmed.finishedStrokes.count == 2 { return document }
    }
    throw CancellationError()
}
