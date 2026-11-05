import SwiftUI

/// The brush width's mark, everywhere a width control appears: Apple's scribble (SF Symbols'
/// `scribble`), traced as a path so its line can be as thick as the brush. It says "line weight",
/// where a dot in the brush's colour read as a second colour button. Android draws the same path.
public struct BrushWidthMark: View {
    /// How far the brush is along its range of widths, 0...1.
    let fraction: Double
    let color: Color
    let size: CGFloat

    public init(width: Double, in range: ClosedRange<Double>, color: Color, size: CGFloat = 30) {
        let span = range.upperBound - range.lowerBound
        fraction = span > 0 ? min(max((width - range.lowerBound) / span, 0), 1) : 0
        self.color = color
        self.size = size
    }

    public var body: some View {
        BrushWidthScribble()
            .stroke(color, style: StrokeStyle(lineWidth: Self.lineWidth(for: fraction, size: size),
                                              lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
            .animation(.snappy(duration: 0.15), value: fraction)
    }

    /// 1.25...4 pt at 30 pt, in proportion at other sizes: every width looks different, and the
    /// scribble's loops stay open at the thickest.
    static func lineWidth(for fraction: Double, size: CGFloat) -> CGFloat {
        (1.25 + 2.75 * CGFloat(fraction)) * size / 30
    }
}

/// The scribble itself, in a square.
struct BrushWidthScribble: Shape {
    func path(in rect: CGRect) -> Path {
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint {
            CGPoint(x: rect.minX + rect.width * x, y: rect.minY + rect.height * y)
        }
        var path = Path()
        path.move(to: point(0.095, 0.612))
        path.addLine(to: point(0.45, 0.262))
        path.addCurve(to: point(0.68, 0.18), control1: point(0.55, 0.165), control2: point(0.63, 0.13))
        path.addCurve(to: point(0.565, 0.43), control1: point(0.73, 0.23), control2: point(0.67, 0.32))
        path.addLine(to: point(0.39, 0.645))
        path.addCurve(to: point(0.40, 0.83), control1: point(0.30, 0.76), control2: point(0.33, 0.83))
        path.addCurve(to: point(0.70, 0.61), control1: point(0.48, 0.83), control2: point(0.60, 0.70))
        path.addCurve(to: point(0.85, 0.59), control1: point(0.78, 0.535), control2: point(0.84, 0.53))
        path.addCurve(to: point(0.76, 0.80), control1: point(0.86, 0.65), control2: point(0.79, 0.73))
        path.addCurve(to: point(0.90, 0.79), control1: point(0.73, 0.88), control2: point(0.78, 0.92))
        return path
    }
}
