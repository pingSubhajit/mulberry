import SwiftUI

/// Where the streak sheet zooms from. The level-up banner leaves as it is tapped, so the sheet it
/// opens grows from the pill too, where the streak lives.
enum StreakZoomSource: Hashable { case pill }

/// A plain toolbar button, so it takes the same Liquid Glass capsule as its neighbours.
struct StreakPill: View {
    @ObservedObject var streaks: StreakModel
    let fallbackDays: Int
    /// Counts the streak sheet's closings: the bolt hops as the sheet shrinks back into it.
    var closings = 0
    let onOpen: () -> Void
    @ScaledMetric(relativeTo: .body) private var bolt: CGFloat = 19
    var body: some View {
        let days = streaks.streak?.currentStreakDays ?? fallbackDays
        Button(action: onOpen) {
            HStack(spacing: 5) {
                Image("HomeStreakBolt").resizable().scaledToFit().frame(height: bolt)
                    .boltBounce(closings, after: 0.3)
                Text(days, format: .number)
                    .font(.body.weight(.semibold)).monospacedDigit()
                    .contentTransition(.numericText(value: Double(days)))
                    .foregroundStyle(MulberryDesign.ink)
            }
            .padding(.horizontal, 4)
            .animation(.snappy, value: days)
        }
            .accessibilityLabel("Daily streak: \(days) \(days == 1 ? "day" : "days")")
            .accessibilityHint("Opens your streak for this week")
            .accessibilityIdentifier("streakButton")
    }
}

struct StreakCanvasCelebration: View {
    @ObservedObject var streaks: StreakModel
    /// The account's first drawing shares the streak's confetti, and never adds to it.
    @ObservedObject var firstDrawing: FirstDrawingModel
    let onOpen: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    private var animateBanner: Bool { !reduceMotion && scenePhase == .active }
    var body: some View {
        ZStack(alignment: .top) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if streaks.progress.showsBanner(at: context.date) {
                    Button {
                        streaks.dismissBanner()
                        onOpen()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "bolt.fill").font(.system(size: 21, weight: .medium))
                            Text("Your streak just got a level up")
                                .font(MulberryDesign.font(13, weight: "Medium", relativeTo: .subheadline))
                                .multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold))
                        }.foregroundStyle(.white).padding(.horizontal, 20).padding(.vertical, 19)
                            .background(MulberryDesign.accent, in: RoundedRectangle(cornerRadius: 16))
                    }.buttonStyle(.plain).padding(.horizontal, 16).padding(.top, 12)
                        // Match Android's 7 dp bob and 850 ms FastOutSlowIn legs.
                        .phaseAnimator(animateBanner ? [false, true] : [false]) { banner, raised in
                            banner.offset(y: raised ? -7 : 0)
                        } animation: { _ in
                            .timingCurve(0.4, 0, 0.2, 1, duration: 0.85)
                        }
                        // A single resting phase at launch does not restart when the phase list changes.
                        .id(animateBanner)
                        .accessibilityIdentifier("streakLevelUpBanner")
                }
            }.frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            if let token = streaks.confettiToken ?? firstDrawing.confettiToken {
                CelebrationConfetti().id(token)
            }
        }
    }
}

struct StreakSheet: View {
    @ObservedObject var streaks: StreakModel
    /// Where the sheet reports that the bolt broke on screen.
    let api: () -> BackendClient?
    let refresh: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var showing = StreakBreakShowing()
    /// The sheet's contents have begun settling into place.
    @State private var arrived = false
    /// Today's moment has played: until then a rise since the sheet last showed the streak waits,
    /// the old count and today's open ring standing in for it.
    @State private var todayPlayed = false
    @State private var celebrations = 0
    @State private var ripples = 0
    @State private var glints = 0
    @State private var presses = 0
    private let burgundy = Color(red: 55 / 255, green: 1 / 255, blue: 8 / 255)
    private let lowerBurgundy = Color(red: 82 / 255, green: 2 / 255, blue: 12 / 255)
    /// A form sheet reports a compact width even on iPad, so the iPad sizing checks the device.
    private let pad = UIDevice.current.userInterfaceIdiom == .pad
    @State private var contentHeight: CGFloat = 0
    @State private var actionHeight: CGFloat = 0
    /// The streak and the Continue button together; iPadOS sizes the sheet before they are measured,
    /// so it starts from the usual total.
    private var sheetHeight: CGFloat {
        contentHeight > 0 && actionHeight > 0 ? contentHeight + actionHeight : 730
    }

