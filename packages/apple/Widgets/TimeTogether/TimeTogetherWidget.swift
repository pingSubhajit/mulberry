import CoreText
import SwiftUI
import WidgetKit

// The Time together widget, as Android's: the squirrel with its heart, how far into the year the
// pair is, how long they have been together, and the day's line. Both the iPhone's and the Mac's
// widget extensions compile this file with ProductCore's TimeTogether.swift; only the iPhone has
// the Lock Screen's forms.

struct TimeTogetherWidget: Widget {
    init() { TimeTogetherFonts.register() }

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: TimeTogetherStore.widgetKind, provider: TimeTogetherProvider()) { entry in
            TimeTogetherView(entry: entry)
        }
        .configurationDisplayName("Time together")
        .description("Keep your love story close with a daily glance at how long you have been together.")
        .supportedFamilies(Self.families)
        .contentMarginsDisabled()
        .pushHandler(TimeTogetherPushHandler.self)
    }

    private static var families: [WidgetFamily] {
        #if os(iOS)
        [.systemSmall, .systemMedium, .accessoryCircular, .accessoryRectangular, .accessoryInline]
        #else
        [.systemSmall, .systemMedium]
        #endif
    }
}

// MARK: Timeline

struct TimeTogetherEntry: TimelineEntry {
    let date: Date
    let model: TimeTogether

    init(date: Date, anniversary: String?, isPaired: Bool = true) {
        self.date = date
        model = TimeTogether(anniversary: anniversary, now: date, isPaired: isPaired)
    }

    /// Android's preview: two years in, fourteen days short of a thousand.
    static func sample(on date: Date = .now) -> TimeTogetherEntry {
        TimeTogetherEntry(date: date, anniversary: CalendarDay(date: date).adding(days: -986).storage)
    }
}

/// One entry for now and one for each of the week's midnights; the app reloads the timeline
/// whenever the anniversary changes.
/// WidgetKit's push token for this widget. A push of type `widgets` to it makes WidgetKit reload the
/// widget at once, which a backgrounded app's own reload request doesn't reliably do.
struct TimeTogetherPushHandler: WidgetPushHandler {
    init() {}

    func pushTokenDidChange(_ pushInfo: WidgetPushInfo, widgets: [WidgetInfo]) {
        TimeTogetherStore.saveWidgetPushToken(pushInfo.token)
    }
}

struct TimeTogetherProvider: TimelineProvider {
    func placeholder(in context: Context) -> TimeTogetherEntry { .sample() }

    func getSnapshot(in context: Context, completion: @escaping (TimeTogetherEntry) -> Void) {
        let anniversary = TimeTogetherStore.anniversary
        let isPaired = TimeTogetherStore.pairSessionId != nil
        completion(anniversary == nil && context.isPreview ? .sample() : TimeTogetherEntry(date: .now, anniversary: anniversary, isPaired: isPaired))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TimeTogetherEntry>) -> Void) {
        // Ask the server first: the push that reloads the widget may have arrived without the app ever
        // saving the change. If the server can't be reached, the app's last copy stands.
        let reply = TimelineReply(completion)
        Task {
            _ = await TimeTogetherStore.refreshFromServer()
            reply.send(Self.timeline())
        }
    }

    private static func timeline() -> Timeline<TimeTogetherEntry> {
        let anniversary = TimeTogetherStore.anniversary
        let isPaired = TimeTogetherStore.pairSessionId != nil
        let calendar = Calendar.current
        let now = Date.now
        var entries = [TimeTogetherEntry(date: now, anniversary: anniversary, isPaired: isPaired)]
        var midnight = calendar.startOfDay(for: now)
        for _ in 0..<7 {
            guard let next = calendar.date(byAdding: .day, value: 1, to: midnight) else { break }
            midnight = next
            entries.append(TimeTogetherEntry(date: midnight, anniversary: anniversary, isPaired: isPaired))
        }
        return Timeline(entries: entries, policy: .atEnd)
    }
}

/// Hands WidgetKit's completion to the task that fetched; WidgetKit calls it from any thread.
private final class TimelineReply: @unchecked Sendable {
    private let completion: (Timeline<TimeTogetherEntry>) -> Void
    init(_ completion: @escaping (Timeline<TimeTogetherEntry>) -> Void) { self.completion = completion }
    func send(_ timeline: Timeline<TimeTogetherEntry>) { completion(timeline) }
}

// MARK: Views

