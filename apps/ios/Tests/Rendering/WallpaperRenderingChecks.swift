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
        // iPad: a square wallpaper, with the phone-shaped canvas inside the centred square that both
        // orientations show (820 pt of the 1180 pt image on an 11-inch iPad Air).
        let diagonal = DrawingStroke(id: "ipad-region", colorArgb: 0xFFFF0000, width: 1 / 461.25,
                                     points: [.init(x: 0, y: 0), .init(x: 1, y: 1)])
        let squareWallpaper = DoodleRenderer.wallpaper(background: background, strokes: [diagonal], dry: false,
            pixelSize: CGSize(width: 2360, height: 2360), displayScale: 2,
            canvasSize: CGSize(width: 461.25, height: 820), canvasRegion: CGSize(width: 820, height: 820))
        precondition(squareWallpaper.size == CGSize(width: 2360, height: 2360), "The iPad wallpaper must stay square")
        let regionInk = inkBounds(squareWallpaper)
        print("iPad region ink bounds: \(regionInk)")
        // Canvas in pixels: x 718.75...1641.25, y 360...2000; the line's round caps add a little.
        precondition(abs(regionInk.minX - 718.75) <= 4 && abs(regionInk.maxX - 1641.25) <= 4, "Canvas left the centred square horizontally")
        precondition(abs(regionInk.minY - 360) <= 4 && abs(regionInk.maxY - 2000) <= 4, "Canvas left the centred square vertically")
        var checks = 1
        // A non-square, partly transparent sticker exercises aspect fitting and alpha.
        let kitten = StickerReference(packKey: "spring-kittens", packVersion: 1, stickerId: "1")
        let stickerFormat = UIGraphicsImageRendererFormat()
        stickerFormat.scale = 1; stickerFormat.opaque = false; stickerFormat.preferredRange = .standard
        let images = [kitten: UIGraphicsImageRenderer(size: CGSize(width: 300, height: 200), format: stickerFormat).image {
            UIColor.clear.setFill(); $0.fill(CGRect(x: 0, y: 0, width: 300, height: 200))
            UIColor.systemTeal.setFill(); $0.cgContext.fillEllipse(in: CGRect(x: 10, y: 10, width: 280, height: 180))
        }]
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
                    // Text paints above strokes; include rotation, scale and a pill.
                    let elements: [CanvasElement] = [
                        .text(.init(id: "label", text: "Hello\nlove", center: .init(x: 0.5, y: 0.4), rotationRad: 0.4,
                                    scale: 1.6, colorArgb: 0xFFFFE66D, backgroundPillEnabled: true, alignment: .left)),
                        .sticker(.init(id: "kitten", center: .init(x: 0.6, y: 0.75), rotationRad: -0.5, scale: 0.4, sticker: kitten)),
                        .sticker(.init(id: "loading", center: .init(x: 0.2, y: 0.2), sticker: .init(packKey: "p", packVersion: 1, stickerId: "missing"))),
                        .text(.init(id: "plain", text: "xo", center: .init(x: 0.3, y: 0.7), colorArgb: 0xFF5BB7E8))]
                    let format = UIGraphicsImageRendererFormat()
                    format.scale = scale; format.opaque = true; format.preferredRange = .standard
                    let canvas = UIGraphicsImageRenderer(size: size, format: format).image {
                        UIColor.black.setFill(); $0.fill(CGRect(origin: .zero, size: size))
                        DoodleRenderer.draw(strokes, elements: elements, stickerImages: images, dry: dry, in: $0.cgContext, size: size)
                    }
                    let pixelSize = CGSize(width: size.width * scale, height: size.height * scale)
                    let wallpaper = DoodleRenderer.wallpaper(background: background, strokes: strokes, elements: elements, stickerImages: images, dry: dry,
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
                        DoodleRenderer.draw(strokes, elements: elements, stickerImages: images, dry: dry, in: $0.cgContext, size: appSize)
                    }
                    let fitted = DoodleRenderer.wallpaper(background: background, strokes: strokes, elements: elements, stickerImages: images, dry: dry,
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
        // The eyedropper samples text upright: the pill fill sits left of the
        // first glyph on an unrotated left-aligned label.
        // Stickers keep their aspect ratio with the long edge at scale x canvas width.
        let wide = CanvasStickerGeometry.size(.init(center: .init(x: 0.5, y: 0.5), scale: 0.5, sticker: kitten), image: images[kitten], canvasWidth: 400)
        precondition(wide == CGSize(width: 200, height: 200 / 1.5), "Sticker size must follow Android's long-edge rule")
        let placed = CanvasStickerElement(center: .init(x: 0.5, y: 0.5), rotationRad: .pi / 2, scale: 0.5, sticker: kitten)
        precondition(DoodleRenderer.color(at: CGPoint(x: 201, y: 300), strokes: [], elements: [.sticker(placed)], stickerImages: images,
                                          dry: false, size: canvasSize, background: .black) != 0xFF000000, "Eyedropper must sample stickers")
        precondition(CanvasElementRenderer(stickerImages: images).hit([.sticker(placed)], at: CGPoint(x: 201, y: 300 + 90), canvasSize: canvasSize) != nil,
                     "Rotated sticker hit area follows its rotation")
        let pill = CanvasTextElement(text: "Hi", center: .init(x: 0.5, y: 0.5), colorArgb: 0xFF2457D6, backgroundPillEnabled: true, alignment: .left)
        let layout = CanvasTextLayout(element: pill, canvasWidth: 402)
        let body = layout.bodyRect(pill: true)
        let edge = CGPoint(x: 201 + body.minX + 4, y: 300)
        precondition(DoodleRenderer.color(at: edge, strokes: [], elements: [.text(pill)], dry: false, size: canvasSize, background: .black) == 0xFF2457D6,
                     "Eyedropper must sample text pills")
        precondition(CanvasElementRenderer().hit([.text(pill)], at: CGPoint(x: 201 + body.midX, y: 300), canvasSize: canvasSize)?.id == pill.id)
        precondition(CanvasElementRenderer().hit([.text(pill)], at: CGPoint(x: 20, y: 20), canvasSize: canvasSize) == nil)
        checks += 1
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
