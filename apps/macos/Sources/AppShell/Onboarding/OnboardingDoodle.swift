import AppKit
import CanvasRendering
import Foundation
import ProductCore

/// What onboarding draws on the desktop and then shows on a phone, as two people would on one
/// canvas: one begins a heart in Mulberry red and, as the other finishes it in pink, moves on to
/// write a note beside it, "hi", a small heart and a stroke, written on an iPad and played back as
/// it was written. Real Mulberry strokes, drawn by the same renderer as every drawing.
enum OnboardingDoodle {
    struct Stroke {
        let color: UInt32
        let width: Double
        /// In the iPhone canvas's 0 to 1 space.
        let points: [DrawingPoint]
        /// When each point is drawn, in seconds from the doodle's start.
        let times: [Double]
    }

    /// The iPhone's drawing area, in points, which the doodle is laid out in.
    static let canvas = CGSize(width: 402, height: 752)

    /// Two palette colours, one for each hand: warm and close, yet told apart, and clear on the
    /// wallpaper's pale sky and on the darkened desktop alike.
    private static let firstHand: UInt32 = 0xFFB3_1329
    private static let secondHand: UInt32 = 0xFFE8_5072

    static let strokes: [Stroke] = heart() + note(after: firstHalfDuration + toTheNote)

    static var duration: Double { strokes.compactMap(\.times.last).max() ?? 0 }

    /// The doodle as far as it has been drawn `time` seconds in.
    static func drawing(at time: Double) -> DrawingState {
        var state = DrawingState()
        for (index, stroke) in strokes.enumerated() {
            let shown = zip(stroke.points, stroke.times).prefix { $0.1 <= time }.map(\.0)
            guard shown.count > 1 else { continue }
            state.strokes.append(DrawingStroke(id: "onboarding-doodle-\(index)", colorArgb: stroke.color, width: stroke.width,
                                               createdAt: 0, points: shown, finished: true))
        }
        return state
    }

    /// The doodle as a picture of the canvas at `size`, as far as it has been drawn.
    @MainActor
    static func image(at time: Double = .infinity, size: CGSize, scale: CGFloat) -> NSImage? {
        let input = CanvasRenderInput(state: drawing(at: time), viewport: .zero, strokeRenderMode: .dryBrush,
                                      surface: .passiveOverlay, stickerImages: [:])
        return CanvasOffscreenRenderer.renderImage(input: input, size: size, scale: scale)
    }

    // MARK: The heart

    /// The first hand's half, then the second hand's, which begins as the first hand moves on to
    /// the note.
    private static let firstHalfDuration = 0.85
    private static let secondHalfDuration = 1.0
    private static let secondHandWaits = 0.35
    private static let toTheNote = 0.25

    /// In the sky left of the wallpaper's tree, below the clock: its middle and width, in points.
    private static let heartCenter = CGPoint(x: 150, y: 240)
    private static let heartWidth: CGFloat = 170
    private static let heartPen = 0.0155

    /// How a hand gets a heart wrong: the halves drawn by different hands, unalike, and wavering.
    private struct Half {
        let widen: Double
        let stretch: Double
        let drop: Double
        /// The phases of the hand's slow waver.
        let waver: Double
    }

    private static let firstHalf = Half(widen: 1.06, stretch: 1.02, drop: 0, waver: 1.3)
    private static let secondHalf = Half(widen: 0.91, stretch: 0.93, drop: 1.1, waver: 0.2)