struct TimeTogetherView: View {
    let entry: TimeTogetherEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.widgetRenderingMode) private var renderingMode
    @Environment(\.showsWidgetContainerBackground) private var showsBackground
    @Environment(\.colorScheme) private var colorScheme

    private var look: TimeTogetherLook {
        TimeTogetherLook(dark: colorScheme == .dark || !showsBackground,
                         tone: renderingMode == .fullColor ? .fullColor : renderingMode == .accented ? .accented : .vibrant)
    }

    var body: some View {
        content
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(entry.model.accessibilityText)
    }

    @ViewBuilder private var content: some View {
        switch family {
        #if os(iOS)
        case .accessoryCircular: TimeTogetherCircular(model: entry.model)
        case .accessoryRectangular: TimeTogetherRectangular(model: entry.model)
        case .accessoryInline: Label(entry.model.summary, systemImage: "heart.fill")
        #endif
        case .systemSmall:
            TimeTogetherCard(model: entry.model, size: .small, look: look)
                .containerBackground(for: .widget) { TimeTogetherBackground(size: .small, look: look) }
        default:
            TimeTogetherCard(model: entry.model, size: .medium, look: look)
                .containerBackground(for: .widget) { TimeTogetherBackground(size: .medium, look: look) }
        }
    }
}

/// How the card is drawn: in its own colours, or tinted or made vibrant by the system, where the
/// Home Screen is tinted or clear, the Mac's desktop is in the background, or in StandBy.
struct TimeTogetherLook {
    enum Tone { case fullColor, accented, vibrant }

    /// Dark mode, or no card behind the words.
    var dark: Bool
    var tone: Tone

    var fullColor: Bool { tone == .fullColor }

    var card: Color { dark ? Color(red: 0.11, green: 0.04, blue: 0.06) : .white }
    var doodle: Color { dark ? .white.opacity(0.09) : .black.opacity(0.13) }
    var gradient: [Color] {
        dark ? [Color(red: 1, green: 0.35, blue: 0.37), Color(red: 0.91, green: 0.2, blue: 0.29)]
            : [Color(red: 216 / 255, green: 16 / 255, blue: 18 / 255), Color(red: 179 / 255, green: 19 / 255, blue: 41 / 255)]
    }
    var plainRed: Color { gradient[0] }
    var text: Color { fullColor ? (dark ? .white : .black) : .primary }
    var ringTrack: Color {
        fullColor ? (dark ? Color(red: 0.29, green: 0.12, blue: 0.15) : Color(red: 253 / 255, green: 230 / 255, blue: 234 / 255)) : .primary.opacity(0.22)
    }
    var ringProgress: Color { fullColor ? Color(red: 250 / 255, green: 38 / 255, blue: 43 / 255) : .primary }
}

enum TimeTogetherSize { case small, medium }

