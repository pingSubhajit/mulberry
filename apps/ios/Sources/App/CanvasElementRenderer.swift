import UIKit

/// Android draws text at 34sp in canvas space, wraps it at `boxWidth` of the
/// canvas width, then rotates and scales the laid-out block around its center.
enum CanvasTextStyle {
    static let baseSize: CGFloat = 34
    static let pillPadding: CGFloat = 12
    static let pillCornerRadius: CGFloat = 18

    static func postScriptName(_ font: CanvasTextFont) -> String {
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

    static func displayName(_ font: CanvasTextFont) -> String {
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

    static func uiFont(_ font: CanvasTextFont, size: CGFloat = baseSize) -> UIFont {
        UIFont(name: postScriptName(font), size: size) ?? UIFont(name: "Poppins-Regular", size: size) ?? .systemFont(ofSize: size)
    }

    /// With the background pill on, the element colour fills the pill and the
    /// text switches to black or white using Android's luminance threshold.
    static func textColor(_ element: CanvasTextElement) -> UInt32 {
        guard element.backgroundPillEnabled else { return element.colorArgb }
        return isLight(element.colorArgb) ? 0xFF000000 : 0xFFFFFFFF
    }

    static func isLight(_ argb: UInt32) -> Bool {
        let red = Double((argb >> 16) & 255) / 255, green = Double((argb >> 8) & 255) / 255, blue = Double(argb & 255) / 255
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue > 0.55
    }

    static func alignment(_ alignment: CanvasTextAlign) -> NSTextAlignment {
        switch alignment {
        case .left: .left
        case .center: .center
        case .right: .right
        }
    }
}

/// One text element laid out in canvas points, before its center, rotation and
/// scale are applied. TextKit instances stay confined to the creating thread.
final class CanvasTextLayout {
    let element: CanvasTextElement
    let canvasWidth: CGFloat
    /// The full wrapping box; Android's StaticLayout is as wide as its wrap width.
    let size: CGSize
    /// Inked line extents within `size`, matching Android's tight bounds.
    let tightBounds: CGRect
    private let storage: NSTextStorage
    private let manager = NSLayoutManager()
    private let container: NSTextContainer

    init(element: CanvasTextElement, canvasWidth: CGFloat) {
        self.element = element
        self.canvasWidth = canvasWidth
        let wrap = max(1, CGFloat(element.boxWidth) * canvasWidth)
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = CanvasTextStyle.alignment(element.alignment)
        storage = NSTextStorage(string: element.text, attributes: [
            .font: CanvasTextStyle.uiFont(element.font),
            .foregroundColor: UIColor(argb: CanvasTextStyle.textColor(element)),
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

    func matches(_ other: CanvasTextElement, canvasWidth: CGFloat) -> Bool {
        self.canvasWidth == canvasWidth && element.text == other.text && element.font == other.font
            && element.alignment == other.alignment && element.boxWidth == other.boxWidth
            && CanvasTextStyle.textColor(element) == CanvasTextStyle.textColor(other)
    }

    /// The visible body around the element's center, before rotation and scale.
    func bodyRect(pill: Bool) -> CGRect {
        let rect = tightBounds.offsetBy(dx: -size.width / 2, dy: -size.height / 2)
        return pill ? rect.insetBy(dx: -CanvasTextStyle.pillPadding, dy: -CanvasTextStyle.pillPadding) : rect
    }

    /// Draws `placed`, which may be a moved, rotated or rescaled copy of `element`.
    func draw(_ placed: CanvasTextElement, in context: CGContext, canvasSize: CGSize) {
        context.saveGState()
        context.translateBy(x: placed.center.x * canvasSize.width, y: placed.center.y * canvasSize.height)
        context.rotate(by: placed.rotationRad)
        context.scaleBy(x: placed.scale, y: placed.scale)
        if placed.backgroundPillEnabled, !tightBounds.isEmpty {
            context.setFillColor(UIColor(argb: placed.colorArgb).cgColor)
            context.addPath(UIBezierPath(roundedRect: bodyRect(pill: true), cornerRadius: CanvasTextStyle.pillCornerRadius).cgPath)
            context.fillPath()
        }
        UIGraphicsPushContext(context)
        manager.drawGlyphs(forGlyphRange: manager.glyphRange(for: container), at: CGPoint(x: -size.width / 2, y: -size.height / 2))
        UIGraphicsPopContext()
        context.restoreGState()
    }

    /// Hit-tests in canvas points. Small text keeps a 44-point touch target.
    func contains(_ point: CGPoint, placed: CanvasTextElement, canvasSize: CGSize) -> Bool {
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
enum CanvasStickerGeometry {
    static let placeholderCornerRadius: CGFloat = 18

    static func size(_ sticker: CanvasStickerElement, image: UIImage?, canvasWidth: CGFloat) -> CGSize {
        let long = max(1, CGFloat(sticker.renderScale) * canvasWidth)
        guard let image, image.size.width > 0, image.size.height > 0 else { return CGSize(width: long, height: long) }
        let aspect = image.size.width / image.size.height
        return aspect >= 1 ? CGSize(width: long, height: long / aspect) : CGSize(width: long * aspect, height: long)
    }

    static func draw(_ sticker: CanvasStickerElement, image: UIImage?, in context: CGContext, canvasSize: CGSize) {
        let size = size(sticker, image: image, canvasWidth: canvasSize.width)
        let rect = CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height)
        context.saveGState()
        context.translateBy(x: sticker.center.x * canvasSize.width, y: sticker.center.y * canvasSize.height)
        context.rotate(by: sticker.rotationRad)
        if let image {
            context.interpolationQuality = .high
            UIGraphicsPushContext(context)
            image.draw(in: rect)
            UIGraphicsPopContext()
        } else {
            // Android's placeholder while the artwork downloads.
            context.setFillColor(UIColor(white: 1, alpha: 64 / 255).cgColor)
            context.addPath(UIBezierPath(roundedRect: rect, cornerRadius: placeholderCornerRadius).cgPath)
            context.fillPath()
        }
        context.restoreGState()
    }

    static func contains(_ point: CGPoint, sticker: CanvasStickerElement, image: UIImage?, canvasSize: CGSize) -> Bool {
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
final class CanvasElementRenderer {
    private var layouts: [String: CanvasTextLayout] = [:]
    /// Decoded sticker artwork; a missing image draws Android's placeholder.
    var stickerImages: [StickerReference: UIImage] = [:]

    init(stickerImages: [StickerReference: UIImage] = [:]) { self.stickerImages = stickerImages }

    func layout(for element: CanvasTextElement, canvasWidth: CGFloat) -> CanvasTextLayout {
        if let cached = layouts[element.id], cached.matches(element, canvasWidth: canvasWidth) { return cached }
        let layout = CanvasTextLayout(element: element, canvasWidth: canvasWidth)
        layouts[element.id] = layout
        return layout
    }

    func retain(_ elements: [CanvasElement]) {
        let ids = Set(elements.compactMap { $0.text?.id })
        layouts = layouts.filter { ids.contains($0.key) }
    }

    func draw(_ elements: [CanvasElement], in context: CGContext, canvasSize: CGSize) {
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
    func hit(_ elements: [CanvasElement], at point: CGPoint, canvasSize: CGSize, where include: (CanvasElement) -> Bool = { _ in true }) -> CanvasElement? {
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
