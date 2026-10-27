import UIKit

/// Stroke math follows Android's RoundStrokeRenderer and DryBrushStrokeRenderer.
/// Both interactive drawing and wallpaper export use this renderer.
enum DoodleRenderer {
    static func draw(_ strokes: [DrawingStroke], dry: Bool, in context: CGContext, size: CGSize) {
        context.saveGState()
        context.clip(to: CGRect(origin: .zero, size: size))
        for stroke in strokes {
            let rendered = RenderStroke(id: stroke.id, colorArgb: stroke.colorArgb,
                width: CGFloat(stroke.width) * min(max(size.width, 1), max(size.height, 1)),
                points: stroke.points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) })
            if dry { DryBrushStrokeRenderer.draw(stroke: rendered, in: context) }
            else { RoundStrokeRenderer.draw(stroke: rendered, in: context) }
        }
        context.restoreGState()
    }

    static func wallpaper(background: UIImage, strokes: [DrawingStroke], dry: Bool, pixelSize: CGSize, displayScale: CGFloat, canvasSize: CGSize? = nil) -> UIImage {
        // UIKit draws the canvas in points, then rasterizes at the screen scale.
        // Keep that coordinate space here too: using pixels as points changes
        // dry-brush minimum widths, bristle count and even its random seed.
        let size = CGSize(width: pixelSize.width / displayScale, height: pixelSize.height / displayScale)
        let format = UIGraphicsImageRendererFormat(); format.scale = displayScale; format.opaque = true
        format.preferredRange = .standard
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { renderer in
            renderer.cgContext.interpolationQuality = .high
            UIColor.black.setFill(); renderer.fill(CGRect(origin: .zero, size: size))
            let scale = max(size.width / background.size.width, size.height / background.size.height)
            let fitted = CGSize(width: background.size.width * scale, height: background.size.height * scale)
            background.draw(in: CGRect(x: (size.width - fitted.width) / 2, y: (size.height - fitted.height) / 2, width: fitted.width, height: fitted.height))
            let placement = CanvasGeometry.placement(canvas: canvasSize, inside: size)
            renderer.cgContext.saveGState()
            renderer.cgContext.translateBy(x: placement.minX, y: placement.minY)
            // Render in the original point space before scaling so dry-brush
            // seeds, bristle counts and minimum widths match the app too.
            let source = canvasSize.flatMap { CanvasGeometry.valid($0) ? $0 : nil } ?? size
            let strokeScale = placement.width / source.width
            renderer.cgContext.scaleBy(x: strokeScale, y: strokeScale)
            draw(strokes, dry: dry, in: renderer.cgContext, size: source)
            renderer.cgContext.restoreGState()
        }
        // Export pixels, not UIKit's point-size/DPI hint. This changes metadata
        // only; the full-resolution bitmap is passed to Shortcuts unchanged.
        return UIImage(cgImage: rendered.cgImage!, scale: 1, orientation: .up)
    }

    static func color(at point: CGPoint, strokes: [DrawingStroke], dry: Bool, size: CGSize, background: UIColor) -> UInt32? {
        var pixel = [UInt8](repeating: 0, count: 4)
        let success = pixel.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.setFillColor(background.cgColor); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
            context.translateBy(x: -point.x, y: -point.y)
            draw(strokes, dry: dry, in: context, size: size)
            return true
        }
        guard success else { return nil }
        return 0xFF000000 | UInt32(pixel[0]) << 16 | UInt32(pixel[1]) << 8 | UInt32(pixel[2])
    }
}

private enum RoundStrokeRenderer {
    static func draw(stroke: RenderStroke, in context: CGContext) {
        guard !stroke.points.isEmpty else { return }
        context.setStrokeColor(UIColor(argb: stroke.colorArgb).cgColor)
        context.setFillColor(UIColor(argb: stroke.colorArgb).cgColor)
        context.setLineWidth(stroke.width)
        context.setLineCap(.round)
        context.setLineJoin(.round)

        if stroke.points.count == 1, let point = stroke.points.first {
            context.fillEllipse(in: CGRect(
                x: point.x - stroke.width / 2,
                y: point.y - stroke.width / 2,
                width: stroke.width,
                height: stroke.width
            ))
            return
        }

        context.beginPath()
        context.move(to: stroke.points[0])
        stroke.points.dropFirst().forEach { context.addLine(to: $0) }
        context.strokePath()
    }
}

private enum DryBrushStrokeRenderer {
    static func draw(stroke: RenderStroke, in context: CGContext) {
        guard stroke.points.count >= 2 else {
            RoundStrokeRenderer.draw(stroke: stroke, in: context)
            return
        }
        drawBody(stroke: stroke, in: context)
        drawBristles(stroke: stroke, in: context)
    }

    private static func drawBody(stroke: RenderStroke, in context: CGContext) {
        context.saveGState()
        context.setStrokeColor(UIColor(argb: stroke.colorArgb).withAlphaComponent(132 / 255).cgColor)
        context.setLineCap(.square)
        context.setLineJoin(.round)
        context.setLineWidth(max(1.5, stroke.width * 0.42))
        context.addPath(smoothPath(points: stroke.points))
        context.strokePath()
        context.restoreGState()
    }

