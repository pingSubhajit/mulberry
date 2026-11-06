import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers
#if canImport(ProductCore)
import ProductCore
#endif

/// The lock screen's clock as the share image shows it: the date line and the time.
public struct ShareClock: Hashable, Sendable {
    public let date: String
    public let time: String

    public init(date: String, time: String) {
        self.date = date; self.time = time
    }

    /// `date` as the lock screen shows it: "Friday, 6 November" above "7:23", in the device's
    /// 12- or 24-hour style with no AM/PM and no leading zero.
    public static func at(_ date: Date, locale: Locale = .autoupdatingCurrent, timeZone: TimeZone = .autoupdatingCurrent) -> ShareClock {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.timeZone = timeZone
        formatter.dateFormat = "EEEE, d MMMM"
        let line = formatter.string(from: date)
        let hourStyle = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: locale) ?? "h"
        formatter.dateFormat = hourStyle.contains("H") || hourStyle.contains("k") ? "H:mm" : "h:mm"
        return ShareClock(date: line, time: formatter.string(from: date))
    }
}

/// What fills the phone's screen before the clock.
public struct ShareWallpaper: @unchecked Sendable {
    /// Scaled to fill the screen and centre-cropped.
    public let image: CGImage
    /// Darkened with black at 20%: the fallback wallpaper only, never the person's own.
    public let dimmed: Bool
    /// Drawn over the wallpaper, filling and centre-cropped the same way: the doodles over the
    /// fallback wallpaper. The real wallpaper already has them.
    public let overlay: CGImage?

    public init(image: CGImage, dimmed: Bool = false, overlay: CGImage? = nil) {
        self.image = image; self.dimmed = dimmed; self.overlay = overlay
    }
}

/// Draws the share image as docs/share-frames.md's rendering contract lays out, matching
/// temp/share-frames/v1/src/render_preview.py. Every coordinate is in output pixels with y
/// pointing down; `scale` draws the same image smaller, for thumbnails.
public enum ShareFrameRenderer {
    /// The lock screen content's size: an iPhone screen.
    public static let contentSize = CGSize(width: 1179, height: 2556)
    public static let outputSize = CGSize(width: 1080, height: 1920)
    /// Black over the fallback wallpaper, which is too bright for white text.
    public static let fallbackDim: CGFloat = 0.2
    static let captionCornerRadius: CGFloat = 7
    static let brandGap: CGFloat = 13

    // MARK: Content

    /// The lock screen: the wallpaper (dimmed and with its overlay, for the fallback), then the
    /// clock and its shadow.
    public static func content(_ wallpaper: ShareWallpaper, clock: ShareClock, fonts: ShareFrameFonts, scale: CGFloat = 1) -> CGImage? {
        let size = contentSize
        guard let context = makeContext(size: size, scale: scale) else { return nil }
        let bounds = CGRect(origin: .zero, size: size)
        context.setFillColor(CGColor(gray: 0, alpha: 1))
        context.fill(bounds)
        drawImage(wallpaper.image, in: aspectFill(CGSize(width: wallpaper.image.width, height: wallpaper.image.height), in: bounds), context: context)
        if wallpaper.dimmed {
            context.setFillColor(CGColor(gray: 0, alpha: fallbackDim))
            context.fill(bounds)
        }
        if let overlay = wallpaper.overlay {
            drawImage(overlay, in: aspectFill(CGSize(width: overlay.width, height: overlay.height), in: bounds), context: context)
        }
        drawClock(clock, in: size, fonts: fonts, scale: scale, context: context)
        return context.makeImage()
    }

