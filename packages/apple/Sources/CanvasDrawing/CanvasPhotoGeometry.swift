import CoreGraphics
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif
#if canImport(ProductCore)
import ProductCore
#endif

/// A photo's look on the canvas. The framed photo's long edge is `scale` of the canvas width.
/// Frames are white paper with a thin border; a polaroid's bottom edge is deeper; a sticker is the
/// cut-out subject with its white outline baked in. Every style casts a soft shadow so it lifts
/// off the strokes beneath.
public enum CanvasPhotoGeometry {
    /// The paper's colour, a touch warm so it never glows on a white canvas.
    public static let paperArgb: UInt32 = 0xFFFCFAF6

    /// The framed photo laid out with its long edge `long` points: the outer size, and where the
    /// photo sits inside it, from the outer top left.
    public struct Layout: Equatable, Sendable {
        public var size: CGSize
        public var photo: CGRect
        public var cornerRadius: CGFloat
        public var photoCornerRadius: CGFloat
    }

    /// Insets in units of the photo's short side: top, side and bottom.
    private static func insets(_ style: CanvasPhotoStyle) -> (top: CGFloat, side: CGFloat, bottom: CGFloat) {
        switch style {
        case .frame: (0.05, 0.05, 0.05)
        case .polaroid: (0.07, 0.07, 0.3)
        // Without its cut-out, a sticker falls back to a rounded, thick-bordered print.
        case .sticker: (0.06, 0.06, 0.06)
        }
    }

    public static func layout(style: CanvasPhotoStyle, aspectRatio: Double, long: CGFloat) -> Layout {
        let aspect = CGFloat(aspectRatio > 0 && aspectRatio.isFinite ? aspectRatio : 1)
        // A photo of short side 1, then scaled so the outer long edge is `long`.
        let photoWidth = aspect >= 1 ? aspect : 1, photoHeight = aspect >= 1 ? 1 : 1 / aspect
        let inset = insets(style)
        let outerWidth = photoWidth + 2 * inset.side, outerHeight = photoHeight + inset.top + inset.bottom
        let unit = long / max(outerWidth, outerHeight)
        let size = CGSize(width: outerWidth * unit, height: outerHeight * unit)
        let photo = CGRect(x: inset.side * unit, y: inset.top * unit, width: photoWidth * unit, height: photoHeight * unit)
        let corner: CGFloat = style == .sticker ? 0.08 : style == .polaroid ? 0.008 : 0.012
        return Layout(size: size, photo: photo, cornerRadius: corner * long,
                      photoCornerRadius: style == .sticker ? 0.05 * long : 0.003 * long)
    }

    /// The drawn size: the cut-out's own shape for a sticker, or the frame.
    public static func size(_ photo: CanvasPhotoElement, cutout: CanvasImage?, canvasWidth: CGFloat) -> CGSize {
        let long = max(1, CGFloat(photo.renderScale) * canvasWidth)
        if photo.style == .sticker, let cutout {
            let imageSize = CanvasGraphics.size(of: cutout)
            if imageSize.width > 0, imageSize.height > 0 {
                let aspect = imageSize.width / imageSize.height
                return aspect >= 1 ? CGSize(width: long, height: long / aspect) : CGSize(width: long * aspect, height: long)
            }
        }
        return layout(style: photo.style, aspectRatio: photo.aspectRatio, long: long).size
    }

    /// The shadow under every style, for a photo whose long edge is `long`.
    public static func shadow(long: CGFloat) -> (offset: CGSize, blur: CGFloat, alpha: CGFloat) {
        (CGSize(width: 0, height: 0.014 * long), 0.06 * long, 0.3)
    }