    /// Two halves of the classic heart curve, each from the top's dip round its lobe to the point,
    /// leaning to the left. The first hand's lobe is the fuller, its pen landing with a small hook;
    /// the second's is narrower and lower, starts below and right of where the first began, and
    /// carries on past the point.
    private static func heart() -> [Stroke] {
        let steps = 70
        let lean = -0.16
        let waver = 0.55
        func point(_ t: Double, _ half: Half) -> CGPoint {
            var x = 16 * pow(sin(t), 3) * half.widen
            var y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)) * half.stretch + half.drop
            x += waver * (sin(2.7 * t + half.waver) + 0.6 * sin(5.3 * t + 2 * half.waver))
            y += waver * (0.8 * cos(2.1 * t + half.waver) + 0.5 * sin(4.7 * t - half.waver))
            let scale = Double(heartWidth) / 32
            let (dx, dy) = (x * scale, y * scale)
            return CGPoint(x: heartCenter.x + dx * cos(lean) - dy * sin(lean),
                           y: heartCenter.y + dx * sin(lean) + dy * cos(lean))
        }
        var first = (0...steps).map { point(-Double.pi * Double($0) / Double(steps), firstHalf) }
        if let landing = first.first {
            first.insert(CGPoint(x: landing.x - 0.012 * heartWidth, y: landing.y + 0.02 * heartWidth), at: 0)
        }
        let offset = CGPoint(x: 0.022 * heartWidth, y: 0.038 * heartWidth)
        var second = (0...steps).map { index -> CGPoint in
            let p = point(Double.pi * Double(index) / Double(steps), secondHalf)
            let remaining = pow(1 - CGFloat(index) / CGFloat(steps), 2)
            return CGPoint(x: p.x + offset.x * remaining, y: p.y + offset.y * remaining)
        }
        if let end = second.last, second.count > 2 {
            let before = second[second.count - 3]
            second.append(CGPoint(x: end.x + (end.x - before.x) * 1.4, y: end.y + (end.y - before.y) * 1.4))
        }
        return [
            Stroke(color: firstHand, width: heartPen, points: first.map(canvasPoint),
                   times: handTimes(first.count, from: 0, over: firstHalfDuration)),
            Stroke(color: secondHand, width: heartPen, points: second.map(canvasPoint),
                   times: handTimes(second.count, from: firstHalfDuration + secondHandWaits, over: secondHalfDuration)),
        ]
    }

    /// A hand's pace along a stroke: unhurried setting off and arriving, quicker between.
    private static func handTimes(_ count: Int, from start: Double, over duration: Double) -> [Double] {
        (0..<count).map { index in
            let s = Double(index) / Double(max(count - 1, 1))
            let eased = (0.35 * s + (s - sin(2 * .pi * s) / (2 * .pi))) / 1.35
            return start + eased * duration
        }
    }

    // MARK: The note

    /// Tucked into the heart's lower right curve, above the tree: its top left corner and width, in points.
    private static let noteOrigin = CGPoint(x: 212, y: 236)
    private static let noteWidth: CGFloat = 88
    /// The note's width over its height, as it was written.
    private static let noteAspect: CGFloat = 1.11
    private static let notePen = 0.011
    /// Each stroke at the speed it was written; the pauses between, which were longer, shortened.
    private static let longestPause = 0.4

    private struct WrittenStroke: Decodable {
        /// When the stroke began, in seconds from the note's first.
        let start: Double
        /// Each point's place in the note's box, 0 to 1, and its time from the stroke's start.
        let points: [[Double]]
    }

    private static func note(after start: Double) -> [Stroke] {
        guard let url = AppResources.url("Onboarding/HiNote.json"),
              let data = try? Data(contentsOf: url),
              let written = try? JSONDecoder().decode([WrittenStroke].self, from: data) else { return [] }
        let height = noteWidth / noteAspect
        var strokes: [Stroke] = []
        var clock = start
        var previousEnd: Double?
        for stroke in written {
            let valid = stroke.points.filter { $0.count == 3 }
            guard let duration = valid.last?[2] else { continue }
            if let previousEnd { clock += min(longestPause, max(0, stroke.start - previousEnd)) }
            let points = valid.map { canvasPoint(CGPoint(x: noteOrigin.x + $0[0] * noteWidth, y: noteOrigin.y + $0[1] * height)) }
            strokes.append(Stroke(color: firstHand, width: notePen, points: points, times: valid.map { clock + $0[2] }))
            previousEnd = stroke.start + duration
            clock += duration
        }
        return strokes
    }

    private static func canvasPoint(_ point: CGPoint) -> DrawingPoint {
        DrawingPoint(x: point.x / canvas.width, y: point.y / canvas.height)
    }
}
