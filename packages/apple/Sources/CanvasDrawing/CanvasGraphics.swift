import CoreGraphics
#if canImport(UIKit)
import UIKit

/// Sticker artwork as each platform decodes it.
public typealias CanvasImage = UIImage
public typealias CanvasFont = UIFont
typealias CanvasColor = UIColor
#else
import AppKit

public typealias CanvasImage = NSImage
public typealias CanvasFont = NSFont
typealias CanvasColor = NSColor
#endif

/// The few drawing calls that differ between UIKit and AppKit. Everything else in the
/// canvas renderer is Core Graphics, drawn in a top-left-origin context as UIKit uses.
enum CanvasGraphics {
    static func color(_ argb: UInt32) -> CanvasColor {
        let red = CGFloat((argb >> 16) & 255) / 255, green = CGFloat((argb >> 8) & 255) / 255
        let blue = CGFloat(argb & 255) / 255, alpha = CGFloat((argb >> 24) & 255) / 255
        #if canImport(UIKit)
        return UIColor(red: red, green: green, blue: blue, alpha: alpha)
        #else
        return NSColor(srgbRed: red, green: green, blue: blue, alpha: alpha)
        #endif
    }

    static func font(postScriptName: String, size: CGFloat) -> CanvasFont? {
        CanvasFont(name: postScriptName, size: size)
    }

    static func systemFont(size: CGFloat) -> CanvasFont {
        CanvasFont.systemFont(ofSize: size)
    }

    static func roundedRect(_ rect: CGRect, cornerRadius: CGFloat) -> CGPath {
        #if canImport(UIKit)
        return UIBezierPath(roundedRect: rect, cornerRadius: cornerRadius).cgPath
        #else
        return NSBezierPath(roundedRect: rect, xRadius: cornerRadius, yRadius: cornerRadius).cgPath
        #endif
    }

    /// Text and images draw through the platform's current context, which must be `context`.
    static func withCurrentContext(_ context: CGContext, _ body: () -> Void) {
        #if canImport(UIKit)
        UIGraphicsPushContext(context)
        body()
        UIGraphicsPopContext()
        #else
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(cgContext: context, flipped: true)
        body()
        NSGraphicsContext.restoreGraphicsState()
        #endif
    }

    static func size(of image: CanvasImage) -> CGSize {
        image.size
    }

    static func draw(_ image: CanvasImage, in rect: CGRect, context: CGContext) {
        withCurrentContext(context) {
            #if canImport(UIKit)
            image.draw(in: rect)
            #else
            image.draw(in: rect, from: .zero, operation: .sourceOver, fraction: 1, respectFlipped: true, hints: nil)
            #endif
        }
    }
}