    /// The photo upright with its shadow, as a bitmap of `pixelsPerPoint`, with the size it covers in
    /// points, centred on the photo's centre. Blurring a shadow is most of a photo's cost, so a canvas
    /// renders each photo once and copies the result while only its place or angle changes.
    public static func rendered(_ photo: CanvasPhotoElement, images: [String: CanvasImage], canvasWidth: CGFloat,
                                pixelsPerPoint: CGFloat) -> (image: CanvasImage, size: CGSize)? {
        let long = max(1, CGFloat(photo.renderScale) * canvasWidth)
        let shadow = shadow(long: long)
        let drawn = size(photo, cutout: photo.cutoutPhotoId.flatMap { images[$0] }, canvasWidth: canvasWidth)
        let pad = ceil(shadow.blur * 1.5 + shadow.offset.height)
        let size = CGSize(width: drawn.width + pad * 2, height: drawn.height + pad * 2)
        let scale = max(1, pixelsPerPoint)
        let width = Int(ceil(size.width * scale)), height = Int(ceil(size.height * scale))
        guard width > 0, height > 0, width * height < 40_000_000,
              let context = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        // Points, top down, as the canvas draws; the bitmap's own space runs bottom up, which the
        // shadow's offset follows, so it falls downward with a negative height.
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: scale, y: -scale)
        context.translateBy(x: size.width / 2, y: size.height / 2)
        drawUpright(photo, images: images, long: long, in: context,
                    shadowOffset: CGSize(width: 0, height: -shadow.offset.height * scale), shadowBlur: shadow.blur * scale)
        guard let bitmap = context.makeImage() else { return nil }
        #if canImport(UIKit)
        let image = UIImage(cgImage: bitmap, scale: scale, orientation: .up)
        #else
        let image = NSImage(cgImage: bitmap, size: size)
        #endif
        return (image, size)
    }

    /// Draws the photo straight onto `context`, shadow and all. A live canvas copies `rendered` instead.
    public static func draw(_ photo: CanvasPhotoElement, images: [String: CanvasImage], in context: CGContext, canvasSize: CGSize) {
        let long = max(1, CGFloat(photo.renderScale) * canvasSize.width)
        let shadow = shadow(long: long)
        context.saveGState()
        context.translateBy(x: photo.center.x * canvasSize.width, y: photo.center.y * canvasSize.height)
        context.rotate(by: photo.rotationRad)
        drawUpright(photo, images: images, long: long, in: context, shadowOffset: shadow.offset, shadowBlur: shadow.blur)
        context.restoreGState()
    }

    /// The photo centred on the origin, unrotated. A photo not loaded yet shows its paper with a
    /// soft grey where the picture will be.
    private static func drawUpright(_ photo: CanvasPhotoElement, images: [String: CanvasImage], long: CGFloat, in context: CGContext,
                                    shadowOffset: CGSize, shadowBlur: CGFloat) {
        let shadowColor = CanvasColor(white: 0, alpha: shadow(long: long).alpha).cgColor
        context.saveGState()
        defer { context.restoreGState() }
        context.interpolationQuality = .high

        if photo.style == .sticker, let cutoutID = photo.cutoutPhotoId, let cutout = images[cutoutID] {
            let size = size(photo, cutout: cutout, canvasWidth: long / CGFloat(photo.renderScale))
            context.setShadow(offset: shadowOffset, blur: shadowBlur, color: shadowColor)
            CanvasGraphics.draw(cutout, in: CGRect(x: -size.width / 2, y: -size.height / 2, width: size.width, height: size.height), context: context)
            return
        }

        let layout = layout(style: photo.style, aspectRatio: photo.aspectRatio, long: long)
        let origin = CGPoint(x: -layout.size.width / 2, y: -layout.size.height / 2)
        let paper = CGRect(origin: origin, size: layout.size)
        context.saveGState()
        context.setShadow(offset: shadowOffset, blur: shadowBlur, color: shadowColor)
        context.setFillColor(CanvasGraphics.color(paperArgb).cgColor)
        context.addPath(CanvasGraphics.roundedRect(paper, cornerRadius: layout.cornerRadius))
        context.fillPath()
        context.restoreGState()

        let picture = layout.photo.offsetBy(dx: origin.x, dy: origin.y)
        context.addPath(CanvasGraphics.roundedRect(picture, cornerRadius: layout.photoCornerRadius))
        context.clip()
        if let image = images[photo.photoId] {
            CanvasGraphics.draw(image, in: aspectFill(CanvasGraphics.size(of: image), in: picture), context: context)
        } else {
            context.setFillColor(CanvasColor(white: 0.82, alpha: 1).cgColor)
            context.fill(picture)
        }
    }

    public static func contains(_ point: CGPoint, photo: CanvasPhotoElement, cutout: CanvasImage?, canvasSize: CGSize) -> Bool {
        let size = size(photo, cutout: cutout, canvasWidth: canvasSize.width)
        let dx = point.x - photo.center.x * canvasSize.width, dy = point.y - photo.center.y * canvasSize.height
        let cosine = cos(photo.rotationRad), sine = sin(photo.rotationRad)
        let local = CGPoint(x: cosine * dx + sine * dy, y: -sine * dx + cosine * dy)
        let width = max(44, size.width), height = max(44, size.height)
        return CGRect(x: -width / 2, y: -height / 2, width: width, height: height).contains(local)
    }

    private static func aspectFill(_ image: CGSize, in rect: CGRect) -> CGRect {
        guard image.width > 0, image.height > 0 else { return rect }
        let scale = max(rect.width / image.width, rect.height / image.height)
        let size = CGSize(width: image.width * scale, height: image.height * scale)
        return CGRect(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2, width: size.width, height: size.height)
    }
}
