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

    public static func draw(_ sticker: CanvasStickerElement, image: CanvasImage?, in context: CGContext, canvasSize: CGSize) {
        let size = size(sticker, image: image, canvasWidth: canvasSize.width)
        let rect = CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height)
        context.saveGState()
        context.translateBy(x: sticker.center.x * canvasSize.width, y: sticker.center.y * canvasSize.height)
        context.rotate(by: sticker.rotationRad)
        if let image {
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

/// Draws and hit-tests text and stickers in their shared paint order. Text
/// layouts are reused while only position, rotation or scale change.
public final class CanvasElementRenderer {
    private var layouts: [String: CanvasTextLayout] = [:]
    /// Decoded sticker artwork; a missing image draws Android's placeholder.
    public var stickerImages: [StickerReference: CanvasImage] = [:]

    public init(stickerImages: [StickerReference: CanvasImage] = [:]) { self.stickerImages = stickerImages }

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

    public func draw(_ elements: [CanvasElement], in context: CGContext, canvasSize: CGSize) {
        for element in elements {
            switch element {
            case .text(let text) where !text.text.isEmpty:
                layout(for: text, canvasWidth: canvasSize.width).draw(text, in: context, canvasSize: canvasSize)
            case .sticker(let sticker):
                CanvasStickerGeometry.draw(sticker, image: stickerImages[sticker.sticker], in: context, canvasSize: canvasSize)
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
