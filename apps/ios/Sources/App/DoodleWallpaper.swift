import UIKit

/// The iPhone and iPad's wallpaper export and colour sampling, on the shared canvas renderer.
extension DoodleRenderer {
    static func wallpaper(background: UIImage, strokes: [DrawingStroke], elements: [CanvasElement] = [],
                          stickerImages: [StickerReference: UIImage] = [:], dry: Bool, pixelSize: CGSize, displayScale: CGFloat, canvasSize: CGSize? = nil,
                          canvasRegion: CGSize? = nil) -> UIImage {
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
            // The canvas fits a centred region when only part of the image is always on screen.
            let region = canvasRegion.map { CGRect(x: (size.width - $0.width) / 2, y: (size.height - $0.height) / 2,
                                                   width: $0.width, height: $0.height) } ?? CGRect(origin: .zero, size: size)
            let placement = CanvasGeometry.placement(canvas: canvasSize, inside: region.size)
                .offsetBy(dx: region.minX, dy: region.minY)
            renderer.cgContext.saveGState()
            renderer.cgContext.translateBy(x: placement.minX, y: placement.minY)
            // Render in the original point space before scaling so dry-brush
            // seeds, bristle counts and minimum widths match the app too.
            let source = canvasSize.flatMap { CanvasGeometry.valid($0) ? $0 : nil } ?? size
            let strokeScale = placement.width / source.width
            renderer.cgContext.scaleBy(x: strokeScale, y: strokeScale)
            draw(strokes, elements: elements, stickerImages: stickerImages, dry: dry, in: renderer.cgContext, size: source)
            renderer.cgContext.restoreGState()
        }
        // Export pixels, not UIKit's point-size/DPI hint. This changes metadata
        // only; the full-resolution bitmap is passed to Shortcuts unchanged.
        return UIImage(cgImage: rendered.cgImage!, scale: 1, orientation: .up)
    }

    static func color(at point: CGPoint, strokes: [DrawingStroke], elements: [CanvasElement] = [], stickerImages: [StickerReference: UIImage] = [:],
                      dry: Bool, size: CGSize, background: UIColor) -> UInt32? {
        var pixel = [UInt8](repeating: 0, count: 4)
        let success = pixel.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.setFillColor(background.cgColor); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
            // Use UIKit's top-left origin so glyphs are sampled upright.
            context.translateBy(x: 0, y: 1); context.scaleBy(x: 1, y: -1)
            context.translateBy(x: -point.x, y: -point.y)
            draw(strokes, elements: elements, stickerImages: stickerImages, dry: dry, in: context, size: size)
            return true
        }
        guard success else { return nil }
        return 0xFF000000 | UInt32(pixel[0]) << 16 | UInt32(pixel[1]) << 8 | UInt32(pixel[2])
    }
}
