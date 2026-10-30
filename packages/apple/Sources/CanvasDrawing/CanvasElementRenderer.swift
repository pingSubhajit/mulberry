import CoreGraphics
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
#if canImport(ProductCore)
import ProductCore
#endif

/// Android draws text at 34sp in canvas space, wraps it at `boxWidth` of the
/// canvas width, then rotates and scales the laid-out block around its center.
public enum CanvasTextStyle {
    public static let baseSize: CGFloat = 34
    public static let pillPadding: CGFloat = 12
    public static let pillCornerRadius: CGFloat = 18

    public static func postScriptName(_ font: CanvasTextFont) -> String {
        switch font {
        case .poppins: "Poppins-Regular"
        case .virgil: "Virgil3YOFF"
        case .dmSans: "DMSans-Regular"
        case .spaceMono: "SpaceMono-Regular"
        case .playfairDisplay: "PlayfairDisplay-Regular"
        case .bangers: "Bangers-Regular"
        case .permanentMarker: "PermanentMarker-Regular"
        case .kalam: "Kalam-Regular"
        case .oswald: "Oswald-Regular"
        }
    }

    public static func displayName(_ font: CanvasTextFont) -> String {
        switch font {
        case .poppins: "Poppins"
        case .virgil: "Virgil"
        case .dmSans: "DM Sans"
        case .spaceMono: "Space Mono"
        case .playfairDisplay: "Playfair"
        case .bangers: "Bangers"
        case .permanentMarker: "Marker"
        case .kalam: "Kalam"
        case .oswald: "Oswald"
        }
    }

    public static func font(_ font: CanvasTextFont, size: CGFloat = baseSize) -> CanvasFont {
        CanvasGraphics.font(postScriptName: postScriptName(font), size: size)
            ?? CanvasGraphics.font(postScriptName: "Poppins-Regular", size: size) ?? CanvasGraphics.systemFont(size: size)
    }

    /// With the background pill on, the element colour fills the pill and the
    /// text switches to black or white using Android's luminance threshold.
    public static func textColor(_ element: CanvasTextElement) -> UInt32 {
        guard element.backgroundPillEnabled else { return element.colorArgb }
        return isLight(element.colorArgb) ? 0xFF000000 : 0xFFFFFFFF
    }

    public static func isLight(_ argb: UInt32) -> Bool {
        let red = Double((argb >> 16) & 255) / 255, green = Double((argb >> 8) & 255) / 255, blue = Double(argb & 255) / 255
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue > 0.55
    }

    public static func alignment(_ alignment: CanvasTextAlign) -> NSTextAlignment {
        switch alignment {
        case .left: .left
        case .center: .center
        case .right: .right
        }
    }
}

/// One text element laid out in canvas points, before its center, rotation and
/// scale are applied. TextKit instances stay confined to the creating thread.
public final class CanvasTextLayout {
    public let element: CanvasTextElement
    public let canvasWidth: CGFloat
    /// The full wrapping box; Android's StaticLayout is as wide as its wrap width.
    public let size: CGSize
    /// Inked line extents within `size`, matching Android's tight bounds.
    public let tightBounds: CGRect
    private let storage: NSTextStorage
    private let manager = NSLayoutManager()
    private let container: NSTextContainer