    /// The date line, its baseline at 11% of the height; the time below, its ink top at 13%, sized
    /// so "7:23" is 230 pixels tall; and under both, their shadow.
    static func drawClock(_ clock: ShareClock, in size: CGSize, fonts: ShareFrameFonts, scale: CGFloat, context: CGContext) {
        let white = CGColor(red: 1, green: 1, blue: 1, alpha: 1)
        let dateFont = fonts.font(fonts.medium, size: 59)
        let timeFont = fonts.font(fonts.semiBold, size: clockFontSize(fonts))
        let date = TextLine(clock.date, font: dateFont, color: white)
        let time = TextLine(clock.time, font: timeFont, color: white)
        context.saveGState()
        // The shadow's offset and blur are in device pixels, and its y axis points up.
        context.setShadow(offset: CGSize(width: 0, height: -3 * scale), blur: 8 * scale, color: CGColor(gray: 0, alpha: 0.3))
        context.beginTransparencyLayer(auxiliaryInfo: nil)
        date.draw(at: CGPoint(x: size.width / 2 - date.advance / 2, y: size.height * 0.11), context: context)
        time.draw(at: CGPoint(x: size.width / 2 - time.advance / 2, y: size.height * 0.13 + time.ink.top), context: context)
        context.endTransparencyLayer()
        context.restoreGState()
    }

    /// `round(320 × 230 / inkHeight("7:23" at 320))`.
    public static func clockFontSize(_ fonts: ShareFrameFonts) -> CGFloat {
        let probe = TextLine("7:23", font: fonts.font(fonts.semiBold, size: 320), color: CGColor(gray: 1, alpha: 1))
        return (320 * 230 / probe.ink.height).rounded()
    }

    // MARK: Composition

    /// The share image: the content mapped into the frame's screen, the frame over it, then the
    /// caption and the brand line unless `text` is false, as for thumbnails.
    public static func compose(content: CGImage, frame: CGImage, layout: ShareFrameLayout, caption: String,
                               fonts: ShareFrameFonts, scale: CGFloat = 1, text: Bool = true) -> CGImage? {
        let size = CGSize(width: layout.canvas.width, height: layout.canvas.height)
        guard let context = makeContext(size: size, scale: scale) else { return nil }
        context.setFillColor(CGColor(gray: 0, alpha: 1))
        context.fill(CGRect(origin: .zero, size: size))
        context.saveGState()
        context.concatenate(contentTransform(layout.content.quad, contentSize: contentSize))
        drawImage(content, in: CGRect(origin: .zero, size: contentSize), context: context)
        context.restoreGState()
        drawImage(frame, in: CGRect(origin: .zero, size: size), context: context)
        if text {
            drawCaption(caption, layout: layout.caption, fonts: fonts, context: context)
            drawBrand(layout.brand, fonts: fonts, context: context)
        }
        return context.makeImage()
    }

    /// Maps the content's corners onto the quad: topLeft from (0, 0), topRight from
    /// (width, 0) and bottomLeft from (0, height).
    public static func contentTransform(_ quad: ShareFrameLayout.Quad, contentSize: CGSize) -> CGAffineTransform {
        let ux = quad.topRight.x - quad.topLeft.x, uy = quad.topRight.y - quad.topLeft.y
        let vx = quad.bottomLeft.x - quad.topLeft.x, vy = quad.bottomLeft.y - quad.topLeft.y
        return CGAffineTransform(a: ux / contentSize.width, b: uy / contentSize.width,
                                 c: vx / contentSize.height, d: vy / contentSize.height,
                                 tx: quad.topLeft.x, ty: quad.topLeft.y)
    }

    /// Places a block of `size` so its `anchor` sits at `position`, turned about that point by
    /// `degrees`, clockwise for positive values.
    public static func blockTransform(size: CGSize, anchor: ShareFrameLayout.Anchor, position: ShareFrameLayout.Point,
                                      degrees: Double) -> CGAffineTransform {
        let anchorPoint: CGPoint = switch anchor {
        case .topLeft: .zero
        case .topRight: CGPoint(x: size.width, y: 0)
        case .bottomLeft: CGPoint(x: 0, y: size.height)
        case .bottomRight: CGPoint(x: size.width, y: size.height)
        case .center: CGPoint(x: size.width / 2, y: size.height / 2)
        }
        return CGAffineTransform(translationX: -anchorPoint.x, y: -anchorPoint.y)
            .concatenating(CGAffineTransform(rotationAngle: degrees * .pi / 180))
            .concatenating(CGAffineTransform(translationX: position.x, y: position.y))
    }