    private static func drawBristles(stroke: RenderStroke, in context: CGContext) {
        let bristleCount = Int((stroke.width / 2.6).rounded()).clamped(to: 6...14)
        let bristleWidth = max(0.85, stroke.width / (CGFloat(bristleCount) * 1.75))
        let halfWidth = stroke.width / 2

        for index in 0..<bristleCount {
            let lane = bristleCount == 1
                ? 0
                : -halfWidth + ((CGFloat(index) / CGFloat(bristleCount - 1)) * stroke.width)
            let seed = stroke.seed(index: index)
            let jitter = (CGFloat(seed & 0xFF) / 255 - 0.5) * stroke.width * 0.24
            let alpha = CGFloat(164 + ((seed >> 8) % 70)) / 255
            let widthJitter = 0.78 + (CGFloat((seed >> 16) & 0xFF) / 255) * 0.7

            context.saveGState()
            context.setStrokeColor(UIColor(argb: stroke.colorArgb).withAlphaComponent(alpha).cgColor)
            context.setLineCap(.square)
            context.setLineJoin(.round)
            context.setLineWidth(bristleWidth * widthJitter)
            context.addPath(bristlePath(
                points: stroke.points,
                laneOffset: lane + jitter,
                seed: seed,
                strokeWidth: stroke.width
            ))
            context.strokePath()
            context.restoreGState()
        }
    }

    private static func smoothPath(points: [CGPoint]) -> CGPath {
        let path = CGMutablePath()
        path.move(to: points[0])
        if points.count > 2 {
            for index in 1..<(points.count - 1) {
                let point = points[index]
                let next = points[index + 1]
                path.addQuadCurve(to: CGPoint(x: (point.x + next.x) / 2, y: (point.y + next.y) / 2), control: point)
            }
        }
        path.addLine(to: points[points.count - 1])
        return path
    }

    private static func bristlePath(points: [CGPoint], laneOffset: CGFloat, seed: Int, strokeWidth: CGFloat) -> CGPath {
        let path = CGMutablePath()
        var drawing = false
        let startTrim = seed % 3
        let endTrim = (seed >> 3) % 4

        for index in points.indices {
            let point = points[index]
            let noise = seededNoise(seed: seed, index: index)
            let shouldGap = index > startTrim &&
                index < points.count - 1 - endTrim &&
                noise < 0.085
            if shouldGap {
                drawing = false
                continue
            }

            let normal = normalAt(index: index, points: points)
            let tangent = tangentAt(index: index, points: points)
            let feather = (seededNoise(seed: seed ^ 0x6D2B79F5, index: index) - 0.5) * strokeWidth * 0.18
            let next = CGPoint(
                x: point.x + normal.dx * laneOffset + tangent.dx * feather,
                y: point.y + normal.dy * laneOffset + tangent.dy * feather
            )
            if !drawing {
                path.move(to: next)
                drawing = true
            } else {
                path.addLine(to: next)
            }
        }

        return path
    }

    private static func normalAt(index: Int, points: [CGPoint]) -> CGVector {
        let tangent = tangentAt(index: index, points: points)
        return CGVector(dx: -tangent.dy, dy: tangent.dx)
    }

    private static func tangentAt(index: Int, points: [CGPoint]) -> CGVector {
        let previous = points[max(0, index - 1)]
        let next = points[min(points.count - 1, index + 1)]
        let dx = next.x - previous.x
        let dy = next.y - previous.y
        let distance = hypot(dx, dy)
        let length = distance > 0.001 ? distance : 1
        return CGVector(dx: dx / length, dy: dy / length)
    }

    private static func seededNoise(seed: Int, index: Int) -> CGFloat {
        let mixed = Int32(truncatingIfNeeded: seed ^ Int(truncatingIfNeeded: Int32(index) &* Int32(bitPattern: 0x9E3779B9))).mixed
        return CGFloat((UInt32(bitPattern: mixed) >> 1) & 0x7FFFFFFF) / CGFloat(Int32.max)
    }
}

private struct RenderStroke {
    let id: String
    let colorArgb: UInt32
    let width: CGFloat
    let points: [CGPoint]

    func seed(index: Int) -> Int {
        var hash = Int32(0x45D9F3B)
        for scalar in id.utf16 {
            hash = (hash &* 31) ^ Int32(scalar)
        }
        hash = (hash &* 31) ^ Int32(bitPattern: colorArgb)
        hash = (hash &* 31) ^ Int32(bitPattern: widthFloatBits)
        hash = (hash &* 31) ^ Int32(index)
        return Int(UInt32(bitPattern: hash.mixed) & 0x7FFFFFFF)
    }

    private var widthFloatBits: UInt32 {
        Float(width).bitPattern
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

private extension Int32 {
    var mixed: Int32 {
        var value = self
        value = value ^ Int32(bitPattern: UInt32(bitPattern: value) >> 16)
        value = value &* Int32(bitPattern: 0x7FEB352D)
        value = value ^ Int32(bitPattern: UInt32(bitPattern: value) >> 15)
        value = value &* Int32(bitPattern: 0x846CA68B)
        value = value ^ Int32(bitPattern: UInt32(bitPattern: value) >> 16)
        return value
    }
}
