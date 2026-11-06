import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import ProductCore
@testable import ShareFrames

private let sample = "Look what Aarav drew on my lock screen"
private let long = "Look what my favourite person drew on my lock screen today!!"

/// Rendered images land here, to be looked at beside the reference previews.
let renderOutput = URL(fileURLWithPath: "/tmp/mulberry-share-frames", isDirectory: true)

func fonts() throws -> ShareFrameFonts { try ShareFrameFonts(directory: fixture("fonts")) }

func image(_ path: String) throws -> CGImage {
    try #require(ShareFrameRenderer.decodeImage(Data(contentsOf: fixture(path))))
}

/// An image's pixels as 8-bit sRGB RGBA.
struct Pixels {
    let width: Int
    let height: Int
    let bytes: [UInt8]

    init(_ image: CGImage) {
        width = image.width; height = image.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        bytes.withUnsafeMutableBytes { buffer in
            let context = CGContext(data: buffer.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8,
                                    bytesPerRow: image.width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        self.bytes = bytes
    }

    func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
        let i = (y * width + x) * 4
        return (Int(bytes[i]), Int(bytes[i + 1]), Int(bytes[i + 2]))
    }
}

/// How far apart two same-size images are.
struct Difference {
    /// Mean absolute difference per RGB channel, 0–255.
    let mean: Double
    /// Share of pixels whose largest channel difference is over 48.
    let changed: Double
    /// The largest mean difference of any 16 × 16 tile: a misplaced block shows here even when the
    /// whole image's mean stays low.
    let worstTile: Double
    let worstTileOrigin: (x: Int, y: Int)

    init(_ a: Pixels, _ b: Pixels, tile: Int = 16) {
        precondition(a.width == b.width && a.height == b.height)
        var total = 0, changed = 0
        let tilesX = (a.width + tile - 1) / tile, tilesY = (a.height + tile - 1) / tile
        var tiles = [Int](repeating: 0, count: tilesX * tilesY)
        var counts = [Int](repeating: 0, count: tilesX * tilesY)
        for y in 0..<a.height {
            for x in 0..<a.width {
                let (r1, g1, b1) = a.rgb(x, y), (r2, g2, b2) = b.rgb(x, y)
                let d = (abs(r1 - r2), abs(g1 - g2), abs(b1 - b2))
                let sum = d.0 + d.1 + d.2
                total += sum
                if max(d.0, d.1, d.2) > 48 { changed += 1 }
                tiles[(y / tile) * tilesX + x / tile] += sum
                counts[(y / tile) * tilesX + x / tile] += 3
            }
        }
        mean = Double(total) / Double(a.width * a.height * 3)
        self.changed = Double(changed) / Double(a.width * a.height)
        var worst = 0.0, origin = (x: 0, y: 0)
        for index in tiles.indices {
            let value = Double(tiles[index]) / Double(counts[index])
            if value > worst { worst = value; origin = ((index % tilesX) * tile, (index / tilesX) * tile) }
        }
        worstTile = worst
        worstTileOrigin = origin
    }
}

func save(_ image: CGImage, _ name: String) throws -> URL {
    try FileManager.default.createDirectory(at: renderOutput, withIntermediateDirectories: true)
    let url = renderOutput.appendingPathComponent(name)
    try #require(ShareFrameRenderer.pngData(image)).write(to: url)
    return url
}

// MARK: Caption fitting

@Test func fitsTheSampleCaptionAtTheLargestSize() throws {
    let fonts = try fonts()
    let caption = try layout("plain").caption
    let fit = ShareFrameRenderer.captionFit(sample, layout: caption, fonts: fonts)
    #expect(fit == ShareCaptionFit(fontSize: 64, lines: ["Look what Aarav", "drew on my", "lock screen"]))
    #expect(ShareFrameRenderer.captionFit(sample, layout: try layout("polaroid").caption, fonts: fonts) == fit)
}

@Test func fitsTheLongestCaptionSmaller() throws {
    #expect(long.count == ShareCaption.maxLength)
    let fit = ShareFrameRenderer.captionFit(long, layout: try layout("plain").caption, fonts: try fonts())
    #expect(fit == ShareCaptionFit(fontSize: 48, lines: ["Look what my favourite", "person drew on my", "lock screen today!!"]))
}

@Test func fillsTheFirstLineThenBalancesTheRest() throws {
    var caption = try layout("plain").caption
    caption.maxWidth = 10; caption.maxLines = 3; caption.maxFontSize = 2; caption.minFontSize = 1
    // Each character is `size` wide, so at size 1 a line holds ten characters.
    let width: (String, Int) -> Double = { Double($0.count * $1) }
    // The first line takes "aaa bbb" (7) though "aaa" alone would balance better; the rest split
    // 3 + 4 rather than 7 + 0, the fewest lines that fit, then the smallest squared gaps.
    #expect(ShareCaptionFitter.fit("aaa bbb ccc dd ee", caption: caption, width: width)
            == ShareCaptionFit(fontSize: 1, lines: ["aaa bbb", "ccc dd ee"]))
    #expect(ShareCaptionFitter.fit("aaa bbb ccc dddd eeee", caption: caption, width: width)
            == ShareCaptionFit(fontSize: 1, lines: ["aaa bbb", "ccc dddd", "eeee"]))
    // The largest size that fits wins.
    #expect(ShareCaptionFitter.fit("ab cd", caption: caption, width: width) == ShareCaptionFit(fontSize: 2, lines: ["ab cd"]))
    // Nothing fits: the smallest size, wrapped greedily, over the line limit if need be.
    #expect(ShareCaptionFitter.fit("aaaa bbbb cccc dddd eeee ffff gggg", caption: caption, width: width)
            == ShareCaptionFit(fontSize: 1, lines: ["aaaa bbbb", "cccc dddd", "eeee ffff", "gggg"]))
    #expect(ShareCaptionFitter.fit("   ", caption: caption, width: width).lines.isEmpty)
}

