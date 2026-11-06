import CoreGraphics

/// The Mulberry mark, from BrandMark.svg (a 37 × 61 view box): the leaf, and the berry's two
/// halves. Drawn as paths so it tints and scales cleanly.
enum MulberryMark {
    static let viewBox = CGSize(width: 37, height: 61)

    // CGPath is immutable once built; Core Graphics just doesn't mark it Sendable.
    /// The leaf, filled with the non-zero rule.
    nonisolated(unsafe) static let leaf: CGPath = {
        let p = CGMutablePath()
        p.move(to: .init(x: 9.11417, y: 3.27085))
        p.addCurve(to: .init(x: 6.06208, y: 9.82282), control1: .init(x: 7.07981, y: 5.07258), control2: .init(x: 6.27528, y: 7.52225))
        p.addCurve(to: .init(x: 11.8877, y: 18.7668), control1: .init(x: 5.69559, y: 13.7775), control2: .init(x: 8.07813, y: 17.6442))
        p.addCurve(to: .init(x: 17.8453, y: 19.0728), control1: .init(x: 14.3032, y: 19.4786), control2: .init(x: 16.5687, y: 19.4396))
        p.addCurve(to: .init(x: 30.0323, y: 3.27085), control1: .init(x: 20.5682, y: 18.3858), control2: .init(x: 27.5283, y: 14.691))
        p.addCurve(to: .init(x: 9.11417, y: 3.27085), control1: .init(x: 20.3214, y: -2.65297), control2: .init(x: 12.0407, y: 0.802589))
        p.closeSubpath()
        return p
    }()

    /// The berry, filled with the even-odd rule.
    nonisolated(unsafe) static let berry: CGPath = {
        let p = CGMutablePath()
        p.move(to: .init(x: 29.6234, y: 32.4553))
        p.addCurve(to: .init(x: 36.1098, y: 37.1829), control1: .init(x: 33.2053, y: 31.046), control2: .init(x: 37.1773, y: 33.4847))
        p.addLine(to: .init(x: 32.4652, y: 49.8059))
        p.addCurve(to: .init(x: 16.6234, y: 60.9709), control1: .init(x: 30.4521, y: 56.7791), control2: .init(x: 23.8675, y: 61.4203))
        p.addCurve(to: .init(x: 2.28262, y: 47.9329), control1: .init(x: 9.37926, y: 60.5215), control2: .init(x: 3.41819, y: 55.1016))
        p.addCurve(to: .init(x: 5.76309, y: 41.845), control1: .init(x: 1.87132, y: 45.3384), control2: .init(x: 3.31873, y: 42.8069))
        p.addLine(to: .init(x: 29.6234, y: 32.4553))
        p.closeSubpath()
        p.move(to: .init(x: 26.8744, y: 15.7112))
        p.addCurve(to: .init(x: 32.5707, y: 20.2942), control1: .init(x: 28.9962, y: 16.8067), control2: .init(x: 30.9413, y: 18.3366))
        p.addCurve(to: .init(x: 29.6342, y: 28.4817), control1: .init(x: 34.9457, y: 23.1477), control2: .init(x: 33.0888, y: 27.1221))
        p.addLine(to: .init(x: 5.50919, y: 37.9768))
        p.addCurve(to: .init(x: 0.22696, y: 34.9563), control1: .init(x: 3.18794, y: 38.8903), control2: .init(x: 0.617004, y: 37.4201))
        p.addCurve(to: .init(x: 5.82657, y: 18.635), control1: .init(x: -0.728423, y: 28.924), control2: .init(x: 1.36914, y: 22.8103))
        p.addCurve(to: .init(x: 6.25821, y: 18.2434), control1: .init(x: 5.96887, y: 18.5017), control2: .init(x: 6.11292, y: 18.3713))
        p.addCurve(to: .init(x: 9.89883, y: 20.8928), control1: .init(x: 7.21088, y: 19.403), control2: .init(x: 8.44168, y: 20.3309))
        p.addCurve(to: .init(x: 18.31, y: 21.6858), control1: .init(x: 13.2528, y: 22.1861), control2: .init(x: 16.5559, y: 22.1959))
        p.addCurve(to: .init(x: 26.8744, y: 15.7112), control1: .init(x: 20.1647, y: 21.2121), control2: .init(x: 23.6489, y: 19.5659))
        p.closeSubpath()
        return p
    }()

    /// Fills the mark into `rect` in a context whose y axis points down, stretched to it as the
    /// reference renderer resizes its raster.
    static func fill(in rect: CGRect, color: CGColor, context: CGContext) {
        context.saveGState()
        context.translateBy(x: rect.minX, y: rect.minY)
        context.scaleBy(x: rect.width / viewBox.width, y: rect.height / viewBox.height)
        context.setFillColor(color)
        context.addPath(leaf)
        context.fillPath(using: .winding)
        context.addPath(berry)
        context.fillPath(using: .evenOdd)
        context.restoreGState()
    }
}
