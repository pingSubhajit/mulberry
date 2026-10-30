import CoreGraphics
import Foundation
import Testing
@testable import CanvasDrawing
import ProductCore

private let size = CGSize(width: 120, height: 200)

private func stroke(_ x: Double, colour: UInt32) -> DrawingStroke {
    DrawingStroke(colorArgb: colour, width: 0.05, points: (0..<24).map { .init(x: x + sin(Double($0) / 4) / 10, y: Double($0) / 24) }, finished: true)
}

private func pixels(_ raster: StrokeRasterizer.Raster?) throws -> Data {
    let image = try #require(raster?.image)
    return try #require(image.dataProvider?.data as Data?)
}

private let strokes = [stroke(0.3, colour: 0xFFB31329), stroke(0.5, colour: 0xFF5BB7E8), stroke(0.7, colour: 0x99FFE66D)]

@Test(arguments: [true, false])
func strokesPaintedOverEarlierOnesMatchARedraw(dry: Bool) async throws {
    let growing = StrokeRasterizer()
    for count in 1...strokes.count {
        _ = await growing.render(.init(strokes: Array(strokes.prefix(count)), size: size, scale: 2, dry: dry))
    }
    let all = StrokeRasterizer.Request(strokes: strokes, size: size, scale: 2, dry: dry)
    #expect(try pixels(await growing.render(all)) == pixels(await StrokeRasterizer().render(all)))
}

@Test func anErasedStrokeIsRedrawnAway() async throws {
    let rasterizer = StrokeRasterizer()
    _ = await rasterizer.render(.init(strokes: strokes, size: size, scale: 2, dry: true))
    let erased = StrokeRasterizer.Request(strokes: [strokes[0], strokes[2]], size: size, scale: 2, dry: true)
    let raster = try #require(await rasterizer.render(erased))
    #expect(raster.request == erased)
    #expect(try pixels(raster) == pixels(await StrokeRasterizer().render(erased)))
}

@Test func aNewSizeOrScaleGetsABitmapToMatch() async throws {
    let rasterizer = StrokeRasterizer()
    _ = await rasterizer.render(.init(strokes: strokes, size: size, scale: 2, dry: true))
    let raster = try #require(await rasterizer.render(.init(strokes: strokes, size: CGSize(width: 90, height: 160), scale: 3, dry: true)))
    #expect(raster.image.width == 270 && raster.image.height == 480)
}

@Test func anEmptyCanvasHasNoBitmap() async {
    #expect(await StrokeRasterizer().render(.init(strokes: strokes, size: .zero, scale: 2, dry: true)) == nil)
}