// MARK: Geometry

@Test func mapsTheContentCornersOntoTheQuad() throws {
    for key in ["plain", "polaroid"] {
        let quad = try layout(key).content.quad
        let transform = ShareFrameRenderer.contentTransform(quad, contentSize: ShareFrameRenderer.contentSize)
        func close(_ point: CGPoint, _ corner: ShareFrameLayout.Point, within tolerance: Double) -> Bool {
            abs(point.x - corner.x) < tolerance && abs(point.y - corner.y) < tolerance
        }
        #expect(close(CGPoint.zero.applying(transform), quad.topLeft, within: 1e-9))
        #expect(close(CGPoint(x: 1179, y: 0).applying(transform), quad.topRight, within: 1e-9))
        #expect(close(CGPoint(x: 0, y: 2556).applying(transform), quad.bottomLeft, within: 1e-9))
        // The quad is a parallelogram, so the fourth corner follows, to its five decimals.
        #expect(close(CGPoint(x: 1179, y: 2556).applying(transform), quad.bottomRight, within: 1e-3))
    }
}

@Test func turnsBlocksClockwiseAboutTheirAnchor() {
    let size = CGSize(width: 100, height: 40)
    let position = ShareFrameLayout.Point(x: 500, y: 300)
    let bottomRight = ShareFrameRenderer.blockTransform(size: size, anchor: .bottomRight, position: position, degrees: 90)
    // The anchor stays put, and a quarter turn clockwise (y down) takes the block's top-left corner,
    // up and to the left of its anchor, to up and to the right.
    #expect(CGPoint(x: 100, y: 40).applying(bottomRight) == CGPoint(x: 500, y: 300))
    let corner = CGPoint.zero.applying(bottomRight)
    #expect(abs(corner.x - 540) < 1e-9 && abs(corner.y - 200) < 1e-9)
    let center = ShareFrameRenderer.blockTransform(size: size, anchor: .center, position: position, degrees: 0)
    #expect(CGPoint(x: 50, y: 20).applying(center) == CGPoint(x: 500, y: 300))
    #expect(CGPoint.zero.applying(center) == CGPoint(x: 450, y: 280))
}

