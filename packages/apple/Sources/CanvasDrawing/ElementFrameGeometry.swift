import CoreGraphics
import Foundation
#if canImport(ProductCore)
import ProductCore
#endif

/// What a selection frame can move, resize and rotate: text and stickers alike.
public protocol CanvasTransformable: Equatable {
    var center: DrawingPoint { get set }
    var scale: Double { get set }
    var rotationRad: Double { get set }
    static var scaleRange: ClosedRange<Double> { get }
}

extension CanvasTextElement: CanvasTransformable {}
extension CanvasStickerElement: CanvasTransformable {}

/// Where a text or sticker sits on screen: its centre, its visible body around that centre
/// before rotation (already scaled to screen points), and its rotation. The Mac's and the iPad's
/// selection frames and floating bars are placed from it.
public struct ElementFrameGeometry: Equatable, Sendable {
    public var center: CGPoint
    public var body: CGRect
    public var angle: Double

    public init(center: CGPoint, body: CGRect, angle: Double) {
        self.center = center
        self.body = body
        self.angle = angle
    }

    /// Where a text sits. Text with nothing typed yet takes the height of one line at its wrap width.
    public static func text(_ text: CanvasTextElement, layout: CanvasTextLayout, canvasSize: CGSize,
                            zoom: CGFloat = 1, offset: CGPoint = .zero) -> ElementFrameGeometry {
        let scale = CGFloat(text.scale) * zoom
        var body = layout.bodyRect(pill: text.backgroundPillEnabled)
        if layout.tightBounds.isEmpty {
            let height = CanvasTextStyle.baseSize * 1.3
            body = CGRect(x: -layout.size.width / 2, y: -height / 2, width: layout.size.width, height: height)
        }
        return ElementFrameGeometry(
            center: CGPoint(x: text.center.x * canvasSize.width * zoom + offset.x, y: text.center.y * canvasSize.height * zoom + offset.y),
            body: CGRect(x: body.minX * scale, y: body.minY * scale, width: body.width * scale, height: body.height * scale),
            angle: text.rotationRad
        )
    }

    /// Where a sticker sits, at its drawn size.
    public static func sticker(_ sticker: CanvasStickerElement, image: CanvasImage?, canvasSize: CGSize,
                               zoom: CGFloat = 1, offset: CGPoint = .zero) -> ElementFrameGeometry {
        let size = CanvasStickerGeometry.size(sticker, image: image, canvasWidth: canvasSize.width)
        let width = size.width * zoom, height = size.height * zoom
        return ElementFrameGeometry(
            center: CGPoint(x: sticker.center.x * canvasSize.width * zoom + offset.x, y: sticker.center.y * canvasSize.height * zoom + offset.y),
            body: CGRect(x: -width / 2, y: -height / 2, width: width, height: height),
            angle: sticker.rotationRad
        )
    }

    /// The body's own centre on screen, after rotation about the element's centre.
    public var bodyCenter: CGPoint { screenPoint(CGPoint(x: body.midX, y: body.midY)) }

    /// The body's corners on screen, clockwise from the top left as drawn upright.
    public var corners: [CGPoint] {
        [CGPoint(x: body.minX, y: body.minY), CGPoint(x: body.maxX, y: body.minY),
         CGPoint(x: body.maxX, y: body.maxY), CGPoint(x: body.minX, y: body.maxY)].map(screenPoint)
    }

    /// The upright box that holds the rotated body, for placing the format bar.
    public var bounds: CGRect {
        let points = corners
        let xs = points.map(\.x), ys = points.map(\.y)
        return CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
    }

    /// A screen point in the body's own upright coordinates, around the element's centre.
    public func local(_ screen: CGPoint) -> CGPoint {
        let dx = screen.x - center.x, dy = screen.y - center.y
        let cosine = cos(angle), sine = sin(angle)
        return CGPoint(x: dx * cosine + dy * sine, y: -dx * sine + dy * cosine)
    }

    /// A point in the body's own upright coordinates, on screen.
    public func screenPoint(_ local: CGPoint) -> CGPoint {
        let cosine = cos(angle), sine = sin(angle)
        return CGPoint(x: center.x + local.x * cosine - local.y * sine, y: center.y + local.x * sine + local.y * cosine)
    }
}

/// Rotation settles level, or on a quarter turn, when it comes within a few degrees, as the
/// iPhone's rotation slider settles level near zero.
public enum ElementRotation {
    public static let settleTolerance = 3 * Double.pi / 180

    /// The quarter turn nearest `angle`, if it is within `tolerance` of it.
    public static func quarterTurn(near angle: Double, within tolerance: Double = settleTolerance) -> Double? {
        let quarter = Double.pi / 2
        let nearest = (angle / quarter).rounded() * quarter
        return abs(angle - nearest) <= tolerance ? nearest : nil
    }

    /// Whether `angle` sits exactly on a quarter turn.
    public static func isSquare(_ angle: Double) -> Bool { quarterTurn(near: angle, within: 1e-9) != nil }
}