    public init(element: CanvasTextElement, canvasWidth: CGFloat) {
        self.element = element
        self.canvasWidth = canvasWidth
        let wrap = max(1, CGFloat(element.boxWidth) * canvasWidth)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = CanvasTextStyle.alignment(element.alignment)
        storage = NSTextStorage(string: element.text, attributes: [
            .font: CanvasTextStyle.font(element.font),
            .foregroundColor: CanvasGraphics.color(CanvasTextStyle.textColor(element)),
            .paragraphStyle: paragraph
        ])
        container = NSTextContainer(size: CGSize(width: wrap, height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        manager.addTextContainer(container)
        storage.addLayoutManager(manager)
        manager.ensureLayout(for: container)
        let used = manager.usedRect(for: container)
        size = CGSize(width: wrap, height: ceil(used.height))
        var left = CGFloat.infinity, right = -CGFloat.infinity
        manager.enumerateLineFragments(forGlyphRange: manager.glyphRange(for: container)) { _, line, _, _, _ in
            left = min(left, line.minX); right = max(right, line.maxX)
        }
        tightBounds = left.isFinite && right > left ? CGRect(x: left, y: 0, width: right - left, height: size.height) : .zero
    }

    public func matches(_ other: CanvasTextElement, canvasWidth: CGFloat) -> Bool {
        self.canvasWidth == canvasWidth && element.text == other.text && element.font == other.font
            && element.alignment == other.alignment && element.boxWidth == other.boxWidth
            && CanvasTextStyle.textColor(element) == CanvasTextStyle.textColor(other)
    }

    /// The visible body around the element's center, before rotation and scale.
    public func bodyRect(pill: Bool) -> CGRect {
        let rect = tightBounds.offsetBy(dx: -size.width / 2, dy: -size.height / 2)
        return pill ? rect.insetBy(dx: -CanvasTextStyle.pillPadding, dy: -CanvasTextStyle.pillPadding) : rect
    }

    /// Draws `placed`, which may be a moved, rotated or rescaled copy of `element`.
    public func draw(_ placed: CanvasTextElement, in context: CGContext, canvasSize: CGSize) {
        context.saveGState()
        context.translateBy(x: placed.center.x * canvasSize.width, y: placed.center.y * canvasSize.height)
        context.rotate(by: placed.rotationRad)
        context.scaleBy(x: placed.scale, y: placed.scale)
        if placed.backgroundPillEnabled, !tightBounds.isEmpty {
            context.setFillColor(CanvasGraphics.color(placed.colorArgb).cgColor)
            context.addPath(CanvasGraphics.roundedRect(bodyRect(pill: true), cornerRadius: CanvasTextStyle.pillCornerRadius))
            context.fillPath()
        }
        CanvasGraphics.withCurrentContext(context) {
            manager.drawGlyphs(forGlyphRange: manager.glyphRange(for: container), at: CGPoint(x: -size.width / 2, y: -size.height / 2))
        }
        context.restoreGState()
    }

    /// Hit-tests in canvas points. Small text keeps a 44-point touch target.
    public func contains(_ point: CGPoint, placed: CanvasTextElement, canvasSize: CGSize) -> Bool {
        let dx = point.x - placed.center.x * canvasSize.width, dy = point.y - placed.center.y * canvasSize.height
        let cosine = cos(placed.rotationRad), sine = sin(placed.rotationRad)
        let scale = max(0.0001, placed.scale)
        let local = CGPoint(x: (cosine * dx + sine * dy) / scale, y: (-sine * dx + cosine * dy) / scale)
        let body = bodyRect(pill: placed.backgroundPillEnabled)
        let minimum = 44 / scale
        return body.insetBy(dx: -max(0, (minimum - body.width) / 2), dy: -max(0, (minimum - body.height) / 2)).contains(local)
    }
}

/// Android's sticker geometry: the long edge is `scale` of the canvas width,
/// keeping the artwork's aspect ratio; no extra scale transform is applied.
public enum CanvasStickerGeometry {
    public static let placeholderCornerRadius: CGFloat = 18

    public static func size(_ sticker: CanvasStickerElement, image: CanvasImage?, canvasWidth: CGFloat) -> CGSize {
        let long = max(1, CGFloat(sticker.renderScale) * canvasWidth)
        guard let image else { return CGSize(width: long, height: long) }
        let imageSize = CanvasGraphics.size(of: image)
        guard imageSize.width > 0, imageSize.height > 0 else { return CGSize(width: long, height: long) }
        let aspect = imageSize.width / imageSize.height
        return aspect >= 1 ? CGSize(width: long, height: long / aspect) : CGSize(width: long * aspect, height: long)
    }

    /// Draws the artwork, or without `appearance` (a still render), Android's placeholder where
    /// it is missing. A live canvas passes how far the artwork has faded in and how strongly its
    /// loading placeholder shows.
    public static func draw(_ sticker: CanvasStickerElement, image: CanvasImage?, in context: CGContext, canvasSize: CGSize,
                            appearance: StickerAppearance? = nil) {
        let size = size(sticker, image: image, canvasWidth: canvasSize.width)
        let rect = CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height)
        context.saveGState()
        context.translateBy(x: sticker.center.x * canvasSize.width, y: sticker.center.y * canvasSize.height)
        context.rotate(by: sticker.rotationRad)
        if let appearance {
            if appearance.placeholder > 0 {
                // A soft grey that reads on a light canvas and a dark one, never a white block.
                let path = CanvasGraphics.roundedRect(rect.insetBy(dx: 0.5, dy: 0.5), cornerRadius: placeholderCornerRadius)
                context.setFillColor(CanvasColor(white: 0.5, alpha: 0.16 * appearance.placeholder).cgColor)
                context.addPath(path)
                context.fillPath()
                context.setStrokeColor(CanvasColor(white: 0.5, alpha: 0.3 * appearance.placeholder).cgColor)
                context.setLineWidth(1)
                context.addPath(path)
                context.strokePath()
            }
            if let image, appearance.artwork > 0 {
                context.setAlpha(appearance.artwork)
                context.interpolationQuality = .high
                CanvasGraphics.draw(image, in: rect, context: context)
            }
        } else if let image {
            context.interpolationQuality = .high
            CanvasGraphics.draw(image, in: rect, context: context)
        } else {
            // Android's placeholder while the artwork downloads.
            context.setFillColor(CanvasColor(white: 1, alpha: 64 / 255).cgColor)
            context.addPath(CanvasGraphics.roundedRect(rect, cornerRadius: placeholderCornerRadius))
            context.fillPath()
        }
        context.restoreGState()
    }

    public static func contains(_ point: CGPoint, sticker: CanvasStickerElement, image: CanvasImage?, canvasSize: CGSize) -> Bool {
        let size = size(sticker, image: image, canvasWidth: canvasSize.width)
        let dx = point.x - sticker.center.x * canvasSize.width, dy = point.y - sticker.center.y * canvasSize.height
        let cosine = cos(sticker.rotationRad), sine = sin(sticker.rotationRad)
        let local = CGPoint(x: cosine * dx + sine * dy, y: -sine * dx + cosine * dy)
        let width = max(44, size.width), height = max(44, size.height)
        return CGRect(x: -width / 2, y: -height / 2, width: width, height: height).contains(local)
    }
}

/// How a sticker shows on a live canvas this frame: how far its artwork has faded in, and how
/// strongly its loading placeholder shows, each from 0 to 1.
public struct StickerAppearance: Equatable, Sendable {
    public var artwork: CGFloat
    public var placeholder: CGFloat