// MARK: Measurements against the reference renderer

@Test func sizesBlocksAsTheReferenceRendererDoes() throws {
    let fonts = try fonts()
    let plain = try layout("plain")
    // render_preview.py with Pillow and FreeType: caption blocks 582 × 199 and 598 × 153, brand 217 × 53,
    // and a 307-pixel clock. Core Text measures within a few pixels: FreeType's hinting snaps the
    // digits' overshoot, so its "7:23" is 240 pixels tall at 320 where Core Text's outline is 241.5,
    // and the clock comes out at 305.
    let caption = try #require(ShareFrameRenderer.captionBlock(sample, layout: plain.caption, fonts: fonts))
    #expect(abs(caption.size.width - 582) <= 3 && abs(caption.size.height - 199) <= 3, "\(caption.size)")
    let longBlock = try #require(ShareFrameRenderer.captionBlock(long, layout: plain.caption, fonts: fonts))
    #expect(abs(longBlock.size.width - 598) <= 3 && abs(longBlock.size.height - 153) <= 3, "\(longBlock.size)")
    let brand = ShareFrameRenderer.brandBlockSize(plain.brand, fonts: fonts)
    #expect(abs(brand.width - 217) <= 2 && abs(brand.height - 53) <= 2, "\(brand)")
    #expect(abs(ShareFrameRenderer.clockFontSize(fonts) - 307) <= 3)
}

@Test func clockFollowsTheDeviceHourStyle() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "UTC")!
    let date = calendar.date(from: DateComponents(year: 2026, month: 11, day: 6, hour: 19, minute: 3))!
    let british = ShareClock.at(date, locale: Locale(identifier: "en_GB"), timeZone: calendar.timeZone)
    #expect(british == ShareClock(date: "Friday, 6 November", time: "19:03"))
    let american = ShareClock.at(date, locale: Locale(identifier: "en_US"), timeZone: calendar.timeZone)
    #expect(american == ShareClock(date: "Friday, 6 November", time: "7:03"))
    let morning = calendar.date(from: DateComponents(year: 2026, month: 11, day: 6, hour: 7, minute: 23))!
    #expect(ShareClock.at(morning, locale: Locale(identifier: "en_GB"), timeZone: calendar.timeZone).time == "7:23")
}

// MARK: Rendering

/// The plain frame with the sample content and caption, against render_preview.py's preview.
///
/// Both start from the same frame.png and sample-content.png, so the art agrees to a few levels
/// everywhere; what differs is the text and the resampling. FreeType (with hinting) and Core Text
/// rasterise glyphs differently and place them a pixel or two apart, and the reference draws the
/// caption into a bitmap and resamples that bicubically, where this draws vector text straight
/// into the rotated block. The content is resampled differently too (Pillow's bicubic without
/// prefiltering against Core Graphics' high-quality filter). Measured: mean 1.2 levels, 0.6% of
/// pixels off by more than 48 levels (glyph edges), worst 32-pixel tile 26 levels.
///
/// Tolerances: mean under 2 levels, under 1.5% of pixels off by more than 48, and no 32-pixel tile
/// off by more than 64 on average. A one-to-three-pixel glyph shift in white-on-red text stays
/// under that (the fallback content test's date line, 52); a missing caption (178, and 3% of
/// pixels) or a misplaced block does not, which the test checks with an empty caption.
@Test func rendersThePlainFrameLikeTheReference() throws {
    let fonts = try fonts()
    let plain = try layout("plain")
    let rendered = try #require(ShareFrameRenderer.compose(content: try image("reference/sample-content.png"),
                                                           frame: try image("frames/plain/frame.png"),
                                                           layout: plain, caption: sample, fonts: fonts))
    #expect(rendered.width == 1080 && rendered.height == 1920)
    let output = try save(rendered, "plain-render.png")
    let reference = Pixels(try image("reference/plain-preview.png"))
    let difference = Difference(Pixels(rendered), reference, tile: 32)
    print("plain: mean \(difference.mean), changed \(difference.changed), worst tile \(difference.worstTile) at \(difference.worstTileOrigin); \(output.path)")
    let uncaptioned = try #require(ShareFrameRenderer.compose(content: try image("reference/sample-content.png"),
                                                              frame: try image("frames/plain/frame.png"),
                                                              layout: plain, caption: "", fonts: fonts))
    let missing = Difference(Pixels(uncaptioned), reference, tile: 32)
    print("plain without caption: mean \(missing.mean), changed \(missing.changed), worst tile \(missing.worstTile)")
    #expect(difference.mean < 2)
    #expect(difference.changed < 0.015)
    #expect(difference.worstTile < 64)
    // The tolerances catch a missing caption.
    #expect(missing.changed >= 0.015 && missing.worstTile >= 64)
}