    // MARK: Caption

    /// The caption's lines laid out in its unrotated block, padding included.
    public struct CaptionBlock {
        public let fit: ShareCaptionFit
        public let size: CGSize
        fileprivate let lines: [TextLine]
        fileprivate let origins: [CGPoint]
    }

    public static func captionFit(_ text: String, layout: ShareFrameLayout.Caption, fonts: ShareFrameFonts) -> ShareCaptionFit {
        var fontsBySize: [Int: CTFont] = [:]
        return ShareCaptionFitter.fit(text, caption: layout) { line, size in
            let font = fontsBySize[size] ?? fonts.font(fonts.caption, size: CGFloat(size))
            fontsBySize[size] = font
            return Double(TextLine(line, font: font, color: CGColor(gray: 0, alpha: 1)).advance)
        }
    }

    public static func captionBlock(_ text: String, layout: ShareFrameLayout.Caption, fonts: ShareFrameFonts) -> CaptionBlock? {
        let fit = captionFit(text, layout: layout, fonts: fonts)
        guard !fit.lines.isEmpty, let ink = ShareFrameColor(hex: layout.inkColor) else { return nil }
        let font = fonts.font(fonts.caption, size: CGFloat(fit.fontSize))
        let lines = fit.lines.map { TextLine($0, font: font, color: ink.cgColor) }
        let padX = layout.backing?.paddingX ?? 0, padY = layout.backing?.paddingY ?? 0
        let advance = CGFloat(fit.fontSize) * layout.lineHeight
        let widest = lines.map(\.ink.width).max() ?? 0
        let tallest = lines.map(\.ink.height).max() ?? 0
        let size = CGSize(width: (widest + 2 * padX).rounded(.up),
                          height: (CGFloat(lines.count - 1) * advance + tallest + 2 * padY).rounded(.up))
        // Each line's ink box starts here: its left edge, and its ink top.
        let origins = lines.enumerated().map { index, line in
            CGPoint(x: layout.align == .left ? padX : size.width - padX - line.ink.width, y: padY + CGFloat(index) * advance)
        }
        return CaptionBlock(fit: fit, size: size, lines: lines, origins: origins)
    }

    static func drawCaption(_ text: String, layout: ShareFrameLayout.Caption, fonts: ShareFrameFonts, context: CGContext) {
        guard let block = captionBlock(text, layout: layout, fonts: fonts) else { return }
        context.saveGState()
        context.concatenate(blockTransform(size: block.size, anchor: layout.anchor, position: layout.position, degrees: layout.rotationDegrees))
        if let backing = layout.backing, let color = ShareFrameColor(hex: backing.color) {
            context.setFillColor(color.cgColor)
            for (line, origin) in zip(block.lines, block.origins) {
                let strip = CGRect(x: origin.x - backing.paddingX, y: origin.y - backing.paddingY,
                                   width: line.ink.width + 2 * backing.paddingX, height: line.ink.height + 2 * backing.paddingY)
                context.addPath(CGPath(roundedRect: strip, cornerWidth: captionCornerRadius, cornerHeight: captionCornerRadius, transform: nil))
                context.fillPath()
            }
        }
        for (line, origin) in zip(block.lines, block.origins) {
            line.draw(at: CGPoint(x: origin.x - line.ink.left, y: origin.y + line.ink.top), context: context)
        }
        context.restoreGState()
    }

    // MARK: Brand

    public static let brandText = "mulberry.my"

    /// The brand line's padded block size.
    public static func brandBlockSize(_ layout: ShareFrameLayout.Brand, fonts: ShareFrameFonts) -> CGSize {
        let text = TextLine(brandText, font: fonts.font(fonts.medium, size: layout.fontSize), color: CGColor(gray: 0, alpha: 1))
        let mark = markSize(height: layout.markHeight)
        let padX = layout.backing?.paddingX ?? 0, padY = layout.backing?.paddingY ?? 0
        return CGSize(width: mark.width + brandGap + text.ink.width + 2 * padX, height: max(mark.height, text.ink.height) + 2 * padY)
    }

