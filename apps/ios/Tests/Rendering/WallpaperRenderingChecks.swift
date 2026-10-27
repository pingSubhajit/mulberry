import UIKit

/// Runs against UIKit on an iOS simulator. Compares the real canvas drawing
/// path with the exported PNG at the same physical size, not a mock renderer.
@main
struct WallpaperRenderingChecks {
    @MainActor static func main() throws {
        let bgFormat = UIGraphicsImageRendererFormat()
        bgFormat.scale = 1; bgFormat.opaque = true; bgFormat.preferredRange = .standard
        let background = UIGraphicsImageRenderer(size: CGSize(width: 16, height: 16), format: bgFormat).image {
            UIColor.black.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 16, height: 16))
        }
        var checks = 0
        for (size, scale) in [(CGSize(width: 402, height: 874), CGFloat(3)),
                              (CGSize(width: 393, height: 852), CGFloat(3)),
                              (CGSize(width: 414, height: 896), CGFloat(2))] {
            for dry in [false, true] {
                for width in [4.0, 10.0, 42.0] {
                    let points = (0..<80).map { i in
                        DrawingPoint(x: 0.2 + Double(i) / 140, y: 0.5 + sin(Double(i) / 12) * 0.25)
                    }
                    let strokes = [DrawingStroke(id: "render-regression", colorArgb: 0xFFB31329, width: width / 1080, points: points),
                                   DrawingStroke(id: "single-point", colorArgb: 0xFFFFE66D, width: width / 1080, points: [.init(x: 0.2, y: 0.2)])]
                    let format = UIGraphicsImageRendererFormat()
                    format.scale = scale; format.opaque = true; format.preferredRange = .standard
                    let canvas = UIGraphicsImageRenderer(size: size, format: format).image {
                        UIColor.black.setFill(); $0.fill(CGRect(origin: .zero, size: size))
                        DoodleRenderer.draw(strokes, dry: dry, in: $0.cgContext, size: size)
                    }
                    let pixelSize = CGSize(width: size.width * scale, height: size.height * scale)
                    let wallpaper = DoodleRenderer.wallpaper(background: background, strokes: strokes, dry: dry,
                                                            pixelSize: pixelSize, displayScale: scale)
                    precondition(wallpaper.scale == 1 && wallpaper.size == pixelSize, "Export must describe physical pixels")
                    // Check the file Shortcuts receives, including PNG serialization.
                    let decoded = UIImage(data: wallpaper.pngData()!)!
                    precondition(decoded.cgImage!.width == Int(pixelSize.width))
                    precondition(decoded.cgImage!.height == Int(pixelSize.height))
                    precondition(pixels(canvas) == pixels(decoded), "Canvas/export pixels differ: scale=\(scale), dry=\(dry), width=\(width)")
                    checks += 1
                }
            }
        }
        print("Passed \(checks) canvas-to-wallpaper pixel comparisons at 2x and 3x, including PNG round trips.")
    }

    static func pixels(_ image: UIImage) -> [UInt8] {
        let cg = image.cgImage!
        var bytes = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
        bytes.withUnsafeMutableBytes {
            let context = CGContext(data: $0.baseAddress, width: cg.width, height: cg.height,
                                    bitsPerComponent: 8, bytesPerRow: cg.width * 4,
                                    space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
        }
        return bytes
    }
}