    public init(artwork: CGFloat, placeholder: CGFloat) {
        self.artwork = artwork
        self.placeholder = placeholder
    }
}

/// When each sticker's artwork was asked for and when it arrived, so a live canvas never flashes
/// a placeholder for artwork that is only being read from disk. Artwork already in memory shows
/// at once; artwork being decoded leaves its place empty, then fades in; a placeholder shows
/// only once a download has run past a short grace period, pulsing softly, and fades out as the
/// artwork fades in over it.
public struct StickerArtworkTimeline: Equatable, Sendable {
    public static let fade: TimeInterval = 0.18
    public static let grace: TimeInterval = 0.4
    public static let pulse: TimeInterval = 1.2

    /// Holds the placeholder still instead of pulsing.
    public var reduceMotion = false
    private var requested: [StickerReference: TimeInterval] = [:]
    private var arrivals: [StickerReference: Arrival] = [:]

    private struct Arrival: Equatable, Sendable {
        let time: TimeInterval
        /// Whether the placeholder was showing, so it fades out under the artwork.
        let replacesPlaceholder: Bool
    }

    public init() {}

    /// The clock the timeline runs on.
    public static var now: TimeInterval { ProcessInfo.processInfo.systemUptime }

    /// The artwork is being loaded.
    public mutating func request(_ sticker: StickerReference, at now: TimeInterval = Self.now) {
        if requested[sticker] == nil { requested[sticker] = now }
    }

    /// The artwork arrived; it fades in if it was waited for.
    public mutating func arrive(_ sticker: StickerReference, at now: TimeInterval = Self.now) {
        guard let start = requested.removeValue(forKey: sticker) else { return }
        arrivals[sticker] = Arrival(time: now, replacesPlaceholder: now - start >= Self.grace)
    }

    /// The artwork could not be loaded; its place keeps a still placeholder.
    public mutating func fail(_ sticker: StickerReference) {
        requested[sticker] = nil
    }

    public mutating func reset() {
        requested = [:]
        arrivals = [:]
    }

