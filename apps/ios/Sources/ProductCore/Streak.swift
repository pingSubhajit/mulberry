import Foundation

public struct StreakWeekDay: Codable, Equatable, Sendable {
    public let day: String
    public let hasActivity: Bool
}

public struct StreakResponse: Codable, Equatable, Sendable {
    public let today: String
    public let currentStreakDays: Int
    public let previousStreakDays: Int
    public let hasActivityToday: Bool
    public let lastActivityDay: String?
    public let week: [StreakWeekDay]

    public var lostRecently: Bool {
        guard currentStreakDays == 0, previousStreakDays > 0, let lastActivityDay,
              let date = Self.dayFormatter.date(from: today),
              let twoDaysAgo = Self.dayFormatter.calendar.date(byAdding: .day, value: -2, to: date) else { return false }
        return lastActivityDay == Self.dayFormatter.string(from: twoDaysAgo)
    }

    public var title: String {
        if lostRecently { return "Oops! Streak lost" }
        if currentStreakDays == 0 { return "Start your streak" }
        return "\(currentStreakDays) \(currentStreakDays == 1 ? "day" : "days") streak!"
    }

    public var message: String {
        let choices: [String]
        if lostRecently {
            choices = [
                "You had a \(previousStreakDays) day streak. Start again today and it will grow in no time",
                "That was a \(previousStreakDays) day streak. Take a breath and begin again today",
                "You built a \(previousStreakDays) day streak. A fresh start today is all you need",
                "Your \(previousStreakDays) day streak was a great run. Let today be day one again",
                "You were consistent for \(previousStreakDays) days. Restart today and build it back"
            ]
        } else if currentStreakDays == 0 {
            choices = [
                "Your streak begins with one day. Share a small moment with your partner today",
                "Start simple. Connect with your partner today and let the streak build naturally",
                "One day at a time. A quick check in today is a great start",
                "Begin today. Little moments with your partner add up quickly",
                "Ready when you are. Make today your first streak day"
            ]
        } else if hasActivityToday {
            choices = [
                "Beautiful work today. Keep connecting with your partner and watch the streak grow",
                "You showed up today. Keep sharing little moments with your partner every day",
                "Nice momentum. Come back tomorrow and keep building your streak together",
                "That was a good day to connect. Keep it going with your partner",
                "Proud of this consistency. A small moment each day adds up"
            ]
        } else {
            choices = [
                "Strong consistency. Keep connecting with your partner every day",
                "You are on a roll. Make a small moment together today",
                "Keep the streak alive. A quick hello today is enough",
                "One small connection today keeps the streak going",
                "You have built great momentum. Keep it going today"
            ]
        }
        // Match Android's stable daily copy, rather than Swift's randomized hash.
        let hash = today.utf16.reduce(Int32(0)) { ($0 &* 31) &+ Int32($1) }
        let seed = (hash &* 31) &+ (Int32(clamping: currentStreakDays) &* 7) &+ (Int32(clamping: previousStreakDays) &* 13)
        return choices[Int(seed.magnitude) % choices.count]
    }

    private static var dayFormatter: DateFormatter {
        let result = DateFormatter()
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        result.calendar = calendar
        result.locale = Locale(identifier: "en_US_POSIX")
        result.timeZone = TimeZone(secondsFromGMT: 0)
        result.dateFormat = "yyyy-MM-dd"
        return result
    }
}

/// Stored per account and pair. A first observation establishes the baseline;
/// only a later server-confirmed increase celebrates. Repeated refreshes do not.
public struct StreakProgress: Codable, Equatable, Sendable {
    public private(set) var lastObservedDays: Int?
    public private(set) var bannerExpiresAt: Date?
    public init() {}

    @discardableResult public mutating func observe(days: Int, now: Date = Date()) -> Bool {
        let previous = lastObservedDays
        lastObservedDays = days
        if let previous, days > previous {
            bannerExpiresAt = now.addingTimeInterval(3600)
            return true
        }
        if days == 0 || (previous.map { days < $0 } ?? false) { bannerExpiresAt = nil }
        return false
    }

    public func showsBanner(at date: Date = Date()) -> Bool {
        bannerExpiresAt.map { date < $0 } ?? false
    }

    public mutating func dismissBanner() { bannerExpiresAt = nil }
}

public enum PairingCelebration {
    public static func shouldPresent(previous: BootstrapState?, current: BootstrapState) -> Bool {
        guard let previous, let user = current.userId, previous.userId == user else { return false }
        return !previous.isPaired && current.isPaired
    }
}