    /// The mark at its natural shape and `height` tall, its width rounded to a whole pixel.
    static func markSize(height: CGFloat) -> CGSize {
        CGSize(width: (height * MulberryMark.viewBox.width / MulberryMark.viewBox.height).rounded(), height: height.rounded())
    }

    static func drawBrand(_ layout: ShareFrameLayout.Brand, fonts: ShareFrameFonts, context: CGContext) {
        guard let ink = ShareFrameColor(hex: layout.color) else { return }
        let text = TextLine(brandText, font: fonts.font(fonts.medium, size: layout.fontSize), color: ink.cgColor)
        let mark = markSize(height: layout.markHeight)
        let padX = layout.backing?.paddingX ?? 0
        let size = brandBlockSize(layout, fonts: fonts)
        context.saveGState()
        context.concatenate(blockTransform(size: size, anchor: layout.anchor, position: layout.position, degrees: layout.rotationDegrees))
        if let backing = layout.backing, let color = ShareFrameColor(hex: backing.color) {
            let radius = min(backing.cornerRadius ?? 0, size.width / 2, size.height / 2)
            context.setFillColor(color.cgColor)
            context.addPath(CGPath(roundedRect: CGRect(origin: .zero, size: size), cornerWidth: radius, cornerHeight: radius, transform: nil))
            context.fillPath()
        }
        MulberryMark.fill(in: CGRect(x: padX, y: ((size.height - mark.height) / 2).rounded(.down), width: mark.width, height: mark.height),
                          color: ink.cgColor, context: context)
        let textLeft = padX + mark.width + brandGap
        text.draw(at: CGPoint(x: textLeft - text.ink.left, y: (size.height - text.ink.height) / 2 + text.ink.top), context: context)
        context.restoreGState()
    }

    // MARK: Export

    /// A JPEG at `quality`, 0.92 or higher by the contract.
    public static func jpegData(_ image: CGImage, quality: CGFloat = 0.95) -> Data? {
        encode(image, type: .jpeg, properties: [kCGImageDestinationLossyCompressionQuality: max(0.92, quality)])
    }

    public static func pngData(_ image: CGImage) -> Data? {
        encode(image, type: .png, properties: [:])
    }

    private static func encode(_ image: CGImage, type: UTType, properties: [CFString: Any]) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, properties as CFDictionary)
        return CGImageDestinationFinalize(destination) ? data as Data : nil
    }

    /// Decodes an image file, the frames' PNG or WebP included, at full size, or at most
    /// `maxPixelSize` on its long side.
    public static func decodeImage(_ data: Data, maxPixelSize: Int? = nil) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        if let maxPixelSize {
            return CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true, kCGImageSourceThumbnailMaxPixelSize: maxPixelSize
            ] as CFDictionary)
        }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }

    // MARK: Drawing

    /// An sRGB bitmap of `size` × `scale` pixels whose user space is `size`, y pointing down.
    static func makeContext(size: CGSize, scale: CGFloat) -> CGContext? {
        let width = Int((size.width * scale).rounded()), height = Int((size.height * scale).rounded())
        guard width > 0, height > 0, let space = CGColorSpace(name: CGColorSpace.sRGB),
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.setShouldSmoothFonts(false)
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: CGFloat(width) / size.width, y: -CGFloat(height) / size.height)
        return context
    }

    /// Draws `image` upright into `rect` of a y-down context.
    static func drawImage(_ image: CGImage, in rect: CGRect, context: CGContext) {
        context.saveGState()
        context.translateBy(x: rect.minX, y: rect.maxY)
        context.scaleBy(x: 1, y: -1)
        context.draw(image, in: CGRect(origin: .zero, size: rect.size))
        context.restoreGState()
    }

    /// `size` scaled to cover `bounds`, centred.
    static func aspectFill(_ size: CGSize, in bounds: CGRect) -> CGRect {
        guard size.width > 0, size.height > 0 else { return bounds }
        let scale = max(bounds.width / size.width, bounds.height / size.height)
        let fitted = CGSize(width: size.width * scale, height: size.height * scale)
        return CGRect(x: bounds.midX - fitted.width / 2, y: bounds.midY - fitted.height / 2, width: fitted.width, height: fitted.height)
    }
}

