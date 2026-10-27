import SwiftUI

/// The same two-sided burst and particle motion as Mulberry Android.
struct CelebrationConfetti: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var phase
    @Environment(\.displayScale) private var displayScale
    @State private var start = Date()
    @State private var finished = false
    @State private var burst = ConfettiBurst()
    private let colors = ConfettiBurst.colors.map { Color(uiColor: UIColor(argb: $0)) }

    var body: some View {
        Group {
            if !reduceMotion && !finished && phase == .active {
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
        }.clipped().allowsHitTesting(false).accessibilityHidden(true)
        .task {
            start = Date(); finished = false
            try? await Task.sleep(for: .seconds(ConfettiBurst.duration))
            guard !Task.isCancelled else { return }
            finished = true
        }
    }
}