    public func appearance(_ sticker: StickerReference, hasArtwork: Bool, at now: TimeInterval = Self.now) -> StickerAppearance {
        if hasArtwork {
            guard let arrival = arrivals[sticker] else { return StickerAppearance(artwork: 1, placeholder: 0) }
            let progress = Self.ease(min(1, max(0, (now - arrival.time) / Self.fade)))
            return StickerAppearance(artwork: progress, placeholder: arrival.replacesPlaceholder ? pulse(now) * (1 - progress) : 0)
        }
        guard let start = requested[sticker] else {
            // Missing and not loading, such as offline: a still placeholder keeps its place.
            return StickerAppearance(artwork: 0, placeholder: 1)
        }
        let waited = now - start - Self.grace
        guard waited > 0 else { return StickerAppearance(artwork: 0, placeholder: 0) }
        return StickerAppearance(artwork: 0, placeholder: min(1, waited / Self.fade) * pulse(now))
    }

    /// Whether anything is fading or pulsing, so the canvas keeps redrawing.
    public func isAnimating(at now: TimeInterval = Self.now) -> Bool {
        !requested.isEmpty || arrivals.values.contains { now - $0.time < Self.fade }
    }

    /// Forgets arrivals that have finished fading in.
    public mutating func settle(at now: TimeInterval = Self.now) {
        arrivals = arrivals.filter { now - $0.value.time < Self.fade }
    }

    private func pulse(_ now: TimeInterval) -> CGFloat {
        guard !reduceMotion else { return 0.8 }
        let phase = now.truncatingRemainder(dividingBy: Self.pulse) / Self.pulse
        return 0.6 + 0.4 * CGFloat(0.5 - 0.5 * cos(2 * .pi * phase))
    }

    private static func ease(_ x: Double) -> CGFloat { CGFloat(1 - pow(1 - x, 3)) }
}

/// Draws and hit-tests text and stickers in their shared paint order. Text
/// layouts are reused while only position, rotation or scale change.
public final class CanvasElementRenderer {
    private var layouts: [String: CanvasTextLayout] = [:]
    /// Decoded sticker artwork; a missing image draws Android's placeholder.
    public var stickerImages: [StickerReference: CanvasImage] = [:]
    /// Set on a live canvas: stickers fade in as their artwork arrives, and show a placeholder
    /// only while a download runs long, instead of Android's placeholder.
    public var stickerTimeline: StickerArtworkTimeline?

    public init(stickerImages: [StickerReference: CanvasImage] = [:], stickerTimeline: StickerArtworkTimeline? = nil) {
        self.stickerImages = stickerImages
        self.stickerTimeline = stickerTimeline
    }

    public func layout(for element: CanvasTextElement, canvasWidth: CGFloat) -> CanvasTextLayout {
        if let cached = layouts[element.id], cached.matches(element, canvasWidth: canvasWidth) { return cached }
        let layout = CanvasTextLayout(element: element, canvasWidth: canvasWidth)
        layouts[element.id] = layout
        return layout
    }

    public func retain(_ elements: [CanvasElement]) {
        let ids = Set(elements.compactMap { $0.text?.id })
        layouts = layouts.filter { ids.contains($0.key) }
    }

    public func draw(_ elements: [CanvasElement], in context: CGContext, canvasSize: CGSize,
                     now: TimeInterval = StickerArtworkTimeline.now) {
        for element in elements {
            switch element {
            case .text(let text) where !text.text.isEmpty:
                layout(for: text, canvasWidth: canvasSize.width).draw(text, in: context, canvasSize: canvasSize)
            case .sticker(let sticker):
                let image = stickerImages[sticker.sticker]
                let appearance = stickerTimeline?.appearance(sticker.sticker, hasArtwork: image != nil, at: now)
                CanvasStickerGeometry.draw(sticker, image: image, in: context, canvasSize: canvasSize, appearance: appearance)
            default: break
            }
        }
    }

    /// The topmost element under `point`, optionally limited to one kind.
    public func hit(_ elements: [CanvasElement], at point: CGPoint, canvasSize: CGSize, where include: (CanvasElement) -> Bool = { _ in true }) -> CanvasElement? {
        elements.reversed().first { element in
            guard include(element) else { return false }
            switch element {
            case .text(let text):
                return !text.text.isEmpty && layout(for: text, canvasWidth: canvasSize.width).contains(point, placed: text, canvasSize: canvasSize)
            case .sticker(let sticker):
                return CanvasStickerGeometry.contains(point, sticker: sticker, image: stickerImages[sticker.sticker], canvasSize: canvasSize)
            }
        }
    }
}
