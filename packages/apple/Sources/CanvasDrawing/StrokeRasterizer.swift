import CoreGraphics
#if canImport(ProductCore)
import ProductCore
#endif

/// Finished strokes drawn once into a bitmap and kept, so a canvas shows one image instead of
/// redrawing every stroke each frame. Strokes added after the ones already drawn are painted over
/// them; any other change (an erase, an undo, a clear, a new size or brush) redraws them all.
/// It runs away from the main thread, so even a full redraw does not hold up drawing.
public actor StrokeRasterizer {
    /// What the bitmap should show: strokes in painting order, on a canvas of a size in points.
    public struct Request: Equatable, Sendable {
        public var strokes: [DrawingStroke]
        public var size: CGSize
        /// Pixels per point.
        public var scale: CGFloat
        public var dry: Bool

        public init(strokes: [DrawingStroke], size: CGSize, scale: CGFloat, dry: Bool) {
            self.strokes = strokes; self.size = size; self.scale = scale; self.dry = dry
        }

        /// Whether a bitmap of the other request can be drawn on to give this one.
        func extends(_ other: Request) -> Bool {
            size == other.size && scale == other.scale && dry == other.dry && strokes.starts(with: other.strokes)
        }
    }

    /// A bitmap, and what it shows.
    public struct Raster: Sendable {
        public let image: CGImage
        public let request: Request
    }

    private var context: CGContext?
    private var drawn: Request?

    public init() {}

    public func render(_ request: Request) -> Raster? {
        if let drawn, request.extends(drawn), let context {
            DoodleRenderer.draw(Array(request.strokes.dropFirst(drawn.strokes.count)), dry: request.dry, in: context, size: request.size)
        } else {
            guard let context = Self.context(size: request.size, scale: request.scale) else { return nil }
            DoodleRenderer.draw(request.strokes, dry: request.dry, in: context, size: request.size)
            self.context = context
        }
        drawn = request
        return context?.makeImage().map { Raster(image: $0, request: request) }
    }

    /// A transparent bitmap drawn in points with a top-left origin, as UIKit draws.
    private static func context(size: CGSize, scale: CGFloat) -> CGContext? {
        let width = Int((size.width * scale).rounded(.up)), height = Int((size.height * scale).rounded(.up))
        guard width > 0, height > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: scale, y: -scale)
        return context
    }
}
