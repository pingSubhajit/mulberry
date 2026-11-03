import Foundation
#if canImport(WidgetKit)
import WidgetKit
#endif

/// What the Time together widget says on a given day: Android's `RelationshipWidgetModelFactory`,
/// word for word, so both phones of a pair read the same.
public struct TimeTogether: Equatable, Sendable {
    /// "123 days" in the first year, then "2 years".
    public let primary: String
    /// "and 256 days" once a year has passed.
    public let secondary: String?
    public let caption: String
    /// An anniversary or a milestone today, or one close enough to count down to.
    public let isCelebratory: Bool
    /// Years read in the red gradient; days alone read in plain red.
    public let primaryUsesGradient: Bool
    /// How far through the current relationship year, from 0 to 1.
    public let progress: Double
    /// Whole years together, for the Lock Screen's short forms.
    public let years: Int
    /// Days since the last anniversary, or since the first day in the first year.
    public let days: Int
    public let hasAnniversary: Bool

    /// The Home Screen's one-line form: "123 days", "2 years, 256 days".
    public var summary: String {
        guard hasAnniversary else { return primary }
        if years == 0 { return Self.count(days, "day") }
        return days == 0 ? Self.count(years, "year") : "\(Self.count(years, "year")), \(Self.count(days, "day"))"
    }

    /// `isPaired` only matters without an anniversary: someone with no partner is asked to pair, not
    /// to set a date.
    public init(anniversary: String?, today: CalendarDay, isPaired: Bool = true) {
        guard let start = anniversary.flatMap(CalendarDay.init(storage:)) else {
            primary = "Time together"
            secondary = nil
            caption = isPaired ? "Set your anniversary in Settings" : "Pair with your partner in Mulberry"
            isCelebratory = false
            primaryUsesGradient = false
            progress = 0
            years = 0
            days = 0
            hasAnniversary = false
            return
        }
        let daysTogether = max(0, start.days(until: today))
        let yearsCompleted = max(0, start.months(until: today) / 12)
        let lastAnniversary = start.adding(years: yearsCompleted)
        let daysIntoYear = max(0, lastAnniversary.days(until: today))
        let daysInYear = max(1, lastAnniversary.days(until: lastAnniversary.adding(years: 1)))

        primary = yearsCompleted == 0 ? Self.count(daysTogether, "day") : Self.count(yearsCompleted, "year")
        secondary = yearsCompleted == 0 ? nil : "and \(Self.count(daysIntoYear, "day"))"
        caption = Self.caption(start: start, today: today, daysTogether: daysTogether)
        isCelebratory = caption.hasPrefix("Wow") || caption.hasPrefix("Happy")
        primaryUsesGradient = yearsCompleted > 0
        progress = min(1, max(0, Double(daysIntoYear) / Double(daysInYear)))
        years = yearsCompleted
        days = yearsCompleted == 0 ? daysTogether : daysIntoYear
        hasAnniversary = true
    }

    public init(anniversary: String?, now: Date = .now, calendar: Calendar = .current, isPaired: Bool = true) {
        self.init(anniversary: anniversary, today: CalendarDay(date: now, calendar: calendar), isPaired: isPaired)
    }

    private static func caption(start: CalendarDay, today: CalendarDay, daysTogether: Int) -> String {
        let toNextAnniversary = start.daysUntilNextAnniversary(from: today)
        if toNextAnniversary == 0 && daysTogether > 0 {
            let years = max(0, start.months(until: today) / 12)
            return years > 0 ? "Happy \(count(years, "year")) anniversary!!" : "Happy anniversary!!"
        }
        if (1...30).contains(toNextAnniversary) {
            return "Next anniversary in \(count(toNextAnniversary, "day"))"
        }
        if daysTogether >= 100 && daysTogether.isMultiple(of: 100) {
            return "Wow, \(count(daysTogether, "day")) now!!"
        }
        let months = start.months(until: today)
        if months > 0 && months <= 24 && start.adding(months: months) == today {
            return "Wow, \(count(months, "month")) now!!"
        }
        if let upcoming = [500, 700, 1000, 1500, 2000].first(where: { $0 > daysTogether }),
           (1...30).contains(upcoming - daysTogether) {
            return "Wow, \(count(upcoming - daysTogether, "day")) to \(upcoming)!!"
        }
        return "Together since \(start.formatted)"
    }

    static func count(_ value: Int, _ unit: String) -> String { "\(value) \(unit)\(value == 1 ? "" : "s")" }
}

