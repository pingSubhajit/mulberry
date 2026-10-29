import AppKit
import ProductCore
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

/// Android's two-sided burst, with the same particle motion, from the shared confetti model.
struct CelebrationConfetti: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.appearsActive) private var appearsActive
    @Environment(\.displayScale) private var displayScale
    @State private var start = Date()
    @State private var finished = false
    @State private var burst = ConfettiBurst()
    private let colors = ConfettiBurst.colors.map { Color(argb: $0) }

    var body: some View {
        Group {
            if !reduceMotion && !finished && appearsActive {
                TimelineView(.animation(minimumInterval: 1.0 / 60)) { timeline in
                    Canvas { context, size in
                        let elapsed = timeline.date.timeIntervalSince(start)
                        for piece in burst.particles {
                            guard let frame = burst.frame(piece, elapsed: elapsed, width: size.width,
                                                          height: size.height, displayScale: displayScale) else { continue }
                            var particle = context
                            particle.opacity = frame.opacity
                            particle.translateBy(x: frame.x, y: frame.y)
                            particle.rotate(by: .degrees(frame.rotation))
                            particle.scaleBy(x: frame.scaleX, y: 1)
                            let height = piece.shape == 2 ? piece.size * 0.35 : piece.size
                            let rect = CGRect(x: -piece.size / 2, y: -height / 2, width: piece.size, height: height)
                            particle.fill(piece.shape == 1 ? Path(ellipseIn: rect) : Path(rect), with: .color(colors[piece.color]))
                        }
                    }
                }
            }
        }
        .clipped()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .task {
            start = Date()
            finished = false
            try? await Task.sleep(for: .seconds(ConfettiBurst.duration))
            guard !Task.isCancelled else { return }
            finished = true
        }
    }
}
