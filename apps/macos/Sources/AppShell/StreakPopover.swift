import AppKit
import ProductCore
import SwiftUI

/// The iPad's streak sheet as a glass popover from the streak button: the bolt, the week, and
/// the day's words, in Poppins, over Mulberry red fading down the glass. Clicking away closes it, so it has no close or Continue buttons.
struct StreakPopover: View {
    @ObservedObject var streaks: StreakModel
    let refresh: () async -> Void

    var body: some View {
        VStack(spacing: 0) {
            if let streak = streaks.streak {
                if let bolt = StreakArtwork.image(broken: streak.lostRecently) {
                    Image(nsImage: bolt)
                        .resizable()
                        .scaledToFit()
                        .frame(height: 176)
                        .padding(.top, 28)
                        .padding(.bottom, 22)
                        .accessibilityHidden(true)
                }
                StreakWeekRow(streak: streak)
                    .padding(.horizontal, 28)
                VStack(spacing: 10) {
                    Text(streak.title)
                        .font(.custom("Poppins-SemiBold", size: 22, relativeTo: .title))
                        .accessibilityAddTraits(.isHeader)
                    Text(streak.message)
                        .font(.custom("Poppins-Medium", size: 13, relativeTo: .subheadline))
                        .foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 28)
                .padding(.top, 24)
                .padding(.bottom, streaks.errorMessage == nil ? 28 : 8)
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
        .task { await refresh() }
    }
}

/// The week, Sunday first, as on iPhone and iPad: done days filled in Mulberry red with a tick,
/// missed days crossed out, and days still to come ringed in red, today more boldly.
private struct StreakWeekRow: View {
    let streak: StreakResponse
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