/// A calendar day with no time or time zone, as the server stores the anniversary. The arithmetic is
/// `java.time.LocalDate`'s, so a Feb 29 anniversary lands where Android puts it.
public struct CalendarDay: Equatable, Hashable, Sendable {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// `yyyy-MM-dd`, or nil for anything that isn't a real day.
    public init?(storage: String) {
        let parts = storage.trimmingCharacters(in: .whitespaces).split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let year = Int(parts[0]), let month = Int(parts[1]), let day = Int(parts[2]),
              (1...12).contains(month), (1...Self.length(of: month, in: year)).contains(day) else { return nil }
        self.init(year: year, month: month, day: day)
    }

    /// The day `date` falls on in `calendar`'s time zone.
    public init(date: Date, calendar: Calendar = .current) {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    public var storage: String { String(format: "%04d-%02d-%02d", year, month, day) }

    /// "Mar 5, 2023", in the reader's language.
    public var formatted: String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .gmt
        let date = calendar.date(from: DateComponents(year: year, month: month, day: day)) ?? .now
        var style = Date.FormatStyle(date: .abbreviated, time: .omitted)
        style.timeZone = calendar.timeZone
        return date.formatted(style)
    }

    /// Days since 1970-01-01, by the proleptic Gregorian calendar.
    var epochDay: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yearOfEra = y - era * 400
        let dayOfYear = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    init(epochDay: Int) {
        let shifted = epochDay + 719_468
        let era = (shifted >= 0 ? shifted : shifted - 146_096) / 146_097
        let dayOfEra = shifted - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let monthIndex = (5 * dayOfYear + 2) / 153
        let month = monthIndex + (monthIndex < 10 ? 3 : -9)
        self.init(year: yearOfEra + era * 400 + (month <= 2 ? 1 : 0), month: month, day: dayOfYear - (153 * monthIndex + 2) / 5 + 1)
    }

    public func adding(days: Int) -> CalendarDay { CalendarDay(epochDay: epochDay + days) }

    public func days(until other: CalendarDay) -> Int { other.epochDay - epochDay }

    /// Whole months from this day to `other`, as `Period.between(...).toTotalMonths()`.
    public func months(until other: CalendarDay) -> Int {
        var total = (other.year * 12 + other.month) - (year * 12 + month)
        let dayDifference = other.day - day
        if total > 0 && dayDifference < 0 { total -= 1 } else if total < 0 && dayDifference > 0 { total += 1 }
        return total
    }

    /// Months later, on the same day or the month's last if it is shorter.
    public func adding(months: Int) -> CalendarDay {
        let index = year * 12 + (month - 1) + months
        let newYear = Int((Double(index) / 12).rounded(.down))
        let newMonth = index - newYear * 12 + 1
        return CalendarDay(year: newYear, month: newMonth, day: min(day, Self.length(of: newMonth, in: newYear)))
    }

    public func adding(years: Int) -> CalendarDay { adding(months: years * 12) }

    /// Days from `today` to this day's next anniversary; 0 on the day. Feb 29 is kept on Feb 28 in
    /// other years.
    func daysUntilNextAnniversary(from today: CalendarDay) -> Int {
        func inYear(_ year: Int) -> CalendarDay {
            CalendarDay(year: year, month: month, day: min(day, Self.length(of: month, in: year)))
        }
        let candidate = inYear(today.year)
        let next = today.days(until: candidate) >= 0 ? candidate : inYear(today.year + 1)
        return max(0, today.days(until: next))
    }

    static func length(of month: Int, in year: Int) -> Int {
        switch month {
        case 2: (year.isMultiple(of: 4) && !year.isMultiple(of: 100)) || year.isMultiple(of: 400) ? 29 : 28
        case 4, 6, 9, 11: 30
        default: 31
        }
    }
}

/// The anniversary the app last saw, where the widget extension can read it. The app writes it
/// whenever its account changes, and from a silent push when the partner changes it.
public enum TimeTogetherStore {
    /// The widget's kind, which WidgetKit reloads by.
    public static let widgetKind = "TimeTogether"
    private static let anniversaryKey = "timeTogether.anniversaryDate"
    private static let pairKey = "timeTogether.pairSessionId"

    /// The app group both bundles name in their Info.plist.
    private static var defaults: UserDefaults? {
        guard let group = Bundle.main.object(forInfoDictionaryKey: "MulberryAppGroup") as? String,
              !group.isEmpty, !group.hasPrefix("$(") else { return nil }
        return UserDefaults(suiteName: group)
    }

    public static var anniversary: String? { defaults?.string(forKey: anniversaryKey) }
    public static var pairSessionId: String? { defaults?.string(forKey: pairKey) }

    /// Keeps the account's anniversary, or forgets it when signed out. Reloads the widget only when
    /// something changed.
    public static func save(anniversary: String?, pairSessionId: String?) {
        guard let defaults else { return }
        if save(anniversary: anniversary, pairSessionId: pairSessionId, in: defaults) { reloadWidget() }
    }