/// The fallback content from the auth background and the sample doodle, against sample-content.png,
/// which build_sample_content.py draws the same way, except that it puts the doodle over the clock
/// where the contract puts the clock last; the doodle never reaches the clock band, so that
/// doesn't show. Tolerances as for the plain frame; measured: mean 0.9, 0.3% of pixels, worst
/// tile 52 on the date line, where Core Text's clock is 305 pixels to FreeType's 307.
@Test func drawsTheFallbackContentLikeTheSample() throws {
    let fonts = try fonts()
    let wallpaper = try #require(ShareFrameRenderer.decodeImage(Data(contentsOf: URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().appendingPathComponent("../../../../apps/ios/Resources/Assets.xcassets/AuthBackground.imageset/auth_login_bg.jpg")
        .standardizedFileURL)))
    // The sample stretches its doodle over the screen; in the app the doodles are drawn at the
    // screen's own shape, so filling and stretching agree.
    let doodle = try image("reference/doodle-master.png")
    let stretched = try #require(CGContext(data: nil, width: 1179, height: 2556, bitsPerComponent: 8, bytesPerRow: 0,
                                           space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    stretched.interpolationQuality = .high
    stretched.draw(doodle, in: CGRect(x: 0, y: 0, width: 1179, height: 2556))
    let content = try #require(ShareFrameRenderer.content(
        ShareWallpaper(image: wallpaper, dimmed: true, overlay: try #require(stretched.makeImage())),
        clock: ShareClock(date: "Friday, 6 November", time: "7:23"), fonts: fonts))
    #expect(content.width == 1179 && content.height == 2556)
    let output = try save(content, "fallback-content.png")
    let difference = Difference(Pixels(content), Pixels(try image("reference/sample-content.png")), tile: 32)
    print("content: mean \(difference.mean), changed \(difference.changed), worst tile \(difference.worstTile) at \(difference.worstTileOrigin); \(output.path)")
    #expect(difference.mean < 2)
    #expect(difference.changed < 0.015)
    #expect(difference.worstTile < 64)
}

/// Writes the full share image for the long caption too, for a look.
@Test func rendersTheLongCaption() throws {
    let rendered = try #require(ShareFrameRenderer.compose(content: try image("reference/sample-content.png"),
                                                           frame: try image("frames/plain/frame.png"),
                                                           layout: try layout("plain"), caption: long, fonts: try fonts()))
    _ = try save(rendered, "plain-long-caption.png")
    let thumbnail = try #require(ShareFrameRenderer.compose(content: try image("reference/sample-content.png"),
                                                            frame: try image("frames/plain/frame.png"),
                                                            layout: try layout("plain"), caption: long, fonts: try fonts(),
                                                            scale: 0.25, text: false))
    #expect(thumbnail.width == 270 && thumbnail.height == 480)
    #expect(try #require(ShareFrameRenderer.jpegData(rendered)).count > 0)
}
