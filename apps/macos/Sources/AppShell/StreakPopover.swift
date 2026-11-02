import AppKit
import ProductCore
import ProductUI
import SwiftUI

/// The iPad's streak sheet as a glass popover from the streak button: the bolt, the week, and
/// the day's words, in Poppins, over Mulberry red fading down the glass. Clicking away closes it, so it has no close or Continue buttons.
struct StreakPopover: View {
    @ObservedObject var streaks: StreakModel
    /// Where the popover reports that the bolt broke on screen.
    let api: () -> BackendClient?
    let refresh: () async -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var showing = StreakBreakShowing()
    /// The popover's contents have begun settling into place.
    @State private var arrived = false
    /// Today's moment has played: until then a rise since the streak was last shown waits, the
    /// old count and today's open ring standing in for it.
    @State private var todayPlayed = false
    @State private var celebrations = 0
    @State private var ripples = 0
    @State private var glints = 0
    @State private var presses = 0
    private var film: URL? { AppResources.url("Streak/streak_bolt_break.mov") }

    var body: some View {
        VStack(spacing: 0) {
            if let streak = streaks.streak {
                let tickFrom = todayPlayed ? nil : streaks.celebrationStart
                let days = tickFrom ?? streak.currentStreakDays
                hero(streak)
                    .frame(height: 176)
                    .padding(.top, 28)
                    .padding(.bottom, 22)
                    .accessibilityHidden(true)
                StreakWeekRow(streak: streak, arrived: arrived, todayWaits: tickFrom != nil,
                              celebrations: celebrations, ripples: ripples)
                    .padding(.horizontal, 28)
                VStack(spacing: 10) {
                    Text(streak.title(days: days))
                        .font(.custom("Poppins-SemiBold", size: 22, relativeTo: .title))
                        .contentTransition(reduceMotion ? .opacity : .numericText(value: Double(days)))
                        .accessibilityAddTraits(.isHeader)
                        .streakEntrance(arrived, delay: 0.24, rise: 8, blur: 5)
                    Text(streak.message)
                        .font(.custom("Poppins-Medium", size: 13, relativeTo: .subheadline))
                        .foregroundStyle(.secondary)
                        .streakEntrance(arrived, delay: 0.32, rise: 8, blur: 5)
                }
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 28)
                .padding(.top, 24)
                .padding(.bottom, streaks.errorMessage == nil ? 28 : 8)
                // The words follow the shards.
                .opacity(showing.hidesCopy(streaks, film: film, reduceMotion: reduceMotion) ? 0 : 1)
            } else if streaks.loading {
                ProgressView()
                    .controlSize(.large)
                    .frame(height: 240)
            }
            if let error = streaks.errorMessage {
                VStack(spacing: 10) {
                    Text(error)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Try Again") { Task { await refresh() } }
                        .buttonStyle(.glass)
                        .disabled(streaks.loading)
                }
                .padding(24)
            }
        }
        .frame(width: 340)
        // Mulberry red glowing down from the top, over the glass, behind the bolt.
        .background {
            // A soft glow through the bolt, gone before the week row, whatever the message's length.
            LinearGradient(
                colors: [mulberryRed.opacity(0.3), mulberryRed.opacity(0)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 200)
            .frame(maxHeight: .infinity, alignment: .top)
            .ignoresSafeArea()
            .allowsHitTesting(false)
        }
        .animation(.snappy, value: streaks.streak)
        .onAppear { arrived = true }
        .task {
            let opened = ContinuousClock.now
            await refresh()
            await showing.decide(streaks, film: film, reduceMotion: reduceMotion, api: api)
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
        return !streak.lostRecently && !showing.showsFilm(streaks, film: film, reduceMotion: reduceMotion)
    }

    /// Today's moment: a rise since the streak was last shown ticks up and fills today in; a day
    /// still to do calls with a ripple. A streak done earlier and already seen keeps still.
    private func playToday() {
        guard !todayPlayed, let streak = streaks.streak else { return }
        let rose = streaks.celebrationStart != nil
        withAnimation(reduceMotion ? .easeInOut(duration: 0.25) : .snappy(duration: 0.4)) { todayPlayed = true }
        streaks.streakShown()
        if rose {
            celebrations += 1
            Haptics.tap()
        } else if !streak.hasActivityToday, !showing.showsFilm(streaks, film: film, reduceMotion: reduceMotion) {
            ripples += 1
        }
    }

    /// The film while the bolt breaks; otherwise the artwork, broken as the film ends.
    @ViewBuilder private func hero(_ streak: StreakResponse) -> some View {
        ZStack {
            // The bolt's light, wider than its artwork's own halo can reach. Behind the whole bolt,
            // the breaking one and the broken one alike, so it carries through the break unchanged.
            BoltGlow(color: mulberryRed)
                .scaleEffect(1.3)
                .streakEntrance(arrived, delay: 0.05, from: 0.6, animation: .smooth(duration: 0.7))
            if showing.showsFilm(streaks, film: film, reduceMotion: reduceMotion), let film {
                StreakBoltBreak(url: film, start: showing.phase == .playing, onCrack: Haptics.tap,
                                onReveal: { withAnimation(.easeOut(duration: 0.45)) { showing.reveal() } },
                                onFinish: showing.finished)
                    .aspectRatio(540.0 / 840.0, contentMode: .fit)
                    .scaleEffect(StreakBoltBreak.brokenScale)
                    .streakEntrance(arrived)
            } else if let bolt = StreakArtwork.image(broken: streak.lostRecently) {
                Image(nsImage: bolt)
                    .resizable()
                    .scaledToFit()
                    .boltGlint(streak.lostRecently ? 0 : glints)
                    .scaleEffect(streak.lostRecently ? StreakBoltBreak.brokenScale : 1)
                    .boltSquish(presses)
                    .streakEntrance(arrived, delay: 0.05, from: 0.82, animation: .bouncy(duration: 0.55, extraBounce: 0.12))
                    .contentShape(Rectangle())
                    .onTapGesture {
                        presses += 1
                        Haptics.tap()
                    }
            }
        }
    }
}

/// The week, Sunday first, as on iPhone and iPad: done days filled in Mulberry red with a tick,
/// missed days crossed out, and days still to come ringed in red, today more boldly.
private struct StreakWeekRow: View {
    let streak: StreakResponse
    /// The days settle in, one after another, as the popover opens.
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
                VStack(spacing: 8) {
                    Text(labels[index])
                        .font(.custom("Poppins-Medium", size: 13, relativeTo: .caption))
                        .foregroundStyle(past && !day.hasActivity ? .tertiary : .primary)
                    ZStack {
                        Circle().fill(day.hasActivity ? AnyShapeStyle(mulberryRed) : AnyShapeStyle(.quaternary))
                        if !past && !day.hasActivity {
                            Circle().strokeBorder(mulberryRed, lineWidth: today ? 3 : 2)
                        }
                        if day.hasActivity {
                            Image(systemName: "checkmark")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(.white)
                        } else if past {
                            Image(systemName: "xmark")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .frame(width: 31, height: 31)
                }
                .frame(maxWidth: .infinity)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(day.day)\(today ? ", today" : ""): \(day.hasActivity ? "Completed" : past ? "No activity" : "Not completed yet")")
            }
        }
    }
}

/// The iOS streak bolts, shipped in Resources/Streak.
@MainActor
private enum StreakArtwork {
    private static var cache: [Bool: NSImage] = [:]

    static func image(broken: Bool) -> NSImage? {
        if let cached = cache[broken] { return cached }
        let name = broken ? "streak_bolt_broken_large.png" : "streak_bolt_large.png"
        guard let url = AppResources.url("Streak/\(name)"), let image = NSImage(contentsOf: url) else { return nil }
        cache[broken] = image
        return image
    }
}
