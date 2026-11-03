import Foundation
import Testing
@testable import ProductCore

// The Time together widget's words, against Android's simulation presets and its LocalDate arithmetic.

private let today = CalendarDay(year: 2026, month: 11, day: 3)

private func model(_ anniversary: CalendarDay, on day: CalendarDay = today) -> TimeTogether {
    TimeTogether(anniversary: anniversary.storage, today: day)
}

@Test func calendarDaysRoundTripThroughEpochDays() {
    for offset in stride(from: -800_000, through: 800_000, by: 997) {
        let day = CalendarDay(epochDay: offset)
        #expect(day.epochDay == offset)
    }
    #expect(CalendarDay(year: 1970, month: 1, day: 1).epochDay == 0)
    #expect(CalendarDay(year: 2024, month: 3, day: 1).adding(days: -1) == CalendarDay(year: 2024, month: 2, day: 29))
}

@Test func storageRejectsDaysThatDoNotExist() {
    #expect(CalendarDay(storage: "2024-02-29") == CalendarDay(year: 2024, month: 2, day: 29))
    #expect(CalendarDay(storage: "2023-02-29") == nil)
    #expect(CalendarDay(storage: "2023-13-01") == nil)
    #expect(CalendarDay(storage: "23-01-01") == nil)
    #expect(CalendarDay(storage: "") == nil)
}

@Test func missingAnniversaryAsksForOne() {
    let value = TimeTogether(anniversary: nil, today: today)
    #expect(value.primary == "Time together")
    #expect(value.secondary == nil)
    #expect(value.caption == "Set your anniversary in Settings")
    #expect(!value.hasAnniversary && !value.isCelebratory && value.progress == 0)
}

@Test func firstYearCountsDays() {
    let value = model(today.adding(days: -123))
    #expect(value.primary == "123 days")
    #expect(value.secondary == nil)
    #expect(!value.primaryUsesGradient)
    #expect(value.summary == "123 days")
    #expect(model(today.adding(days: -1)).primary == "1 day")
}

@Test func yearsAndDaysWithUpcomingMilestone() {
    // Android's preview: 986 days, two years in, 14 days short of a thousand.
    let value = model(CalendarDay(year: 2024, month: 2, day: 21))
    #expect(value.primary == "2 years")
    #expect(value.secondary == "and 255 days")
    #expect(value.caption == "Wow, 14 days to 1000!!")
    #expect(value.isCelebratory && value.primaryUsesGradient)
    #expect(value.summary == "2 years, 255 days")
    #expect(abs(value.progress - 255.0 / 365.0) < 0.0001)
}

@Test func anniversaryDayIsCelebrated() {
    let value = model(CalendarDay(year: 2024, month: 11, day: 3))
    #expect(value.primary == "2 years")
    #expect(value.secondary == "and 0 days")
    #expect(value.caption == "Happy 2 years anniversary!!")
    #expect(value.progress == 0)
    #expect(value.summary == "2 years")
}

@Test func anniversarySoonCountsDown() {
    let value = model(CalendarDay(year: 2024, month: 11, day: 18))
    #expect(value.primary == "1 year")
    #expect(value.caption == "Next anniversary in 15 days")
    #expect(!value.isCelebratory)
}

@Test func hundredDayMilestones() {
    let day = CalendarDay(year: 2026, month: 6, day: 15)
    let value = model(day.adding(days: -300), on: day)
    #expect(value.caption == "Wow, 300 days now!!")
    #expect(value.isCelebratory)
}

@Test func monthMilestonesUpToTwoYears() {
    #expect(model(CalendarDay(year: 2026, month: 7, day: 3)).caption == "Wow, 4 months now!!")
    #expect(model(CalendarDay(year: 2025, month: 8, day: 3)).caption == "Wow, 15 months now!!")
    // As Period.between counts it, Jan 31 to Feb 28 is not yet a month.
    #expect(model(CalendarDay(year: 2026, month: 1, day: 31), on: CalendarDay(year: 2026, month: 2, day: 28)).caption != "Wow, 1 month now!!")
}