    var body: some View {
        GeometryReader { geometry in
            let scale = geometry.size.width / 402
            VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    HStack {
                        Spacer()
                        Button { dismiss() } label: {
                            Image(systemName: "xmark").font(.system(size: 17, weight: .medium))
                                .frame(width: 44, height: 44)
                        }.buttonStyle(.glass).buttonBorderShape(.circle)
                            .accessibilityLabel("Close streak")
                    }.padding(.horizontal, 20).padding(.top, 24)
                    if let streak = streaks.streak {
                        let tickFrom = todayPlayed ? nil : streaks.celebrationStart
                        let days = tickFrom ?? streak.currentStreakDays
                        hero(streak)
                            .frame(height: typeSize.isAccessibilitySize ? 170 : min(340, geometry.size.width * 0.8))
                            .frame(maxWidth: .infinity).padding(.top, 12).padding(.bottom, 24)
                            .accessibilityHidden(true)
                        StreakWeekRow(streak: streak, arrived: arrived, todayWaits: tickFrom != nil,
                                      celebrations: celebrations, ripples: ripples)
                            .padding(.horizontal, 40)
                        VStack(spacing: 16) {
                            Text(streak.title(days: days)).font(MulberryDesign.font(24, weight: "SemiBold", relativeTo: .title))
                                .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(days)))
                                .accessibilityAddTraits(.isHeader)
                                .streakEntrance(arrived, delay: 0.24, rise: 10, blur: 6)
                            Text(streak.message).font(MulberryDesign.font(14, weight: "Medium", relativeTo: .subheadline))
                                .foregroundStyle(.white.opacity(0.7))
                                .streakEntrance(arrived, delay: 0.32, rise: 10, blur: 6)
                        }.multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 40).padding(.top, 32).padding(.bottom, 24)
                            // The words follow the shards.
                            .opacity(showing.hidesCopy(streaks, film: StreakBreakFilm.url, reduceMotion: reduceMotion) ? 0 : 1)
                    } else if streaks.loading {
                        ProgressView("Loading your streak…").tint(.white).padding(.vertical, 100)
                    }
                    if let error = streaks.errorMessage {
                        VStack(spacing: 12) {
                            Text(error).font(.callout).multilineTextAlignment(.center)
                            if let streak = streaks.streak, streak.today != ServerDate.localDay() {
                                Text("Last updated \(streak.todayDisplay)").font(.caption)
                            }
                            Button("Try again") { Task { await refresh() } }
                                .buttonStyle(.glass).disabled(streaks.loading)
                        }.foregroundStyle(.white.opacity(0.85)).padding(24)
                    }
                }
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
            }.scrollBounceBehavior(.basedOnSize)
                    Button { dismiss() } label: {
                        Text("Continue").font(MulberryDesign.font(17, weight: "Medium"))
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, minHeight: typeSize.isAccessibilitySize ? 64 : 44)
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 16))
                        .tint(MulberryDesign.accent)
                        .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { actionHeight = $0 }
                }
                .background {
                    ZStack(alignment: .topLeading) {
                        burgundy
                        // Android's large lower oval, adapted to the sheet's shorter top inset.
                        Path(ellipseIn: CGRect(x: -165 * scale, y: 340 * scale,
                                               width: 733 * scale, height: 635 * scale))
                            .fill(lowerBurgundy)
                            // Once the sheet has nearly grown into place, the floor swells up from below,
                            // overshooting a touch, under the bolt.
                            .streakEntrance(arrived, delay: 0.2, rise: 130 * scale, from: 1.12,
                                            animation: .spring(duration: 0.42, bounce: 0.2))
                    }.ignoresSafeArea(edges: .bottom)
                }
        }
        .foregroundStyle(.white).preferredColorScheme(.dark)
        // A phone's full-height detent would override the iPad's sizing, so each gets its own.
        .modifier(StreakSheetSizing(pad: pad, height: sheetHeight))
        .sensoryFeedback(.impact(weight: .heavy), trigger: showing.cracks)
        .sensoryFeedback(.success, trigger: celebrations)
        .sensoryFeedback(.impact(weight: .light), trigger: presses)
        .onAppear { arrived = true }
        .task {
            let opened = ContinuousClock.now
            await refresh()
            await showing.decide(streaks, film: StreakBreakFilm.url, reduceMotion: reduceMotion, api: api)
            try? await Task.sleep(until: opened + StreakMotion.todayMoment)
            if !Task.isCancelled { playToday() }
        }
        .task {
            try? await Task.sleep(for: StreakMotion.glint)
            if wholeBolt { glints += 1 }
        }
    }

    private var wholeBolt: Bool {
        guard let streak = streaks.streak else { return false }
        return !streak.lostRecently && !showing.showsFilm(streaks, film: StreakBreakFilm.url, reduceMotion: reduceMotion)
    }

    /// Today's moment: a rise since the sheet last showed the streak ticks up and fills today in;
    /// a day still to do calls with a ripple. A streak done earlier and already seen keeps still.
    private func playToday() {
        guard !todayPlayed, let streak = streaks.streak else { return }
        let rose = streaks.celebrationStart != nil
        withAnimation(reduceMotion ? .easeInOut(duration: 0.25) : .snappy(duration: 0.4)) { todayPlayed = true }
        streaks.streakShown()
        if rose {
            celebrations += 1
        } else if !streak.hasActivityToday, !showing.showsFilm(streaks, film: StreakBreakFilm.url, reduceMotion: reduceMotion) {
            ripples += 1
        }
    }

    /// The film while the bolt breaks; otherwise the artwork, broken as the film ends.
    @ViewBuilder private func hero(_ streak: StreakResponse) -> some View {
        ZStack {
            // The bolt's light, wider than its artwork's own halo can reach. Behind the whole bolt,
            // the breaking one and the broken one alike, so it carries through the break unchanged.
            BoltGlow(color: Color(red: 1, green: 0.16, blue: 0.27))
                .scaleEffect(1.25)
                .streakEntrance(arrived, delay: 0.05, from: 0.6, animation: .smooth(duration: 0.7))
            if showing.showsFilm(streaks, film: StreakBreakFilm.url, reduceMotion: reduceMotion), let film = StreakBreakFilm.url {
                StreakBoltBreak(url: film, start: showing.phase == .playing, onCrack: showing.cracked,
                                onReveal: { withAnimation(.easeOut(duration: 0.45)) { showing.reveal() } },
                                onFinish: showing.finished)
                    .aspectRatio(540.0 / 840.0, contentMode: .fit)
                    .scaleEffect(StreakBoltBreak.brokenScale)
                    .streakEntrance(arrived)
            } else {
                Group {
                    if streak.lostRecently {
                        Image("StreakBrokenBolt").resizable().scaledToFit().scaleEffect(StreakBoltBreak.brokenScale)
                    } else {
                        Image("StreakBolt").resizable().scaledToFit().boltGlint(glints)
                    }
                }
                .boltSquish(presses)
                .streakEntrance(arrived, delay: 0.05, from: 0.82, animation: .bouncy(duration: 0.55, extraBounce: 0.12))
                .contentShape(Rectangle())
                .onTapGesture { presses += 1 }
            }
        }
    }
}