struct TimeTogetherCard: View {
    let model: TimeTogether
    let size: TimeTogetherSize
    let look: TimeTogetherLook

    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width, height = proxy.size.height
            ZStack(alignment: .topLeading) {
                squirrel(width: width, height: height)
                cluster
                    .frame(maxWidth: size == .medium ? width * 0.62 : .infinity, alignment: .trailing)
                    .frame(width: width - padding * 2, height: height - padding * 2, alignment: size == .medium ? .trailing : .bottomTrailing)
                    .padding(padding)
            }
        }
    }

    private var padding: CGFloat { size == .medium ? 16 : 13 }

    /// Android's framing of the squirrel: peeking up with its heart down the medium card's full
    /// height, and hanging upside down from the small card's top corner. On a celebration day it
    /// squeals among confetti instead.
    private func squirrel(width: CGFloat, height: CGFloat) -> some View {
        // As Android places them, measured from its previews: the full height of the medium card, and
        // 69% of the small card's height, both from the top corner.
        let artHeight = size == .medium ? height : height * 0.69
        let name = "TimeTogetherSquee" + (model.isCelebratory ? "Celebrating" : "") + (size == .medium ? "" : "Small")
        return Image(name)
            .resizable()
            .widgetAccentedRenderingMode(.accentedDesaturated)
            .scaledToFit()
            .frame(height: artHeight)
            .opacity(look.tone == .vibrant ? 0.5 : 1)
            .accessibilityHidden(true)
    }

    private var metrics: (ring: CGFloat, primary: CGFloat, secondary: CGFloat, caption: CGFloat) {
        size == .medium ? (42, 34, 15, 11) : (34, 27, 13, 10)
    }

    private var cluster: some View {
        let metrics = metrics
        let showsCaption = size == .medium || !model.hasAnniversary
        return VStack(alignment: .trailing, spacing: 0) {
            TimeTogetherRing(progress: model.progress, look: look)
                .frame(width: metrics.ring, height: metrics.ring)
                .padding(.bottom, size == .medium ? 2 : 0)
            primary(size: model.hasAnniversary ? metrics.primary : metrics.primary * 0.78)
                // Poppins sets its lines tall; the count sits close to what follows it.
                .padding(.bottom, -metrics.primary * 0.28)
                .padding(.top, -metrics.primary * 0.06)
            if let secondary = model.secondary {
                Text(secondary)
                    .font(TimeTogetherFonts.bold(metrics.secondary))
                    .foregroundStyle(look.text)
                    .padding(.top, metrics.primary * 0.12)
            }
            if showsCaption {
                Text(model.caption)
                    .font(TimeTogetherFonts.medium(metrics.caption))
                    .foregroundStyle(look.text.opacity(look.fullColor ? (look.dark ? 0.78 : 0.86) : 0.8))
                    .padding(.top, model.secondary == nil ? metrics.primary * 0.2 : 1)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(size == .medium ? 1 : 2)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }

    @ViewBuilder private func primary(size: CGFloat) -> some View {
        let text = Text(model.primary).font(TimeTogetherFonts.extraBold(size))
        if !look.fullColor {
            text.foregroundStyle(.primary).widgetAccentable()
        } else if model.primaryUsesGradient {
            text.foregroundStyle(LinearGradient(colors: look.gradient, startPoint: .leading, endPoint: .trailing))
        } else {
            text.foregroundStyle(look.plainRed)
        }
    }
}

/// The card: white, or deep mulberry in dark mode, with a scatter of doodles that fades out behind
/// the words. The system takes it away where the Home Screen is tinted or clear.
struct TimeTogetherBackground: View {
    let size: TimeTogetherSize
    let look: TimeTogetherLook

    var body: some View {
        ZStack {
            look.card
            TimeTogetherDoodles(color: look.doodle)
            LinearGradient(stops: [.init(color: look.card.opacity(0), location: size == .medium ? 0.25 : 0.3),
                                   .init(color: look.card.opacity(0.92), location: size == .medium ? 0.72 : 0.85)],
                           startPoint: size == .medium ? .leading : .topLeading,
                           endPoint: size == .medium ? .trailing : .bottomTrailing)
        }
    }
}

/// Android's year ring: how far through the year since the last anniversary, round a heart.
struct TimeTogetherRing: View {
    let progress: Double
    let look: TimeTogetherLook

    var body: some View {
        GeometryReader { proxy in
            let side = min(proxy.size.width, proxy.size.height)
            let stroke = side * 0.15
            ZStack {
                Circle().inset(by: stroke / 2).stroke(look.ringTrack, lineWidth: stroke)
                Circle().inset(by: stroke / 2)
                    .trim(from: 0, to: progress)
                    .stroke(look.ringProgress, style: StrokeStyle(lineWidth: stroke, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
                    .widgetAccentable()
                if look.fullColor {
                    Circle().inset(by: stroke).fill(look.dark ? look.card : .white)
                    Image("TimeTogetherHeart").resizable().scaledToFit().frame(width: side * 0.5, height: side * 0.5)
                } else {
                    Image(systemName: "heart.fill")
                        .resizable().scaledToFit()
                        .frame(width: side * 0.4, height: side * 0.4)
                        .widgetAccentable()
                }
            }
            .frame(width: side, height: side)
        }
        .accessibilityHidden(true)
    }
}

/// Little hand-drawn marks, as Android's backdrop has: rings, crosses, pluses and squiggles on a
/// loose grid, the same every time.
struct TimeTogetherDoodles: View {
    let color: Color

    var body: some View {
        Canvas { context, size in
            let cell: CGFloat = 25
            var seed: UInt64 = 0x5EED_1E55
            func next() -> CGFloat {
                seed = seed &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
                return CGFloat((seed >> 33) % 10_000) / 10_000
            }
            let style = StrokeStyle(lineWidth: 1.3, lineCap: .round, lineJoin: .round)
            for row in 0...Int(size.height / cell) {
                for column in 0...Int(size.width / cell) {
                    let kind = Int(next() * 6)
                    let center = CGPoint(x: (CGFloat(column) + 0.2 + next() * 0.6) * cell, y: (CGFloat(row) + 0.2 + next() * 0.6) * cell)
                    let scale = 0.7 + next() * 0.6
                    let angle = next() * .pi
                    var path = Path()
                    switch kind {
                    case 0:
                        let radius = 3.2 * scale
                        path.addEllipse(in: CGRect(x: center.x - radius, y: center.y - radius, width: radius * 2, height: radius * 2))
                    case 1, 2:
                        let arm = 3.2 * scale
                        let turn = kind == 1 ? CGFloat.pi / 4 : 0
                        for spoke in [turn, turn + .pi / 2] {
                            let dx = cos(spoke) * arm, dy = sin(spoke) * arm
                            path.move(to: CGPoint(x: center.x - dx, y: center.y - dy))
                            path.addLine(to: CGPoint(x: center.x + dx, y: center.y + dy))
                        }
                    case 3, 4:
                        let length = 5 * scale
                        let dx = cos(angle) * length, dy = sin(angle) * length
                        path.move(to: CGPoint(x: center.x - dx, y: center.y - dy))
                        path.addQuadCurve(to: CGPoint(x: center.x + dx, y: center.y + dy),
                                          control: CGPoint(x: center.x - dy * 0.9, y: center.y + dx * 0.9))
                    default:
                        continue
                    }
                    context.stroke(path, with: .color(color), style: style)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

#if os(iOS)
/// The Lock Screen's ring: the year's progress, round the years or, in the first year, the days.
struct TimeTogetherCircular: View {
    let model: TimeTogether

    var body: some View {
        if model.hasAnniversary {
            Gauge(value: model.progress) {
                Image(systemName: "heart.fill")
            } currentValueLabel: {
                VStack(spacing: -3) {
                    Text("\(model.years > 0 ? model.years : model.days)")
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .minimumScaleFactor(0.6)
                    Text(model.years > 0 ? (model.years == 1 ? "year" : "years") : (model.days == 1 ? "day" : "days"))
                        .font(.system(size: 9, weight: .semibold, design: .rounded))
                }
            }
            .gaugeStyle(.accessoryCircularCapacity)
            .widgetAccentable()
        } else {
            ZStack {
                AccessoryWidgetBackground()
                Image(systemName: "heart.fill").font(.title2)
            }
        }
    }
}

/// The Lock Screen's line: how long, the day's caption, and the year's progress.
struct TimeTogetherRectangular: View {
    let model: TimeTogether

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label {
                Text(model.summary).font(.headline).minimumScaleFactor(0.75)
            } icon: {
                Image(systemName: "heart.fill").widgetAccentable()
            }
            Text(model.caption)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(model.hasAnniversary ? 1 : 2)
            if model.hasAnniversary {
                Gauge(value: model.progress) { EmptyView() }
                    .gaugeStyle(.accessoryLinearCapacity)
                    .widgetAccentable()
            }
        }
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
#endif

// MARK: Fonts and words

/// The widget's Poppins, from the extension's own bundle.
enum TimeTogetherFonts {
    private static let registration: Void = {
        for url in Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? [] {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }()

    static func register() { registration }

    static func extraBold(_ size: CGFloat) -> Font { .custom("Poppins-ExtraBold", fixedSize: size) }
    static func bold(_ size: CGFloat) -> Font { .custom("Poppins-Bold", fixedSize: size) }
    static func medium(_ size: CGFloat) -> Font { .custom("Poppins-Medium", fixedSize: size) }
}

extension TimeTogether {
    var accessibilityText: String {
        hasAnniversary ? "Together \(summary). \(caption)" : caption
    }
}

// MARK: Previews

#Preview("Medium", as: .systemMedium) {
    TimeTogetherWidget()
} timeline: {
    TimeTogetherEntry.sample()
    TimeTogetherEntry(date: .now, anniversary: CalendarDay(date: .now).adding(days: -123).storage)
    TimeTogetherEntry(date: .now, anniversary: CalendarDay(date: .now).adding(years: -2).storage)
    TimeTogetherEntry(date: .now, anniversary: nil)
    TimeTogetherEntry(date: .now, anniversary: nil, isPaired: false)
}

#Preview("Small", as: .systemSmall) {
    TimeTogetherWidget()
} timeline: {
    TimeTogetherEntry.sample()
    TimeTogetherEntry(date: .now, anniversary: CalendarDay(date: .now).adding(days: -123).storage)
    TimeTogetherEntry(date: .now, anniversary: nil)
}

#if os(iOS)
#Preview("Lock Screen", as: .accessoryRectangular) {
    TimeTogetherWidget()
} timeline: {
    TimeTogetherEntry.sample()
    TimeTogetherEntry(date: .now, anniversary: nil)
}
#endif
