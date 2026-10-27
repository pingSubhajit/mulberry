import SwiftUI

struct StreakPill: View {
    @ObservedObject var streaks: StreakModel
    let fallbackDays: Int
    let onOpen: () -> Void
    @Environment(\.colorScheme) private var colorScheme
    var body: some View {
        let days = streaks.streak?.currentStreakDays ?? fallbackDays
        Button(action: onOpen) {
            HStack(spacing: 6) {
                Image("HomeStreakBolt").resizable().scaledToFit().frame(width: 16, height: 23)
                Text("\(days) \(days == 1 ? "day" : "days")")
                    .font(MulberryDesign.font(14, weight: "Bold", relativeTo: .subheadline))
            }
            .foregroundStyle(colorScheme == .dark ? .white : MulberryDesign.accent)
            .padding(.horizontal, 9).frame(minHeight: 31)
            .background(MulberryDesign.accent.opacity(0.15), in: Capsule())
            .overlay(Capsule().strokeBorder(MulberryDesign.accent, lineWidth: 1))
            .fixedSize().frame(minHeight: 44)
        }.buttonStyle(.plain)
            .accessibilityLabel("Daily streak: \(days) \(days == 1 ? "day" : "days")")
            .accessibilityHint("Opens your streak for this week")
            .accessibilityIdentifier("streakButton")
    }
}

struct StreakCanvasCelebration: View {
    @ObservedObject var streaks: StreakModel
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
            if let token = streaks.confettiToken {
                CelebrationConfetti().id(token)
            }
        }
    }
}

struct StreakSheet: View {
    @ObservedObject var streaks: StreakModel
    let refresh: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    private let burgundy = Color(red: 55 / 255, green: 1 / 255, blue: 8 / 255)
    private let lowerBurgundy = Color(red: 82 / 255, green: 2 / 255, blue: 12 / 255)

    var body: some View {
        GeometryReader { geometry in
            let scale = geometry.size.width / 402
            VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    HStack(spacing: 16) {
                        Spacer()
                        if let streak = streaks.streak {
                            HStack(spacing: 5) {
                                Image("HomeStreakBolt").resizable().scaledToFit().frame(width: 13, height: 20)
                                Text("\(streak.currentStreakDays) \(streak.currentStreakDays == 1 ? "day" : "days")")
                                    .font(MulberryDesign.font(14, weight: "Bold", relativeTo: .subheadline))
                            }.padding(.horizontal, 12).padding(.vertical, 6)
                                .background(MulberryDesign.accent.opacity(0.15), in: Capsule())
                                .overlay(Capsule().strokeBorder(MulberryDesign.accent, lineWidth: 1))
                                .accessibilityElement(children: .ignore)
                                .accessibilityLabel("Daily streak: \(streak.currentStreakDays) \(streak.currentStreakDays == 1 ? "day" : "days")")
                        }
                        Button { dismiss() } label: {
                            Image(systemName: "xmark").font(.system(size: 17, weight: .medium))
                                .frame(width: 44, height: 44)
                        }.buttonStyle(.glass).buttonBorderShape(.circle)
                            .accessibilityLabel("Close streak")
                    }.padding(.horizontal, 20).padding(.top, 24)
                    if let streak = streaks.streak {
                        Image(streak.lostRecently ? "StreakBrokenBolt" : "StreakBolt")
                            .resizable().scaledToFit()
                            .frame(height: typeSize.isAccessibilitySize ? 170 : min(340, geometry.size.width * 0.8))
                            .frame(maxWidth: .infinity).padding(.top, 12).padding(.bottom, 24)
                            .accessibilityHidden(true)
                        StreakWeekRow(streak: streak).padding(.horizontal, 40)
                        VStack(spacing: 16) {
                            Text(streak.title).font(MulberryDesign.font(24, weight: "SemiBold", relativeTo: .title))
                                .accessibilityAddTraits(.isHeader)
                            Text(streak.message).font(MulberryDesign.font(14, weight: "Medium", relativeTo: .subheadline))
                                .foregroundStyle(.white.opacity(0.7))
                        }.multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 40).padding(.top, 32).padding(.bottom, 24)
                    } else if streaks.loading {
                        ProgressView("Loading your streak…").tint(.white).padding(.vertical, 100)
                    }
                    if let error = streaks.errorMessage {
                        VStack(spacing: 12) {
                            Text(error).font(.callout).multilineTextAlignment(.center)
                            if let streak = streaks.streak, streak.today != ServerDate.localDay() {
                                Text("Last updated \(streak.today)").font(.caption)
                            }
                            Button("Try again") { Task { await refresh() } }
                                .buttonStyle(.glass).disabled(streaks.loading)
                        }.foregroundStyle(.white.opacity(0.85)).padding(24)
                    }
                }
            }.scrollBounceBehavior(.basedOnSize)
                    Button { dismiss() } label: {
                        Text("Continue").font(MulberryDesign.font(17, weight: "Medium"))
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, minHeight: typeSize.isAccessibilitySize ? 64 : 44)
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.roundedRectangle(radius: 16))
                        .tint(MulberryDesign.accent)
                        .padding(.horizontal, 20).padding(.top, 12).padding(.bottom, 16)
                }
                .background {
                    ZStack(alignment: .topLeading) {
                        burgundy
                        // Android's large lower oval, adapted to the sheet's shorter top inset.
                        Path(ellipseIn: CGRect(x: -165 * scale, y: 340 * scale,
                                               width: 733 * scale, height: 635 * scale))
                            .fill(lowerBurgundy)
                    }.ignoresSafeArea(edges: .bottom)
                }
        }
        .foregroundStyle(.white).preferredColorScheme(.dark)
        .presentationDetents([.large]).presentationDragIndicator(.visible)
        .task { await refresh() }
    }
}

private struct StreakWeekRow: View {
    let streak: StreakResponse
    private let labels = ["S", "M", "T", "W", "T", "F", "S"]
    var body: some View {
        HStack(alignment: .top, spacing: 4) {
            ForEach(Array(streak.week.prefix(7).enumerated()), id: \.element.day) { index, day in
                let past = day.day < streak.today
                let today = day.day == streak.today
                VStack(spacing: 9) {
                    Text(labels[index]).font(MulberryDesign.font(14, weight: "Medium", relativeTo: .caption))
                        .foregroundStyle(past && !day.hasActivity ? .white.opacity(0.4) : .white)
                    ZStack {
                        Circle().fill(day.hasActivity ? MulberryDesign.accent : Color(red: 75 / 255, green: 34 / 255, blue: 39 / 255))
                        if !past && !day.hasActivity { Circle().strokeBorder(MulberryDesign.accent, lineWidth: today ? 3 : 2) }
                        if day.hasActivity { Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)) }
                        else if past { Image(systemName: "xmark").font(.system(size: 12, weight: .medium)).foregroundStyle(.white.opacity(0.4)) }
                    }.frame(width: 31, height: 31)
                }.frame(maxWidth: .infinity)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(day.day)\(today ? ", today" : ""): \(day.hasActivity ? "Completed" : past ? "No activity" : "Not completed yet")")
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
