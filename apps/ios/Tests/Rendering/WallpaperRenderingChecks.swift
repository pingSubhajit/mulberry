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
        // The app canvas is shorter than the screen because the header and tools
        // take space. A square in that canvas must stay square on the wallpaper.
        let canvasSize = CGSize(width: 402, height: 600)
        let square = DrawingStroke(id: "aspect-regression", colorArgb: 0xFFFF0000, width: 2 / 402,
            points: [.init(x: 0.25, y: 0.25), .init(x: 0.75, y: 0.25),
                     .init(x: 0.75, y: 0.25 + 201.0 / 600), .init(x: 0.25, y: 0.25 + 201.0 / 600),
                     .init(x: 0.25, y: 0.25)])
        let squareExport = DoodleRenderer.wallpaper(background: background, strokes: [square], dry: false,
            pixelSize: CGSize(width: 1206, height: 2622), displayScale: 3, canvasSize: canvasSize)
        let bounds = inkBounds(squareExport)
        print("Square export bounds: \(bounds.width) x \(bounds.height); canvas: \(canvasSize)")
        precondition(abs(bounds.width - bounds.height) <= 1, "Wallpaper stretched a square from the shorter app canvas")
        // Golden dimensions from Android's authored-canvas placement test.
        let androidPlacement = CanvasGeometry.placement(canvas: CGSize(width: 774, height: 1134),
                                                         inside: CGSize(width: 768, height: 1688))
        precondition(abs(androidPlacement.minX) < 0.001 && abs(androidPlacement.minY - 281.3954) < 0.001)
        precondition(abs(androidPlacement.width - 768) < 0.001 && abs(androidPlacement.height - 1125.2092) < 0.001)
        let tallPlacement = CanvasGeometry.placement(canvas: CGSize(width: 300, height: 1000),
                                                      inside: CGSize(width: 400, height: 800))
        precondition(tallPlacement == CGRect(x: 80, y: 0, width: 240, height: 800))
        precondition(CanvasGeometry.placement(canvas: .zero, inside: canvasSize) == CGRect(origin: .zero, size: canvasSize))
        var checks = 1
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

                    // Exercise the real mismatch: the app has a header and toolbars,
                    // while the wallpaper fills the screen. Compare the complete
                    // shorter canvas against its centered region in the PNG.
                    let appSize = CGSize(width: size.width, height: size.height - 200)
                    let appCanvas = UIGraphicsImageRenderer(size: appSize, format: format).image {
                        UIColor.black.setFill(); $0.fill(CGRect(origin: .zero, size: appSize))
                        DoodleRenderer.draw(strokes, dry: dry, in: $0.cgContext, size: appSize)
                    }
                    let fitted = DoodleRenderer.wallpaper(background: background, strokes: strokes, dry: dry,
                        pixelSize: pixelSize, displayScale: scale, canvasSize: appSize)
                    let decodedFit = UIImage(data: fitted.pngData()!)!
                    let bytes = pixels(decodedFit)
                    let rowBytes = Int(pixelSize.width) * 4
                    let first = Int(100 * scale) * rowBytes
                    let count = Int(appSize.height * scale) * rowBytes
                    let appPixels = pixels(appCanvas), fittedPixels = Array(bytes[first..<(first + count)])
                    var maxDifference = 0, totalDifference = 0
                    for (a, b) in zip(appPixels, fittedPixels) {
                        let delta = abs(Int(a) - Int(b))
                        maxDifference = max(maxDifference, delta); totalDifference += delta
                    }
                    // Translation changes float rounding at a few antialiased
                    // edges, including overlapping translucent bristles. Allow
                    // at most 2/255 there, with <0.001% total channel error.
                    precondition(maxDifference <= 2 && totalDifference <= count / 100_000,
                        "Short app canvas/export pixels differ: scale=\(scale), dry=\(dry), width=\(width)")
                    checks += 1
                }
            }
        }
        print("Passed \(checks) canvas-to-wallpaper pixel comparisons at 2x and 3x, including shorter app canvases, PNG round trips and Android placement fixtures.")
    }

    static func inkBounds(_ image: UIImage) -> CGRect {
        let bytes = pixels(image), width = image.cgImage!.width, height = image.cgImage!.height
        var minX = width, minY = height, maxX = -1, maxY = -1
        for y in 0..<height {
            for x in 0..<width where bytes[(y * width + x) * 4] > 32 {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
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
