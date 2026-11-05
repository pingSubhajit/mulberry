import CoreGraphics
import Foundation
import Testing
import AppKit
@testable import CanvasDrawing
@testable import ProductCore

/// A photo on a live canvas is rendered once with its shadow, then only copied, and the copy matches
/// drawing it directly.
@Test func aPhotoIsRenderedOnceThenCopied() throws {
    let source = NSImage(size: NSSize(width: 1600, height: 1200))
    source.lockFocus(); NSColor.orange.setFill(); NSRect(x: 0, y: 0, width: 1600, height: 1200).fill(); source.unlockFocus()
    let canvas = CGSize(width: 402, height: 700)
    func context() -> CGContext {
        let context = CGContext(data: nil, width: 1206, height: 2100, bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.translateBy(x: 0, y: 2100); context.scaleBy(x: 3, y: -3)
        return context
    }
    let renderer = CanvasElementRenderer(photoImages: ["a": source])
    let photo = CanvasPhotoElement(center: .init(x: 0.5, y: 0.5), scale: 0.64, photoId: "a", aspectRatio: 0.75, style: .polaroid)
    let live = context()
    renderer.draw([.photo(photo)], in: live, canvasSize: canvas)
    var moved = photo
    moved.center = .init(x: 0.45, y: 0.5)
    let start = Date()
    for _ in 0..<10 { renderer.draw([.photo(moved)], in: live, canvasSize: canvas) }
    let copy = Date().timeIntervalSince(start) / 10
    #expect(copy < 0.008)

    // The copy sits where a direct drawing does: the paper's middle is the same colour.
    let cached = context(), direct = context()
    renderer.draw([.photo(photo)], in: cached, canvasSize: canvas)
    CanvasPhotoGeometry.draw(photo, images: ["a": source], in: direct, canvasSize: canvas)
    func pixel(_ context: CGContext, _ x: Int, _ y: Int) -> [UInt8] {
        let data = context.data!.assumingMemoryBound(to: UInt8.self)
        let offset = y * context.bytesPerRow + x * 4
        return [data[offset], data[offset + 1], data[offset + 2], data[offset + 3]]
    }
    for (x, y) in [(603, 1050), (603, 1400), (603, 1650)] {
        let a = pixel(cached, x, y), b = pixel(direct, x, y)
        #expect(zip(a, b).allSatisfy { abs(Int($0) - Int($1)) <= 6 }, "\(x),\(y): \(a) vs \(b)")
    }
    // Below the paper, the shadow falls downward in both.
    let below = Int(1050 + 0.64 * 402 * 3 / 2) + 12
    #expect(pixel(cached, 603, below)[3] > 0)
    #expect(pixel(cached, 603, Int(1050 - 0.64 * 402 * 3 / 2) - 30)[3] < pixel(cached, 603, below)[3])
}

/// Every platform paints stickers and photos first, then text, then strokes over them all; within
/// each layer the element changed last is on top, and taps find the topmost.
@Test func layersPaintStickersAndPhotosThenTextThenStrokes() throws {
    let text = CanvasElement.text(CanvasTextElement(id: "t", text: "hi", center: .init(x: 0.5, y: 0.5), colorArgb: 0xFF2457D6))
    let sticker = CanvasElement.sticker(CanvasStickerElement(id: "s", center: .init(x: 0.5, y: 0.5),
                                                            sticker: StickerReference(packKey: "p", packVersion: 1, stickerId: "1")))
    let photo = CanvasElement.photo(CanvasPhotoElement(id: "p", center: .init(x: 0.5, y: 0.5), photoId: "a", aspectRatio: 1))
    #expect(CanvasElementRenderer.paintOrder([text, sticker, photo]).map(\.id) == ["s", "p", "t"])
    #expect(CanvasElementRenderer.paintOrder([photo, text, sticker]).map(\.id) == ["p", "s", "t"])
    let canvas = CGSize(width: 402, height: 700)
    #expect(CanvasElementRenderer().hit([text, sticker], at: CGPoint(x: 201, y: 350), canvasSize: canvas)?.id == "t")

    // A stroke drawn before a sticker still paints over it.
    let context = CGContext(data: nil, width: 402, height: 700, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.translateBy(x: 0, y: 700); context.scaleBy(x: 1, y: -1)
    let stroke = DrawingStroke(colorArgb: 0xFFFF0000, width: 0.05, points: [.init(x: 0.3, y: 0.5), .init(x: 0.7, y: 0.5)])
    DoodleRenderer.draw([stroke], elements: [photo], dry: false, in: context, size: canvas)
    let data = context.data!.assumingMemoryBound(to: UInt8.self)
    let offset = 350 * context.bytesPerRow + 201 * 4
    #expect(data[offset] > 200 && data[offset + 1] < 60, "The stroke must cover the photo")
}