/// The breaking bolt's film, written out of the asset catalog once for AVFoundation to open.
@MainActor
enum StreakBreakFilm {
    static let url: URL? = {
        guard let asset = NSDataAsset(name: "StreakBoltBreak"),
              let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        // Named by size, so a new film replaces the one an older build left.
        let url = caches.appendingPathComponent("streak_bolt_break-\(asset.data.count).mov")
        if !FileManager.default.fileExists(atPath: url.path) {
            do { try asset.data.write(to: url, options: .atomic) } catch { return nil }
        }
        return url
    }()
}

private struct StreakWeekRow: View {
    let streak: StreakResponse
    /// The days settle in, one after another, as the sheet opens.
    let arrived: Bool
    /// Today shows as still to do, until its moment fills it in.
    let todayWaits: Bool
    let celebrations: Int
    let ripples: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let labels = ["S", "M", "T", "W", "T", "F", "S"]
    var body: some View {
        HStack(alignment: .top, spacing: 4) {
            ForEach(Array(streak.week.prefix(7).enumerated()), id: \.element.day) { index, day in
                let past = day.day < streak.today
                let today = day.day == streak.today
                let done = day.hasActivity && !(today && todayWaits)
                VStack(spacing: 9) {
                    Text(labels[index]).font(MulberryDesign.font(14, weight: "Medium", relativeTo: .caption))
                        .foregroundStyle(past && !done ? .white.opacity(0.4) : .white)
                    ZStack {
                        Circle().fill(done ? MulberryDesign.accent : Color(red: 75 / 255, green: 34 / 255, blue: 39 / 255))
                        if !past && !done { Circle().strokeBorder(MulberryDesign.accent, lineWidth: today ? 3 : 2) }
                        if done {
                            Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold))
                                .transition(streakCheckTransition(reduceMotion: reduceMotion))
                        } else if past { Image(systemName: "xmark").font(.system(size: 12, weight: .medium)).foregroundStyle(.white.opacity(0.4)) }
                    }.frame(width: 31, height: 31)
                        .dayRipple(today ? ripples : 0, color: MulberryDesign.accent)
                        .dayPop(today ? celebrations : 0)
                }.frame(maxWidth: .infinity)
                    .streakEntrance(arrived, delay: 0.14 + Double(index) * StreakMotion.dayStagger, from: 0.6,
                                    animation: .spring(duration: 0.4, bounce: 0.3))
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(day.day)\(today ? ", today" : ""): \(done ? "Completed" : past ? "No activity" : "Not completed yet")")
            }
        }
    }
}