@Test func otherwiseTogetherSince() {
    let value = model(CalendarDay(year: 2021, month: 3, day: 5))
    #expect(value.caption.hasPrefix("Together since "))
    #expect(!value.isCelebratory)
}

@Test func leapDayAnniversaryFollowsAndroid() {
    let start = CalendarDay(year: 2020, month: 2, day: 29)
    let firstFeb28 = model(start, on: CalendarDay(year: 2021, month: 2, day: 28))
    #expect(firstFeb28.primary == "365 days")
    #expect(firstFeb28.caption == "Happy anniversary!!")
    let march = model(start, on: CalendarDay(year: 2021, month: 3, day: 1))
    #expect(march.primary == "1 year")
    #expect(march.secondary == "and 1 day")
}

@Test func futureAnniversaryReadsAsZero() {
    let value = model(today.adding(days: 10))
    #expect(value.primary == "0 days")
    #expect(value.progress == 0)
}

@Test func anniversaryPushIsTakenOnlyForThisPair() {
    let push: [AnyHashable: Any] = ["type": "ANNIVERSARY_CHANGED", "pairSessionId": "pair-1", "anniversaryDate": "2026-07-03"]
    #expect(TimeTogetherStore.anniversary(in: push, for: "pair-1") == "2026-07-03")
    #expect(TimeTogetherStore.anniversary(in: push, for: "pair-2") == nil)
    #expect(TimeTogetherStore.anniversary(in: push, for: nil) == nil)
    var other = push
    other["type"] = "REACTION"
    #expect(TimeTogetherStore.anniversary(in: other, for: "pair-1") == nil)
    var invalid = push
    invalid["anniversaryDate"] = "2026-02-30"
    #expect(TimeTogetherStore.anniversary(in: invalid, for: "pair-1") == nil)
}

@Test func storeSavesOnlyChanges() throws {
    let suite = "TimeTogetherTests.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    #expect(TimeTogetherStore.save(anniversary: "2024-02-21", pairSessionId: "pair-1", in: defaults))
    #expect(!TimeTogetherStore.save(anniversary: "2024-02-21", pairSessionId: "pair-1", in: defaults))
    #expect(TimeTogetherStore.save(anniversary: "not a day", pairSessionId: "pair-1", in: defaults))
    #expect(defaults.string(forKey: "timeTogether.anniversaryDate") == nil)
    #expect(TimeTogetherStore.save(anniversary: nil, pairSessionId: nil, in: defaults))
    #expect(defaults.string(forKey: "timeTogether.pairSessionId") == nil)
}

@Test func withoutAPartnerItAsksToPair() {
    let value = TimeTogether(anniversary: nil, today: today, isPaired: false)
    #expect(value.primary == "Time together")
    #expect(value.caption == "Pair with your partner in Mulberry")
    #expect(!value.hasAnniversary)
    // A date still counts before pairing, as Android does.
    #expect(TimeTogether(anniversary: "2026-07-03", today: today, isPaired: false).primary == "123 days")
}

@Test func pairEndedPushIsTakenOnlyForThisPair() {
    let push: [AnyHashable: Any] = ["type": "PAIR_ENDED", "pairSessionId": "pair-1"]
    #expect(TimeTogetherStore.change(in: push, for: "pair-1") == .pairEnded)
    #expect(TimeTogetherStore.change(in: push, for: "pair-2") == nil)
    #expect(TimeTogetherStore.change(in: push, for: nil) == nil)
    let anniversary: [AnyHashable: Any] = ["type": "ANNIVERSARY_CHANGED", "pairSessionId": "pair-1", "anniversaryDate": "2026-07-03"]
    #expect(TimeTogetherStore.change(in: anniversary, for: "pair-1") == .anniversary("2026-07-03"))
}
