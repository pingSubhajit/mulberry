import AppKit
import ProductCore
import ProductUI
import SwiftUI

/// The iPhone's level-up moment over the canvas: for an hour after the streak grows, a Mulberry
/// red banner bobs at the top, and clicking it opens the streak. Confetti bursts once as it lands.
struct StreakCanvasCelebration: View {
    @ObservedObject var streaks: StreakModel
    let onOpen: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appearsActive) private var appearsActive
    private var animateBanner: Bool { !reduceMotion && appearsActive }

    var body: some View {
        ZStack(alignment: .top) {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                if streaks.progress.showsBanner(at: context.date) {
                    Button {
                        streaks.dismissBanner()
                        onOpen()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "bolt.fill").font(.system(size: 19, weight: .medium))
                            Text("Your streak just got a level up")
                                .font(.custom("Poppins-Medium", size: 13, relativeTo: .subheadline))
                                .multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold))
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 15)
                        .background(mulberryRed, in: .rect(cornerRadius: 16, style: .continuous))
                        .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
                        .contentShape(.rect(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    // Android's 7-point bob on 850 ms FastOutSlowIn legs.
                    .phaseAnimator(animateBanner ? [false, true] : [false]) { banner, raised in
                        banner.offset(y: raised ? -7 : 0)
                    } animation: { _ in
                        .timingCurve(0.4, 0, 0.2, 1, duration: 0.85)
                    }
                    .id(animateBanner)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .accessibilityHint("Opens your streak for this week")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            if let token = streaks.confettiToken {
                CelebrationConfetti().id(token)
            }
        }
    }
}