    /// A silent push for this pair: the partner changed the anniversary, or the pair ended. Returns
    /// whether it was taken. The server's WidgetKit push that follows redraws the widget.
    @discardableResult
    public static func receivePush(_ payload: [AnyHashable: Any]) -> Bool {
        guard let defaults, let change = change(in: payload, for: defaults.string(forKey: pairKey)) else { return false }
        let changed = switch change {
        case let .anniversary(date): save(anniversary: date, pairSessionId: defaults.string(forKey: pairKey), in: defaults)
        case .pairEnded: save(anniversary: nil, pairSessionId: nil, in: defaults)
        }
        if changed { reloadWidget() }
        return true
    }

    enum Change: Equatable {
        case anniversary(String)
        case pairEnded
    }

    /// What a push means for this device's pair, if it is about it.
    static func change(in payload: [AnyHashable: Any], for pairSessionId: String?) -> Change? {
        guard let pairSessionId, payload["pairSessionId"] as? String == pairSessionId else { return nil }
        switch payload["type"] as? String {
        case "ANNIVERSARY_CHANGED": return anniversary(in: payload, for: pairSessionId).map(Change.anniversary)
        case "PAIR_ENDED": return .pairEnded
        default: return nil
        }
    }

    /// Whether anything changed.
    static func save(anniversary: String?, pairSessionId: String?, in defaults: UserDefaults) -> Bool {
        let date = anniversary.flatMap(CalendarDay.init(storage:))?.storage
        guard date != defaults.string(forKey: anniversaryKey) || pairSessionId != defaults.string(forKey: pairKey) else { return false }
        defaults.set(date, forKey: anniversaryKey)
        defaults.set(pairSessionId, forKey: pairKey)
        return true
    }

    /// The new day from an `ANNIVERSARY_CHANGED` push, if it is for this device's pair.
    static func anniversary(in payload: [AnyHashable: Any], for pairSessionId: String?) -> String? {
        guard payload["type"] as? String == "ANNIVERSARY_CHANGED", let pairSessionId,
              payload["pairSessionId"] as? String == pairSessionId else { return nil }
        return (payload["anniversaryDate"] as? String).flatMap(CalendarDay.init(storage:))?.storage
    }

    private static let widgetSecretKey = "timeTogether.widgetSecret"
    private static let apiBaseURLKey = "timeTogether.apiBaseURL"

    /// The secret this device's widget fetches what to show with, made once and kept in the app group.
    public static var widgetSecret: String? {
        guard let defaults else { return nil }
        if let existing = defaults.string(forKey: widgetSecretKey) { return existing }
        var generator = SystemRandomNumberGenerator()
        let secret = (0..<32).map { _ in String(format: "%02x", UInt8.random(in: .min ... .max, using: &generator)) }.joined()
        defaults.set(secret, forKey: widgetSecretKey)
        return secret
    }

    /// Where the widget asks: the server the app talks to.
    public static func saveAPIBaseURL(_ url: URL) {
        defaults?.set(url.absoluteString, forKey: apiBaseURLKey)
    }

    /// Asks the server what the widget should show, and keeps it. WidgetKit reloads the widget on the
    /// server's push even when the app never got the silent push that would have saved the change, so
    /// the widget can't rely on the app alone. Returns whether the store now matches the server.
    public static func refreshFromServer(session: URLSession = .shared) async -> Bool {
        guard let defaults, let secret = defaults.string(forKey: widgetSecretKey),
              let base = defaults.string(forKey: apiBaseURLKey).flatMap(URL.init(string:)) else { return false }
        var request = URLRequest(url: base.appendingPathComponent("widget/time-together"), timeoutInterval: 8)
        request.setValue(secret, forHTTPHeaderField: "x-mulberry-widget-secret")
        guard let result = try? await session.data(for: request),
              let response = result.1 as? HTTPURLResponse else { return false }
        switch response.statusCode {
        case 200:
            struct Body: Decodable {
                let anniversaryDate: String?
                let pairSessionId: String?
            }
            guard let body = try? JSONDecoder().decode(Body.self, from: result.0) else { return false }
            _ = save(anniversary: body.anniversaryDate, pairSessionId: body.pairSessionId, in: defaults)
            return true
        case 401:
            // Signed out on this device: nothing to count.
            _ = save(anniversary: nil, pairSessionId: nil, in: defaults)
            return true
        default:
            return false
        }
    }

    private static let widgetPushTokenKey = "timeTogether.widgetPushToken"

    /// The widget's WidgetKit push token, as hex, once WidgetKit has issued one.
    public static var widgetPushToken: String? { defaults?.string(forKey: widgetPushTokenKey) }

    public static func saveWidgetPushToken(_ token: Data) {
        defaults?.set(token.map { String(format: "%02x", $0) }.joined(), forKey: widgetPushTokenKey)
    }

    public static func reloadWidget() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadTimelines(ofKind: widgetKind)
        #endif
    }
}
