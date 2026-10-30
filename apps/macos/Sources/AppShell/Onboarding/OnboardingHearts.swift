import AppKit
import CanvasRendering
import ProductCore

/// The two hearts onboarding draws on the desktop and then shows on a phone: real Mulberry strokes
/// in the dry brush, drawn by the same renderer as every drawing, a point at a time as a hand
/// would.
enum OnboardingHearts {
    /// The iPhone's drawing area, whose proportions the strokes' points are written in.
    static let canvas = CGSize(width: 402, height: 752)

    /// The palette's Blue: the opposite of the wallpaper's pinks and reds, strong on its pale sky,
    /// where both hearts sit clear of the red tree, and still bright on the darkened desktop.
    private static let ink: UInt32 = 0xFF24_57D6
    private static let width = 0.02

    /// The big heart, leaning, its point at the lower right, then a small one beside it.
    private static let outlines: [(points: [CGPoint], frame: CGRect)] = [
        ([
            (0.508, 0.389), (0.424, 0.358), (0.288, 0.321), (0.136, 0.396), (0.034, 0.547), (0.024, 0.698),
            (0.085, 0.849), (0.186, 0.906), (0.458, 0.913), (0.729, 0.925), (0.898, 0.970), (0.966, 1.0),
            (0.983, 0.849), (0.993, 0.547), (0.966, 0.245), (0.881, 0.075), (0.780, 0.008), (0.661, 0.038),
            (0.576, 0.151), (0.532, 0.283), (0.508, 0.377),
        ].map { CGPoint(x: $0.0, y: $0.1) }, CGRect(x: 0.12, y: 0.235, width: 0.45, height: 0.199)),
        ([
            (0.533, 0.24), (0.667, 0.07), (0.867, 0.12), (0.978, 0.32), (0.889, 0.57), (0.611, 0.80),
            (0.244, 1.0), (0.056, 0.72), (0.0, 0.42), (0.089, 0.12), (0.278, 0.02), (0.444, 0.12), (0.511, 0.24),
        ].map { CGPoint(x: $0.0, y: $0.1) }, CGRect(x: 0.63, y: 0.255, width: 0.138, height: 0.076)),
    ]

    /// Each heart's points, smoothed as the iPhone smooths a finger's, in the canvas's 0 to 1 space.
    static let strokes: [[DrawingPoint]] = outlines.map { outline in
        smooth(outline.points).map { point in
            DrawingPoint(x: outline.frame.minX + point.x * outline.frame.width,
                         y: outline.frame.minY + point.y * outline.frame.height)
        }
    }

    static var pointCount: Int { strokes.reduce(0) { $0 + $1.count } }

    /// The hearts with their first `count` points drawn, the second begun once the first is done.
    static func drawing(upTo count: Int) -> DrawingState {
        var state = DrawingState()
        var remaining = count
        for (index, points) in strokes.enumerated() where remaining > 1 {
            let shown = Array(points.prefix(remaining))
            remaining -= points.count
            state.strokes.append(DrawingStroke(id: "onboarding-heart-\(index)", colorArgb: ink, width: width,
                                               createdAt: 0, points: shown, finished: true))
        }
        return state
    }

    /// The drawing as a picture of the canvas at `size`.
    @MainActor
    static func image(upTo count: Int = pointCount, size: CGSize, scale: CGFloat) -> NSImage? {
        let input = CanvasRenderInput(state: drawing(upTo: count), viewport: .zero, strokeRenderMode: .dryBrush,
                                      surface: .passiveOverlay, stickerImages: [:])
        return CanvasOffscreenRenderer.renderImage(input: input, size: size, scale: scale)
    }

    /// Catmull-Rom through the outline's points, about a point every few pixels of a heart.
    private static func smooth(_ points: [CGPoint], steps: Int = 10) -> [CGPoint] {
        guard points.count > 2 else { return points }
        var result: [CGPoint] = []
        for index in 0..<(points.count - 1) {
            let p0 = points[max(index - 1, 0)], p1 = points[index]
            let p2 = points[index + 1], p3 = points[min(index + 2, points.count - 1)]
            for step in 0..<steps {
                let t = CGFloat(step) / CGFloat(steps), t2 = t * t, t3 = t2 * t
                func axis(_ a: CGFloat, _ b: CGFloat, _ c: CGFloat, _ d: CGFloat) -> CGFloat {
                    0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
                }
                result.append(CGPoint(x: axis(p0.x, p1.x, p2.x, p3.x), y: axis(p0.y, p1.y, p2.y, p3.y)))
            }
        }
        result.append(points[points.count - 1])
        return result
    }
}
