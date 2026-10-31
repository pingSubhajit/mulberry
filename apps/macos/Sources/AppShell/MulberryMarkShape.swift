import SwiftUI

/// The Mulberry mark's outline, from Resources/Settings/BrandMark.svg, as a shape: the glass
/// effects need a shape to be cut to, not a picture.
struct MulberryMarkShape: Shape {
    /// The artwork's own frame.
    static let size = CGSize(width: 37, height: 61)

    func path(in rect: CGRect) -> Path {
        let scale = min(rect.width / Self.size.width, rect.height / Self.size.height)
        let origin = CGPoint(x: rect.midX - Self.size.width * scale / 2, y: rect.midY - Self.size.height * scale / 2)
        func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: origin.x + x * scale, y: origin.y + y * scale) }
        var path = Path()
        path.move(to: p(9.11417, 3.27085))
        path.addCurve(to: p(6.06208, 9.82282), control1: p(7.07981, 5.07258), control2: p(6.27528, 7.52225))
        path.addCurve(to: p(11.8877, 18.7668), control1: p(5.69559, 13.7775), control2: p(8.07813, 17.6442))
        path.addCurve(to: p(17.8453, 19.0728), control1: p(14.3032, 19.4786), control2: p(16.5687, 19.4396))
        path.addCurve(to: p(30.0323, 3.27085), control1: p(20.5682, 18.3858), control2: p(27.5283, 14.691))
        path.addCurve(to: p(9.11417, 3.27085), control1: p(20.3214, -2.65297), control2: p(12.0407, 0.802589))
        path.closeSubpath()
        path.move(to: p(29.6234, 32.4553))
        path.addCurve(to: p(36.1098, 37.1829), control1: p(33.2053, 31.046), control2: p(37.1773, 33.4847))
        path.addLine(to: p(32.4652, 49.8059))
        path.addCurve(to: p(16.6234, 60.9709), control1: p(30.4521, 56.7791), control2: p(23.8675, 61.4203))
        path.addCurve(to: p(2.28262, 47.9329), control1: p(9.37926, 60.5215), control2: p(3.41819, 55.1016))
        path.addCurve(to: p(5.76309, 41.845), control1: p(1.87132, 45.3384), control2: p(3.31873, 42.8069))
        path.addLine(to: p(29.6234, 32.4553))
        path.closeSubpath()
        path.move(to: p(26.8744, 15.7112))
        path.addCurve(to: p(32.5707, 20.2942), control1: p(28.9962, 16.8067), control2: p(30.9413, 18.3366))
        path.addCurve(to: p(29.6342, 28.4817), control1: p(34.9457, 23.1477), control2: p(33.0888, 27.1221))
        path.addLine(to: p(5.50919, 37.9768))
        path.addCurve(to: p(0.22696, 34.9563), control1: p(3.18794, 38.8903), control2: p(0.617004, 37.4201))
        path.addCurve(to: p(5.82657, 18.635), control1: p(-0.728423, 28.924), control2: p(1.36914, 22.8103))
        path.addCurve(to: p(6.25821, 18.2434), control1: p(5.96887, 18.5017), control2: p(6.11292, 18.3713))
        path.addCurve(to: p(9.89883, 20.8928), control1: p(7.21088, 19.403), control2: p(8.44168, 20.3309))
        path.addCurve(to: p(18.31, 21.6858), control1: p(13.2528, 22.1861), control2: p(16.5559, 22.1959))
        path.addCurve(to: p(26.8744, 15.7112), control1: p(20.1647, 21.2121), control2: p(23.6489, 19.5659))
        path.closeSubpath()
        return path
    }
}
