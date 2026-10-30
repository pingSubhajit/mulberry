import Foundation
import Testing
@testable import ProductCore

private func streak(today: String, active: Bool) throws -> StreakResponse {
    try JSONDecoder().decode(StreakResponse.self, from: JSONSerialization.data(withJSONObject: [
        "today": today, "currentStreakDays": active ? 1 : 0, "previousStreakDays": 0,
        "hasActivityToday": active, "lastActivityDay": active ? today as Any : NSNull(), "week": [],
    ]))
}

private func freshDefaults() -> UserDefaults {
    let name = "FirstDrawingTests.\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: name)!
    defaults.removePersistentDomain(forName: name)
    return defaults
}

private let stroke = DrawingOperation(type: "FINISH_STROKE", strokeId: "s")

@Test func onlyEditsThatDrawSomethingCount() {
    #expect(DrawingOperation.drawsSomething([stroke]))
    #expect(DrawingOperation.drawsSomething([.init(type: "ADD_STICKER_ELEMENT")]))
    #expect(DrawingOperation.drawsSomething([.init(type: "UPDATE_TEXT_ELEMENT")]))
    #expect(!DrawingOperation.drawsSomething([.init(type: "ADD_STROKE", strokeId: "s"), .init(type: "APPEND_POINTS", strokeId: "s")]))
    #expect(!DrawingOperation.drawsSomething([.init(type: "CLEAR_CANVAS"), .init(type: "DELETE_STICKER_ELEMENT")]))
    // A palm's stroke, finished and taken away together.
    #expect(!DrawingOperation.drawsSomething([stroke, .init(type: "DELETE_STROKE", strokeId: "s")]))
}

@Test func theStreakCelebratesWhenTheDrawingGrowsIt() throws {
    let today = "2026-10-31"
    #expect(FirstDrawingModel.decide(paired: false, streak: nil, today: today) == .celebrate)
    #expect(FirstDrawingModel.decide(paired: true, streak: nil, today: today) == .unknown)
    // The partner already drew today: the streak has grown, so this drawing is celebrated here.
    #expect(FirstDrawingModel.decide(paired: true, streak: try streak(today: today, active: true), today: today) == .celebrate)
    #expect(FirstDrawingModel.decide(paired: true, streak: try streak(today: today, active: false), today: today) == .leaveToStreak)
    // Yesterday's streak says nothing about today.
    #expect(FirstDrawingModel.decide(paired: true, streak: try streak(today: "2026-10-30", active: true), today: today) == .unknown)
}

@MainActor @Test func celebratesTheFirstDrawingOnce() {
    let defaults = freshDefaults()
    let model = FirstDrawingModel(defaults: defaults)
    model.bind(userId: "u1", hasDrawn: false)
    #expect(model.awaitsFirstDrawing)

    model.saved([.init(type: "ADD_STROKE", strokeId: "s")], paired: false, streaks: StreakModel(defaults: defaults)) {}
    #expect(model.confettiToken == nil)

    model.saved([stroke], paired: false, streaks: StreakModel(defaults: defaults)) {}
    let first = model.confettiToken
    #expect(first != nil)
    #expect(!model.awaitsFirstDrawing)

    model.saved([stroke], paired: false, streaks: StreakModel(defaults: defaults)) {}
    #expect(model.confettiToken == first)

    // Remembered on this device until the server's answer catches up, even after a relaunch.
    let relaunched = FirstDrawingModel(defaults: defaults)
    relaunched.bind(userId: "u1", hasDrawn: false)
    #expect(!relaunched.awaitsFirstDrawing)
}

@MainActor @Test func accountsThatHaveDrawnAreNotCelebrated() {
    let defaults = freshDefaults()
    let model = FirstDrawingModel(defaults: defaults)
    model.bind(userId: "u1", hasDrawn: true)
    #expect(!model.awaitsFirstDrawing)
    // An older server that does not say.
    model.bind(userId: "u2", hasDrawn: nil)
    #expect(!model.awaitsFirstDrawing)
    model.bind(userId: nil, hasDrawn: false)
    #expect(!model.awaitsFirstDrawing)
}

@MainActor @Test func celebratesItselfWhenTheStreakCannotBeReached() async throws {
    let defaults = freshDefaults()
    let model = FirstDrawingModel(defaults: defaults, attempts: [.milliseconds(10), .milliseconds(10)])
    model.bind(userId: "u1", hasDrawn: false)
    var looked = 0
    model.saved([stroke], paired: true, streaks: StreakModel(defaults: defaults)) { looked += 1 }
    // Waits on the streak first.
    #expect(model.confettiToken == nil)
    try await Task.sleep(for: .milliseconds(200))
    #expect(looked == 2)
    #expect(model.confettiToken != nil)
}
