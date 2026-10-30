import Foundation
import Testing
@testable import ProductCore

private func freshDefaults() -> UserDefaults {
    let suite = "ReviewPromptTests-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    return defaults
}

@Test func reviewPromptWaitsForAThreeDayStreak() {
    let defaults = freshDefaults()
    #expect(!ReviewPrompt.reserve(streakDays: 2, defaults: defaults))
    #expect(ReviewPrompt.reserve(streakDays: 3, defaults: defaults))
}

@Test func reviewPromptBacksOffFourteenSixtyOneEightyThenAYear() {
    let defaults = freshDefaults()
    let start = Date(timeIntervalSince1970: 1_800_000_000)
    let day = 86_400.0
    #expect(ReviewPrompt.reserve(streakDays: 3, now: start, defaults: defaults))
    #expect(!ReviewPrompt.reserve(streakDays: 9, now: start.addingTimeInterval(13 * day), defaults: defaults))
    var asked = start.addingTimeInterval(14 * day)
    #expect(ReviewPrompt.reserve(streakDays: 9, now: asked, defaults: defaults))
    for wait in [60.0, 180, 365, 365] {
        #expect(!ReviewPrompt.reserve(streakDays: 9, now: asked.addingTimeInterval((wait - 1) * day), defaults: defaults))
        asked = asked.addingTimeInterval(wait * day)
        #expect(ReviewPrompt.reserve(streakDays: 9, now: asked, defaults: defaults))
    }
}