struct PairingConfirmedSheet: View {
    let userName: String
    let partnerName: String
    let onContinue: () -> Void
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Text("Welcome \(userName)").font(MulberryDesign.font(18, weight: "SemiBold"))
                    .foregroundStyle(MulberryDesign.accent)
                Text("You and \(partnerName) are paired on Mulberry")
                    .font(MulberryDesign.font(24, weight: "SemiBold", relativeTo: .title2))
                    .accessibilityAddTraits(.isHeader)
                Image("PairingCouple").resizable().scaledToFit().frame(height: 236).accessibilityHidden(true)
                Button(action: onContinue) {
                    Text("Continue").font(MulberryDesign.font(17, weight: "SemiBold"))
                        .frame(maxWidth: .infinity, minHeight: 44)
                }.buttonStyle(.glassProminent).tint(MulberryDesign.accent)
            }.multilineTextAlignment(.center).padding(.horizontal, 24).padding(.top, 36).padding(.bottom, 24)
        }.scrollBounceBehavior(.basedOnSize)
            .overlay { CelebrationConfetti() }
            .presentationDetents([.height(480), .large]).presentationDragIndicator(.visible)
    }
}

/// iPad: a form sheet as tall as the streak. Phone: the full-height sheet with its grabber.
private struct StreakSheetSizing: ViewModifier {
    let pad: Bool
    let height: CGFloat
    func body(content: Content) -> some View {
        if pad {
            content.frame(idealHeight: height)
                .presentationSizing(.form.fitted(horizontal: false, vertical: true))
        } else {
            content.presentationDetents([.large]).presentationDragIndicator(.visible)
        }
    }
}