/// One line of text, laid out and measured as the reference renderer's FreeType does: each glyph
/// advances by its width rounded to a whole pixel, with no kerning (Pillow's basic layout applies
/// none that shows) and no optional ligatures. Core Text still shapes the line, so characters the
/// font lacks, as emoji, come from fallback fonts.
struct TextLine {
    /// The ink's extent around the pen's start on the baseline. As FreeType's box in the reference,
    /// it reaches back to the pen's start and on to its end at least.
    struct Ink {
        let left: CGFloat
        let right: CGFloat
        /// Above the baseline.
        let top: CGFloat
        /// Below the baseline.
        let bottom: CGFloat
        var width: CGFloat { right - left }
        var height: CGFloat { top + bottom }
    }

    private struct Run {
        let font: CTFont
        let glyphs: [CGGlyph]
        /// Each glyph's pen position along the baseline.
        let positions: [CGPoint]
    }

    private let runs: [Run]
    private let color: CGColor
    let advance: CGFloat
    let ink: Ink

    init(_ text: String, font: CTFont, color: CGColor) {
        self.color = color
        let attributes: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTKernAttributeName as String): 0,
            NSAttributedString.Key(kCTLigatureAttributeName as String): 0
        ]
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
        var pen: CGFloat = 0
        var bounds = CGRect.null
        var runs: [Run] = []
        for run in (CTLineGetGlyphRuns(line) as? [CTRun]) ?? [] {
            let count = CTRunGetGlyphCount(run)
            guard count > 0 else { continue }
            let runAttributes = CTRunGetAttributes(run) as NSDictionary
            let runFont = runAttributes[kCTFontAttributeName as String].map { $0 as! CTFont } ?? font
            var glyphs = [CGGlyph](repeating: 0, count: count)
            var advances = [CGSize](repeating: .zero, count: count)
            CTRunGetGlyphs(run, CFRange(location: 0, length: count), &glyphs)
            CTRunGetAdvances(run, CFRange(location: 0, length: count), &advances)
            var rects = [CGRect](repeating: .zero, count: count)
            CTFontGetBoundingRectsForGlyphs(runFont, .horizontal, glyphs, &rects, count)
            var positions: [CGPoint] = []
            for index in 0..<count {
                positions.append(CGPoint(x: pen, y: 0))
                if !rects[index].isEmpty { bounds = bounds.union(rects[index].offsetBy(dx: pen, dy: 0)) }
                pen += advances[index].width.rounded()
            }
            runs.append(Run(font: runFont, glyphs: glyphs, positions: positions))
        }
        self.runs = runs
        advance = pen
        if bounds.isNull {
            ink = Ink(left: 0, right: pen, top: 0, bottom: 0)
        } else {
            ink = Ink(left: min(0, bounds.minX), right: max(pen, bounds.maxX), top: bounds.maxY, bottom: -bounds.minY)
        }
    }

    /// Draws with the pen's start at `origin` on the baseline, in a y-down context.
    func draw(at origin: CGPoint, context: CGContext) {
        context.saveGState()
        context.translateBy(x: origin.x, y: origin.y)
        context.scaleBy(x: 1, y: -1)
        context.textMatrix = .identity
        context.setFillColor(color)
        for run in runs {
            CTFontDrawGlyphs(run.font, run.glyphs, run.positions, run.glyphs.count, context)
        }
        context.restoreGState()
    }
}

extension ShareFrameColor {
    var cgColor: CGColor {
        CGColor(srgbRed: red, green: green, blue: blue, alpha: 1)
    }
}
